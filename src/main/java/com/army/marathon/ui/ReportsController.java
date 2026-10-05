package com.army.marathon.ui;

import com.army.marathon.model.*;
import com.army.marathon.repository.*;
import com.army.marathon.service.ReportService;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.stage.FileChooser;
import lombok.extern.slf4j.Slf4j;
import net.rgielen.fxweaver.core.FxmlView;
import org.springframework.stereotype.Component;

import java.io.File;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Historical race attempts by date - fully independent of Live Tracking. Reads only:
 * it never triggers, depends on, or is affected by Reset, Arm Readers, Start Race, Stop
 * Race or Calculate Results, and opening or using this tab has no side effects on any
 * other tab's state. Each attempt is a {@link RaceSession} row (see LiveTrackingController
 * .beginNewAttempt); its window [startedAt, endedAt-or-now) scopes which readings/results
 * belong to it, the same way the Results tab scopes to the CURRENT attempt.
 */
@Slf4j
@Component
@FxmlView("ReportsView.fxml")
public class ReportsController {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm");

    @FXML private ToggleButton todayButton;
    @FXML private ToggleButton yesterdayButton;
    @FXML private ToggleButton customRangeButton;
    @FXML private javafx.scene.layout.HBox customRangeBox;
    @FXML private DatePicker fromDatePicker;
    @FXML private DatePicker toDatePicker;
    @FXML private Button applyRangeButton;
    @FXML private Label rangeSummaryLabel;

    @FXML private TableView<SessionRow> sessionsTable;
    @FXML private TableColumn<SessionRow, String> sessionRaceNameColumn;
    @FXML private TableColumn<SessionRow, String> sessionDateColumn;
    @FXML private TableColumn<SessionRow, String> sessionModeColumn;
    @FXML private TableColumn<SessionRow, Number> sessionParticipantsColumn;
    @FXML private TableColumn<SessionRow, Number> sessionCompletedColumn;
    @FXML private TableColumn<SessionRow, Void> sessionActionColumn;

    @FXML private Label selectedSessionLabel;
    @FXML private Label totalFinishersLabel;
    @FXML private Label totalDnfLabel;
    @FXML private Button exportExcelButton;

    @FXML private TableView<ResultsRenderer.ParticipantResultRow> resultsTable;
    @FXML private TableColumn<ResultsRenderer.ParticipantResultRow, Integer> rankColumn;
    @FXML private TableColumn<ResultsRenderer.ParticipantResultRow, String> nameColumn;
    @FXML private TableColumn<ResultsRenderer.ParticipantResultRow, String> serviceNoColumn;
    @FXML private TableColumn<ResultsRenderer.ParticipantResultRow, String> rankArmyColumn;
    @FXML private TableColumn<ResultsRenderer.ParticipantResultRow, String> unitColumn;
    @FXML private TableColumn<ResultsRenderer.ParticipantResultRow, String> categoryColumn;
    @FXML private TableColumn<ResultsRenderer.ParticipantResultRow, String> totalTimeColumn;
    @FXML private TableColumn<ResultsRenderer.ParticipantResultRow, String> statusColumn;

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
    private final RaceSessionRepository raceSessionRepository;
    private final RaceResultRepository resultRepository;
    private final CheckpointReadingRepository readingRepository;
    private final ReportService reportService;
    private final ResultsRenderer renderer;

    private final ObservableList<SessionRow> sessionRows = FXCollections.observableArrayList();
    private final ObservableList<ResultsRenderer.ParticipantResultRow> resultsData = FXCollections.observableArrayList();
    private final ObservableList<ResultsRenderer.DualTimingRow> dualTimingData = FXCollections.observableArrayList();
    private final List<TableColumn<ResultsRenderer.ParticipantResultRow, String>> checkpointColumns = new ArrayList<>();

    private ToggleGroup rangeGroup;
    private SessionRow selectedSession;

    public ReportsController(
            RaceRepository raceRepository,
            RaceSessionRepository raceSessionRepository,
            RaceResultRepository resultRepository,
            CheckpointReadingRepository readingRepository,
            ReportService reportService,
            ResultsRenderer renderer
    ) {
        this.raceRepository = raceRepository;
        this.raceSessionRepository = raceSessionRepository;
        this.resultRepository = resultRepository;
        this.readingRepository = readingRepository;
        this.reportService = reportService;
        this.renderer = renderer;
    }

