package com.army.marathon.ui;

import com.army.marathon.model.*;
import com.army.marathon.repository.*;
import com.army.marathon.service.*;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.Region;
import javafx.stage.FileChooser;
import lombok.extern.slf4j.Slf4j;
import net.rgielen.fxweaver.core.FxmlView;
import org.springframework.stereotype.Component;

import java.io.File;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ✅ Read-only results view. Calculation now happens on the Live Tracking tab
 * (calculation mode + "Calculate Results"); this screen just displays whatever
 * RaceResults currently exist for the race, and lets you export/refresh.
 * MainViewController refreshes this screen automatically whenever the Results tab
 * is selected.
 *
 * <p>Two genuinely different renderings live here and are kept apart:
 * <ul>
 *   <li>the standard table (rank, army rank, category, one column per checkpoint, total time,
 *       status) used by every mode except the next one, with the single overall Winner card;</li>
 *   <li>the MANUAL_START_DUAL_TIMING table (Status, Declared Start, Start Time, Finish Time,
 *       Duration (Declared Start), Duration (Start Time), editable Note) - no rank, no winner.</li>
 * </ul>
 */
@Slf4j
@Component
@FxmlView("ResultsView.fxml")
public class ResultsController {

    @FXML private Label raceNameLabel;

    @FXML private Button exportExcelButton;
    @FXML private Button refreshButton;

    @FXML private Label totalFinishersLabel;
    @FXML private Label totalDnfLabel;
    @FXML private Region winnerCard;
    @FXML private Label resultsHintLabel;
    @FXML private Label winnerNameLabel;
    @FXML private Label winnerTimeLabel;

    // ---- standard results table
    @FXML private TableView<ResultsRenderer.ParticipantResultRow> resultsTable;
    @FXML private TableColumn<ResultsRenderer.ParticipantResultRow, Integer> rankColumn;
    @FXML private TableColumn<ResultsRenderer.ParticipantResultRow, String> nameColumn;
    @FXML private TableColumn<ResultsRenderer.ParticipantResultRow, String> serviceNoColumn;
    @FXML private TableColumn<ResultsRenderer.ParticipantResultRow, String> rankArmyColumn;
    @FXML private TableColumn<ResultsRenderer.ParticipantResultRow, String> unitColumn;
    @FXML private TableColumn<ResultsRenderer.ParticipantResultRow, String> categoryColumn;

    private List<TableColumn<ResultsRenderer.ParticipantResultRow, String>> checkpointColumns = new ArrayList<>();

    @FXML private TableColumn<ResultsRenderer.ParticipantResultRow, String> totalTimeColumn;
    @FXML private TableColumn<ResultsRenderer.ParticipantResultRow, String> statusColumn;

    // ---- MANUAL_START_DUAL_TIMING results table
    @FXML private TableView<ResultsRenderer.DualTimingRow> dualTimingTable;
    @FXML private TableColumn<ResultsRenderer.DualTimingRow, String> dualNameColumn;
    @FXML private TableColumn<ResultsRenderer.DualTimingRow, String> dualTagNoColumn;
    @FXML private TableColumn<ResultsRenderer.DualTimingRow, String> dualUnitColumn;
    @FXML private TableColumn<ResultsRenderer.DualTimingRow, String> dualStatusColumn;
    @FXML private TableColumn<ResultsRenderer.DualTimingRow, String> dualDeclaredStartColumn;
    @FXML private TableColumn<ResultsRenderer.DualTimingRow, String> dualStartColumn;
    @FXML private TableColumn<ResultsRenderer.DualTimingRow, String> dualFinishColumn;
    @FXML private TableColumn<ResultsRenderer.DualTimingRow, String> dualDeclaredDurationColumn;
    @FXML private TableColumn<ResultsRenderer.DualTimingRow, String> dualStartDurationColumn;
    @FXML private TableColumn<ResultsRenderer.DualTimingRow, String> dualNoteColumn;

    private final RaceRepository raceRepository;
    private final RaceResultRepository resultRepository;
    private final ReportService reportService;
    private final CheckpointReadingRepository readingRepository;
    private final RaceTimingService timingService;
    private final ResultsRenderer renderer;

    private final ObservableList<ResultsRenderer.ParticipantResultRow> resultsData = FXCollections.observableArrayList();
    private final ObservableList<ResultsRenderer.DualTimingRow> dualTimingData = FXCollections.observableArrayList();

    private Race currentRace;
    private List<ResultsRenderer.CheckpointColumn> currentColumns = new ArrayList<>();
    private Race.CalculationMode currentMode = Race.CalculationMode.TWO_CHECKPOINT;

