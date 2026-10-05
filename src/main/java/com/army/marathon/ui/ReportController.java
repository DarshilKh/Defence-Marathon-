// src/main/java/com/army/marathon/ui/ReportController.java
package com.army.marathon.ui;

import com.army.marathon.model.Race;
import com.army.marathon.repository.RaceRepository;
import com.army.marathon.service.ReportService;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.FileChooser;
import lombok.extern.slf4j.Slf4j;
import net.rgielen.fxweaver.core.FxmlView;
import org.springframework.stereotype.Component;

import java.io.File;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Component
@FxmlView("ReportView.fxml")
public class ReportController {

    @FXML private ComboBox<String> raceComboBox;
    @FXML private DatePicker fromDatePicker;
    @FXML private DatePicker toDatePicker;
    @FXML private CheckBox includeCheckpointDetailsCheckBox;
    @FXML private CheckBox includeSummaryCheckBox;
    @FXML private Button generateReportButton;
    @FXML private Button exportExcelButton;
    @FXML private TextArea reportPreviewArea;
    @FXML private Label statusLabel;
    
    private final ReportService reportService;
    private final RaceRepository raceRepository;
    private Long selectedRaceId;

    public ReportController(ReportService reportService, RaceRepository raceRepository) {
        this.reportService = reportService;
        this.raceRepository = raceRepository;
    }

    @FXML
    public void initialize() {
        loadRaces();
        setupDefaults();
    }

    private void loadRaces() {
        List<Race> races = raceRepository.findAll();
        ObservableList<String> raceNames = FXCollections.observableArrayList();
        
        races.forEach(race -> {
            String displayName = race.getRaceName() + " - " + 
                (race.getRaceDate() != null ? 
                    race.getRaceDate().format(DateTimeFormatter.ofPattern("dd-MMM-yyyy")) : 
                    "No Date");
            raceNames.add(displayName);
        });
        
        raceComboBox.setItems(raceNames);
        
        if (!races.isEmpty()) {
            raceComboBox.setValue(raceNames.get(0));
            selectedRaceId = races.get(0).getId();
        }
        
        raceComboBox.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                int index = raceComboBox.getItems().indexOf(newVal);
                selectedRaceId = races.get(index).getId();
            }
        });
    }

    private void setupDefaults() {
        fromDatePicker.setValue(LocalDate.now().minusDays(30));
        toDatePicker.setValue(LocalDate.now());
        includeCheckpointDetailsCheckBox.setSelected(true);
        includeSummaryCheckBox.setSelected(true);
    }

    @FXML
    private void handleGenerateReport() {
        if (selectedRaceId == null) {
            showAlert(Alert.AlertType.WARNING, "No Race", "Please select a race first");
            return;
        }
        
        try {
            statusLabel.setText("🔄 Generating report preview...");
            statusLabel.setStyle("-fx-text-fill: orange;");
            
            Race race = raceRepository.findById(selectedRaceId).orElse(null);
            if (race == null) {
                showAlert(Alert.AlertType.ERROR, "Error", "Race not found");
                return;
            }
            
            StringBuilder preview = new StringBuilder();
            preview.append("═══════════════════════════════════════════════════\n");
            preview.append("         RACE REPORT PREVIEW\n");
            preview.append("═══════════════════════════════════════════════════\n\n");
            preview.append("Race Name: ").append(race.getRaceName()).append("\n");
            preview.append("Race Date: ").append(
                race.getRaceDate() != null ? 
                    race.getRaceDate().format(DateTimeFormatter.ofPattern("dd MMMM yyyy")) : 
                    "N/A"
            ).append("\n");
            preview.append("Status: ").append(race.getStatus()).append("\n");
            preview.append("Total Participants: ").append(race.getTotalParticipants()).append("\n");
            preview.append("Finished: ").append(race.getFinishedParticipants()).append("\n\n");
            preview.append("═══════════════════════════════════════════════════\n\n");
            preview.append("Options Selected:\n");
            preview.append("  ✓ Include Checkpoint Details: ").append(includeCheckpointDetailsCheckBox.isSelected()).append("\n");
            preview.append("  ✓ Include Summary: ").append(includeSummaryCheckBox.isSelected()).append("\n\n");
            preview.append("Click 'Export to Excel' to generate the full report.\n");
            
            reportPreviewArea.setText(preview.toString());
            
            statusLabel.setText("✅ Preview generated");
            statusLabel.setStyle("-fx-text-fill: green;");
            
        } catch (Exception e) {
            showAlert(Alert.AlertType.ERROR, "Error", 
                "Failed to generate preview: " + e.getMessage());
            log.error("Preview generation error", e);
        }
    }

    @FXML
    private void handleExportExcel() {
        if (selectedRaceId == null) {
            showAlert(Alert.AlertType.WARNING, "No Race", "Please select a race first");
            return;
        }
        
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Save Race Report");
        fileChooser.setInitialFileName("race_report_" + LocalDate.now() + ".xlsx");
        fileChooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("Excel Files", "*.xlsx")
        );
        
        File file = fileChooser.showSaveDialog(exportExcelButton.getScene().getWindow());
        if (file != null) {
            try {
                statusLabel.setText("🔄 Exporting to Excel...");
                statusLabel.setStyle("-fx-text-fill: orange;");
                
                reportService.exportRaceResults(selectedRaceId, file);
                
                showAlert(Alert.AlertType.INFORMATION, "Success", 
                    "Race report exported successfully to:\n" + file.getAbsolutePath());
                
                statusLabel.setText("✅ Export completed");
                statusLabel.setStyle("-fx-text-fill: green;");
                
            } catch (Exception e) {
                showAlert(Alert.AlertType.ERROR, "Error", 
                    "Failed to export Excel: " + e.getMessage());
                log.error("Excel export error", e);
                
                statusLabel.setText("❌ Export failed");
                statusLabel.setStyle("-fx-text-fill: red;");
            }
        }
    }

    private void showAlert(Alert.AlertType type, String title, String content) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }
}