    @FXML
    public void initialize() {
        setupRangeToggles();
        setupSessionsTable();
        setupResultColumns();
        setupDualTimingColumns();
        todayButton.setSelected(true);
        applyRange(LocalDate.now(), LocalDate.now());
    }

    // ------------------------------------------------------------------
    // Date range filter
    // ------------------------------------------------------------------

    private void setupRangeToggles() {
        rangeGroup = new ToggleGroup();
        todayButton.setToggleGroup(rangeGroup);
        yesterdayButton.setToggleGroup(rangeGroup);
        customRangeButton.setToggleGroup(rangeGroup);

        fromDatePicker.setValue(LocalDate.now());
        toDatePicker.setValue(LocalDate.now());

        todayButton.setOnAction(e -> {
            setShown(customRangeBox, false);
            applyRange(LocalDate.now(), LocalDate.now());
        });
        yesterdayButton.setOnAction(e -> {
            setShown(customRangeBox, false);
            LocalDate yesterday = LocalDate.now().minusDays(1);
            applyRange(yesterday, yesterday);
        });
        customRangeButton.setOnAction(e -> setShown(customRangeBox, true));
    }

    /**
     * Public so MainViewController can call this when the Reports tab is selected, picking
     * up any race attempt finished since the last visit - re-runs whichever quick filter or
     * custom range is currently selected. Never touches Live Tracking's own state.
     */
    public void refresh() {
        if (customRangeButton.isSelected()) {
            handleApplyCustomRange();
        } else if (yesterdayButton.isSelected()) {
            LocalDate yesterday = LocalDate.now().minusDays(1);
            applyRange(yesterday, yesterday);
        } else {
            applyRange(LocalDate.now(), LocalDate.now());
        }
    }

    @FXML
    private void handleApplyCustomRange() {
        LocalDate from = fromDatePicker.getValue();
        LocalDate to = toDatePicker.getValue();
        if (from == null || to == null) {
            showAlert(Alert.AlertType.WARNING, "Pick Both Dates", "Choose both a From and a To date.");
            return;
        }
        if (from.isAfter(to)) {
            showAlert(Alert.AlertType.WARNING, "Invalid Range", "From date must not be after the To date.");
            return;
        }
        applyRange(from, to);
    }

    private static void setShown(javafx.scene.Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }

    private void applyRange(LocalDate from, LocalDate to) {
        LocalDateTime start = from.atStartOfDay();
        LocalDateTime end = to.plusDays(1).atStartOfDay();
        rangeSummaryLabel.setText(from.equals(to)
                ? "Showing: " + from
                : "Showing: " + from + " to " + to);
        loadSessions(start, end);
    }

    // ------------------------------------------------------------------
    // Sessions list
    // ------------------------------------------------------------------

    private void setupSessionsTable() {
        sessionRaceNameColumn.setCellValueFactory(new PropertyValueFactory<>("raceName"));
        sessionDateColumn.setCellValueFactory(new PropertyValueFactory<>("dateDisplay"));
        sessionModeColumn.setCellValueFactory(new PropertyValueFactory<>("modeDisplay"));
        sessionParticipantsColumn.setCellValueFactory(new PropertyValueFactory<>("participantCount"));
        sessionCompletedColumn.setCellValueFactory(new PropertyValueFactory<>("completedCount"));
        sessionsTable.setItems(sessionRows);

        // A Delete button on every row, so it works under every date filter (Today, Yesterday, Custom).
        sessionActionColumn.setCellFactory(col -> new TableCell<>() {
            private final Button deleteButton = new Button("Delete");
            {
                deleteButton.getStyleClass().addAll("btn", "btn-danger");
                deleteButton.setOnAction(e -> {
                    if (getIndex() >= 0 && getIndex() < getTableView().getItems().size()) {
                        handleDeleteSession(getTableView().getItems().get(getIndex()));
                    }
                });
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty ? null : deleteButton);
            }
        });

