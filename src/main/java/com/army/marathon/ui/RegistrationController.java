package com.army.marathon.ui;

import com.army.marathon.model.Participant;
import com.army.marathon.service.MultiReaderService;
import com.army.marathon.service.ParticipantService;
import com.army.marathon.service.UhfReaderService;
import javafx.application.Platform;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@FxmlView("RegistrationView.fxml")
public class RegistrationController {

    // RFID Fields
    @FXML private TextField rfidTagField;
    @FXML private Button scanButton;
    @FXML private Label readerStatusLabel;
    @FXML private TextField searchField;

    // Personal Details Fields
    @FXML private TextField nameField;
    @FXML private TextField serviceNumberField;
    @FXML private ComboBox<String> unitComboBox;
    @FXML private ComboBox<String> rankComboBox;
    @FXML private TextField serialNoField;
    @FXML private DatePicker dobPicker;
    @FXML private TextField ageField;
    @FXML private TextField ageGroupField;
    // Fallback TextFields
    @FXML private TextField unitField;

    // Action Buttons
    @FXML private Button saveButton;
    @FXML private Button editButton;
    @FXML private Button deleteButton;
    @FXML private Button clearButton;
    @FXML private Button refreshButton;
    @FXML private Button downloadTemplateButton;
    @FXML private Button uploadExcelButton;

    // Table and Columns
    @FXML private TableView<Participant> participantTable;
    @FXML private TableColumn<Participant, String> rfidTagColumn;
    @FXML private TableColumn<Participant, String> nameColumn;
    @FXML private TableColumn<Participant, String> serviceNumberColumn;
    @FXML private TableColumn<Participant, String> rankColumn;
    @FXML private TableColumn<Participant, String> unitColumn;
    @FXML private TableColumn<Participant, String> serialNoColumn;
    @FXML private TableColumn<Participant, LocalDate> dobColumn;
    @FXML private TableColumn<Participant, Integer> ageColumn;
    @FXML private TableColumn<Participant, String> ageGroupColumn;

    // Status Labels
    @FXML private Label totalCountLabel;

    private final ParticipantService participantService;
    private final UhfReaderService readerService;
    private final ObservableList<Participant> participantList = FXCollections.observableArrayList();

    private Participant selectedParticipant;
    private boolean isEditMode = false;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    private final MultiReaderService multiReaderService;

    public RegistrationController(ParticipantService participantService,
                                  UhfReaderService readerService,
                                  MultiReaderService multiReaderService) {
        this.participantService = participantService;
        this.readerService = readerService;
        this.multiReaderService = multiReaderService;
    }

    @FXML
    public void initialize() {
        log.info("🔧 Initializing Registration Controller");

        setupTable();
        setupDropdowns();
        setupDobListener();
        setupSearchListener();
        loadParticipants();
        updateReaderStatus();

        // Table selection listener
        participantTable.getSelectionModel().selectedItemProperty()
                .addListener((obs, oldVal, newVal) -> {
                    if (newVal != null) {
                        populateFields(newVal);
                        selectedParticipant = newVal;
                        isEditMode = true;
                        editButton.setDisable(false);
                        deleteButton.setDisable(false);
                        saveButton.setText("Update");
                    } else {
                        editButton.setDisable(true);
                        deleteButton.setDisable(true);
                    }
                });

        log.info("✅ Registration Controller initialized");
    }

    private void setupTable() {
        rfidTagColumn.setCellValueFactory(new PropertyValueFactory<>("rfidTag"));
        nameColumn.setCellValueFactory(new PropertyValueFactory<>("name"));
        serviceNumberColumn.setCellValueFactory(new PropertyValueFactory<>("serviceNumber"));
        rankColumn.setCellValueFactory(new PropertyValueFactory<>("rank"));
        unitColumn.setCellValueFactory(new PropertyValueFactory<>("unit"));
        serialNoColumn.setCellValueFactory(new PropertyValueFactory<>("serialNo"));
        dobColumn.setCellValueFactory(new PropertyValueFactory<>("dateOfBirth"));
        ageColumn.setCellValueFactory(new PropertyValueFactory<>("age"));
        ageGroupColumn.setCellValueFactory(new PropertyValueFactory<>("ageGroup"));

        participantTable.setItems(participantList);
    }