    public ResultsController(
            RaceRepository raceRepository,
            RaceResultRepository resultRepository,
            ReportService reportService,
            CheckpointReadingRepository readingRepository,
            RaceTimingService timingService,
            ResultsRenderer renderer
    ) {
        this.raceRepository = raceRepository;
        this.resultRepository = resultRepository;
        this.reportService = reportService;
        this.readingRepository = readingRepository;
        this.timingService = timingService;
        this.renderer = renderer;
    }

    @FXML
    public void initialize() {
        setupBasicColumns();
        setupDualTimingColumns();
        refreshResults();
    }

    private void setupBasicColumns() {
        rankColumn.setCellValueFactory(new PropertyValueFactory<>("rank"));
        nameColumn.setCellValueFactory(new PropertyValueFactory<>("name"));
        serviceNoColumn.setCellValueFactory(new PropertyValueFactory<>("serviceNo"));
        rankArmyColumn.setCellValueFactory(new PropertyValueFactory<>("armyRank"));
        unitColumn.setCellValueFactory(new PropertyValueFactory<>("unit"));
        categoryColumn.setCellValueFactory(new PropertyValueFactory<>("category"));
        totalTimeColumn.setCellValueFactory(new PropertyValueFactory<>("totalTime"));

        statusColumn.setCellValueFactory(new PropertyValueFactory<>("status"));
        statusColumn.setCellFactory(column -> statusCell("Finished"));

        resultsTable.setItems(resultsData);
    }