        sessionsTable.getSelectionModel().selectedItemProperty().addListener((obs, oldRow, newRow) -> {
            selectedSession = newRow;
            showSelectedSession();
        });
    }

    /** The one race this installation runs - read-only, never created or modified here. */
    private Optional<Race> currentRace() {
        List<Race> races = raceRepository.findAll();
        return races.isEmpty() ? Optional.empty() : Optional.of(races.get(races.size() - 1));
    }

    private void loadSessions(LocalDateTime start, LocalDateTime end) {
        sessionRows.clear();
        resultsData.clear();
        dualTimingData.clear();
        selectedSession = null;
        showDualTimingLayout(false);
        selectedSessionLabel.setText("Select a race attempt on the left");
        totalFinishersLabel.setText("0");
        totalDnfLabel.setText("0");
        exportExcelButton.setDisable(true);

        Optional<Race> raceOpt = currentRace();
        if (raceOpt.isEmpty()) {
            return;
        }
        Race race = raceOpt.get();

        List<RaceSession> sessions = raceSessionRepository
                .findByRaceIdAndStartedAtBetweenOrderByStartedAtDesc(race.getId(), start, end);

        for (RaceSession session : sessions) {
            // Scoped by raceSessionId - the definitive link stamped at calculation time
            // (see RaceTimingService.resolveSessionId) - not by createdAt, which has no
            // reliable relationship to when the race itself ran.
            List<RaceResult> sessionResults = resultRepository.findByRaceSessionId(session.getId());
            long completed = sessionResults.stream().filter(RaceResult::isCompletedRace).count();
            sessionRows.add(new SessionRow(session, sessionResults.size(), (int) completed));
        }
    }

    // ------------------------------------------------------------------
    // Selected attempt's results - same rendering as the Results tab, scoped to
    // this attempt's [startedAt, endedAt-or-now) window instead of "the current one"
    // ------------------------------------------------------------------

    private void showSelectedSession() {
        resultsData.clear();
        dualTimingData.clear();

        if (selectedSession == null) {
            selectedSessionLabel.setText("Select a race attempt on the left");
            totalFinishersLabel.setText("0");
            totalDnfLabel.setText("0");
            exportExcelButton.setDisable(true);
            return;
        }

        Optional<Race> raceOpt = currentRace();
        if (raceOpt.isEmpty()) {
            return;
        }
        Race race = raceOpt.get();
        RaceSession session = selectedSession.session;

        Race.CalculationMode mode = session.getCalculationMode() != null
                ? session.getCalculationMode() : Race.CalculationMode.TWO_CHECKPOINT;

        selectedSessionLabel.setText(selectedSession.getRaceName() + " - " + selectedSession.getDateDisplay());
        exportExcelButton.setDisable(false);

        boolean dualTiming = mode == Race.CalculationMode.MANUAL_START_DUAL_TIMING;
        showDualTimingLayout(dualTiming);

        // Scoped by raceSessionId - the definitive link stamped at calculation time - not
        // by a createdAt time-window, which is when Calculate Results happened to be
        // clicked and has no reliable relationship to when the race itself ran.
        List<RaceResult> results = resultRepository.findByRaceSessionId(session.getId());

        if (dualTiming) {
            renderDualTiming(results);
        } else {
            // Same window Delete uses: [startedAt, endedAt-or-now], capped by the next attempt's start.
            List<CheckpointReading> readings = reportService.readingsForAttempt(session);
            renderStandard(race, mode, results, readings);
        }
    }

    private void renderStandard(Race race, Race.CalculationMode mode, List<RaceResult> results,
                                 List<CheckpointReading> readings) {
        List<ResultsRenderer.CheckpointColumn> columns = renderer.resolveCheckpointColumns(race, mode);
        updateCheckpointColumns(columns);

        results = new ArrayList<>(results);
        results.sort(ResultsRenderer.FINISHING_ORDER);

        var readingsByParticipant = readings.stream()
                .filter(r -> r.getParticipant() != null)
                .collect(Collectors.groupingBy(r -> r.getParticipant().getId()));

        for (RaceResult result : results) {
            if (result.getParticipant() == null) continue;
            List<CheckpointReading> participantReadings = readingsByParticipant
                    .getOrDefault(result.getParticipant().getId(), new ArrayList<>());
            resultsData.add(new ResultsRenderer.ParticipantResultRow(result, participantReadings, columns, mode));
        }

        long finished = results.stream().filter(RaceResult::isCompletedRace).count();
        totalFinishersLabel.setText(String.valueOf(finished));
        totalDnfLabel.setText(String.valueOf(results.size() - finished));
    }

    private void renderDualTiming(List<RaceResult> results) {
        List<RaceResult> sorted = new ArrayList<>(results);
        sorted.sort(java.util.Comparator
                .comparing((RaceResult r) -> !r.isCompletedRace())
                .thenComparing(RaceResult::getFinishTime, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())));

        for (RaceResult result : sorted) {
            if (result.getParticipant() != null) {
                dualTimingData.add(new ResultsRenderer.DualTimingRow(result));
            }
        }

        long completed = sorted.stream().filter(RaceResult::isCompletedRace).count();
        totalFinishersLabel.setText(String.valueOf(completed));
        totalDnfLabel.setText(String.valueOf(sorted.size() - completed));
    }

    private void updateCheckpointColumns(List<ResultsRenderer.CheckpointColumn> columns) {
        resultsTable.getColumns().removeAll(checkpointColumns);
        checkpointColumns.clear();

        int insertIndex = resultsTable.getColumns().indexOf(categoryColumn) + 1;
        for (int i = 1; i <= columns.size(); i++) {
            TableColumn<ResultsRenderer.ParticipantResultRow, String> col = new TableColumn<>(columns.get(i - 1).header());
            final int checkpointIndex = i;
            col.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getCheckpointTime(checkpointIndex)));
            col.setPrefWidth(100);
            col.setStyle("-fx-alignment: CENTER;");
            checkpointColumns.add(col);
            resultsTable.getColumns().add(insertIndex++, col);
        }
    }

    private void setupResultColumns() {
        rankColumn.setCellValueFactory(new PropertyValueFactory<>("rank"));
        nameColumn.setCellValueFactory(new PropertyValueFactory<>("name"));
        serviceNoColumn.setCellValueFactory(new PropertyValueFactory<>("serviceNo"));
        rankArmyColumn.setCellValueFactory(new PropertyValueFactory<>("armyRank"));
        unitColumn.setCellValueFactory(new PropertyValueFactory<>("unit"));
        categoryColumn.setCellValueFactory(new PropertyValueFactory<>("category"));
        totalTimeColumn.setCellValueFactory(new PropertyValueFactory<>("totalTime"));
        statusColumn.setCellValueFactory(new PropertyValueFactory<>("status"));
        resultsTable.setItems(resultsData);
    }

    private void setupDualTimingColumns() {
        dualNameColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name));
        dualTagNoColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().tagNo));
        dualUnitColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().unit));
        dualStatusColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().status));
        dualDeclaredStartColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().declaredStart));
        dualStartColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().startTime));
        dualFinishColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().finishTime));
        dualDeclaredDurationColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().declaredDuration));
        dualStartDurationColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().startDuration));
        // Read-only here - the Reports tab is a history view, not somewhere notes get edited
        dualNoteColumn.setCellValueFactory(c -> c.getValue().noteProperty());
        dualTimingTable.setItems(dualTimingData);
    }

    private void showDualTimingLayout(boolean dualTiming) {
        setShown(dualTimingTable, dualTiming);
        setShown(resultsTable, !dualTiming);
    }

    // ------------------------------------------------------------------
    // Export
    // ------------------------------------------------------------------

    @FXML
    private void handleExportExcel() {
        if (selectedSession == null) {
            return;
        }
        Optional<Race> raceOpt = currentRace();
        if (raceOpt.isEmpty()) {
            return;
        }
        Race race = raceOpt.get();
        RaceSession session = selectedSession.session;
        LocalDateTime start = session.getStartedAt();
        LocalDateTime end = session.getEndedAt() != null ? session.getEndedAt() : LocalDateTime.now();

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Export Results");
        String safeName = (race.getRaceName() != null ? race.getRaceName() : "Race").replaceAll("\\s+", "_");
        fileChooser.setInitialFileName(safeName + "_" + start.toLocalDate() + "_Results.xlsx");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Excel Files", "*.xlsx"));

        File file = fileChooser.showSaveDialog(exportExcelButton.getScene().getWindow());
        if (file == null) {
            return;
        }

        exportExcelButton.setDisable(true);
        final Long raceId = race.getId();
        final Long sessionId = session.getId();
        new Thread(() -> {
            try {
                reportService.exportRaceResults(raceId, sessionId, start, end, file);
                Platform.runLater(() -> {
                    showAlert(Alert.AlertType.INFORMATION, "Success", "Exported: " + file.getName());
                    exportExcelButton.setDisable(false);
                });
            } catch (Exception e) {
                log.error("Reports export failed", e);
                Platform.runLater(() -> {
                    showAlert(Alert.AlertType.ERROR, "Error", "Export failed: " + e.getMessage());
                    exportExcelButton.setDisable(false);
                });
            }
        }).start();
    }

    // ------------------------------------------------------------------
    // Delete an attempt (the ONLY place race history is ever deleted)
    // ------------------------------------------------------------------

    private void handleDeleteSession(SessionRow row) {
        final Long sessionId = row.session.getId();

        if (reportService.isRunningAttempt(sessionId)) {
            showAlert(Alert.AlertType.WARNING, "Race In Progress",
                    "This is the current attempt of a race that is running (armed or active).\n\n" +
                            "Stop or reset the race before deleting it.");
            return;
        }

        ReportService.AttemptCounts counts;
        try {
            counts = reportService.previewAttempt(sessionId);
        } catch (Exception e) {
            showAlert(Alert.AlertType.ERROR, "Error", "Could not read this attempt: " + e.getMessage());
            return;
        }

        ButtonType deleteType = new ButtonType("Delete", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert confirm = new Alert(Alert.AlertType.WARNING, "", deleteType, cancelType);
        confirm.setTitle("Delete Race Attempt");
        confirm.setHeaderText("Delete this race attempt?");
        confirm.setContentText(
                "Race: " + row.getRaceName() + "\n" +
                "Date/time: " + row.getDateDisplay() + "\n\n" +
                "This permanently deletes:\n" +
                "  - " + counts.readings() + " tag reading(s)\n" +
                "  - " + counts.results() + " result(s)\n" +
                "  - the attempt itself\n\n" +
                "Participants and other attempts are not affected.\n" +
                "This cannot be undone.");
        // Cancel is the default (Enter/Escape), never Delete
        ((Button) confirm.getDialogPane().lookupButton(deleteType)).setDefaultButton(false);
        ((Button) confirm.getDialogPane().lookupButton(cancelType)).setDefaultButton(true);

        Optional<ButtonType> answer = confirm.showAndWait();
        if (answer.isEmpty() || answer.get() != deleteType) {
            return;
        }

        try {
            ReportService.AttemptCounts deleted = reportService.deleteAttempt(sessionId);
            log.warn("Reports: deleted attempt {} ({} readings, {} results)",
                    sessionId, deleted.readings(), deleted.results());
            // Re-runs the current filter; loadSessions also clears the results pane, which is
            // right whether or not the deleted attempt was the one being shown.
            refresh();
        } catch (Exception e) {
            log.error("Reports delete failed", e);
            showAlert(Alert.AlertType.ERROR, "Delete Failed", e.getMessage());
        }
    }

    private void showAlert(Alert.AlertType type, String title, String content) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }

    // ------------------------------------------------------------------
    // Row
    // ------------------------------------------------------------------

    /** One row in the attempts list: a RaceSession plus its computed counts. */
    public static class SessionRow {
        final RaceSession session;
        private final int participantCount;
        private final int completedCount;

        SessionRow(RaceSession session, int participantCount, int completedCount) {
            this.session = session;
            this.participantCount = participantCount;
            this.completedCount = completedCount;
        }

        public String getRaceName() { return session.getRaceName() != null ? session.getRaceName() : ""; }
        public String getDateDisplay() { return session.getStartedAt() != null ? session.getStartedAt().format(DATE_FMT) : ""; }
        public String getModeDisplay() {
            return session.getCalculationMode() != null ? modeLabel(session.getCalculationMode()) : "";
        }
        public int getParticipantCount() { return participantCount; }
        public int getCompletedCount() { return completedCount; }

        private static String modeLabel(Race.CalculationMode mode) {
            return switch (mode) {
                case SAME_START_FINISH -> "Same Start/Finish";
                case LAP_RACE -> "Lap Race";
                case MANUAL_START_DUAL_TIMING -> "Manual Start";
                case LONG_DISTANCE_MANUAL_START -> "Long Distance";
                case LONG_DISTANCE_2_MANUAL_START -> "Long Distance 2";
                default -> "2 Checkpoints";
            };
        }
    }
}