    private void setupDropdowns() {
        if (unitComboBox != null) {
            unitComboBox.setItems(FXCollections.observableArrayList(
                    "Mrc",
                    "1 Mahar", "2 Mahar", "3 Mahar", "4 Mahar", "5 Mahar",
                    "6 Mahar", "7 Mahar", "8 Mahar", "9 Mahar", "10 Mahar",
                    "11 Mahar", "12 Mahar", "13 Mahar", "14 Mahar", "15 Mahar",
                    "17 Mahar", "18 Mahar", "19 Mahar", "20 Mahar", "21 Mahar", "22 Mahar"
            ));
            unitComboBox.setEditable(false);
        }

        if (rankComboBox != null) {
            rankComboBox.setItems(FXCollections.observableArrayList(
                    "AGV", "sep", "Nk", "Hav", "Nb sub", "Sub", "Sub Maj",
                    "Lt", "Capt", "Maj", "Lt Col", "Col", "Brig", "Maj Gen", "Lt Gen", "Gen"
            ));
            rankComboBox.setEditable(false);
        }
    }

    private void setupDobListener() {
        if (dobPicker != null) {
            dobPicker.valueProperty().addListener((obs, oldVal, newVal) -> {
                if (newVal != null) {
                    Participant temp = new Participant();
                    temp.setDateOfBirth(newVal);
                    ageField.setText(String.valueOf(temp.getAge()));
                    ageGroupField.setText(temp.getAgeGroup());
                } else {
                    ageField.clear();
                    ageGroupField.clear();
                }
            });
        }
    }

    private void setupSearchListener() {
        if (searchField != null) {
            searchField.textProperty().addListener((obs, oldVal, newVal) -> {
                filterParticipants(newVal);
            });
        }
    }

    private void filterParticipants(String searchText) {
        if (searchText == null || searchText.trim().isEmpty()) {
            loadParticipants();
            return;
        }

        String search = searchText.toLowerCase();
        List<Participant> allParticipants = participantService.getAllParticipants();
        List<Participant> filtered = allParticipants.stream()
                .filter(p ->
                        (p.getName() != null && p.getName().toLowerCase().contains(search)) ||
                                (p.getServiceNumber() != null && p.getServiceNumber().toLowerCase().contains(search)) ||
                                (p.getUnit() != null && p.getUnit().toLowerCase().contains(search)) ||
                                (p.getRfidTag() != null && p.getRfidTag().toLowerCase().contains(search)) ||
                                (p.getSerialNo() != null && p.getSerialNo().toLowerCase().contains(search))  // ✅ ADDED
                )
                .toList();

        participantList.clear();
        participantList.addAll(filtered);
        updateStatistics();
    }


