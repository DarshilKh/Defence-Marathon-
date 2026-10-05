package com.army.marathon.ui;

import com.army.marathon.model.*;
import com.army.marathon.repository.*;
import com.army.marathon.service.*;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import lombok.extern.slf4j.Slf4j;
import net.rgielen.fxweaver.core.FxmlView;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.io.File;
import java.io.FileOutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Component
@FxmlView("LiveTrackingView.fxml")
public class LiveTrackingController {

    // Declared Start controls - only shown in the MANUAL_START_DUAL_TIMING mode. Every other
    // mode takes the start from the first valid scan at the start-line checkpoint, with no
    // manual entry at all. There is no separate "Set" button any more - Start Race captures
    // the Declared Start itself, atomically, as part of arming (see handleStartRace).
    @FXML private HBox declaredStartBox;
    @FXML private TextField manualStartTimeField;   // ticking "now" preview before Start Race; frozen to the captured value after
    @FXML private Label currentStartTimeLabel;      // the declared start once captured

    @FXML private Button startRaceButton;
    @FXML private Button stopRaceButton;
    @FXML private Button resetRaceButton;
    @FXML private Button exportReadingsButton;

    @FXML private Label raceStatusLabel;
    @FXML private Label startLineLabel;
    @FXML private Label totalParticipantsLabel;
    @FXML private Label finishedCountLabel;
    @FXML private Label activeReadersLabel;
    @FXML private Label unregisteredScansLabel;
    @FXML private Label offRosterScansLabel;

    // ===== Race Configuration controls (moved here from the Checkpoint
    // Configuration page, which now only holds the 4 location cards) =====
    @FXML private TextField raceNameField;
    @FXML private Spinner<Integer> duplicateReadWindowSpinner;
    @FXML private ComboBox<RaceConfiguration.ReaderType> readerTypeComboBox;
    @FXML private Button saveRaceConfigButton;
    @FXML private Label raceConfigStatusLabel;

    @FXML private TableView<LiveParticipantRow> liveResultsTable;
    @FXML private TableColumn<LiveParticipantRow, String> nameColumn;
    @FXML private TableColumn<LiveParticipantRow, String> serviceNoColumn;
    @FXML private TableColumn<LiveParticipantRow, String> unitColumn;
    @FXML private TableColumn<LiveParticipantRow, String> checkpointColumn;
    @FXML private TableColumn<LiveParticipantRow, String> timeColumn;

    // ===== Calculate Results controls (moved here from the Results tab; now
    // computed straight from live RFID readings — no file upload) =====
    @FXML private ComboBox<String> calculationModeComboBox;
    @FXML private VBox modeSettingsContainer;
    @FXML private Button calculateButton;

    private final MultiReaderService multiReaderService;
    private final RaceRepository raceRepository;
    private final CheckpointRepository checkpointRepository;
    private final CheckpointReadingRepository readingRepository;
    private final RaceResultRepository resultRepository;
    private final RaceTimingService timingService;
    private final DeviceModeService deviceModeService;
    private final RaceConfigurationRepository raceConfigRepository;
    private final RaceEntryRepository raceEntryRepository;
    private final RaceSessionRepository raceSessionRepository;
    private final RaceLifecycleService lifecycleService;
    private final CalculationModeSelection modeSelection;

    private final ObservableList<LiveParticipantRow> liveResults = FXCollections.observableArrayList();

    // ✅ Background thread pool for reader connect work (arming, manual start
    // override) so blocking TCP connects never run on the JavaFX Application Thread
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    private ScheduledExecutorService scheduler;
    private Timeline clockTimeline;
    private Race currentRace;

    // True while the Start Race connect / Calculate work is running on a background thread, so the
    // 2-second UI refresh doesn't re-enable a button that is mid-operation.
    private volatile boolean starting = false;
    private volatile boolean calculating = false;

    /** Called after a successful calculation so the main window can show the Results tab. */
    private Runnable onResultsReady;

    // Calculate Results state
    private TextField lapCountField;
    private TextField lapDistanceField;
    private Race.CalculationMode currentMode = Race.CalculationMode.TWO_CHECKPOINT;

    public LiveTrackingController(
        MultiReaderService multiReaderService,
        RaceRepository raceRepository,
        CheckpointRepository checkpointRepository,
        CheckpointReadingRepository readingRepository,
        RaceResultRepository resultRepository,
        RaceTimingService timingService,
        DeviceModeService deviceModeService,
        RaceConfigurationRepository raceConfigRepository,
        RaceEntryRepository raceEntryRepository,
        RaceSessionRepository raceSessionRepository,
        RaceLifecycleService lifecycleService,
        CalculationModeSelection modeSelection
    ) {
        this.multiReaderService = multiReaderService;
        this.raceRepository = raceRepository;
        this.checkpointRepository = checkpointRepository;
        this.readingRepository = readingRepository;
        this.resultRepository = resultRepository;
        this.timingService = timingService;
        this.deviceModeService = deviceModeService;
        this.raceConfigRepository = raceConfigRepository;
        this.raceEntryRepository = raceEntryRepository;
        this.raceSessionRepository = raceSessionRepository;
        this.lifecycleService = lifecycleService;
        this.modeSelection = modeSelection;
    }

    /** Wired by MainViewController: switches to the Results tab once results are calculated. */
    public void setOnResultsReady(Runnable onResultsReady) {
        this.onResultsReady = onResultsReady;
    }

    @FXML
    public void initialize() {
        setupTables();
        setupComboBoxes();
        setupRaceConfigControls();
        loadActiveRace();
        loadRaceConfiguration();
        adaptUIForDeviceMode();
        updateModeSettingsUI();
        startLiveUpdates();
        updateStatus();
        restoreRaceState();
        updateStartLineLabel();
        updateUnregisteredScansLabel();
        updateOffRosterScansLabel();
        startClock();
        updateDeclaredStartVisibility();

        // Readers are deliberately NOT armed here. They connect and start scanning only when the
        // operator clicks "Start Race" (see handleStartRace).
    }

    private void loadActiveRace() {
        try {
            List<Race> races = raceRepository.findAll();
            
            if (races.isEmpty()) {
                Race newRace = new Race();
                newRace.setRaceName("Indian Defence Marathon " + LocalDate.now().getYear());
                newRace.setRaceDate(LocalDateTime.now());
                newRace.setStatus(Race.RaceStatus.NOT_STARTED);
                newRace.setCalculationMode(Race.CalculationMode.AUTO);
                newRace.setMasterDeviceId(deviceModeService.getDeviceId());
                
                currentRace = raceRepository.save(newRace);
                log.info("✅ Created default race: {}", currentRace.getRaceName());
            } else {
                currentRace = races.get(races.size() - 1);
                log.info("✅ Loaded race: {}", currentRace.getRaceName());
            }
            
            if (currentRace.getManualStartTime() != null) {
                currentStartTimeLabel.setText(currentRace.getManualStartTime().format(DECLARED_START_FORMAT));
            }

            if (currentRace.getCalculationMode() != null) {
                currentMode = currentRace.getCalculationMode();
                calculationModeComboBox.setValue(getModeLabel(currentMode));
            }

        } catch (Exception e) {
            log.error("❌ Failed to load race", e);
            showAlert(Alert.AlertType.ERROR, "Error", 
                "Failed to load race: " + e.getMessage());
        }
    }

    private static final DateTimeFormatter DECLARED_START_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter TIME_ONLY_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    public  Race currentRace() {
        return raceRepository.findById(1l).get();
    }

    /**
     * Starts a new attempt on the one race this installation runs: stamps the boundary that
     * {@link RaceTimingService} filters readings by, and records a {@link RaceSession} row so
     * this attempt shows up in the Reports tab's history list. Called only on a genuinely fresh
     * start (the very first one, or the first Start Race after a Reset) - readings and results
     * from any earlier attempt keep their rows (Reset never deletes them) and are simply left
     * out of this new attempt's live views and calculation.
     */
    private void beginNewAttempt(Race race) {
        LocalDateTime now = LocalDateTime.now();
        race.setCurrentAttemptStartedAt(now);

        RaceSession session = new RaceSession();
        session.setRaceId(race.getId());
        session.setRaceName(race.getRaceName());
        session.setCalculationMode(race.getCalculationMode());
        session.setStartedAt(now);
        session = raceSessionRepository.save(session);

        race.setCurrentSessionId(session.getId());
    }

