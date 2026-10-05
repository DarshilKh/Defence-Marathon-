package com.army.marathon.ui;

import com.army.marathon.model.Checkpoint;
import com.army.marathon.model.Race;
import com.army.marathon.model.RaceConfiguration;
import com.army.marathon.reader.ClouReaderClient;
import com.army.marathon.reader.ReaderTestResult;
import com.army.marathon.reader.ZebraFxReaderClient;
import com.army.marathon.repository.CheckpointRepository;
import com.army.marathon.service.CalculationModeSelection;
import com.army.marathon.service.MultiReaderService;
import com.army.marathon.repository.RaceConfigurationRepository;
import com.army.marathon.repository.RaceRepository;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import lombok.extern.slf4j.Slf4j;
import net.rgielen.fxweaver.core.FxmlView;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Component
@FxmlView("ConfigurationView.fxml")
public class ConfigurationController {

    private final CheckpointRepository checkpointRepository;
    private final RaceConfigurationRepository raceConfigRepository;
    private final MultiReaderService multiReaderService;
    private final RaceRepository raceRepository;
    private final CalculationModeSelection modeSelection;
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    // Location 1 Controls
    @FXML
    private TextField location1NameField;
    @FXML
    private CheckBox location1EnabledCheckBox;
    @FXML
    private ComboBox<Integer> location1ReaderCountComboBox;
    @FXML
    private TextField location1ReaderAIpField;
    @FXML
    private TextField location1ReaderAPortField;
    @FXML
    private Circle location1ReaderAStatus;
    @FXML
    private TextField location1ReaderBIpField;
    @FXML
    private TextField location1ReaderBPortField;
    @FXML
    private Circle location1ReaderBStatus;
    @FXML
    private TextField location1DistanceField;
    @FXML
    private CheckBox location1StartCheckBox;
    @FXML
    private CheckBox location1FinishCheckBox;
    @FXML
    private VBox location1ReaderBContainer;
    // Location 2 Controls
    @FXML
    private TextField location2NameField;
    @FXML
    private CheckBox location2EnabledCheckBox;
    @FXML
    private ComboBox<Integer> location2ReaderCountComboBox;
    @FXML
    private TextField location2ReaderAIpField;
    @FXML
    private TextField location2ReaderAPortField;
    @FXML
    private Circle location2ReaderAStatus;
    @FXML
    private TextField location2ReaderBIpField;
    @FXML
    private TextField location2ReaderBPortField;
    @FXML
    private Circle location2ReaderBStatus;
    @FXML
    private TextField location2DistanceField;
    @FXML
    private CheckBox location2StartCheckBox;
    @FXML
    private CheckBox location2FinishCheckBox;
    @FXML
    private VBox location2ReaderBContainer;
    // Location 3 Controls
    @FXML
    private TextField location3NameField;
    @FXML
    private CheckBox location3EnabledCheckBox;
    @FXML
    private ComboBox<Integer> location3ReaderCountComboBox;
    @FXML
    private TextField location3ReaderAIpField;
    @FXML
    private TextField location3ReaderAPortField;
    @FXML
    private Circle location3ReaderAStatus;
    @FXML
    private TextField location3ReaderBIpField;
    @FXML
    private TextField location3ReaderBPortField;
    @FXML
    private Circle location3ReaderBStatus;
    @FXML
    private TextField location3DistanceField;
    @FXML
    private CheckBox location3StartCheckBox;
    @FXML
    private CheckBox location3FinishCheckBox;
    @FXML
    private VBox location3ReaderBContainer;
    // Location 4 Controls
    @FXML
    private TextField location4NameField;
    @FXML
    private CheckBox location4EnabledCheckBox;
    @FXML
    private ComboBox<Integer> location4ReaderCountComboBox;
    @FXML
    private TextField location4ReaderAIpField;
    @FXML
    private TextField location4ReaderAPortField;
    @FXML
    private Circle location4ReaderAStatus;
    @FXML
    private TextField location4ReaderBIpField;
    @FXML
    private TextField location4ReaderBPortField;
    @FXML
    private Circle location4ReaderBStatus;
    @FXML
    private TextField location4DistanceField;
    @FXML
    private CheckBox location4StartCheckBox;
    @FXML
    private CheckBox location4FinishCheckBox;
    @FXML
    private VBox location4ReaderBContainer;
    @FXML
    private Button saveConfigButton;
    @FXML
    private Button testReadersButton;
    @FXML
    private Button resetButton;

    // Antenna indicator mount points (4 per reader, stacked vertically)
    @FXML private VBox location1ReaderAAntennaBox;
    @FXML private VBox location1ReaderBAntennaBox;
    @FXML private VBox location2ReaderAAntennaBox;
    @FXML private VBox location2ReaderBAntennaBox;
    @FXML private VBox location3ReaderAAntennaBox;
    @FXML private VBox location3ReaderBAntennaBox;
    @FXML private VBox location4ReaderAAntennaBox;
    @FXML private VBox location4ReaderBAntennaBox;

    // "X/4 antennas active" summary line under each antenna list
    @FXML private Label location1ReaderAAntennaSummary;
    @FXML private Label location1ReaderBAntennaSummary;
    @FXML private Label location2ReaderAAntennaSummary;
    @FXML private Label location2ReaderBAntennaSummary;
    @FXML private Label location3ReaderAAntennaSummary;
    @FXML private Label location3ReaderBAntennaSummary;
    @FXML private Label location4ReaderAAntennaSummary;
    @FXML private Label location4ReaderBAntennaSummary;