    /** Green for a finish, red for a DNF (including "DNF (No-Show)"). */
    private static <R> TableCell<R, String> statusCell(String finishedText) {
        return new TableCell<R, String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item);
                    if (item.contains(finishedText)) {
                        setStyle("-fx-text-fill: #27ae60; -fx-font-weight: bold;");
                    } else if (item.contains("DNF")) {
                        setStyle("-fx-text-fill: #e74c3c; -fx-font-weight: bold;");
                    }
                }
            }
        };
    }

    private void setupDualTimingColumns() {
        dualNameColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name));
        dualTagNoColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().tagNo));
        dualUnitColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().unit));
        dualStatusColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().status));
        dualStatusColumn.setCellFactory(column -> statusCell("COMPLETED"));
        dualDeclaredStartColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().declaredStart));
        dualStartColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().startTime));
        dualFinishColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().finishTime));
        dualDeclaredDurationColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().declaredDuration));
        dualStartDurationColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().startDuration));

        // Note: the operator's own annotation, edited in place and saved straight to the result row
        dualNoteColumn.setCellValueFactory(c -> c.getValue().noteProperty());
        dualNoteColumn.setCellFactory(TextFieldTableCell.forTableColumn());
        dualNoteColumn.setOnEditCommit(event -> {
            ResultsRenderer.DualTimingRow row = event.getRowValue();
            String note = event.getNewValue() == null ? "" : event.getNewValue().trim();
            row.noteProperty().set(note);
            saveNote(row.resultId, note);
        });

        dualTimingTable.setEditable(true);
        dualTimingTable.setItems(dualTimingData);
    }

    private void saveNote(Long resultId, String note) {
        try {
            resultRepository.findById(resultId).ifPresent(result -> {
                result.setRemarks(note.isEmpty() ? null : note);
                resultRepository.save(result);
            });
        } catch (Exception e) {
            log.error("Failed to save note for result {}", resultId, e);
            showAlert(Alert.AlertType.ERROR, "Note Not Saved", "Could not save the note: " + e.getMessage());
        }
    }

    private void updateTableColumns() {
        resultsTable.getColumns().removeAll(checkpointColumns);
        checkpointColumns.clear();

        int insertIndex = resultsTable.getColumns().indexOf(categoryColumn) + 1;

        for (int i = 1; i <= currentColumns.size(); i++) {
            TableColumn<ResultsRenderer.ParticipantResultRow, String> col = new TableColumn<>(currentColumns.get(i - 1).header());

            final int checkpointIndex = i;
            col.setCellValueFactory(cellData ->
                    new SimpleStringProperty(cellData.getValue().getCheckpointTime(checkpointIndex))
            );

            col.setPrefWidth(100);
            col.setStyle("-fx-alignment: CENTER;");

            checkpointColumns.add(col);
            resultsTable.getColumns().add(insertIndex++, col);
        }

        log.info("Table updated with {} checkpoint columns", currentColumns.size());
    }

    /**
     * Loads whatever race exists and the calculation mode it was last computed
     * with, purely to know how many/what-named checkpoint columns to render.
     * Does not create or modify races — that's owned by Live Tracking now.
     */
    private void loadCurrentRace() {
        try {
            List<Race> races = raceRepository.findAll();

            if (races.isEmpty()) {
                currentRace = null;
                raceNameLabel.setText("No Race Configured");
                return;
            }

            currentRace = races.get(races.size() - 1);
            raceNameLabel.setText(currentRace.getRaceName());

            currentMode = currentRace.getCalculationMode() != null
                    ? currentRace.getCalculationMode()
                    : Race.CalculationMode.TWO_CHECKPOINT;
            currentColumns = renderer.resolveCheckpointColumns(currentRace, currentMode);

        } catch (Exception e) {
            log.error("Failed to load race", e);
            raceNameLabel.setText("Error Loading Race");
        }
    }

    /**
     * ✅ Public so MainViewController can call this when the Results tab is
     * selected, picking up whatever was last calculated on Live Tracking.
     */
    public void refreshResults() {
        loadCurrentRace();

        boolean dualTiming = currentRace != null && currentMode == Race.CalculationMode.MANUAL_START_DUAL_TIMING;
        showDualTimingLayout(dualTiming);

        // No current attempt (fresh launch, or right after Reset) renders EMPTY - never "everything
        // on file". History is only ever shown by the Reports tab.
        if (currentRace == null || !currentRace.hasCurrentAttempt()) {
            if (currentRace != null) {
                raceNameLabel.setText("");
            }
            resultsData.clear();
            dualTimingData.clear();
            totalFinishersLabel.setText("0");
            if (totalDnfLabel != null) totalDnfLabel.setText("0");
            winnerNameLabel.setText("N/A");
            winnerTimeLabel.setText("N/A");
            return;
        }

        if (dualTiming) {
            refreshDualTimingResults();
        } else {
            updateTableColumns();
            refreshStandardResults();
        }
    }

    /** Swaps which of the two differently-shaped tables is shown; the Winner card belongs to the standard one. */
    private void showDualTimingLayout(boolean dualTiming) {
        setShown(dualTimingTable, dualTiming);
        setShown(resultsTable, !dualTiming);
        setShown(winnerCard, !dualTiming);
        if (resultsHintLabel != null) {
            resultsHintLabel.setText(dualTiming ? "Double-click a Note to edit it" : "");
        }
    }

    private static void setShown(javafx.scene.Node node, boolean shown) {
        if (node != null) {
            node.setVisible(shown);
            node.setManaged(shown);
        }
    }

    // ------------------------------------------------------------------
    // Standard rendering (every mode except MANUAL_START_DUAL_TIMING)
    // ------------------------------------------------------------------

    /**
     * This attempt's RaceResult rows. Scoped by raceSessionId — the reliable link
     * stamped at calculation time (see RaceTimingService.resolveSessionId) — not by
     * createdAt, which has no dependable relationship to when the race itself ran.
     * There is deliberately no fallback: with no current attempt (NOT_STARTED, or a legacy
     * row with no session) this is EMPTY, never every result on file for the race.
     */
    private List<RaceResult> resultsForCurrentAttempt() {
        if (!currentRace.hasCurrentAttempt()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(resultRepository.findByRaceSessionId(currentRace.getCurrentSessionId()));
    }

    private void refreshStandardResults() {
        try {
            // Scoped to the CURRENT attempt only - Reset no longer deletes readings/results, so an
            // earlier attempt's rows stay in the same raceId's history (see the Reports tab) and
            // must not bleed into what this screen shows as "the" results.
            List<RaceResult> allResults = resultsForCurrentAttempt();

            log.info("Found {} total results for race ID: {}", allResults.size(), currentRace.getId());

            // Finishers first, in finishing order (fastest first); ties fall back to the assigned rank,
            // then to who crossed the line first, so the Rank column always counts 1, 2, 3... downwards.
            // DNF / No-Show rows come after every finisher and carry no rank.
            allResults.sort(ResultsRenderer.FINISHING_ORDER);

            // CheckpointReading has no session id (readTime genuinely reflects when a scan
            // happened, so a time-window filter is legitimate here, unlike for RaceResult).
            LocalDateTime attemptStart = currentRace.getCurrentAttemptStartedAt();
            // No boundary means no current attempt: empty, not "all readings".
            List<CheckpointReading> allReadings = attemptStart == null
                    ? new ArrayList<>()
                    : readingRepository.findByRaceId(currentRace.getId()).stream()
                            .filter(r -> r.getReadTime() != null && !r.getReadTime().isBefore(attemptStart))
                            .collect(Collectors.toList());

            log.info("Found {} total checkpoint readings", allReadings.size());

            Map<Long, List<CheckpointReading>> readingsByParticipant = allReadings.stream()
                    .filter(r -> r.getParticipant() != null)
                    .collect(Collectors.groupingBy(r -> r.getParticipant().getId()));

            log.info("Readings grouped for {} participants", readingsByParticipant.size());

            resultsData.clear();
            int rowCount = 0;

            for (RaceResult result : allResults) {
                if (result.getParticipant() == null) continue;

                Long participantId = result.getParticipant().getId();
                List<CheckpointReading> participantReadings = readingsByParticipant
                        .getOrDefault(participantId, new ArrayList<>());

                resultsData.add(new ResultsRenderer.ParticipantResultRow(result, participantReadings, currentColumns, currentMode));
                rowCount++;
            }

            log.info("Built {} result rows", rowCount);

            long finishedCount = allResults.stream()
                    .filter(RaceResult::isCompletedRace)
                    .count();
            long dnfCount = allResults.size() - finishedCount;

            totalFinishersLabel.setText(String.valueOf(finishedCount));
            if (totalDnfLabel != null) {
                totalDnfLabel.setText(String.valueOf(dnfCount));
            }

            // The Winner is the single overall best finishing time in the whole race - not scoped by
            // category, age group or unit. FINISHING_ORDER puts that finisher first.
            Optional<RaceResult> winner = ResultsRenderer.findWinner(allResults);

            if (winner.isPresent()) {
                winnerNameLabel.setText(ResultsRenderer.displayName(winner.get().getParticipant()));
                winnerTimeLabel.setText(winner.get().getFormattedTime());
            } else {
                winnerNameLabel.setText("N/A");
                winnerTimeLabel.setText("N/A");
            }

            log.info("Results refreshed: {} total ({} finished, {} DNF)",
                    allResults.size(), finishedCount, dnfCount);

        } catch (Exception e) {
            log.error("Failed to refresh results", e);
        }
    }

    // ------------------------------------------------------------------
    // MANUAL_START_DUAL_TIMING rendering
    // ------------------------------------------------------------------

    private void refreshDualTimingResults() {
        try {
            List<RaceResult> results = resultsForCurrentAttempt();
            log.info("Found {} total results for race ID: {} (manual start / dual timing)",
                    results.size(), currentRace.getId());

            // Completed runners in the order they finished, then everyone else by name
            results.sort(Comparator
                    .comparing((RaceResult r) -> !r.isCompletedRace())
                    .thenComparing(RaceResult::getFinishTime, Comparator.nullsLast(Comparator.<LocalDateTime>naturalOrder()))
                    .thenComparing(r -> r.getParticipant() != null ? ResultsRenderer.displayName(r.getParticipant()) : ""));

            dualTimingData.clear();
            for (RaceResult result : results) {
                if (result.getParticipant() != null) {
                    dualTimingData.add(new ResultsRenderer.DualTimingRow(result));
                }
            }

            long completed = results.stream().filter(RaceResult::isCompletedRace).count();
            totalFinishersLabel.setText(String.valueOf(completed));
            if (totalDnfLabel != null) {
                totalDnfLabel.setText(String.valueOf(results.size() - completed));
            }
            // No Winner in this mode; winnerNameLabel/winnerTimeLabel sit inside the hidden Winner card.

        } catch (Exception e) {
            log.error("Failed to refresh manual start results", e);
        }
    }

    @FXML
    private void handleExportExcel() {
        boolean empty = currentMode == Race.CalculationMode.MANUAL_START_DUAL_TIMING
                ? dualTimingData.isEmpty() : resultsData.isEmpty();
        if (currentRace == null || !currentRace.hasCurrentAttempt() || empty) {
            showAlert(Alert.AlertType.WARNING, "No Results",
                    "No results to export yet — calculate results from the Live Tracking tab first");
            return;
        }

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Export Results");
        fileChooser.setInitialFileName(
                currentRace.getRaceName().replaceAll("\\s+", "_") + "_Results.xlsx"
        );
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Excel Files", "*.xlsx")
        );

        File file = fileChooser.showSaveDialog(exportExcelButton.getScene().getWindow());

        if (file != null) {
            exportExcelButton.setDisable(true);
            final Long raceId = currentRace.getId();
            final Long sessionId = currentRace.getCurrentSessionId();
            final LocalDateTime attemptStart = currentRace.getCurrentAttemptStartedAt();

            new Thread(() -> {
                try {
                    // Scoped to the current attempt via its RaceSession id (the reliable link),
                    // same as what's on screen - an earlier attempt's results (kept on file since
                    // Reset stopped deleting them) stay out of this export.
                    reportService.exportRaceResults(raceId, sessionId, attemptStart, null, file);

                    Platform.runLater(() -> {
                        showAlert(Alert.AlertType.INFORMATION, "Success",
                                "Exported: " + file.getName());
                        exportExcelButton.setDisable(false);
                    });

                } catch (Exception e) {
                    Platform.runLater(() -> {
                        showAlert(Alert.AlertType.ERROR, "Error",
                                "Export failed: " + e.getMessage());
                        exportExcelButton.setDisable(false);
                    });
                    log.error("Export error", e);
                }
            }).start();
        }
    }

    @FXML
    private void handleRefresh() {
        refreshResults();
    }

    private void showAlert(Alert.AlertType type, String title, String content) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }

}
