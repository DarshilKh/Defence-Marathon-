package com.army.marathon.ui;

import com.army.marathon.model.SystemConfig;
import com.army.marathon.service.DeviceModeService;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.Stage;
import lombok.extern.slf4j.Slf4j;
import net.rgielen.fxweaver.core.FxmlView;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@FxmlView("SetupWizardView.fxml")
public class SetupWizardController {

    @FXML private TextField deviceIdField;
    @FXML private ComboBox<String> deviceRoleComboBox;
    @FXML private TextField checkpointNameField;
    @FXML private Label instructionLabel;
    @FXML private Button saveButton;
    @FXML private Button cancelButton;
    @FXML private Button testConnectionButton;  // ✅ TEST BUTTON

    private final DeviceModeService deviceModeService;
    private boolean isReconfiguration = false;

    public SetupWizardController(DeviceModeService deviceModeService) {
        this.deviceModeService = deviceModeService;
    }

    @FXML
    public void initialize() {
        log.info("🔧 Initializing Setup Wizard");
        
        // Populate device role options
        deviceRoleComboBox.setItems(FXCollections.observableArrayList(
            "MASTER",
            "SLAVE"
        ));
        
        // Set default device ID suggestions
        deviceIdField.setPromptText("e.g., MASTER, CHECKPOINT_1, CHECKPOINT_2");
        checkpointNameField.setPromptText("e.g., Start Line, 2KM Point, Finish Line");
        
        // Add listener for role selection to update instructions
        deviceRoleComboBox.valueProperty().addListener((obs, oldVal, newVal) -> {
            updateInstructions(newVal);
        });
        
        // Pre-fill if already configured (for reconfiguration)
        SystemConfig existing = deviceModeService.getSystemConfig();
        if (existing != null) {
            isReconfiguration = true;
            deviceIdField.setText(existing.getDeviceId());
            deviceRoleComboBox.setValue(existing.getDeviceRole().toString());
            
            if (existing.getCheckpointName() != null) {
                checkpointNameField.setText(existing.getCheckpointName());
            }
            
            // Update UI for reconfiguration
            saveButton.setText("Update Configuration");
            log.info("Setup Wizard opened in RECONFIGURATION mode");
        } else {
            // First time setup - default to MASTER
            deviceRoleComboBox.setValue("MASTER");
            updateInstructions("MASTER");
        }
    }

    private void updateInstructions(String role) {
        if ("MASTER".equals(role)) {
            instructionLabel.setText(
                "🎯 MASTER MODE:\n" +
                "• Main control system for race management\n" +
                "• Controls race start/stop\n" +
                "• Can set manual start times\n" +
                "• Imports data from checkpoint devices (USB)\n" +
                "• Generates final race results and reports\n" +
                "• Full access to all features\n\n" +
                "📍 Recommended Device ID: MASTER or CONTROL_CENTER"
            );
            
            if (deviceIdField.getText().isEmpty() || deviceIdField.getText().startsWith("CHECKPOINT")) {
                deviceIdField.setText("MASTER");
            }
            
            if (checkpointNameField.getText().isEmpty() || !checkpointNameField.getText().contains("Start")) {
                checkpointNameField.setText("Start Line / Control Center");
            }
            
        } else if ("SLAVE".equals(role)) {
            instructionLabel.setText(
                "📍 SLAVE MODE (Checkpoint Device):\n" +
                "• Autonomous data collection at checkpoints\n" +
                "• RFID readers start automatically\n" +
                "• No manual race control buttons\n" +
                "• Continuous tag scanning during race\n" +
                "• Exports collected data to USB\n" +
                "• Limited features (data collection only)\n\n" +
                "📍 Recommended Device ID: CHECKPOINT_1, CHECKPOINT_2KM, etc."
            );
            
            if (deviceIdField.getText().isEmpty() || deviceIdField.getText().equals("MASTER")) {
                deviceIdField.setText("CHECKPOINT_1");
            }
            
            if (checkpointNameField.getText().isEmpty() || checkpointNameField.getText().contains("Control")) {
                checkpointNameField.setText("2KM Checkpoint");
            }
        }
    }

    /**
     * ✅ TEST DATABASE CONNECTION
     */
    @FXML
    private void handleTestConnection() {
        log.info("🔍 Testing database connection...");
        
        // Disable button during test
        testConnectionButton.setDisable(true);
        testConnectionButton.setText("Testing...");
        
        // Run test in background thread
        new Thread(() -> {
            try {
                boolean connected = deviceModeService.testDatabaseConnection();
                
                Platform.runLater(() -> {
                    // Re-enable button
                    testConnectionButton.setDisable(false);
                    testConnectionButton.setText("🔍 Test Connection");
                    
                    if (connected) {
                        showInfo("Database Connection Test", 
                            "✅ SUCCESS!\n\n" +
                            "Database connection is working properly.\n" +
                            "You can proceed with the setup.");
                    } else {
                        showError("Database Connection Test", 
                            "❌ FAILED!\n\n" +
                            "Cannot connect to the database.\n\n" +
                            "Please check:\n" +
                            "• Database service is running\n" +
                            "• Connection settings in application.properties\n" +
                            "• Network connectivity");
                    }
                });
                
            } catch (Exception e) {
                Platform.runLater(() -> {
                    testConnectionButton.setDisable(false);
                    testConnectionButton.setText("🔍 Test Connection");
                    
                    showError("Database Connection Test", 
                        "❌ ERROR!\n\n" +
                        "Exception occurred while testing connection:\n\n" +
                        e.getMessage());
                });
                log.error("Database connection test error", e);
            }
        }).start();
    }