    private final Map<String, Circle[]> antennaIndicators = new HashMap<>();
    private final Map<String, Label> antennaSummaryLabels = new HashMap<>();

    public ConfigurationController(CheckpointRepository checkpointRepository,
                                   RaceConfigurationRepository raceConfigRepository,
                                   MultiReaderService multiReaderService,
                                   RaceRepository raceRepository,
                                   CalculationModeSelection modeSelection) {
        this.checkpointRepository = checkpointRepository;
        this.raceConfigRepository = raceConfigRepository;
        this.multiReaderService = multiReaderService;
        this.raceRepository = raceRepository;
        this.modeSelection = modeSelection;
    }

    @FXML
    public void initialize() {
        log.info("🔧 Initializing Configuration Controller");

        setupReaderCountComboBoxes();
        setupCheckboxListeners();
        setupReaderCountListeners();
        buildAntennaIndicators();
        loadConfiguration();

        log.info("✅ Configuration Controller initialized");
    }

    /**
     * ✅ Build 4 antenna indicator circles per reader (8 per location with 2 readers).
     * Built programmatically instead of 32 hand-declared FXML fields.
     */
    private void buildAntennaIndicators() {
        registerAntennaBox("L1_A", location1ReaderAAntennaBox, location1ReaderAAntennaSummary);
        registerAntennaBox("L1_B", location1ReaderBAntennaBox, location1ReaderBAntennaSummary);
        registerAntennaBox("L2_A", location2ReaderAAntennaBox, location2ReaderAAntennaSummary);
        registerAntennaBox("L2_B", location2ReaderBAntennaBox, location2ReaderBAntennaSummary);
        registerAntennaBox("L3_A", location3ReaderAAntennaBox, location3ReaderAAntennaSummary);
        registerAntennaBox("L3_B", location3ReaderBAntennaBox, location3ReaderBAntennaSummary);
        registerAntennaBox("L4_A", location4ReaderAAntennaBox, location4ReaderAAntennaSummary);
        registerAntennaBox("L4_B", location4ReaderBAntennaBox, location4ReaderBAntennaSummary);
    }

    /**
     * ✅ Build one row per antenna (circle + "Antenna N" label), stacked vertically,
     * so each of the 4 antennas can be read off individually instead of 4 tiny dots in a row.
     */
    private void registerAntennaBox(String key, VBox container, Label summaryLabel) {
        if (container == null) return;

        Circle[] circles = new Circle[4];
        for (int i = 0; i < 4; i++) {
            Circle antenna = new Circle(5, Color.GRAY);
            Label numberLabel = new Label("Antenna " + (i + 1));
            numberLabel.getStyleClass().add("field-hint");
            Tooltip.install(antenna, new Tooltip("Antenna " + (i + 1)));

            HBox row = new HBox(6, antenna, numberLabel);
            row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

            circles[i] = antenna;
            container.getChildren().add(row);
        }
        antennaIndicators.put(key, circles);
        if (summaryLabel != null) {
            antennaSummaryLabels.put(key, summaryLabel);
        }
    }

    /**
     * ✅ Color a reader's 4 antenna circles and update the "X/4 active" summary line.
     * statusMap == null -> all gray (reader unreachable / not tested).
     * Otherwise green if the reader reported that antenna as powered, else red.
     */
    private void setAntennaColors(String antennaKey, Map<Integer, Boolean> statusMap) {
        Circle[] circles = antennaIndicators.get(antennaKey);
        if (circles == null) return;

        Platform.runLater(() -> {
            int workingCount = 0;
            for (int i = 0; i < circles.length; i++) {
                int antennaNumber = i + 1;
                Color color;
                if (statusMap == null) {
                    color = Color.GRAY;
                } else {
                    boolean working = Boolean.TRUE.equals(statusMap.get(antennaNumber));
                    color = working ? Color.LIMEGREEN : Color.RED;
                    if (working) workingCount++;
                }
                circles[i].setFill(color);
            }

            Label summary = antennaSummaryLabels.get(antennaKey);
            if (summary == null) return;

            if (statusMap == null) {
                summary.setText("Not tested");
                summary.setStyle("-fx-text-fill: #6b7680; -fx-font-style: italic;");
            } else if (workingCount == 4) {
                summary.setText("4/4 antennas active");
                summary.setStyle("-fx-text-fill: #1e824c; -fx-font-weight: bold;");
            } else {
                summary.setText(workingCount + "/4 antennas active");
                summary.setStyle("-fx-text-fill: #c0392b; -fx-font-weight: bold;");
            }
        });
    }

    private void setupReaderCountComboBoxes() {
        var readerCounts = javafx.collections.FXCollections.observableArrayList(1, 2);

        if (location1ReaderCountComboBox != null) location1ReaderCountComboBox.setItems(readerCounts);
        if (location2ReaderCountComboBox != null) location2ReaderCountComboBox.setItems(readerCounts);
        if (location3ReaderCountComboBox != null) location3ReaderCountComboBox.setItems(readerCounts);
        if (location4ReaderCountComboBox != null) location4ReaderCountComboBox.setItems(readerCounts);
    }