    /**
     * ✅ SCAN BUTTON: Auto-loads existing participant or creates new
     * ✅ FEATURE: If tag already registered, all details populate automatically
     */
    @FXML
    private void handleScan() {
        scanButton.setDisable(true);
        readerStatusLabel.setText("🔄 Scanning... (10s timeout)");
        readerStatusLabel.setStyle("-fx-text-fill: orange; -fx-font-weight: bold;");

        // Auto re-enable after timeout
        scheduler.schedule(() -> {
            Platform.runLater(() -> {
                if (scanButton.isDisabled()) {
                    scanButton.setDisable(false);
                    readerStatusLabel.setText("⏱️ Scan Timeout - Click to retry");
                    readerStatusLabel.setStyle("-fx-text-fill: #e67e22; -fx-font-weight: bold;");
                    log.warn("⏱️ Scan timeout - button re-enabled");
                }
            });
        }, 10, TimeUnit.SECONDS);

        readerService.requestSingleScan(tagId -> {
            Platform.runLater(() -> {
                rfidTagField.setText(tagId);
                readerStatusLabel.setText("✅ Tag Scanned: " + tagId);
                readerStatusLabel.setStyle("-fx-text-fill: green; -fx-font-weight: bold;");
                scanButton.setDisable(false);

                // ✅✅✅ CHECK IF TAG ALREADY REGISTERED IN DATABASE
                Participant existing = participantService.getByRfidTag(tagId);
                if (existing != null) {
                    // ✅✅✅ AUTO-LOAD ALL DETAILS FOR EDITING
                    log.info("🔄 Tag already registered. Loading details: {}", existing.getName());

                    populateFields(existing);
                    selectedParticipant = existing;
                    isEditMode = true;
                    saveButton.setText("Update");
                    editButton.setDisable(false);
                    deleteButton.setDisable(false);
                    rfidTagField.setDisable(true); // Prevent RFID change

                    // Show informative alert
                    Alert alert = new Alert(Alert.AlertType.INFORMATION);
                    alert.setTitle("Tag Already Registered");
                    alert.setHeaderText("⚠️ This RFID Tag is Already Registered");
                    alert.setContentText(String.format(
                            "Tag ID: %s\n" +
                                    "Name: %s\n" +
                                    "Service No: %s\n" +
                                    "Unit: %s\n\n" +
                                    "✅ All participant details have been loaded into the form.\n\n" +
                                    "You can now:\n" +
                                    "• Edit any field and click 'Update' to save changes\n" +
                                    "• Click 'Delete' to remove this participant\n" +
                                    "• Click 'Clear' to start fresh registration",
                            existing.getRfidTag(),
                            existing.getName() != null ? existing.getName() : "N/A",
                            existing.getServiceNumber() != null ? existing.getServiceNumber() : "N/A",
                            existing.getUnit() != null ? existing.getUnit() : "N/A"
                    ));
                    alert.showAndWait();
                } else {
                    // ✅✅✅ NEW TAG - READY FOR REGISTRATION
                    log.info("✨ New tag scanned. Ready for registration: {}", tagId);

                    clearFields();
                    rfidTagField.setText(tagId);
                    rfidTagField.setDisable(true); // Lock RFID field
                    isEditMode = false;
                    saveButton.setText("Save");
                    editButton.setDisable(true);
                    deleteButton.setDisable(true);

                    // Focus on name field for quick data entry
                    nameField.requestFocus();

                    // Show success message
                    readerStatusLabel.setText("✨ New Tag - Fill details and click Save");
                    readerStatusLabel.setStyle("-fx-text-fill: #2ecc71; -fx-font-weight: bold;");
                }
            });
        });
    }

    @FXML
    private void handleSave() {
        try {
            // ✅ Validate mandatory fields (RFID Tag, Tag No)
            if (!validateFields()) return;

            if (isEditMode && selectedParticipant != null) {
                // ✅ UPDATE EXISTING PARTICIPANT
                updateParticipantFromFields(selectedParticipant);
                participantService.updateParticipant(selectedParticipant);

                showAlert(Alert.AlertType.INFORMATION, "Success",
                        "✅ Participant updated successfully!");
                log.info("✅ Updated participant: {} (Tag: {})",
                        selectedParticipant.getName(), selectedParticipant.getRfidTag());
            } else {
                // ✅ SAVE NEW PARTICIPANT
                Participant participant = new Participant();
                updateParticipantFromFields(participant);
                participantService.saveParticipant(participant);

                showAlert(Alert.AlertType.INFORMATION, "Success",
                        "✅ Participant registered successfully!");
                log.info("✅ Registered new participant: {} (Tag: {})",
                        participant.getName(), participant.getRfidTag());
            }

            clearFields();
            loadParticipants();
            isEditMode = false;
            saveButton.setText("Save");
            rfidTagField.setDisable(false);

        } catch (Exception e) {
            showAlert(Alert.AlertType.ERROR, "Error",
                    "❌ Failed to save participant: " + e.getMessage());
            log.error("❌ Save error", e);
        }
    }

    private void updateParticipantFromFields(Participant p) {
        String rfidTag = rfidTagField.getText();
        p.setRfidTag(rfidTag != null && !rfidTag.trim().isEmpty() ? rfidTag.trim() : null);

        String name = getFieldValue(nameField);
        p.setName(name != null && !name.isEmpty() ? name : null);

        String serviceNumber = getFieldValue(serviceNumberField);
        p.setServiceNumber(serviceNumber != null && !serviceNumber.isEmpty() ? serviceNumber : null);

        p.setUnit(getComboBoxOrTextFieldValue(unitComboBox, unitField));
        p.setRank(rankComboBox != null ? rankComboBox.getValue() : null);
        p.setSerialNo(getFieldValue(serialNoField));
        p.setDateOfBirth(dobPicker != null ? dobPicker.getValue() : null);

        log.debug("📝 Participant data: RFID={}, Name={}, Service={}",
                p.getRfidTag(), p.getName(), p.getServiceNumber());
    }