    /** Resuming a race that was stopped by mistake: reopen its session instead of starting a new one. */
    private void reopenCurrentSession(Race race) {
        if (race.getCurrentSessionId() == null) {
            return;
        }
        raceSessionRepository.findById(race.getCurrentSessionId()).ifPresent(session -> {
            session.setEndedAt(null);
            raceSessionRepository.save(session);
        });
    }

    /**
     * True when a reading belongs to the current attempt. No boundary means there is no current
     * attempt (fresh launch / after Reset), so nothing belongs to it - never "include everything".
     */
    private static boolean inCurrentAttempt(CheckpointReading reading, LocalDateTime attemptStart) {
        return attemptStart != null
                && reading.getReadTime() != null && !reading.getReadTime().isBefore(attemptStart);
    }

    private void restoreRaceState() {
        Race currentRace = currentRace();
        Race.RaceStatus status = currentRace != null ? currentRace.getStatus() : null;
        boolean readersUp = multiReaderService.areReadersArmed();

        if (currentRace == null || status == null) {
            startRaceButton.setDisable(starting);
            stopRaceButton.setDisable(true);
            exportReadingsButton.setDisable(true);

            raceStatusLabel.setText("⏸️ READY TO START");
            raceStatusLabel.setStyle("-fx-text-fill: gray; -fx-font-weight: bold;");
            updateCalculateButtonState(null);
            return;
        }

        switch (status) {
            case ARMED:
                // Start Race was clicked and the readers are listening. If the app was restarted
                // the readers are gone (they live in memory) - Start Race reconnects them.
                startRaceButton.setDisable(starting || readersUp);
                stopRaceButton.setDisable(false);
                exportReadingsButton.setDisable(true);

                raceStatusLabel.setText(readersUp
                        ? "🔌 ARMED — waiting for first start-line scan"
                        : "🔌 ARMED — readers not connected, click Start Race to reconnect");
                raceStatusLabel.setStyle("-fx-text-fill: #d68910; -fx-font-weight: bold;");
                break;

            case ACTIVE:
                startRaceButton.setDisable(starting || readersUp);
                stopRaceButton.setDisable(false);
                exportReadingsButton.setDisable(true);

                String startedAt = currentRace.getStartTime() != null
                        ? currentRace.getStartTime().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                        : "?";
                raceStatusLabel.setText("🏁 ACTIVE — started at " + startedAt + " (first start-line scan)"
                        + (readersUp ? "" : " — readers not connected, click Start Race to reconnect"));
                raceStatusLabel.setStyle("-fx-text-fill: green; -fx-font-weight: bold;");
                break;

            case FINISHED:
                // Start Race stays available: it resumes a race that was stopped by mistake
                startRaceButton.setDisable(starting);
                stopRaceButton.setDisable(true);
                exportReadingsButton.setDisable(false);

                raceStatusLabel.setText("✅ RACE FINISHED");
                raceStatusLabel.setStyle("-fx-text-fill: blue; -fx-font-weight: bold;");
                break;

            case CANCELLED:
                startRaceButton.setDisable(starting);
                stopRaceButton.setDisable(true);
                exportReadingsButton.setDisable(true);

                raceStatusLabel.setText("❌ RACE CANCELLED");
                raceStatusLabel.setStyle("-fx-text-fill: red; -fx-font-weight: bold;");
                break;

            case NOT_STARTED:
            default:
                startRaceButton.setDisable(starting);
                stopRaceButton.setDisable(true);
                exportReadingsButton.setDisable(true);

                raceStatusLabel.setText("⏸️ READY TO START");
                raceStatusLabel.setStyle("-fx-text-fill: gray; -fx-font-weight: bold;");
                break;
        }
        updateCalculateButtonState(status);
    }

    /**
     * Calculate Results is only available once the race has been explicitly stopped
     * (status FINISHED) - disabled by default, after a Reset, and while a calculation runs.
     */
    private void updateCalculateButtonState(Race.RaceStatus status) {
        if (calculateButton != null) {
            calculateButton.setDisable(calculating || status != Race.RaceStatus.FINISHED);
        }
    }

    private void setupTables() {
        nameColumn.setCellValueFactory(new PropertyValueFactory<>("name"));
        serviceNoColumn.setCellValueFactory(new PropertyValueFactory<>("serviceNo"));
        unitColumn.setCellValueFactory(new PropertyValueFactory<>("unit"));
        checkpointColumn.setCellValueFactory(new PropertyValueFactory<>("checkpoint"));
        timeColumn.setCellValueFactory(new PropertyValueFactory<>("time"));

        liveResultsTable.setItems(liveResults);
    }

    /**
     * ✅ Single configurable duplicate-read window and the global reader-vendor
     * setting (CLOU vs ZEBRA) - moved here from the Checkpoint Configuration
     * page, which now only holds the 4 location cards (see task 3/5).
     */
    private void setupRaceConfigControls() {
        if (duplicateReadWindowSpinner != null) {
            duplicateReadWindowSpinner.setValueFactory(
                    new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 60, 2));
            // ✅ Editable spinners don't commit typed text until focus is lost -
            // force a commit so a value typed and then immediately saved isn't lost
            duplicateReadWindowSpinner.getEditor().focusedProperty().addListener((obs, wasFocused, isFocused) -> {
                if (!isFocused) {
                    duplicateReadWindowSpinner.increment(0);
                }
            });
        }