    private void setupReaderCountListeners() {
        if (location1ReaderCountComboBox != null) {
            location1ReaderCountComboBox.valueProperty().addListener((obs, old, val) -> {
                if (val != null) updateReaderBVisibility(location1ReaderBContainer, val);
            });
        }

        if (location2ReaderCountComboBox != null) {
            location2ReaderCountComboBox.valueProperty().addListener((obs, old, val) -> {
                if (val != null) updateReaderBVisibility(location2ReaderBContainer, val);
            });
        }

        if (location3ReaderCountComboBox != null) {
            location3ReaderCountComboBox.valueProperty().addListener((obs, old, val) -> {
                if (val != null) updateReaderBVisibility(location3ReaderBContainer, val);
            });
        }

        if (location4ReaderCountComboBox != null) {
            location4ReaderCountComboBox.valueProperty().addListener((obs, old, val) -> {
                if (val != null) updateReaderBVisibility(location4ReaderBContainer, val);
            });
        }
    }

    private void updateReaderBVisibility(VBox container, int readerCount) {
        if (container != null) {
            boolean visible = readerCount >= 2;
            container.setVisible(visible);
            container.setManaged(visible);
        }
    }

    // Start/Finish checkbox pairs, index i = location i+1
    private List<CheckBox> startBoxes = List.of();
    private List<CheckBox> finishBoxes = List.of();
    private List<CheckBox> enabledBoxes = List.of();

    /** True while the selected calculation mode is SAME_START_FINISH (one checkpoint is both). */
    private boolean sameStartFinishMode = false;

    /** Set while code (not the user) is changing checkboxes, so the listeners don't cascade. */
    private boolean syncingStartFinish = false;

    /**
     * ✅ Start/Finish rules, which depend on the selected calculation mode.
     *
     * Every mode except SAME_START_FINISH: only one Start box may be checked across all 4
     * locations, only one Finish box likewise, and a single location can't be both.
     *
     * SAME_START_FINISH: the real setup is ONE checkpoint that is both the start and the
     * finish, so a location's Start and Finish boxes move together, at most one location
     * can hold them, and once one does the other locations' boxes are disabled.
     */
    private void setupCheckboxListeners() {
        startBoxes = Arrays.asList(location1StartCheckBox, location2StartCheckBox, location3StartCheckBox, location4StartCheckBox);
        finishBoxes = Arrays.asList(location1FinishCheckBox, location2FinishCheckBox, location3FinishCheckBox, location4FinishCheckBox);
        enabledBoxes = Arrays.asList(location1EnabledCheckBox, location2EnabledCheckBox, location3EnabledCheckBox, location4EnabledCheckBox);

        for (int i = 0; i < startBoxes.size(); i++) {
            final int index = i;
            CheckBox start = startBoxes.get(i);
            CheckBox finish = finishBoxes.get(i);
            if (start != null) {
                start.selectedProperty().addListener((obs, old, val) -> onStartFinishToggled(index, true, val));
            }
            if (finish != null) {
                finish.selectedProperty().addListener((obs, old, val) -> onStartFinishToggled(index, false, val));
            }
            CheckBox enabled = enabledBoxes.get(i);
            if (enabled != null) {
                enabled.selectedProperty().addListener((obs, old, val) -> onEnabledToggled(index, val));
            }
        }
    }

    /**
     * SAME_START_FINISH uses exactly one physical checkpoint, so enabling a location makes it
     * THE location (Start + Finish both ticked, every other location switched off); disabling
     * it releases the pair. Other modes: Enable is independent.
     */
    private void onEnabledToggled(int index, boolean selected) {
        if (syncingStartFinish || !sameStartFinishMode) {
            return;
        }
        syncingStartFinish = true;
        try {
            if (selected) {
                makeSingleLocation(index);
            } else {
                setChecked(startBoxes.get(index), false);
                setChecked(finishBoxes.get(index), false);
            }
        } finally {
            syncingStartFinish = false;
        }
        updateStartFinishAvailability();
    }

    /** Only this location is enabled, and it carries both Start and Finish; all others are off. */
    private void makeSingleLocation(int owner) {
        for (int i = 0; i < startBoxes.size(); i++) {
            boolean own = i == owner;
            setChecked(enabledBoxes.get(i), own);
            setChecked(startBoxes.get(i), own);
            setChecked(finishBoxes.get(i), own);
        }
    }

    private void onStartFinishToggled(int index, boolean startBox, boolean selected) {
        if (syncingStartFinish) {
            return;
        }
        syncingStartFinish = true;
        try {
            if (sameStartFinishMode) {
                // One checkpoint is start AND finish: the pair moves together, and that one
                // location is also the only enabled one
                if (selected) {
                    makeSingleLocation(index);
                } else {
                    setChecked(startBoxes.get(index), false);
                    setChecked(finishBoxes.get(index), false);
                }
            } else if (selected) {
                List<CheckBox> sameKind = startBox ? startBoxes : finishBoxes;
                List<CheckBox> otherKind = startBox ? finishBoxes : startBoxes;
                setChecked(otherKind.get(index), false);
                for (int other = 0; other < sameKind.size(); other++) {
                    if (other != index) {
                        setChecked(sameKind.get(other), false);
                    }
                }
            }
        } finally {
            syncingStartFinish = false;
        }
        updateStartFinishAvailability();
    }

    private static void setChecked(CheckBox box, boolean checked) {
        if (box != null) {
            box.setSelected(checked);
        }
    }

    private static boolean isChecked(CheckBox box) {
        return box != null && box.isSelected();
    }