    @FXML
    private void handleEdit() {
        if (selectedParticipant == null) {
            showAlert(Alert.AlertType.WARNING, "No Selection",
                    "Please select a participant to edit");
            return;
        }

        isEditMode = true;
        saveButton.setText("Update");
        rfidTagField.setDisable(true);
        nameField.requestFocus();
    }

    @FXML
    private void handleDelete() {
        if (selectedParticipant == null) {
            showAlert(Alert.AlertType.WARNING, "No Selection",
                    "Please select a participant to delete");
            return;
        }

        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.setTitle("Confirm Delete");
        confirmation.setHeaderText("Delete Participant");
        long[] history = participantService.countParticipantHistory(selectedParticipant.getId());
        confirmation.setContentText(String.format(
                "Are you sure you want to delete:\n\n" +
                        "Tag: %s\n" +
                        "Name: %s\n" +
                        "Service No: %s\n\n" +
                        "This permanently deletes the participant and also removes their %d tag readings " +
                        "and %d race results from all past reports. It cannot be undone.",
                selectedParticipant.getRfidTag(),
                selectedParticipant.getName() != null ? selectedParticipant.getName() : "N/A",
                selectedParticipant.getServiceNumber() != null ? selectedParticipant.getServiceNumber() : "N/A",
                history[0], history[1]
        ));

        Optional<ButtonType> result = confirmation.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            try {
                participantService.deleteParticipant(selectedParticipant.getId());
                showAlert(Alert.AlertType.INFORMATION, "Success",
                        "✅ Participant deleted successfully!");
                log.info("🗑️ Deleted participant: {} (Tag: {})",
                        selectedParticipant.getName(), selectedParticipant.getRfidTag());
                clearFields();
                loadParticipants();
            } catch (Exception e) {
                showAlert(Alert.AlertType.ERROR, "Error",
                        "❌ Failed to delete participant: " + e.getMessage());
                log.error("❌ Delete error", e);
            }
        }
    }

    @FXML
    private void handleClear() {
        clearFields();
        selectedParticipant = null;
        isEditMode = false;
        saveButton.setText("Save");
        participantTable.getSelectionModel().clearSelection();
        editButton.setDisable(true);
        deleteButton.setDisable(true);
        rfidTagField.setDisable(false);
        log.debug("🧹 Form cleared");
    }

    @FXML
    private void handleRefresh() {
        log.info("🔃 Refreshing Registration page...");

        clearFields();

        if (searchField != null) {
            searchField.clear();
        }

        selectedParticipant = null;
        isEditMode = false;
        saveButton.setText("Save");
        participantTable.getSelectionModel().clearSelection();
        editButton.setDisable(true);
        deleteButton.setDisable(true);
        rfidTagField.setDisable(false);

        loadParticipants();
        updateReaderStatus();

        readerStatusLabel.setText("✅ Page Refreshed");
        readerStatusLabel.setStyle("-fx-text-fill: green; -fx-font-weight: bold;");

        scheduler.schedule(() -> {
            Platform.runLater(() -> updateReaderStatus());
        }, 2, TimeUnit.SECONDS);

        log.info("✅ Registration page refreshed successfully");
    }

    @FXML
    private void handleDownloadTemplate() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Save Participant Template");
        fileChooser.setInitialFileName("participants_template.xlsx");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Excel Files", "*.xlsx")
        );

        File file = fileChooser.showSaveDialog(downloadTemplateButton.getScene().getWindow());
        if (file == null) {
            return;
        }
        try {
            participantService.writeImportTemplate(file);
            showAlert(Alert.AlertType.INFORMATION, "Template Saved",
                    "✅ Template saved.\n\nFile: " + file.getName() +
                            "\n\nFill in one participant per row, then use Upload Excel." +
                            "\n\"RFID Tag*\" and \"Tag No*\" are mandatory; every other column is optional.");
            log.info("📄 Participant template saved to: {}", file.getAbsolutePath());
        } catch (Exception e) {
            showAlert(Alert.AlertType.ERROR, "Error", "❌ Failed to save template: " + e.getMessage());
            log.error("❌ Template export error", e);
        }
    }

    @FXML
    private void handleUploadExcel() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select Excel File");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Excel Files", "*.xlsx", "*.xls")
        );

        File file = fileChooser.showOpenDialog(uploadExcelButton.getScene().getWindow());
        if (file != null) {
            try {
                List<String> errors = participantService.importFromExcel(file);

                if (errors.isEmpty()) {
                    showAlert(Alert.AlertType.INFORMATION, "Success",
                            "✅ All participants imported successfully!");
                } else {
                    String errorSummary = String.join("\n",
                            errors.subList(0, Math.min(5, errors.size())));
                    if (errors.size() > 5) {
                        errorSummary += "\n... and " + (errors.size() - 5) + " more errors";
                    }
                    showAlert(Alert.AlertType.WARNING, "Import Completed with Errors",
                            "Some rows had errors:\n\n" + errorSummary);
                }

                loadParticipants();
                log.info("📥 Excel import completed. Total errors: {}", errors.size());

            } catch (Exception e) {
                showAlert(Alert.AlertType.ERROR, "Error",
                        "❌ Failed to import Excel: " + e.getMessage());
                log.error("❌ Excel import error", e);
            }
        }
    }


    /**
     * ✅ SEARCH BUTTON HANDLER
     */
    @FXML
    private void handleSearchButton() {
        String searchText = searchField.getText();

        if (searchText == null || searchText.trim().isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "Search Empty",
                    "Please enter RFID Tag or Tag No");
            searchField.setStyle("-fx-border-color: red; -fx-border-width: 2; -fx-border-radius: 5;");
            return;
        }

        searchField.setStyle("-fx-border-color: #3498db; -fx-border-width: 2; -fx-border-radius: 5;");

        Participant participant = participantService.searchByRfidOrSerial(searchText.trim());

        if (participant != null) {
            participantTable.getSelectionModel().select(participant);
            participantTable.scrollTo(participant);
            fillFormFromParticipant(participant);
            searchField.setStyle("-fx-border-color: #27ae60; -fx-border-width: 2; -fx-border-radius: 5;");
            showAlert(Alert.AlertType.INFORMATION, "Found",
                    "Participant: " + (participant.getName() != null ? participant.getName() : "N/A"));
        } else {
            searchField.setStyle("-fx-border-color: #e74c3c; -fx-border-width: 2; -fx-border-radius: 5;");
            showAlert(Alert.AlertType.WARNING, "Not Found",
                    "No participant found: " + searchText);
        }
    }

    /**
     * ✅ FILL FORM FROM PARTICIPANT
     */
    private void fillFormFromParticipant(Participant participant) {
        if (participant == null) return;

        rfidTagField.setText(participant.getRfidTag());
        nameField.setText(participant.getName() != null ? participant.getName() : "");
        serviceNumberField.setText(participant.getServiceNumber() != null ? participant.getServiceNumber() : "");
        unitComboBox.setValue(participant.getUnit());
        if (rankComboBox != null) rankComboBox.setValue(participant.getRank());
        serialNoField.setText(participant.getSerialNo() != null ? participant.getSerialNo() : "");

        dobPicker.setValue(participant.getDateOfBirth());
        ageField.setText(participant.getAge() != null ? String.valueOf(participant.getAge()) : "");
        ageGroupField.setText(participant.getAgeGroup() != null ? participant.getAgeGroup() : "");

        editButton.setDisable(false);
        deleteButton.setDisable(false);
    }

    /**
     * ✅ SHOW ALERT
     */




    private void loadParticipants() {
        try {
            List<Participant> participants = participantService.getAllParticipants();
            participantList.clear();
            participantList.addAll(participants);
            updateStatistics();
            log.info("✅ Loaded {} participants", participants.size());
        } catch (Exception e) {
            log.error("❌ Error loading participants", e);
        }
    }

    private void updateStatistics() {
        if (totalCountLabel != null) {
            totalCountLabel.setText(String.valueOf(participantList.size()));
        }
    }

    private void populateFields(Participant p) {
        rfidTagField.setText(p.getRfidTag() != null ? p.getRfidTag() : "");
        nameField.setText(p.getName() != null ? p.getName() : "");
        setFieldValue(serviceNumberField, p.getServiceNumber());
        setComboBoxOrTextFieldValue(unitComboBox, unitField, p.getUnit());
        if (rankComboBox != null) rankComboBox.setValue(p.getRank());
        setFieldValue(serialNoField, p.getSerialNo());
        if (dobPicker != null) dobPicker.setValue(p.getDateOfBirth());
        ageField.setText(p.getAge() != null ? p.getAge().toString() : "");
        ageGroupField.setText(p.getAgeGroup() != null ? p.getAgeGroup() : "");
    }

    private void clearFields() {
        rfidTagField.clear();
        nameField.clear();
        clearField(serviceNumberField);
        clearComboBoxOrTextField(unitComboBox, unitField);
        if (rankComboBox != null) rankComboBox.setValue(null);
        clearField(serialNoField);
        if (dobPicker != null) dobPicker.setValue(null);
        ageField.clear();
        ageGroupField.clear();
    }

    private boolean validateFields() {
        String rfidTag = rfidTagField.getText();
        if (rfidTag == null || rfidTag.trim().isEmpty()) {
            showAlert(Alert.AlertType.ERROR, "Validation Error",
                    "❌ RFID Tag is required!\n\nPlease scan a tag or enter manually.");
            rfidTagField.requestFocus();
            return false;
        }

        String tagNo = serialNoField != null ? serialNoField.getText() : null;
        if (tagNo == null || tagNo.trim().isEmpty()) {
            showAlert(Alert.AlertType.ERROR, "Validation Error",
                    "❌ Tag No is required!\n\nPlease enter the participant's Tag No.");
            serialNoField.requestFocus();
            return false;
        }
        return true;
    }

    /** Called when the Registration tab is selected, so the header isn't stuck on its startup state. */
    public void refreshReaderStatus() {
        if (scanButton == null || !scanButton.isDisabled()) {
            updateReaderStatus();
        }
    }

    private void updateReaderStatus() {
        // Green as soon as any one reader is up: the local reader, an armed one, or one that tested OK.
        if (readerService.isConnected() || multiReaderService.isAnyReaderConnected()) {
            readerStatusLabel.setText("🟢 Reader Connected");
            readerStatusLabel.setStyle("-fx-text-fill: green; -fx-font-weight: bold;");
        } else {
            readerStatusLabel.setText("🔴 Reader Disconnected");
            readerStatusLabel.setStyle("-fx-text-fill: red; -fx-font-weight: bold;");
        }
    }

    // Utility methods
    private String getComboBoxOrTextFieldValue(ComboBox<String> combo, TextField text) {
        if (combo != null) {
            String value = combo.getValue();
            return (value != null && !value.trim().isEmpty()) ? value.trim() : null;
        }
        if (text != null) {
            String value = text.getText();
            return (value != null && !value.trim().isEmpty()) ? value.trim() : null;
        }
        return null;
    }

    private void setComboBoxOrTextFieldValue(ComboBox<String> combo, TextField text, String value) {
        if (combo != null) combo.setValue(value);
        else if (text != null) text.setText(value != null ? value : "");
    }

    private void clearComboBoxOrTextField(ComboBox<String> combo, TextField text) {
        if (combo != null) combo.setValue(null);
        else if (text != null) text.clear();
    }

    private String getFieldValue(TextField field) {
        if (field == null || field.getText() == null) return null;
        String value = field.getText().trim();
        return value.isEmpty() ? null : value;
    }

    private void setFieldValue(TextField field, String value) {
        if (field != null) {
            field.setText(value != null ? value : "");
        }
    }

    private void clearField(TextField field) {
        if (field != null) field.clear();
    }

    private void showAlert(Alert.AlertType type, String title, String content) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }
}