        if (readerTypeComboBox != null) {
            readerTypeComboBox.setItems(FXCollections.observableArrayList(
                    RaceConfiguration.ReaderType.values()));
            readerTypeComboBox.setValue(RaceConfiguration.ReaderType.CLOU);
            // ✅ Display-only rename: CLOU shows as "Reader CR 15" in the UI.
            // The enum constant itself stays CLOU so existing saved configuration
            // rows (stored as the string "CLOU") keep loading correctly.
            readerTypeComboBox.setConverter(new javafx.util.StringConverter<RaceConfiguration.ReaderType>() {
                @Override
                public String toString(RaceConfiguration.ReaderType type) {
                    if (type == null) return "";
                    return type == RaceConfiguration.ReaderType.CLOU ? "Reader CR 15" : type.name();
                }

                @Override
                public RaceConfiguration.ReaderType fromString(String label) {
                    return "Reader CR 15".equals(label)
                            ? RaceConfiguration.ReaderType.CLOU
                            : RaceConfiguration.ReaderType.ZEBRA;
                }
            });
        }
    }

    private void loadRaceConfiguration() {
        try {
            RaceConfiguration config = raceConfigRepository.findTopByOrderByCreatedAtDesc();
            // The Race Name field is never pre-filled and the "Last Race:" hint (which would
            // re-display the old name) stays hidden: every attempt needs a freshly typed name.
            // Only the duplicate-read window and reader type persist across launches.
            if (raceConfigStatusLabel != null) {
                raceConfigStatusLabel.setVisible(false);
                raceConfigStatusLabel.setManaged(false);
            }
            if (config != null) {
                if (duplicateReadWindowSpinner != null) {
                    duplicateReadWindowSpinner.getValueFactory().setValue(config.getDuplicateReadWindowMinutes());
                }
                if (readerTypeComboBox != null) {
                    readerTypeComboBox.setValue(config.getReaderType() != null
                            ? config.getReaderType() : RaceConfiguration.ReaderType.CLOU);
                }
            }
        } catch (Exception e) {
            log.error("Failed to load race config", e);
        }
    }

    /**
     * ✅ Step 1 of the sequential Live Tracking flow: race name, duplicate-read
     * window and reader type. Saved separately from Start Time / Calculate
     * Results so the operator can set these first, before anything else.
     */
    @FXML
    private void handleSaveRaceConfig() {
        try {
            String raceName = raceNameField != null ? raceNameField.getText() : null;

            if (raceName == null || raceName.trim().isEmpty()) {
                showAlert(Alert.AlertType.ERROR, "Validation Error", "❌ Race name is required!");
                return;
            }

            persistRaceConfig(raceName.trim());

            showAlert(Alert.AlertType.INFORMATION, "Success",
                    "✅ Race configuration saved!\n\nRace: " + raceName.trim());

            loadRaceConfiguration();

        } catch (Exception e) {
            showAlert(Alert.AlertType.ERROR, "Error", "❌ Failed to save race configuration: " + e.getMessage());
            log.error("Race config save error", e);
        }
    }

    /**
     * The persistence half of Save (no dialogs), shared with Start Race: saves a new
     * RaceConfiguration row and stamps the name on the Race row. Start Race calls this BEFORE the
     * attempt begins because beginNewAttempt copies Race.raceName into the new RaceSession.
     */
    private void persistRaceConfig(String trimmedName) {
        int duplicateReadWindowMinutes = duplicateReadWindowSpinner != null
                ? duplicateReadWindowSpinner.getValue() : 2;
        RaceConfiguration.ReaderType readerType = readerTypeComboBox != null && readerTypeComboBox.getValue() != null
                ? readerTypeComboBox.getValue() : RaceConfiguration.ReaderType.CLOU;

        RaceConfiguration raceConfig = new RaceConfiguration();
        raceConfig.setRaceName(trimmedName);
        raceConfig.setCreatedAt(LocalDateTime.now());
        raceConfig.setDuplicateReadWindowMinutes(duplicateReadWindowMinutes);
        raceConfig.setReaderType(readerType);
        raceConfigRepository.save(raceConfig);
        log.info("✅ Race name saved: {} (duplicate read window: {} min, reader type: {})",
                trimmedName, duplicateReadWindowMinutes, readerType);

        raceRepository.findById(1L).ifPresent(race -> {
            race.setRaceName(trimmedName);
            race.setCreatedAt(LocalDateTime.now());
            race.setRaceDate(race.getCreatedAt());
            raceRepository.save(race);
        });
    }

    private void adaptUIForDeviceMode() {
        if (deviceModeService.isSlaveMode()) {
            startRaceButton.setDisable(false);
            stopRaceButton.setDisable(true);
            exportReadingsButton.setDisable(true);
            resetRaceButton.setDisable(false);
            
            raceStatusLabel.setText("📍 CHECKPOINT MODE - Ready to Start");
            raceStatusLabel.setStyle("-fx-text-fill: #3498db; -fx-font-weight: bold;");
            
            log.info("✅ UI adapted for SLAVE mode - Manual control enabled");
        } else {
            log.info("✅ UI adapted for MASTER mode");
        }
    }

    /**
     * ✅ Shows which enabled checkpoint is the start line, so it's always
     * visible during the race instead of only appearing in logs.
     */
    private void updateStartLineLabel() {
        if (startLineLabel == null) {
            return;
        }
        try {
            List<Checkpoint> checkpoints = checkpointRepository.findByEnabledTrueOrderByDisplayOrderAsc();
            Optional<Checkpoint> startCheckpoint = checkpoints.stream()
                .filter(Checkpoint::isStartLine)
                .findFirst();

            startLineLabel.setText(startCheckpoint.isPresent()
                ? "START: " + startCheckpoint.get().getName()
                : "START: Not configured");
        } catch (Exception e) {
            log.debug("Could not resolve start-line checkpoint: {}", e.getMessage());
        }
    }

    /**
     * ✅ Surfaces unregistered-tag scans (previously only a WARN log line) so
     * a stray/foreign tag scanned during a real race is visible in the UI.
     */
    private void updateUnregisteredScansLabel() {
        if (unregisteredScansLabel == null) {
            return;
        }
        int count = multiReaderService.getUnregisteredScanCount();
        unregisteredScansLabel.setText(String.valueOf(count));

        if (count == 0) {
            unregisteredScansLabel.setStyle("-fx-text-fill: #8fc1f0;");
            unregisteredScansLabel.setTooltip(null);
        } else {
            unregisteredScansLabel.setStyle("-fx-text-fill: #ffb4b0; -fx-font-weight: bold;");
            String lastTag = multiReaderService.getLastUnregisteredTag();
            LocalDateTime lastTime = multiReaderService.getLastUnregisteredScanTime();
            String lastTimeStr = lastTime != null ? lastTime.format(DateTimeFormatter.ofPattern("HH:mm:ss")) : "?";
            unregisteredScansLabel.setTooltip(new Tooltip("Last: " + lastTag + " at " + lastTimeStr));
        }
    }

    /**
     * ✅ Sibling of the unregistered-scans label: registered tags that were scanned
     * but are not on the current race's saved roster (rejected, no reading saved).
     */
    private void updateOffRosterScansLabel() {
        if (offRosterScansLabel == null) {
            return;
        }
        int count = multiReaderService.getOffRosterScanCount();
        offRosterScansLabel.setText(String.valueOf(count));

        if (count == 0) {
            offRosterScansLabel.setStyle("-fx-text-fill: #8fc1f0;");
            offRosterScansLabel.setTooltip(null);
        } else {
            offRosterScansLabel.setStyle("-fx-text-fill: #ffb4b0; -fx-font-weight: bold;");
            LocalDateTime lastTime = multiReaderService.getLastOffRosterScanTime();
            String lastTimeStr = lastTime != null ? lastTime.format(DateTimeFormatter.ofPattern("HH:mm:ss")) : "?";
            offRosterScansLabel.setTooltip(new Tooltip("Last: " + multiReaderService.getLastOffRosterParticipant()
                + " [tag " + multiReaderService.getLastOffRosterTag() + "] at " + lastTimeStr));
        }
    }

    /**
     * A live "HH:mm:ss" system clock in the (read-only) Declared Start field, so the operator sees
     * roughly what Start Race is about to capture - unless this race already HAS a captured
     * Declared Start (e.g. the app was restarted mid-race), in which case there's nothing left to
     * preview: show that frozen value instead of ticking over it.
     */
    private void startClock() {
        if (manualStartTimeField == null) {
            return;
        }
        Race race = currentRace();
        if (race != null && race.getManualStartTime() != null) {
            freezeDeclaredStartField(race.getManualStartTime());
            return;
        }
        manualStartTimeField.setText(LocalTime.now().format(TIME_ONLY_FORMAT));
        clockTimeline = new Timeline(new KeyFrame(javafx.util.Duration.millis(250),
                e -> manualStartTimeField.setText(LocalTime.now().format(TIME_ONLY_FORMAT))));
        clockTimeline.setCycleCount(Timeline.INDEFINITE);
        clockTimeline.play();
    }

    /**
     * Once Start Race has captured a Declared Start (see handleStartRace), the field is no longer
     * a live "what's about to happen" preview - it's the record of what did happen. Stops the
     * ticking timeline, shows the captured moment (time-only, matching the field's narrow width
     * and its own original format), and greys it out to signal it's fixed, not an editable input.
     */
    private void freezeDeclaredStartField(LocalDateTime declared) {
        if (clockTimeline != null) {
            clockTimeline.stop();
        }
        manualStartTimeField.setText(declared.format(TIME_ONLY_FORMAT));
        manualStartTimeField.setDisable(true);
    }

    /** Reset clears the Declared Start - the field goes back to a live ticking preview for the next Start Race. */
    private void resumeDeclaredStartTicking() {
        if (manualStartTimeField == null) {
            return;
        }
        manualStartTimeField.setDisable(false);
        manualStartTimeField.setText(LocalTime.now().format(TIME_ONLY_FORMAT));
        if (clockTimeline == null) {
            clockTimeline = new Timeline(new KeyFrame(javafx.util.Duration.millis(250),
                    e -> manualStartTimeField.setText(LocalTime.now().format(TIME_ONLY_FORMAT))));
            clockTimeline.setCycleCount(Timeline.INDEFINITE);
        }
        clockTimeline.play();
    }

    /** The manual Declared Start control exists only in MANUAL_START_DUAL_TIMING. */
    private void updateDeclaredStartVisibility() {
        if (declaredStartBox == null) {
            return;
        }
        boolean show = currentMode == Race.CalculationMode.MANUAL_START_DUAL_TIMING;
        declaredStartBox.setVisible(show);
        declaredStartBox.setManaged(show);
    }

    /**
     * Start Race is the one action that brings the readers up: it connects every enabled
     * checkpoint's readers (on a background thread - blocking TCP connects must never run on the
     * JavaFX thread) and leaves the race ARMED, listening. The official start time is then set by
     * MultiReaderService the moment the first valid tag is scanned at the start-line checkpoint
     * (ARMED -> ACTIVE); nothing here stamps a start time.
     */
    @FXML
    private void handleStartRace() {
        Race currentRace = currentRace();
        if (currentRace == null) {
            showAlert(Alert.AlertType.WARNING, "No Race", "No active race found. Please check configuration.");
            return;
        }

        // A fresh attempt (anything but reconnecting to an ACTIVE race or resuming a FINISHED one -
        // the same test as the branch below that begins a new attempt) needs a race name. Checked
        // before anything is armed or created: blank aborts completely (no readers, no
        // RaceSession, no status change); otherwise the name is saved through the same path as the
        // Save button BEFORE the attempt begins, since RaceSession copies it at creation.
        boolean resumingExisting = currentRace.getStartTime() != null
                && (currentRace.getStatus() == Race.RaceStatus.ACTIVE
                    || currentRace.getStatus() == Race.RaceStatus.FINISHED);
        if (!resumingExisting) {
            String enteredName = raceNameField != null && raceNameField.getText() != null
                    ? raceNameField.getText().trim() : "";
            if (enteredName.isEmpty()) {
                showAlert(Alert.AlertType.WARNING, "Race Name Required",
                        "Enter a race name before starting the race");
                if (raceNameField != null) {
                    raceNameField.requestFocus();
                }
                return;
            }
            try {
                persistRaceConfig(enteredName);
            } catch (Exception e) {
                log.error("❌ Failed to save race name before start", e);
                showAlert(Alert.AlertType.ERROR, "Error", "Failed to save race name: " + e.getMessage());
                return;
            }
        }

        List<Checkpoint> enabledCheckpoints = checkpointRepository.findByEnabledTrueOrderByDisplayOrderAsc();

        if (enabledCheckpoints.isEmpty()) {
            showAlert(Alert.AlertType.ERROR, "No Checkpoints",
                "No checkpoints are configured. Please configure checkpoints first.");
            return;
        }

        boolean hasStartLine = enabledCheckpoints.stream().anyMatch(Checkpoint::isStartLine);
        final Race.RaceStatus statusBefore = currentRace.getStatus();

        // ✅ Immediate feedback on the FX thread before any blocking reader work starts
        starting = true;
        startRaceButton.setDisable(true);
        raceStatusLabel.setText("🔌 Connecting readers...");
        raceStatusLabel.setStyle("-fx-text-fill: orange; -fx-font-weight: bold;");

        CompletableFuture.runAsync(() -> {
            MultiReaderService.ArmResult result;
            try {
                result = multiReaderService.armReaders(currentRace.getId(), enabledCheckpoints);
            } catch (Exception e) {
                log.error("❌ Race start error", e);
                Platform.runLater(() -> {
                    starting = false;
                    restoreRaceState();
                    showAlert(Alert.AlertType.ERROR, "Error", "Failed to start race: " + e.getMessage());
                });
                return;
            }
            final MultiReaderService.ArmResult armResult = result;

            Platform.runLater(() -> {
                starting = false;
                try {
                    if (!armResult.anyConnected()) {
                        restoreRaceState();
                        showAlert(Alert.AlertType.ERROR, "Reader Arming Failed",
                            armResult.attempted == 0
                                ? "No readers are configured on the enabled checkpoints."
                                : "No readers could be connected, so the race was not started:\n\n"
                                    + String.join("\n", armResult.failures));
                        return;
                    }

                    Race latest = currentRace();
                    if (statusBefore == Race.RaceStatus.ACTIVE && latest.getStartTime() != null) {
                        // Reconnecting after a restart: the race already started, keep its start time
                    } else if (statusBefore == Race.RaceStatus.FINISHED && latest.getStartTime() != null) {
                        // Resuming a race stopped by mistake - same attempt continues
                        latest.setStatus(Race.RaceStatus.ACTIVE);
                        latest.setEndTime(null);
                        latest.setManualEndTime(null);
                        reopenCurrentSession(latest);
                    } else {
                        // A fresh start - either the very first one, or the first Start Race after
                        // a Reset. Either way this begins a NEW attempt: its own boundary (so
                        // recalculation never mixes in an earlier attempt's readings) and its own
                        // RaceSession row (so the Reports tab can list it separately).
                        latest.setStatus(Race.RaceStatus.ARMED);
                        latest.setEndTime(null);
                        latest.setManualEndTime(null);
                        beginNewAttempt(latest);

                        // MANUAL_START_DUAL_TIMING: Start Race is also what declares the gun time
                        // now - there's no separate "Set" step any more. Captured as the exact
                        // system moment of THIS click (never from the ticking preview field's
                        // text, which is read-only and just an approximation a quarter-second
                        // stale at worst). Only on a genuinely fresh start - reconnecting after a
                        // restart or resuming a race stopped by mistake (the two branches above)
                        // must never silently re-declare an in-progress race's gun time.
                        if (currentMode == Race.CalculationMode.MANUAL_START_DUAL_TIMING) {
                            latest.setManualStartTime(LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS));
                        }
                    }
                    latest.setMasterDeviceId(deviceModeService.getDeviceId());
                    raceRepository.save(latest);

                    log.info("✅ Start Race: {}/{} reader(s) connected, race {} -> {} (Mode: {})",
                        armResult.connected, armResult.attempted, latest.getRaceName(), latest.getStatus(),
                        deviceModeService.getDeviceRole());

                    if (currentMode == Race.CalculationMode.MANUAL_START_DUAL_TIMING && latest.getManualStartTime() != null) {
                        currentStartTimeLabel.setText(latest.getManualStartTime().format(DECLARED_START_FORMAT));
                        freezeDeclaredStartField(latest.getManualStartTime());
                        log.info("✅ Declared start captured by Start Race: {}", latest.getManualStartTime());
                    }

                    restoreRaceState();
                    updateStartLineLabel();

                    if (!armResult.failures.isEmpty()) {
                        showAlert(Alert.AlertType.WARNING, "Some Readers Failed To Connect",
                            "Armed " + armResult.connected + "/" + armResult.attempted + " reader(s).\n\nFailed:\n"
                                + String.join("\n", armResult.failures));
                    }
                    if (!hasStartLine) {
                        showAlert(Alert.AlertType.WARNING, "No Start Line Checkpoint",
                            "Readers are listening, but no enabled checkpoint is ticked as Start, so the race " +
                                "start can't be detected from a first scan.\n\nTick Start on a location in Configuration.");
                    }
                } catch (Exception e) {
                    log.error("❌ Race start error", e);
                    restoreRaceState();
                    showAlert(Alert.AlertType.ERROR, "Error", "Failed to start race: " + e.getMessage());
                }
            });
        }, executorService);
    }

    @FXML
    private void handleStopRace() {
        Race currentRace = currentRace();
        if (currentRace == null) return;
        
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.setTitle("Stop Race");
        confirmation.setHeaderText("Stop Current Race?");
        confirmation.setContentText("This will stop all readers. Are you sure?");
        
        confirmation.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                try {
                    multiReaderService.stopRace();
                    
                    LocalDateTime endTime = LocalDateTime.now();
                    currentRace.setStatus(Race.RaceStatus.FINISHED);
                    currentRace.setEndTime(endTime);
                    currentRace.setManualEndTime(endTime);
                    raceRepository.save(currentRace);

                    if (currentRace.getCurrentSessionId() != null) {
                        raceSessionRepository.findById(currentRace.getCurrentSessionId()).ifPresent(session -> {
                            session.setEndedAt(endTime);
                            raceSessionRepository.save(session);
                        });
                    }

                    // FINISHED: enables Export + Calculate Results, disables Stop
                    restoreRaceState();
                    
                    String modeLabel = deviceModeService.isSlaveMode() ? "READING" : "RACE";
                    raceStatusLabel.setText("⏹️ " + modeLabel + " STOPPED at " + 
                        endTime.format(DateTimeFormatter.ofPattern("HH:mm:ss")));
                    raceStatusLabel.setStyle("-fx-text-fill: red; -fx-font-weight: bold;");
                    
                    log.info("✅ Race stopped: {} (Mode: {})", 
                        currentRace.getRaceName(), deviceModeService.getDeviceRole());
                    
                } catch (Exception e) {
                    showAlert(Alert.AlertType.ERROR, "Error", 
                        "Failed to stop race: " + e.getMessage());
                    log.error("❌ Stop error", e);
                }
            }
        });
    }

    @FXML
    private void handleResetRace() {
        Race currentRace = currentRace();

        if (currentRace == null) {
            showAlert(Alert.AlertType.WARNING, "No Race", "No active race found.");
            return;
        }
        
        Alert confirmation = new Alert(Alert.AlertType.WARNING);
        confirmation.setTitle("Reset Race Status");
        confirmation.setHeaderText("Reset Race to Initial State?");
        confirmation.setContentText(
            "This will:\n" +
            "• Stop all active RFID readers\n" +
            "• Reset race status to NOT STARTED\n" +
            "• Clear race start/end times\n" +
            "• Clear the race name and the race-day roster\n" +
            "• Enable Start Race button\n\n" +
            "Checkpoint readings and calculated results are NOT deleted - they stay on file " +
            "and remain available on the Reports tab. Starting the race again begins a new " +
            "attempt, so this session's old readings won't be mixed into the next one.\n\n" +
            "Continue?"
        );

        confirmation.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                try {
                    log.info("🔄 Resetting race: {}", currentRace.getRaceName());

                    if (multiReaderService.areReadersArmed()) {
                        multiReaderService.stopRace();
                        log.info("✅ Stopped active readers");
                    }

                    // The same routine app startup runs: seals the outgoing session if it is still
                    // open, blanks the name, NOT_STARTED, no current session/boundary, clears times
                    // and the roster. Never deletes sessions, results or readings.
                    lifecycleService.resetToFreshState();
                    multiReaderService.resetScanCounters();

                    log.info("✅ Race status reset to NOT_STARTED");

                    // Readers are not re-armed: they connect only when Start Race is clicked.
                    Platform.runLater(() -> {
                        currentStartTimeLabel.setText("Not Set");
                        resumeDeclaredStartTicking(); // Declared Start was just cleared - back to a live preview for the next Start Race
                        if (raceNameField != null) {
                            raceNameField.setText("");
                        }
                        if (raceConfigStatusLabel != null) {
                            raceConfigStatusLabel.setVisible(false);
                            raceConfigStatusLabel.setManaged(false);
                        }
                        updateUnregisteredScansLabel();
                        updateOffRosterScansLabel();
                        // NOT_STARTED: Start enabled, Stop/Export/Calculate disabled
                        restoreRaceState();

                        raceStatusLabel.setText("🔄 RESET - Ready to Start");
                        raceStatusLabel.setStyle("-fx-text-fill: #3498db; -fx-font-weight: bold;");

                        totalParticipantsLabel.setText("0");
                        finishedCountLabel.setText("0");
                        activeReadersLabel.setText("0");

                        liveResults.clear();
                        updateModeSettingsUI();

                        showAlert(Alert.AlertType.INFORMATION,
                            "Race Reset Successful",
                            "Race has been reset!\n\n" +
                            "✅ Status reset\n" +
                            "✅ Readers stopped\n" +
                            "✅ Race name and roster cleared\n" +
                            "✅ Readings and results kept - visible on the Reports tab\n" +
                            "✅ Ready to start fresh race"
                        );

                        log.info("✅ Race reset completed successfully");
                    });

                } catch (Exception e) {
                    log.error("❌ Failed to reset race", e);
                    Platform.runLater(() -> {
                        showAlert(Alert.AlertType.ERROR, 
                            "Reset Failed", 
                            "Failed to reset race:\n\n" + e.getMessage());
                    });
                }
            }
        });
    }

    @FXML
    private void handleExportReadings() {
        Race currentRace = currentRace();

        if (currentRace == null) {
            showAlert(Alert.AlertType.WARNING, "No Race", "No active race found.");
            return;
        }
        
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Export Tag Readings");
        fileChooser.setInitialFileName("tag_readings_" + 
            deviceModeService.getDeviceId() + "_" + 
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + 
            ".xlsx");
        fileChooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("Excel Files", "*.xlsx")
        );
        
        File file = fileChooser.showSaveDialog(exportReadingsButton.getScene().getWindow());
        if (file != null) {
            new Thread(() -> {
                try {
                    exportReadingsToExcel(file);
                    
                    Platform.runLater(() -> {
                        showAlert(Alert.AlertType.INFORMATION, "Export Successful", 
                            "✅ Tag readings exported successfully!\n\n" +
                            "File: " + file.getName() + "\n" +
                            "Location: " + file.getAbsolutePath());
                    });
                    
                } catch (Exception e) {
                    log.error("❌ Export failed", e);
                    Platform.runLater(() -> {
                        showAlert(Alert.AlertType.ERROR, "Export Failed", 
                            "Failed to export readings:\n" + e.getMessage());
                    });
                }
            }).start();
        }
    }

    private void exportReadingsToExcel(File file) throws Exception {
        Race currentRace = currentRace();
        LocalDateTime attemptStart = currentRace.getCurrentAttemptStartedAt();

        List<CheckpointReading> allReadings = readingRepository.findByRaceIdOrderByReadTimeAsc(currentRace.getId())
            .stream()
            .filter(r -> r.getParticipant() != null)
            .filter(r -> !r.isUploadedToServer())
            .filter(r -> inCurrentAttempt(r, attemptStart))
            .collect(Collectors.toList());
        
        Map<String, List<CheckpointReading>> readingsByLocation = allReadings.stream()
            .collect(Collectors.groupingBy(r -> 
                r.getCheckpoint() != null ? r.getCheckpoint().getLocationName() : "Unknown"
            ));
        
        try (Workbook workbook = new XSSFWorkbook()) {
            for (Map.Entry<String, List<CheckpointReading>> entry : readingsByLocation.entrySet()) {
                String locationName = entry.getKey();
                List<CheckpointReading> readings = entry.getValue();
                
                Sheet sheet = workbook.createSheet(sanitizeSheetName(locationName));
                
                Row headerRow = sheet.createRow(0);
                CellStyle headerStyle = createHeaderStyle(workbook);
                
                String[] headers = {"#", "Tag ID", "Participant Name", "Service No", "Unit", 
                                   "Read Time", "Device ID", "Reader ID"};
                for (int i = 0; i < headers.length; i++) {
                    org.apache.poi.ss.usermodel.Cell cell = headerRow.createCell(i);
                    cell.setCellValue(headers[i]);
                    cell.setCellStyle(headerStyle);
                }
                
                int rowNum = 1;
                for (CheckpointReading reading : readings) {
                    Row row = sheet.createRow(rowNum++);
                    
                    row.createCell(0).setCellValue(rowNum - 1);
                    row.createCell(1).setCellValue(reading.getRfidTag() != null ? reading.getRfidTag() : "");
                    
                    Participant p = reading.getParticipant();
                    row.createCell(2).setCellValue(p != null && p.getName() != null ? p.getName() : "");
                    row.createCell(3).setCellValue(p != null && p.getServiceNumber() != null ? p.getServiceNumber() : "");
                    row.createCell(4).setCellValue(p != null && p.getUnit() != null ? p.getUnit() : "");
                    
                    row.createCell(5).setCellValue(
                        reading.getReadTime() != null ? 
                        reading.getReadTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")) : ""
                    );
                    
                    row.createCell(6).setCellValue(reading.getDeviceId() != null ? reading.getDeviceId() : "");
                    row.createCell(7).setCellValue(reading.getReaderId() != null ? reading.getReaderId() : "");
                }
                
                for (int i = 0; i < headers.length; i++) {
                    sheet.autoSizeColumn(i);
                }
            }
            
            Sheet summarySheet = workbook.createSheet("Summary");
            Row summaryHeader = summarySheet.createRow(0);
            CellStyle headerStyle = createHeaderStyle(workbook);
            
            summaryHeader.createCell(0).setCellValue("Location");
            summaryHeader.createCell(1).setCellValue("Total Readings");
            summaryHeader.getCell(0).setCellStyle(headerStyle);
            summaryHeader.getCell(1).setCellStyle(headerStyle);
            
            int summaryRow = 1;
            for (Map.Entry<String, List<CheckpointReading>> entry : readingsByLocation.entrySet()) {
                Row row = summarySheet.createRow(summaryRow++);
                row.createCell(0).setCellValue(entry.getKey());
                row.createCell(1).setCellValue(entry.getValue().size());
            }
            
            summarySheet.autoSizeColumn(0);
            summarySheet.autoSizeColumn(1);
            
            try (FileOutputStream fos = new FileOutputStream(file)) {
                workbook.write(fos);
            }
            
            log.info("✅ Exported {} LIVE readings from {} locations to: {}", 
                allReadings.size(), readingsByLocation.size(), file.getAbsolutePath());
        }
    }

    private String sanitizeSheetName(String name) {
        return name.replaceAll("[\\\\/:*?\\[\\]]", "_")
                   .substring(0, Math.min(name.length(), 31));
    }

    private CellStyle createHeaderStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        return style;
    }

    // ========================================================================
    // ✅ CALCULATE RESULTS (moved here from the Results tab — Results is now
    // a read-only view of whatever was last calculated from this screen)
    // ========================================================================

    private void setupComboBoxes() {
        calculationModeComboBox.setItems(FXCollections.observableArrayList(
                "2 CHECKPOINTS - Start + Finish Only",
                "SAME START/FINISH - First Read Start, Second Read Finish",
                "LAP RACE - Same Point, Multiple Laps",
                "LONG DISTANCE - Start + Midpoint + Finish",
                "LONG DISTANCE 2 - Start + 2 Midpoints + Finish",
                "MANUAL START - Declared Start + Start Scan + Finish"
        ));
        calculationModeComboBox.setValue("2 CHECKPOINTS - Start + Finish Only");

        calculationModeComboBox.valueProperty().addListener((obs, oldVal, newVal) -> {
            currentMode = parseCalculationMode(newVal);
            // Published for the Configuration page's mode-dependent Start/Finish rules
            modeSelection.set(currentMode);
            updateModeSettingsUI();
            updateDeclaredStartVisibility();
        });
    }

    /**
     * ✅ Builds whatever extra controls a calculation mode needs (lap count/
     * distance, or a hint pointing at the Start Time control). No file upload
     * anymore — results are calculated straight from the live RFID readings
     * already recorded for this race by the checkpoint readers.
     */
    private void updateModeSettingsUI() {
        modeSettingsContainer.getChildren().clear();

        if (modeNeedsLapConfig()) {
            HBox lapBox = new HBox(12);
            lapBox.setAlignment(Pos.CENTER_LEFT);
            lapBox.getStyleClass().add("upload-row");

            Label lapCountLabel = new Label("Lap Count:");
            lapCountLabel.getStyleClass().add("field-label-light");
            lapCountLabel.setMinWidth(Region.USE_PREF_SIZE);

            lapCountField = new TextField();
            lapCountField.setPromptText("e.g., 4");
            lapCountField.setPrefWidth(100);
            lapCountField.getStyleClass().add("text-field");
            lapCountField.setText(String.valueOf(getLapCountValue()));

            Label lapDistanceLabel = new Label("Lap Distance (km):");
            lapDistanceLabel.getStyleClass().add("field-label-light");
            lapDistanceLabel.setMinWidth(Region.USE_PREF_SIZE);

            lapDistanceField = new TextField();
            lapDistanceField.setPromptText("e.g., 1.6");
            lapDistanceField.setPrefWidth(100);
            lapDistanceField.getStyleClass().add("text-field");
            lapDistanceField.setText(String.valueOf(getLapDistanceValue()));

            Button setLapButton = new Button("Set Laps");
            setLapButton.getStyleClass().addAll("btn", "btn-primary");
            setLapButton.setOnAction(e -> handleSetLapConfig());

            lapBox.getChildren().addAll(lapCountLabel, lapCountField, lapDistanceLabel, lapDistanceField, setLapButton);
            modeSettingsContainer.getChildren().add(lapBox);
        } else {
            lapCountField = null;
            lapDistanceField = null;
        }

        if (modeUsesAutoDetectedRaceStart()) {
            Label startTimeHint = new Label(
                    "ℹ️ The start time is detected automatically: the first tag scan at the start-line checkpoint " +
                            "sets it (runners wait out of read range, then the first scan after they take off becomes the start). " +
                            "Tick Start on a location in Configuration - there is no manual start entry in this mode.");
            startTimeHint.getStyleClass().add("field-hint-light");
            startTimeHint.setWrapText(true);
            modeSettingsContainer.getChildren().add(startTimeHint);
        }

        if (currentMode == Race.CalculationMode.MANUAL_START_DUAL_TIMING) {
            Label declaredHint = new Label(
                    "⏰ Clicking Start Race declares the race start at that exact moment - one shared gun time for " +
                            "everybody, captured automatically, not typed in. Each runner's own Start Time still comes " +
                            "from their first scan at the Start checkpoint. " +
                            "Results show both: Duration (Declared Start) and Duration (Start Time).");
            declaredHint.getStyleClass().add("field-hint-light");
            declaredHint.setWrapText(true);
            modeSettingsContainer.getChildren().add(declaredHint);
        }

        Label readyHint = new Label("✅ Uses live RFID readings already recorded for this race — no file upload needed.");
        readyHint.getStyleClass().add("field-hint-light");
        modeSettingsContainer.getChildren().add(readyHint);
    }

    private void handleSetLapConfig() {
        Race latestRace = currentRace();
        if (latestRace == null) {
            return;
        }
        int lapCount = getLapCountValue();
        double lapDistance = getLapDistanceValue();

        latestRace.setLapCount(lapCount);
        latestRace.setLapDistanceKm(lapDistance);
        raceRepository.save(latestRace);

        showAlert(Alert.AlertType.INFORMATION, "Lap Configuration Saved",
                "Lap Count: " + lapCount + "\nLap Distance (km): " + lapDistance);
    }

    /** Long Distance modes use one race-wide start: the first scan at the start-line checkpoint. */
    private boolean modeUsesAutoDetectedRaceStart() {
        return currentMode == Race.CalculationMode.LONG_DISTANCE_MANUAL_START
                || currentMode == Race.CalculationMode.LONG_DISTANCE_2_MANUAL_START;
    }

    private boolean modeNeedsLapConfig() {
        return currentMode == Race.CalculationMode.LAP_RACE;
    }

    private int getLapCountValue() {
        if (lapCountField != null) {
            try {
                int value = Integer.parseInt(lapCountField.getText().trim());
                return Math.max(1, value);
            } catch (Exception ignored) {
            }
        }
        Race latestRace = currentRace();
        if (latestRace != null && latestRace.getLapCount() != null && latestRace.getLapCount() > 0) {
            return latestRace.getLapCount();
        }
        return 4;
    }

    private double getLapDistanceValue() {
        if (lapDistanceField != null) {
            try {
                double value = Double.parseDouble(lapDistanceField.getText().trim());
                return Math.max(0.1, value);
            } catch (Exception ignored) {
            }
        }
        Race latestRace = currentRace();
        if (latestRace != null && latestRace.getLapDistanceKm() != null && latestRace.getLapDistanceKm() > 0.0) {
            return latestRace.getLapDistanceKm();
        }
        return 1.6;
    }

    /**
     * ✅ Calculates results directly from the live RFID readings already
     * captured for this race by the checkpoint readers — no file upload,
     * no checkpoint re-creation. Only previously-computed results are
     * cleared before recalculating; raw readings are never touched here.
     */
    @FXML
    private void handleCalculate() {
        Race latestRace = currentRace();
        if (latestRace == null) {
            showAlert(Alert.AlertType.WARNING, "No Race", "No race configured");
            return;
        }
        if (latestRace.getStatus() != Race.RaceStatus.FINISHED) {
            showAlert(Alert.AlertType.WARNING, "Race Not Stopped",
                    "Results can only be calculated after the race has been stopped. Press Stop Race first.");
            return;
        }

        Race.CalculationMode selectedMode = parseCalculationMode(calculationModeComboBox.getValue());
        currentMode = selectedMode;

        LocalDateTime attemptStart = latestRace.getCurrentAttemptStartedAt();
        long readingCount = readingRepository.findByRaceIdOrderByReadTimeAsc(latestRace.getId()).stream()
                .filter(r -> inCurrentAttempt(r, attemptStart))
                .count();
        if (readingCount == 0) {
            showAlert(Alert.AlertType.WARNING, "No Tag Readings",
                    "No RFID readings have been recorded for this race yet.\n\n" +
                            "Start the race and let the checkpoint readers scan tags first.");
            return;
        }

        // Checked before anything is touched, so a missing start never costs the existing results.
        // Normally unreachable for a fresh MANUAL_START_DUAL_TIMING attempt (Start Race captures
        // the Declared Start itself) - this only fires if the mode was switched to Manual Start
        // in the dropdown after the race was already started under a different mode.
        if (selectedMode == Race.CalculationMode.MANUAL_START_DUAL_TIMING && latestRace.getManualStartTime() == null) {
            showAlert(Alert.AlertType.WARNING, "Declared Start Not Set",
                    "This attempt has no Declared Start - it was started under a different calculation mode.\n\n" +
                            "Press Reset, select Manual Start, then Start Race to declare a start for this mode.");
            return;
        }
        if ((selectedMode == Race.CalculationMode.LONG_DISTANCE_MANUAL_START
                || selectedMode == Race.CalculationMode.LONG_DISTANCE_2_MANUAL_START)
                && latestRace.getStartTime() == null) {
            showAlert(Alert.AlertType.WARNING, "Start Time Not Detected",
                    "This mode takes its start from the first tag scan at the start-line checkpoint, " +
                            "and no such scan has been recorded.\n\nTick Start on a checkpoint in Configuration " +
                            "and make sure runners are scanned there after Start Race.");
            return;
        }

        latestRace.setCalculationMode(selectedMode);
        if (selectedMode == Race.CalculationMode.LAP_RACE) {
            latestRace.setLapCount(getLapCountValue());
            latestRace.setLapDistanceKm(getLapDistanceValue());
        }
        raceRepository.save(latestRace);
        final Long raceId = latestRace.getId();

        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.setTitle("Calculate Results");
        confirmation.setHeaderText("Calculate results from live RFID readings?");

        String modeDescription;
        switch (selectedMode) {
            case SAME_START_FINISH -> modeDescription = "Mode: Same Start/Finish (2 reads)";
            case LAP_RACE -> modeDescription = "Mode: Lap Race (" + getLapCountValue() + " laps)";
            case LONG_DISTANCE_MANUAL_START -> modeDescription = "Mode: Long Distance (Start + Midpoint + Finish)";
            case LONG_DISTANCE_2_MANUAL_START -> modeDescription = "Mode: Long Distance 2 (Start + 2 Midpoints + Finish)";
            case MANUAL_START_DUAL_TIMING -> modeDescription = "Mode: Manual Start (Declared Start + each runner's Start Scan + Finish)";
            default -> modeDescription = "Mode: Configured checkpoints (Start/Finish, or more if enabled)";
        }

        String startTimeDisplay;
        if (selectedMode == Race.CalculationMode.MANUAL_START_DUAL_TIMING) {
            startTimeDisplay = "Declared Start " + latestRace.getManualStartTime().format(DECLARED_START_FORMAT);
        } else if (modeUsesAutoDetectedRaceStart()) {
            startTimeDisplay = latestRace.getStartTime().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + " (first start-line scan)";
        } else {
            startTimeDisplay = "each runner's own start-line scan";
        }

        confirmation.setContentText(
                String.format("Tag readings recorded: %d\nStart Time: %s\n%s\n\nCalculate results?",
                        readingCount,
                        startTimeDisplay,
                        modeDescription)
        );

        confirmation.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                calculating = true;
                calculateButton.setDisable(true);

                new Thread(() -> {
                    try {
                        log.info("Clearing previously calculated results...");

                        Race threadRace = raceRepository.findById(raceId).orElse(null);
                        if (threadRace == null) {
                            throw new RuntimeException("Race not found while calculating results");
                        }
                        // No current attempt means nothing to calculate - never fall back to a
                        // whole-race (or createdAt) scope, which would touch every attempt's rows.
                        if (!threadRace.hasCurrentAttempt()) {
                            throw new IllegalStateException("No current race attempt - start a race before calculating results");
                        }
                        Long threadSessionId = threadRace.getCurrentSessionId();

                        // Operator notes (Manual Start results) survive a recalculation. Scoped to
                        // the current attempt via raceSessionId (the reliable link) - an earlier
                        // attempt's saved notes are untouched.
                        List<RaceResult> priorAttemptResults = resultRepository.findByRaceSessionId(threadSessionId);
                        Map<Long, String> priorNotes = priorAttemptResults.stream()
                                .filter(r -> r.getParticipant() != null && r.getRemarks() != null && !r.getRemarks().isBlank())
                                .collect(Collectors.toMap(r -> r.getParticipant().getId(), RaceResult::getRemarks, (a, b) -> a));

                        // Only THIS attempt's previously-calculated results are cleared - every
                        // earlier attempt's results are left alone, permanently. Session-scoped
                        // (not createdAt-scoped) so this is exact rather than a time-window guess;
                        // RaceTimingService.calculateRaceResults repeats this same clear as a safety
                        // net for any caller that doesn't go through this UI path (e.g. tests).
                        resultRepository.deleteByRaceSessionId(threadSessionId);

                        threadRace.setCalculationMode(selectedMode);
                        raceRepository.save(threadRace);
                        if (threadRace.getCurrentSessionId() != null) {
                            raceSessionRepository.findById(threadRace.getCurrentSessionId()).ifPresent(session -> {
                                session.setCalculationMode(selectedMode);
                                raceSessionRepository.save(session);
                            });
                        }

                        log.info("Calculating results with mode: {}", selectedMode);

                        timingService.calculateRaceResults(raceId, selectedMode);

                        // The manual-start mode has no Rank/Winner concept
                        if (selectedMode != Race.CalculationMode.MANUAL_START_DUAL_TIMING) {
                            log.info("Assigning rankings...");
                            timingService.assignRankings(raceId);
                        }

                        List<RaceResult> thisAttemptResults = resultRepository.findByRaceSessionId(threadSessionId);

                        if (!priorNotes.isEmpty()) {
                            thisAttemptResults.stream()
                                    .filter(r -> r.getParticipant() != null)
                                    .forEach(r -> r.setRemarks(priorNotes.get(r.getParticipant().getId())));
                            resultRepository.saveAll(thisAttemptResults);
                        }

                        long finished = thisAttemptResults.stream().filter(RaceResult::isCompletedRace).count();
                        long dnf = thisAttemptResults.size() - finished;

                        Platform.runLater(() -> {
                            calculating = false;
                            restoreRaceState();
                            log.info("Calculation complete: {} finished, {} DNF", finished, dnf);

                            // Straight to the Results tab, with a brief confirmation that clears itself
                            if (onResultsReady != null) {
                                onResultsReady.run();
                            }
                            showBriefNotice("Results calculated",
                                    String.format("✅ Results calculated\n\nFinished: %d   DNF: %d", finished, dnf));
                        });

                    } catch (Exception e) {
                        Platform.runLater(() -> {
                            calculating = false;
                            restoreRaceState();
                            showAlert(Alert.AlertType.ERROR, "Calculation Failed",
                                    "Error: " + e.getMessage());
                        });
                        log.error("Calculation error", e);
                    }
                }).start();
            }
        });
    }

    /** A non-blocking confirmation that dismisses itself after a couple of seconds. */
    private void showBriefNotice(String title, String message) {
        Alert notice = new Alert(Alert.AlertType.INFORMATION);
        notice.setTitle(title);
        notice.setHeaderText(null);
        notice.setContentText(message);
        notice.initModality(javafx.stage.Modality.NONE);
        notice.show();

        PauseTransition autoClose = new PauseTransition(javafx.util.Duration.seconds(2.5));
        autoClose.setOnFinished(e -> notice.hide());
        autoClose.play();
    }

    private Race.CalculationMode parseCalculationMode(String modeStr) {
        if (modeStr.startsWith("2 CHECKPOINTS")) return Race.CalculationMode.TWO_CHECKPOINT;
        if (modeStr.startsWith("SAME START/FINISH")) return Race.CalculationMode.SAME_START_FINISH;
        if (modeStr.startsWith("LAP RACE")) return Race.CalculationMode.LAP_RACE;
        if (modeStr.startsWith("MANUAL START")) return Race.CalculationMode.MANUAL_START_DUAL_TIMING;
        // ✅ Check "LONG DISTANCE 2" before the plain "LONG DISTANCE" prefix,
        // since the latter is itself a prefix of the former.
        if (modeStr.startsWith("LONG DISTANCE 2")) return Race.CalculationMode.LONG_DISTANCE_2_MANUAL_START;
        if (modeStr.startsWith("LONG DISTANCE")) return Race.CalculationMode.LONG_DISTANCE_MANUAL_START;
        return Race.CalculationMode.TWO_CHECKPOINT;
    }

    private String getModeLabel(Race.CalculationMode mode) {
        return switch (mode) {
            case SAME_START_FINISH -> "SAME START/FINISH - First Read Start, Second Read Finish";
            case LAP_RACE -> "LAP RACE - Same Point, Multiple Laps";
            case MANUAL_START_DUAL_TIMING -> "MANUAL START - Declared Start + Start Scan + Finish";
            case LONG_DISTANCE_MANUAL_START -> "LONG DISTANCE - Start + Midpoint + Finish";
            case LONG_DISTANCE_2_MANUAL_START -> "LONG DISTANCE 2 - Start + 2 Midpoints + Finish";
            default -> "2 CHECKPOINTS - Start + Finish Only";
        };
    }

    private void startLiveUpdates() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "LiveTracking-Updater");
            t.setDaemon(true);
            return t;
        });
        
        scheduler.scheduleAtFixedRate(() -> {
            Platform.runLater(() -> {
                refreshLiveResults();
                updateStatus();
                // ✅ Picks up the ARMED -> ACTIVE transition MultiReaderService makes
                // in the background the moment the first start-line scan comes in.
                restoreRaceState();
                updateStartLineLabel();
                updateUnregisteredScansLabel();
                updateOffRosterScansLabel();
            });
        }, 0, 2, TimeUnit.SECONDS);
    }

    private void refreshLiveResults() {
        Race currentRace = currentRace();

        if (currentRace != null) {
            try {
                // NOT_STARTED means there is no current attempt (a fresh install, or right
                // after Reset) - render blank instead of falling back to whatever attempt
                // boundary/readings happen to still be on file from before the reset. Readings
                // themselves are never touched by Reset; this only affects what's shown here.
                if (!currentRace.hasCurrentAttempt()) {
                    liveResults.clear();
                    return;
                }
                LocalDateTime attemptStart = currentRace.getCurrentAttemptStartedAt();
                // Bounded at the SQL level (LIMIT 100 via Pageable), not fetch-everything-then-
                // .limit(100)-in-Java - this tick's cost stays flat regardless of how many
                // readings the race has accumulated so far (see repository method javadoc).
                List<CheckpointReading> readings = readingRepository.findRecentForLiveTracking(
                    currentRace.getId(), attemptStart, org.springframework.data.domain.PageRequest.of(0, 100));

                List<LiveParticipantRow> rows = readings.stream()
                    .map(this::convertReadingToRow)
                    .collect(Collectors.toList());
                
                liveResults.clear();
                liveResults.addAll(rows);
                
            } catch (Exception e) {
                log.error("❌ Failed to refresh live results", e);
            }
        }
    }

    private LiveParticipantRow convertReadingToRow(CheckpointReading reading) {
        Participant p = reading.getParticipant();
        String name = p != null ? p.getName() : "Unknown";
        String serviceNo = p != null && p.getSerialNo() != null ? p.getSerialNo() : "";
        String unit = p != null && p.getUnit() != null ? p.getUnit() : "";
        String checkpoint = reading.getCheckpoint() != null ? reading.getCheckpoint().getLocationName() : "Unknown";
        String time = reading.getReadTime() != null ?
            reading.getReadTime().format(DateTimeFormatter.ofPattern("HH:mm:ss")) : "";

        return new LiveParticipantRow(name, serviceNo, unit, checkpoint, time);
    }

    private void updateStatus() {
        Race currentRace = currentRace();

        if (currentRace != null) {
            try {
                // NOT_STARTED means there is no current attempt - the reading/finished counts
                // must render as 0 rather than pick up whatever attempt boundary is still on
                // file from before the last Reset. Roster size is unaffected: it's a fact
                // about registration, not about the current attempt.
                // Aggregated in SQL (COUNT / COUNT DISTINCT), not fetch-every-reading-then-count-
                // in-Java - same flat-cost reasoning as refreshLiveResults() above.
                boolean hasCurrentAttempt = currentRace.hasCurrentAttempt();
                LocalDateTime attemptStart = currentRace.getCurrentAttemptStartedAt();
                long registeredCount = !hasCurrentAttempt ? 0
                    : readingRepository.countDistinctTagsInCurrentAttempt(currentRace.getId(), attemptStart);
                long totalReadings = !hasCurrentAttempt ? 0
                    : readingRepository.countInCurrentAttempt(currentRace.getId(), attemptStart);

                // With a saved roster, PARTICIPANTS is the roster size; without one it
                // stays the number of distinct registered tags seen so far.
                long rosterSize = raceEntryRepository.countByRaceId(currentRace.getId());
                totalParticipantsLabel.setText(String.valueOf(rosterSize > 0 ? rosterSize : registeredCount));
                totalParticipantsLabel.setTooltip(rosterSize > 0
                    ? new Tooltip("Race roster size (" + registeredCount + " seen so far)") : null);
                finishedCountLabel.setText(String.valueOf(totalReadings));
                
                int activeReaderCount = multiReaderService.getActiveReaderCount();
                activeReadersLabel.setText(String.valueOf(activeReaderCount));
                
            } catch (Exception e) {
                log.error("❌ Failed to update status", e);
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        if (clockTimeline != null) {
            Platform.runLater(clockTimeline::stop);
        }
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        executorService.shutdown();
    }

    private void showAlert(Alert.AlertType type, String title, String content) {
        Platform.runLater(() -> {
            Alert alert = new Alert(type);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(content);
            alert.showAndWait();
        });
    }

    // ✅ NEW: Simple data class for live tracking display
    public static class LiveParticipantRow {
        private final String name;
        private final String serviceNo;
        private final String unit;
        private final String checkpoint;
        private final String time;

        public LiveParticipantRow(String name, String serviceNo, String unit,
                                 String checkpoint, String time) {
            this.name = name;
            this.serviceNo = serviceNo;
            this.unit = unit;
            this.checkpoint = checkpoint;
            this.time = time;
        }

        public String getName() { return name; }
        public String getServiceNo() { return serviceNo; }
        public String getUnit() { return unit; }
        public String getCheckpoint() { return checkpoint; }
        public String getTime() { return time; }
    }
}