    /** Index of the location holding the Start (else Finish) tick, or -1. */
    private int startFinishOwner() {
        for (int i = 0; i < startBoxes.size(); i++) {
            if (isChecked(startBoxes.get(i))) return i;
        }
        for (int i = 0; i < finishBoxes.size(); i++) {
            if (isChecked(finishBoxes.get(i))) return i;
        }
        return -1;
    }

    /**
     * Switches the Start/Finish rules to match the calculation mode and reconciles the
     * current ticks with them: entering SAME_START_FINISH collapses to a single location
     * carrying both; leaving it drops the Finish tick from a location that had both
     * (that combination is only valid in SAME_START_FINISH).
     */
    private void applyCalculationMode(Race.CalculationMode mode) {
        boolean same = mode == Race.CalculationMode.SAME_START_FINISH;

        syncingStartFinish = true;
        try {
            if (same) {
                int owner = startFinishOwner();
                if (owner < 0) {
                    for (int i = 0; i < enabledBoxes.size(); i++) {
                        if (isChecked(enabledBoxes.get(i))) { owner = i; break; }
                    }
                }
                if (owner >= 0) {
                    makeSingleLocation(owner);
                } else {
                    for (int i = 0; i < startBoxes.size(); i++) {
                        setChecked(startBoxes.get(i), false);
                        setChecked(finishBoxes.get(i), false);
                    }
                }
            } else {
                for (int i = 0; i < startBoxes.size(); i++) {
                    if (isChecked(startBoxes.get(i)) && isChecked(finishBoxes.get(i))) {
                        setChecked(finishBoxes.get(i), false);
                    }
                }
            }
        } finally {
            syncingStartFinish = false;
        }
        sameStartFinishMode = same;
        updateStartFinishAvailability();
    }

    /** In SAME_START_FINISH, once one location holds the pair every other location's boxes are locked. */
    private void updateStartFinishAvailability() {
        int owner = sameStartFinishMode ? startFinishOwner() : -1;
        for (int i = 0; i < startBoxes.size(); i++) {
            boolean locked = owner >= 0 && i != owner;
            for (CheckBox box : Arrays.asList(startBoxes.get(i), finishBoxes.get(i), enabledBoxes.get(i))) {
                if (box == null) continue;
                box.setDisable(locked);
                box.setTooltip(locked
                        ? new Tooltip("Same Start/Finish mode uses a single checkpoint as both start and finish - untick the other location to move it")
                        : null);
            }
        }
    }

    /**
     * The mode the operator has selected on Live Tracking; before that page has been
     * opened, the mode the race was last calculated with.
     */
    private Race.CalculationMode currentCalculationMode() {
        Race.CalculationMode selected = modeSelection.get();
        if (selected != null) {
            return selected;
        }
        try {
            List<Race> races = raceRepository.findAll();
            if (!races.isEmpty() && races.get(races.size() - 1).getCalculationMode() != null) {
                return races.get(races.size() - 1).getCalculationMode();
            }
        } catch (Exception e) {
            log.debug("Could not load calculation mode: {}", e.getMessage());
        }
        return Race.CalculationMode.TWO_CHECKPOINT;
    }

    private void loadConfiguration() {
        List<Checkpoint> checkpoints = checkpointRepository.findAllByOrderByDisplayOrderAsc();

        if (checkpoints.isEmpty()) {
            createDefaultCheckpoints();
            checkpoints = checkpointRepository.findAllByOrderByDisplayOrderAsc();
        }
        clearLegacySeededReaderB(checkpoints);

        // Load the saved ticks verbatim; the mode-specific rules are applied afterwards
        syncingStartFinish = true;
        try {
            for (Checkpoint checkpoint : checkpoints) {
                switch (checkpoint.getLocationNumber()) {
                    case 1 -> populateLocation1(checkpoint);
                    case 2 -> populateLocation2(checkpoint);
                    case 3 -> populateLocation3(checkpoint);
                    case 4 -> populateLocation4(checkpoint);
                }
            }
        } finally {
            syncingStartFinish = false;
        }
        applyFixedPorts();
        applyCalculationMode(currentCalculationMode());
    }

    /**
     * Older versions seeded every location with a placeholder Reader B IP (192.168.1.11N), which is
     * what made a fresh location open as "Readers: 2". Databases created back then still carry it, so
     * an untouched placeholder is cleared and the location opens as one reader. Any other Reader B IP
     * is a real configuration and is left alone.
     */
    private void clearLegacySeededReaderB(List<Checkpoint> checkpoints) {
        for (Checkpoint cp : checkpoints) {
            if (cp.getLocationNumber() == null || cp.getReaderBIp() == null) continue;
            if (cp.getReaderBIp().trim().equals("192.168.1." + (110 + cp.getLocationNumber()))) {
                cp.setReaderBIp(null);
                checkpointRepository.save(cp);
                log.info("🔧 {} - cleared the old placeholder Reader B IP; location now opens with 1 reader", cp.getName());
            }
        }
    }

    /**
     * Re-reads state that lives on other tabs (the reader type saved on Live
     * Tracking decides the fixed port; the selected calculation mode decides the
     * Start/Finish rules). Called by MainViewController whenever this tab is selected.
     */
    public void refresh() {
        applyFixedPorts();
        applyCalculationMode(currentCalculationMode());
    }

