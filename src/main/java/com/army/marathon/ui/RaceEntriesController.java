package com.army.marathon.ui;

import com.army.marathon.model.Participant;
import com.army.marathon.model.Race;
import com.army.marathon.model.RaceEntry;
import com.army.marathon.service.ParticipantService;
import com.army.marathon.service.RaceEntryService;
import javafx.beans.Observable;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.stage.FileChooser;
import lombok.extern.slf4j.Slf4j;
import net.rgielen.fxweaver.core.FxmlView;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.*;

/**
 * "Race Entries" tab: pick which registered participants are running in the current
 * race. With a saved roster, MultiReaderService rejects scans from everyone else and
 * RaceTimingService builds results from the roster; with no saved roster nothing changes.
 */
@Slf4j
@Component
@FxmlView("RaceEntriesView.fxml")
public class RaceEntriesController {

    private static final int INLINE_NOT_FOUND_LIMIT = 15;

    @FXML private Label raceLabel;
    @FXML private Label rosterStatusLabel;
    @FXML private Label selectedCountLabel;
    @FXML private TextField searchField;
    @FXML private Button selectAllButton;
    @FXML private Button selectNoneButton;
    @FXML private Button downloadTemplateButton;
    @FXML private Button uploadExcelButton;
    @FXML private Button saveRosterButton;

    @FXML private TableView<RosterRow> rosterTable;
    @FXML private TableColumn<RosterRow, Boolean> includedColumn;
    @FXML private TableColumn<RosterRow, String> nameColumn;
    @FXML private TableColumn<RosterRow, String> tagNoColumn;
    @FXML private TableColumn<RosterRow, String> unitColumn;

    private final RaceEntryService raceEntryService;
    private final ParticipantService participantService;

    // The extractor makes a checkbox toggle fire a list "update" event, which is what
    // keeps the "X of Y selected" label live.
    private final ObservableList<RosterRow> rows =
            FXCollections.observableArrayList(row -> new Observable[]{row.selectedProperty()});
    private final FilteredList<RosterRow> filteredRows = new FilteredList<>(rows, row -> true);
    private final Map<Long, RosterRow> rowsById = new HashMap<>();

    private Long currentRaceId;
    private boolean bulkUpdating;

    public RaceEntriesController(RaceEntryService raceEntryService, ParticipantService participantService) {
        this.raceEntryService = raceEntryService;
        this.participantService = participantService;
    }

    @FXML
    public void initialize() {
        log.info("🔧 Initializing Race Entries Controller");

        setupTable();

        rows.addListener((ListChangeListener<RosterRow>) change -> {
            if (!bulkUpdating) {
                updateSelectedCount();
            }
        });
        searchField.textProperty().addListener((obs, oldVal, newVal) ->
                filteredRows.setPredicate(row -> row.matches(newVal)));

        refresh(false);
        log.info("✅ Race Entries Controller initialized");
    }