    @FXML
    private void handleSave() {
        if (!validateInputs()) {
            return;
        }
        
        try {
            String deviceId = deviceIdField.getText().trim().toUpperCase();
            String roleStr = deviceRoleComboBox.getValue();
            String checkpointName = checkpointNameField.getText().trim();
            
            SystemConfig.DeviceRole role = SystemConfig.DeviceRole.valueOf(roleStr);
            
            // Save configuration
            deviceModeService.saveConfiguration(deviceId, role, checkpointName);
            
            log.info("✅ Configuration saved: Device={}, Role={}, Checkpoint={}", 
                deviceId, role, checkpointName);
            
            // Format success message
            String successMessage = String.format(
                "Device ID: %s\n" +
                "Role: %s\n" +
                "Checkpoint: %s\n\n" +
                "%s",
                deviceId, 
                role.toString(), 
                checkpointName,
                (isReconfiguration ? 
                    "Configuration updated! Changes will take effect now." :
                    "Application will now restart to apply changes.")
            );
            
            // Show success message
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Configuration Saved");
            alert.setHeaderText("✅ System Configured Successfully!");
            alert.setContentText(successMessage);
            alert.showAndWait();
            
            // Close wizard
            closeWizard();
            
            // Restart if first-time setup
            if (!isReconfiguration) {
                restartApplication();
            }
            
        } catch (Exception e) {
            showError("Configuration Error", "Failed to save configuration: " + e.getMessage());
            log.error("Configuration save failed", e);
        }
    }

    @FXML
    private void handleCancel() {
        if (!isReconfiguration && !deviceModeService.isConfigured()) {
            // First time setup - show warning
            Alert confirmation = new Alert(Alert.AlertType.WARNING);
            confirmation.setTitle("Cancel Setup");
            confirmation.setHeaderText("⚠️ Exit Setup Wizard?");
            confirmation.setContentText(
                "System is not configured yet!\n\n" +
                "If you cancel now:\n" +
                "• Application will not function properly\n" +
                "• You need to configure before using\n" +
                "• Setup wizard will appear again on next launch\n\n" +
                "Are you sure you want to cancel?"
            );
            
            confirmation.showAndWait().ifPresent(response -> {
                if (response == ButtonType.OK) {
                    closeWizard();
                }
            });
        } else {
            // Reconfiguration - just close
            closeWizard();
        }
    }

    private boolean validateInputs() {
        // Device ID validation
        if (deviceIdField.getText().trim().isEmpty()) {
            showError("Validation Error", "❌ Device ID is required!\n\nPlease enter a unique identifier for this device.");
            deviceIdField.requestFocus();
            return false;
        }
        
        // Check for valid characters in device ID
        String deviceId = deviceIdField.getText().trim();
        if (!deviceId.matches("[A-Z0-9_]+")) {
            showError("Validation Error", 
                "❌ Invalid Device ID!\n\n" +
                "Device ID can only contain:\n" +
                "• Uppercase letters (A-Z)\n" +
                "• Numbers (0-9)\n" +
                "• Underscores (_)\n\n" +
                "Example: MASTER, CHECKPOINT_1, CP_2KM");
            deviceIdField.requestFocus();
            return false;
        }
        
        // Device Role validation
        if (deviceRoleComboBox.getValue() == null) {
            showError("Validation Error", "❌ Device Role is required!\n\nPlease select MASTER or SLAVE.");
            deviceRoleComboBox.requestFocus();
            return false;
        }
        
        // Checkpoint Name validation
        if (checkpointNameField.getText().trim().isEmpty()) {
            showError("Validation Error", 
                "❌ Checkpoint Name is required!\n\n" +
                "Please enter a descriptive name for this location.\n" +
                "Examples: Start Line, 2KM Point, Finish Line");
            checkpointNameField.requestFocus();
            return false;
        }
        
        return true;
    }

    private void closeWizard() {
        Stage stage = (Stage) saveButton.getScene().getWindow();
        if (stage != null) {
            stage.close();
            log.info("Setup Wizard closed");
        }
    }

    private void restartApplication() {
        log.info("Restarting application after initial setup...");
        
        // Show restart message
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Restart Required");
            alert.setHeaderText("Configuration Complete!");
            alert.setContentText("Application will now restart...");
            alert.show();
            
            // Delay for user to read message
            new Thread(() -> {
                try {
                    Thread.sleep(2000);
                    Platform.runLater(() -> {
                        // Close application
                        Platform.exit();
                        System.exit(0);
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();
        });
    }

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
    
    private void showInfo(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
}