    /**
     * Port is not something an operator chooses: the vendor fixes it (Zebra 5084,
     * CLOU 6000). Show it read-only on every reader instead of a free-text box that
     * could be mistyped.
     */
    private void applyFixedPorts() {
        RaceConfiguration.ReaderType readerType = getConfiguredReaderType();
        String port = String.valueOf(readerType.getFixedPort());
        String hint = "Fixed for " + (readerType == RaceConfiguration.ReaderType.ZEBRA ? "Zebra" : "CLOU (Reader CR 15)")
                + " readers - the reader type is set on the Live Tracking page";

        for (TextField field : Arrays.asList(
                location1ReaderAPortField, location1ReaderBPortField,
                location2ReaderAPortField, location2ReaderBPortField,
                location3ReaderAPortField, location3ReaderBPortField,
                location4ReaderAPortField, location4ReaderBPortField)) {
            if (field == null) continue;
            field.setText(port);
            field.setEditable(false);
            field.setFocusTraversable(false);
            field.setTooltip(new Tooltip(hint));
        }
    }

    private void createDefaultCheckpoints() {
        for (int i = 1; i <= 4; i++) {
            checkpointRepository.save(buildDefaultCheckpoint(i, currentFixedPort()));
        }
        log.info("✅ Default checkpoints created");
    }

    /**
     * Seed values for a fresh location. Package-private so the defaults can be tested
     * without touching the database.
     */
    static Checkpoint buildDefaultCheckpoint(int locationNumber, int readerPort) {
        Checkpoint checkpoint = new Checkpoint();
        checkpoint.setLocationName("Location " + locationNumber);
        checkpoint.setLocationNumber(locationNumber);
        checkpoint.setDisplayOrder(locationNumber);
        checkpoint.setEnabled(false);
        checkpoint.setReaderAIp("192.168.1." + (100 + locationNumber));
        checkpoint.setReaderAPort(readerPort);
        // Reader B is deliberately left blank: getActiveReaderCount() treats any
        // non-blank readerBIp as a second reader, so seeding a placeholder IP here
        // made every fresh location load as "Readers: 2". A second reader is opted
        // into via the Readers dropdown, which then asks for its IP.
        checkpoint.setReaderBIp(null);
        checkpoint.setReaderBPort(readerPort);
        checkpoint.setDistanceKm(locationNumber * 2.0);
        // ✅ Seed default only (matches the FXML's initial checkbox state:
        // Location 1 Start / Location 4 Finish selected="true"). Any location
        // can be reassigned as Start/Finish via the UI - saveCheckpoint()
        // persists whichever checkboxes are actually checked and is
        // authoritative from then on.
        checkpoint.setStartLine(locationNumber == 1);
        checkpoint.setFinishLine(locationNumber == 4);
        return checkpoint;
    }

    private void populateLocation1(Checkpoint cp) {
        if (location1NameField != null) location1NameField.setText(cp.getLocationName());
        if (location1EnabledCheckBox != null) location1EnabledCheckBox.setSelected(cp.isEnabled());
        if (location1ReaderAIpField != null) location1ReaderAIpField.setText(cp.getReaderAIp());
        if (location1ReaderBIpField != null) location1ReaderBIpField.setText(cp.getReaderBIp());
        if (location1DistanceField != null) location1DistanceField.setText(String.valueOf(cp.getDistanceKm()));
        if (location1StartCheckBox != null) location1StartCheckBox.setSelected(cp.isStartLine());
        if (location1FinishCheckBox != null) location1FinishCheckBox.setSelected(cp.isFinishLine());
        if (location1ReaderCountComboBox != null) location1ReaderCountComboBox.setValue(getActiveReaderCount(cp));
    }

    private void populateLocation2(Checkpoint cp) {
        if (location2NameField != null) location2NameField.setText(cp.getLocationName());
        if (location2EnabledCheckBox != null) location2EnabledCheckBox.setSelected(cp.isEnabled());
        if (location2ReaderAIpField != null) location2ReaderAIpField.setText(cp.getReaderAIp());
        if (location2ReaderBIpField != null) location2ReaderBIpField.setText(cp.getReaderBIp());
        if (location2DistanceField != null) location2DistanceField.setText(String.valueOf(cp.getDistanceKm()));
        if (location2StartCheckBox != null) location2StartCheckBox.setSelected(cp.isStartLine());
        if (location2FinishCheckBox != null) location2FinishCheckBox.setSelected(cp.isFinishLine());
        if (location2ReaderCountComboBox != null) location2ReaderCountComboBox.setValue(getActiveReaderCount(cp));
    }

    private void populateLocation3(Checkpoint cp) {
        if (location3NameField != null) location3NameField.setText(cp.getLocationName());
        if (location3EnabledCheckBox != null) location3EnabledCheckBox.setSelected(cp.isEnabled());
        if (location3ReaderAIpField != null) location3ReaderAIpField.setText(cp.getReaderAIp());
        if (location3ReaderBIpField != null) location3ReaderBIpField.setText(cp.getReaderBIp());
        if (location3DistanceField != null) location3DistanceField.setText(String.valueOf(cp.getDistanceKm()));
        if (location3StartCheckBox != null) location3StartCheckBox.setSelected(cp.isStartLine());
        if (location3FinishCheckBox != null) location3FinishCheckBox.setSelected(cp.isFinishLine());
        if (location3ReaderCountComboBox != null) location3ReaderCountComboBox.setValue(getActiveReaderCount(cp));
    }