    private void setupTable() {
        rosterTable.setEditable(true);

        includedColumn.setEditable(true);
        includedColumn.setCellValueFactory(cell -> cell.getValue().selectedProperty());
        includedColumn.setCellFactory(CheckBoxTableCell.forTableColumn(includedColumn));

        nameColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().getName()));
        tagNoColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().getTagNo()));
        unitColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().getUnit()));

        SortedList<RosterRow> sortedRows = new SortedList<>(filteredRows);
        sortedRows.comparatorProperty().bind(rosterTable.comparatorProperty());
        rosterTable.setItems(sortedRows);
    }

    /**
     * Reloads participants and the saved roster. Called when the tab is shown, so
     * participants registered since the last visit appear. Unsaved checkbox changes
     * survive a plain refresh (switching tabs shouldn't lose a half-built roster);
     * they are dropped only after a save.
     */
    public void refresh() {
        refresh(true);
    }

    private void refresh(boolean keepUnsavedChanges) {
        try {
            Optional<Race> race = raceEntryService.getCurrentRace();
            currentRaceId = race.map(Race::getId).orElse(null);

            Map<Long, RaceEntry.Source> saved = currentRaceId != null
                    ? raceEntryService.getRosterSources(currentRaceId)
                    : Collections.emptyMap();

            List<RosterRow> fresh = new ArrayList<>();
            Map<Long, RosterRow> freshById = new HashMap<>();
            for (Participant participant : participantService.getAllParticipants()) {
                RosterRow row = new RosterRow(participant);
                RosterRow previous = keepUnsavedChanges ? rowsById.get(participant.getId()) : null;
                if (previous != null) {
                    row.restore(previous.isSelected(), previous.getSource());
                } else if (saved.containsKey(participant.getId())) {
                    row.restore(true, saved.get(participant.getId()));
                }
                fresh.add(row);
                freshById.put(participant.getId(), row);
            }

            bulkUpdating = true;
            try {
                rows.setAll(fresh);
                rowsById.clear();
                rowsById.putAll(freshById);
            } finally {
                bulkUpdating = false;
            }

            raceLabel.setText(race.map(r -> "Race: " + r.getRaceName())
                    .orElse("No race yet - open the Live Tracking tab once to create it"));
            updateRosterStatus(saved.size());
            updateSelectedCount();
            saveRosterButton.setDisable(currentRaceId == null);

        } catch (Exception e) {
            log.error("❌ Failed to load race entries", e);
            showAlert(Alert.AlertType.ERROR, "Error", "❌ Failed to load participants: " + e.getMessage());
        }
    }

    private void updateRosterStatus(int savedCount) {
        if (currentRaceId == null) {
            rosterStatusLabel.setText("");
        } else if (savedCount == 0) {
            rosterStatusLabel.setText("Saved roster: none - ALL registered participants are scannable");
            rosterStatusLabel.setStyle("-fx-text-fill: #b7791f; -fx-font-weight: bold;");
        } else {
            rosterStatusLabel.setText("Saved roster: " + savedCount + " participant(s) - all others are rejected at scan time");
            rosterStatusLabel.setStyle("-fx-text-fill: #1e824c; -fx-font-weight: bold;");
        }
    }

    private void updateSelectedCount() {
        long selected = rows.stream().filter(RosterRow::isSelected).count();
        selectedCountLabel.setText(selected + " of " + rows.size() + " participants selected");
    }

    // ------------------------------------------------------------------
    // Select all / none (operate on the currently filtered view only)
    // ------------------------------------------------------------------

    @FXML
    private void handleSelectAll() {
        setFilteredSelection(true);
    }

    @FXML
    private void handleSelectNone() {
        setFilteredSelection(false);
    }

    private void setFilteredSelection(boolean selected) {
        bulkUpdating = true;
        try {
            for (RosterRow row : new ArrayList<>(filteredRows)) {
                row.setSelectedBy(selected, RaceEntry.Source.MANUAL);
            }
        } finally {
            bulkUpdating = false;
        }
        updateSelectedCount();
    }

    // ------------------------------------------------------------------
    // Excel template / upload
    // ------------------------------------------------------------------

    @FXML
    private void handleDownloadTemplate() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save Tag No Template");
        chooser.setInitialFileName("race_roster_template.xlsx");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Excel Files", "*.xlsx"));

        File file = chooser.showSaveDialog(downloadTemplateButton.getScene().getWindow());
        if (file == null) {
            return;
        }
        try {
            raceEntryService.writeTagNoTemplate(file);
            showAlert(Alert.AlertType.INFORMATION, "Template Saved",
                    "✅ Template saved.\n\nFile: " + file.getName() +
                            "\n\nFill in one Tag No per row under the \"Tag No\" header, then use Upload Tag No Excel.");
        } catch (Exception e) {
            log.error("❌ Template export failed", e);
            showAlert(Alert.AlertType.ERROR, "Error", "❌ Failed to save template: " + e.getMessage());
        }
    }

    @FXML
    private void handleUploadExcel() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select Tag No Excel File");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Excel Files", "*.xlsx"));

        File file = chooser.showOpenDialog(uploadExcelButton.getScene().getWindow());
        if (file == null) {
            return;
        }

        try {
            RaceEntryService.TagNoFile parsed = raceEntryService.readTagNosFromExcel(file);

            // Make sure participants registered since the last refresh can be matched
            refresh(true);
            RaceEntryService.TagNoMatch match = raceEntryService.matchTagNos(parsed.tagNos());

            bulkUpdating = true;
            try {
                for (Participant participant : match.matched()) {
                    RosterRow row = rowsById.get(participant.getId());
                    if (row != null) {
                        row.setSelectedBy(true, RaceEntry.Source.EXCEL_UPLOAD);
                    }
                }
            } finally {
                bulkUpdating = false;
            }
            updateSelectedCount();

            showUploadSummary(match, parsed.duplicateRowsIgnored());
            log.info("📥 Roster upload: {} matched, {} not found ({} duplicate row(s) ignored)",
                    match.matched().size(), match.notFound().size(), parsed.duplicateRowsIgnored());

        } catch (Exception e) {
            log.error("❌ Roster upload failed", e);
            showAlert(Alert.AlertType.ERROR, "Error", "❌ Failed to read Excel: " + e.getMessage());
        }
    }

    private void showUploadSummary(RaceEntryService.TagNoMatch match, int duplicateRows) {
        List<String> notFound = match.notFound();
        String summary = match.matched().size() + " matched, " + notFound.size() + " not found";

        Alert alert = new Alert(notFound.isEmpty() ? Alert.AlertType.INFORMATION : Alert.AlertType.WARNING);
        alert.setTitle("Upload Summary");
        alert.setHeaderText(summary);

        StringBuilder text = new StringBuilder("Matched participants are now checked. Click Save Roster to apply.");
        if (duplicateRows > 0) {
            text.append("\n").append(duplicateRows).append(" repeated Tag No row(s) in the file were counted once.");
        }

        String notFoundList = "[" + String.join(", ", notFound) + "]";
        if (notFound.isEmpty()) {
            alert.setContentText(text.toString());
        } else if (notFound.size() <= INLINE_NOT_FOUND_LIMIT) {
            text.append("\n\nNot found: ").append(notFoundList);
            alert.setContentText(text.toString());
        } else {
            // Long lists go in a scrollable box so the dialog stays usable
            alert.setContentText(text.append("\n\nNot found:").toString());
            TextArea area = new TextArea(notFoundList);
            area.setEditable(false);
            area.setWrapText(true);
            area.setPrefRowCount(8);
            alert.getDialogPane().setExpandableContent(area);
            alert.getDialogPane().setExpanded(true);
        }
        alert.showAndWait();
    }

    // ------------------------------------------------------------------
    // Save roster
    // ------------------------------------------------------------------

    @FXML
    private void handleSaveRoster() {
        if (currentRaceId == null) {
            showAlert(Alert.AlertType.WARNING, "No Race",
                    "There is no current race yet. Open the Live Tracking tab once to create it.");
            return;
        }

        Map<Long, RaceEntry.Source> selection = new LinkedHashMap<>();
        for (RosterRow row : rows) {
            if (row.isSelected()) {
                selection.put(row.getParticipantId(), row.getSource());
            }
        }

        if (selection.isEmpty()) {
            // Zero entries means "no roster", which un-restricts scanning - never let
            // that happen by accident.
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle("Clear Roster");
            confirm.setHeaderText("No participants are selected");
            confirm.setContentText("Saving now will CLEAR the roster for this race, and ALL registered " +
                    "participants will be scannable again.\n\nContinue?");
            Optional<ButtonType> answer = confirm.showAndWait();
            if (answer.isEmpty() || answer.get() != ButtonType.OK) {
                return;
            }
        }

        try {
            raceEntryService.saveRoster(currentRaceId, selection);
            refresh(false);
            showAlert(Alert.AlertType.INFORMATION, "Roster Saved",
                    selection.isEmpty()
                            ? "✅ Roster cleared. All registered participants are scannable."
                            : "✅ Roster saved: " + selection.size() + " participant(s).\n\n" +
                              "Scans from any other registered tag will now be rejected for this race.");
        } catch (Exception e) {
            log.error("❌ Save roster failed", e);
            showAlert(Alert.AlertType.ERROR, "Error", "❌ Failed to save roster: " + e.getMessage());
        }
    }

    private void showAlert(Alert.AlertType type, String title, String content) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }

    /**
     * One table row: a registered participant plus whether they're on the roster and
     * how that checkbox was last set.
     */
    public static class RosterRow {
        private final Long participantId;
        private final String name;
        private final String tagNo;
        private final String unit;
        private final BooleanProperty selected = new SimpleBooleanProperty(false);
        private RaceEntry.Source source = RaceEntry.Source.MANUAL;
        private boolean programmatic;

        RosterRow(Participant participant) {
            this.participantId = participant.getId();
            this.name = participant.getName() != null ? participant.getName() : "";
            this.tagNo = participant.getSerialNo() != null ? participant.getSerialNo() : "";
            this.unit = participant.getUnit() != null ? participant.getUnit() : "";

            // A toggle that isn't one of our own programmatic sets came from the user's click
            selected.addListener((obs, was, is) -> {
                if (!programmatic) {
                    source = RaceEntry.Source.MANUAL;
                }
            });
        }

        /** Set the checkbox on behalf of Select All/None or an upload, recording its provenance. */
        void setSelectedBy(boolean value, RaceEntry.Source newSource) {
            programmatic = true;
            try {
                selected.set(value);
                source = newSource;
            } finally {
                programmatic = false;
            }
        }

        /** Restore previously saved/edited state without changing provenance. */
        void restore(boolean value, RaceEntry.Source restoredSource) {
            programmatic = true;
            try {
                selected.set(value);
                source = restoredSource != null ? restoredSource : RaceEntry.Source.MANUAL;
            } finally {
                programmatic = false;
            }
        }

        boolean matches(String query) {
            if (query == null || query.isBlank()) {
                return true;
            }
            String q = query.trim().toLowerCase(Locale.ROOT);
            return name.toLowerCase(Locale.ROOT).contains(q) || tagNo.toLowerCase(Locale.ROOT).contains(q);
        }

        public BooleanProperty selectedProperty() { return selected; }
        public boolean isSelected() { return selected.get(); }
        public Long getParticipantId() { return participantId; }
        public String getName() { return name; }
        public String getTagNo() { return tagNo; }
        public String getUnit() { return unit; }
        public RaceEntry.Source getSource() { return source; }
    }
}