    private void populateLocation4(Checkpoint cp) {
        if (location4NameField != null) location4NameField.setText(cp.getLocationName());
        if (location4EnabledCheckBox != null) location4EnabledCheckBox.setSelected(cp.isEnabled());
        if (location4ReaderAIpField != null) location4ReaderAIpField.setText(cp.getReaderAIp());
        if (location4ReaderBIpField != null) location4ReaderBIpField.setText(cp.getReaderBIp());
        if (location4DistanceField != null) location4DistanceField.setText(String.valueOf(cp.getDistanceKm()));
        if (location4StartCheckBox != null) location4StartCheckBox.setSelected(cp.isStartLine());
        if (location4FinishCheckBox != null) location4FinishCheckBox.setSelected(cp.isFinishLine());
        if (location4ReaderCountComboBox != null) location4ReaderCountComboBox.setValue(getActiveReaderCount(cp));
    }

    private int getActiveReaderCount(Checkpoint cp) {
        int count = 0;
        if (cp.getReaderAIp() != null && !cp.getReaderAIp().trim().isEmpty()) count++;
        if (cp.getReaderBIp() != null && !cp.getReaderBIp().trim().isEmpty()) count++;
        return Math.max(count, 1);
    }

    /**
     * ✅ TEST ALL READERS
     *
     * ✅ Only tests locations that are actually Enabled, and only tests Reader B
     * when that location's reader count is set to 2 - a disabled location or an
     * unused Reader B slot still carries a default placeholder IP (see
     * createDefaultCheckpoints()) that isn't a real device, so testing it
     * unconditionally used to waste a full connect-timeout's worth of time per
     * phantom slot on every "Test All" click for no reason.
     *
     * ✅ Uses whatever reader type is currently saved in RaceConfiguration
     * (the Reader Type selector now lives on the Live Tracking page's Race
     * Configuration section, not here).
     */
    @FXML
    private void handleTestReaders() {
        log.info("🔍 Testing all readers...");

        testReadersButton.setDisable(true);
        testReadersButton.setText("⏳ Testing...");

        // Reset all indicators to gray
        resetAllIndicators();

        CompletableFuture.runAsync(() -> {
            RaceConfiguration.ReaderType readerType = getConfiguredReaderType();

            testLocationReaders(location1EnabledCheckBox, location1ReaderCountComboBox,
                    location1ReaderAIpField, location1ReaderAPortField, location1ReaderAStatus, "L1_A",
                    location1ReaderBIpField, location1ReaderBPortField, location1ReaderBStatus, "L1_B", readerType);

            testLocationReaders(location2EnabledCheckBox, location2ReaderCountComboBox,
                    location2ReaderAIpField, location2ReaderAPortField, location2ReaderAStatus, "L2_A",
                    location2ReaderBIpField, location2ReaderBPortField, location2ReaderBStatus, "L2_B", readerType);

            testLocationReaders(location3EnabledCheckBox, location3ReaderCountComboBox,
                    location3ReaderAIpField, location3ReaderAPortField, location3ReaderAStatus, "L3_A",
                    location3ReaderBIpField, location3ReaderBPortField, location3ReaderBStatus, "L3_B", readerType);

            testLocationReaders(location4EnabledCheckBox, location4ReaderCountComboBox,
                    location4ReaderAIpField, location4ReaderAPortField, location4ReaderAStatus, "L4_A",
                    location4ReaderBIpField, location4ReaderBPortField, location4ReaderBStatus, "L4_B", readerType);

            Platform.runLater(() -> {
                testReadersButton.setDisable(false);
                testReadersButton.setText("🔍 Test");
                showAlert(Alert.AlertType.INFORMATION, "Test Complete",
                        "✅ Reader + antenna connectivity test completed!\n\n" +
                                "🟢 Green = Connected / antenna powered\n" +
                                "🔴 Red = Failed / antenna not detected\n" +
                                "⚪ Gray = Not configured or not tested yet");
            });

        }, executorService);
    }

    private void resetAllIndicators() {
        setIndicatorColor(location1ReaderAStatus, Color.GRAY);
        setIndicatorColor(location1ReaderBStatus, Color.GRAY);
        setIndicatorColor(location2ReaderAStatus, Color.GRAY);
        setIndicatorColor(location2ReaderBStatus, Color.GRAY);
        setIndicatorColor(location3ReaderAStatus, Color.GRAY);
        setIndicatorColor(location3ReaderBStatus, Color.GRAY);
        setIndicatorColor(location4ReaderAStatus, Color.GRAY);
        setIndicatorColor(location4ReaderBStatus, Color.GRAY);

        for (String key : antennaIndicators.keySet()) {
            setAntennaColors(key, null);
        }
    }

    /**
     * ✅ Test one location's reader(s), skipping what isn't actually in use:
     * the whole location if its Enable checkbox is off, and Reader B if that
     * location's reader count isn't set to 2. Indicators for anything skipped
     * are just left at the gray "not tested" state resetAllIndicators() already put them in.
     */
    private void testLocationReaders(CheckBox enabledCheckBox, ComboBox<Integer> readerCountComboBox,
                                      TextField ipFieldA, TextField portFieldA, Circle statusA, String keyA,
                                      TextField ipFieldB, TextField portFieldB, Circle statusB, String keyB,
                                      RaceConfiguration.ReaderType readerType) {
        boolean enabled = enabledCheckBox == null || enabledCheckBox.isSelected();
        if (!enabled) {
            return;
        }

        testReader(ipFieldA, portFieldA, statusA, keyA, readerType);

        int readerCount = readerCountComboBox != null && readerCountComboBox.getValue() != null
                ? readerCountComboBox.getValue() : 1;
        if (readerCount >= 2) {
            testReader(ipFieldB, portFieldB, statusB, keyB, readerType);
        }
    }

    /**
     * ✅ Real reader test: opens the actual reader protocol connection (CLOU or
     * Zebra, per the configured reader type) and queries per-antenna status.
     * Reader circle = connection result. Antenna circles = per-antenna status
     * (gray if reader unreachable).
     */
    private void testReader(TextField ipField, TextField portField, Circle indicator, String antennaKey,
                             RaceConfiguration.ReaderType readerType) {
        if (ipField == null || portField == null || indicator == null) return;

        String ip = ipField.getText();
        String portText = portField.getText();

        if (ip == null || ip.trim().isEmpty() || portText == null || portText.trim().isEmpty()) {
            setIndicatorColor(indicator, Color.GRAY);
            setAntennaColors(antennaKey, null);
            return;
        }

        try {
            int port = Integer.parseInt(portText.trim());

            // A reader that Live Tracking has already armed is connected and scanning.
            // Zebra FX readers allow only one host session, so opening a second
            // connection here would be rejected ("Connection Already exists") and
            // wrongly paint a healthy, actively-scanning reader red - and on a live
            // race it risks disturbing the session that is recording tags.
            // Report the armed reader's own state instead.
            ReaderTestResult armed = multiReaderService != null
                    ? multiReaderService.getArmedReaderStatus(ip.trim(), port)
                    : null;

            ReaderTestResult result;
            if (armed != null) {
                log.info("✅ Reader already armed and scanning: {}:{} - reporting live status", ip, port);
                result = armed;
            } else {
                result = readerType == RaceConfiguration.ReaderType.ZEBRA
                        ? ZebraFxReaderClient.testConnectionAndAntennas(ip.trim(), port, antennaKey)
                        : ClouReaderClient.testConnectionAndAntennas(ip.trim(), port, antennaKey);
            }

            if (multiReaderService != null) {
                multiReaderService.recordTestResult(ip, port, result.readerConnected);
            }
            if (result.readerConnected) {
                setIndicatorColor(indicator, Color.LIMEGREEN);
                log.info("✅ Reader connected: {}:{}", ip, port);
            } else {
                setIndicatorColor(indicator, Color.RED);
                log.warn("❌ Reader failed: {}:{}", ip, port);
            }
            setAntennaColors(antennaKey, result.readerConnected ? result.antennaStatus : null);

        } catch (NumberFormatException e) {
            setIndicatorColor(indicator, Color.RED);
            setAntennaColors(antennaKey, null);
        }
    }

    /**
     * ✅ One global reader-vendor setting for the whole race - see setupReaderTypeComboBox().
     */
    private RaceConfiguration.ReaderType getConfiguredReaderType() {
        try {
            RaceConfiguration config = raceConfigRepository.findTopByOrderByCreatedAtDesc();
            if (config != null && config.getReaderType() != null) {
                return config.getReaderType();
            }
        } catch (Exception e) {
            log.debug("Could not load reader type from configuration: {}", e.getMessage());
        }
        return RaceConfiguration.ReaderType.CLOU;
    }

    private void setIndicatorColor(Circle indicator, Color color) {
        if (indicator != null) {
            Platform.runLater(() -> indicator.setFill(color));
        }
    }

    /**
     * ✅ SAVE CONFIGURATION
     */
    @FXML
    private void handleSaveConfig() {
        try {
            // ✅ Save all 4 locations
            saveCheckpoint(1);
            saveCheckpoint(2);
            saveCheckpoint(3);
            saveCheckpoint(4);

            String note = (sameStartFinishMode && startFinishOwner() < 0)
                    ? "\n\n⚠️ Same Start/Finish mode: no location is ticked as Start/Finish. "
                    + "Tick one so the race start can be detected from its first scan."
                    : "";
            showAlert(Alert.AlertType.INFORMATION, "Success",
                    "✅ Checkpoint configuration saved successfully!" + note);

        } catch (Exception e) {
            showAlert(Alert.AlertType.ERROR, "Error",
                    "❌ Failed to save: " + e.getMessage());
            log.error("Save error", e);
        }
    }

    private void saveCheckpoint(int locationNum) {
        Checkpoint cp = getOrCreateCheckpoint(locationNum);
        int fixedPort = currentFixedPort();

        switch (locationNum) {
            case 1 -> {
                if (location1NameField != null) cp.setLocationName(location1NameField.getText());
                if (location1EnabledCheckBox != null) cp.setEnabled(location1EnabledCheckBox.isSelected());
                if (location1ReaderAIpField != null) cp.setReaderAIp(location1ReaderAIpField.getText());
                cp.setReaderAPort(fixedPort);
                applyReaderB(cp, location1ReaderCountComboBox,
                        location1ReaderBIpField, fixedPort);
                if (location1DistanceField != null) cp.setDistanceKm(parseDouble(location1DistanceField.getText()));
                if (location1StartCheckBox != null) cp.setStartLine(location1StartCheckBox.isSelected());
                if (location1FinishCheckBox != null) cp.setFinishLine(location1FinishCheckBox.isSelected());
            }
            case 2 -> {
                if (location2NameField != null) cp.setLocationName(location2NameField.getText());
                if (location2EnabledCheckBox != null) cp.setEnabled(location2EnabledCheckBox.isSelected());
                if (location2ReaderAIpField != null) cp.setReaderAIp(location2ReaderAIpField.getText());
                cp.setReaderAPort(fixedPort);
                applyReaderB(cp, location2ReaderCountComboBox,
                        location2ReaderBIpField, fixedPort);
                if (location2DistanceField != null) cp.setDistanceKm(parseDouble(location2DistanceField.getText()));
                if (location2StartCheckBox != null) cp.setStartLine(location2StartCheckBox.isSelected());
                if (location2FinishCheckBox != null) cp.setFinishLine(location2FinishCheckBox.isSelected());
            }
            case 3 -> {
                if (location3NameField != null) cp.setLocationName(location3NameField.getText());
                if (location3EnabledCheckBox != null) cp.setEnabled(location3EnabledCheckBox.isSelected());
                if (location3ReaderAIpField != null) cp.setReaderAIp(location3ReaderAIpField.getText());
                cp.setReaderAPort(fixedPort);
                applyReaderB(cp, location3ReaderCountComboBox,
                        location3ReaderBIpField, fixedPort);
                if (location3DistanceField != null) cp.setDistanceKm(parseDouble(location3DistanceField.getText()));
                if (location3StartCheckBox != null) cp.setStartLine(location3StartCheckBox.isSelected());
                if (location3FinishCheckBox != null) cp.setFinishLine(location3FinishCheckBox.isSelected());
            }
            case 4 -> {
                if (location4NameField != null) cp.setLocationName(location4NameField.getText());
                if (location4EnabledCheckBox != null) cp.setEnabled(location4EnabledCheckBox.isSelected());
                if (location4ReaderAIpField != null) cp.setReaderAIp(location4ReaderAIpField.getText());
                cp.setReaderAPort(fixedPort);
                applyReaderB(cp, location4ReaderCountComboBox,
                        location4ReaderBIpField, fixedPort);
                if (location4DistanceField != null) cp.setDistanceKm(parseDouble(location4DistanceField.getText()));
                if (location4StartCheckBox != null) cp.setStartLine(location4StartCheckBox.isSelected());
                if (location4FinishCheckBox != null) cp.setFinishLine(location4FinishCheckBox.isSelected());
            }
        }

        checkpointRepository.save(cp);
    }

    /**
     * The one port every reader of the configured vendor listens on (Zebra 5084,
     * CLOU 6000). Not user-editable - see applyFixedPorts().
     */
    private int currentFixedPort() {
        return getConfiguredReaderType().getFixedPort();
    }

    private double parseDouble(String text) {
        try {
            return Double.parseDouble(text.trim());
        } catch (Exception e) {
            return 0.0;
        }
    }

    @FXML
    private void handleReset() {
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.setTitle("Reset Configuration");
        confirmation.setHeaderText("Reset to Default?");
        confirmation.setContentText("This will reset all settings to default values.");

        confirmation.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                checkpointRepository.deleteAll();
                createDefaultCheckpoints();
                loadConfiguration();
                resetAllIndicators();
                showAlert(Alert.AlertType.INFORMATION, "Success",
                        "✅ Configuration reset!");
            }
        });
    }

    private Checkpoint getOrCreateCheckpoint(int locationNumber) {
        List<Checkpoint> checkpoints = checkpointRepository.findAllByOrderByDisplayOrderAsc();
        return checkpoints.stream()
                .filter(cp -> cp.getLocationNumber() == locationNumber)
                .findFirst()
                .orElseGet(() -> {
                    Checkpoint newCheckpoint = new Checkpoint();
                    newCheckpoint.setLocationNumber(locationNumber);
                    newCheckpoint.setDisplayOrder(locationNumber);
                    return newCheckpoint;
                });
    }

    private void showAlert(Alert.AlertType type, String title, String content) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }

    /**
     * Persist Reader B only when the location's "Readers:" dropdown is set to 2.
     *
     * Why this exists: Checkpoint has no readerCount column - arming decides
     * whether to connect Reader B purely by whether readerBIp is non-empty
     * (see Checkpoint.isReaderBConfigured()). The Reader B fields keep their
     * seeded default IP (192.168.1.11x) even while hidden by a "Readers: 1"
     * selection, so saving them unconditionally left a stale IP in the DB and
     * made arming try to connect a second reader the user never configured -
     * producing bogus "Failed: ... Reader B (192.168.1.111:6000)" errors and a
     * misleading "Armed 1/4 readers" count.
     *
     * Clearing the fields keeps the single source of truth (readerBIp) honest.
     */
    private void applyReaderB(Checkpoint cp,
                              ComboBox<Integer> readerCountComboBox,
                              TextField readerBIpField,
                              int fixedPort) {
        int readerCount = (readerCountComboBox != null && readerCountComboBox.getValue() != null)
                ? readerCountComboBox.getValue()
                : 1;

        if (readerCount >= 2) {
            if (readerBIpField != null) cp.setReaderBIp(readerBIpField.getText());
            cp.setReaderBPort(fixedPort);
        } else {
            // Single-reader location: clear Reader B so arming/testing skips it entirely.
            cp.setReaderBIp(null);
            cp.setReaderBPort(null);
        }
    }
}
