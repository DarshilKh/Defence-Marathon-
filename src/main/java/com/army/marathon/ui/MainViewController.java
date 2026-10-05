package com.army.marathon.ui;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.stage.Modality;
import javafx.stage.Stage;
import lombok.extern.slf4j.Slf4j;
import net.rgielen.fxweaver.core.FxControllerAndView;
import net.rgielen.fxweaver.core.FxWeaver;
import net.rgielen.fxweaver.core.FxmlView;
import org.springframework.stereotype.Component;

import com.army.marathon.service.DeviceModeService;

@Slf4j
@Component
@FxmlView("MainView.fxml")
public class MainViewController {

    @FXML private BorderPane mainContainer;
    @FXML private TabPane mainTabPane;
    @FXML private Label systemStatusLabel;
    @FXML private Label deviceModeLabel;
    @FXML private Button runSetupButton;

    // Tabs
    @FXML private Tab registrationTab;
    @FXML private Tab configurationTab;
    @FXML private Tab raceEntriesTab;
    @FXML private Tab liveTrackingTab;
    @FXML private Tab resultsTab;
    @FXML private Tab reportsTab;
    @FXML private Tab dataSyncTab;

    private final FxWeaver fxWeaver;
    private final DeviceModeService deviceModeService;

    private RegistrationController registrationController;
    private ConfigurationController configurationController;
    private LiveTrackingController liveTrackingController;
    private ResultsController resultsController;
    private RaceEntriesController raceEntriesController;
    private ReportsController reportsController;

    public MainViewController(FxWeaver fxWeaver, DeviceModeService deviceModeService) {
        this.fxWeaver = fxWeaver;
        this.deviceModeService = deviceModeService;
    }

    @FXML
public void initialize() {
    log.info("Initializing Main View Controller");

    try {
        registrationController = loadViewIntoTabSafely(registrationTab, "Registration", RegistrationController.class);
        configurationController = loadViewIntoTabSafely(configurationTab, "Configuration", ConfigurationController.class);
        liveTrackingController = loadViewIntoTabSafely(liveTrackingTab, "Live Tracking", LiveTrackingController.class);
        // Loaded after Live Tracking even though its tab sits before it: Live Tracking's
        // initialize() is what creates the default Race on a fresh database, and the
        // roster needs that race to exist.
        raceEntriesController = loadViewIntoTabSafely(raceEntriesTab, "Race Entries", RaceEntriesController.class);
        resultsController = loadViewIntoTabSafely(resultsTab, "Results", ResultsController.class);
        reportsController = loadViewIntoTabSafely(reportsTab, "Reports", ReportsController.class);

        // ✅ TEMPORARILY DISABLED - Database issue
        // loadViewIntoTabSafely(dataSyncTab, "Data Sync", DataSyncController.class);
        if (dataSyncTab != null) {
            dataSyncTab.setDisable(true);
            dataSyncTab.setText("💾 Data Sync (Coming Soon)");
        }

        updateSystemStatus();
        enableAllTabs();
        setupTabRefresh();

        // After "Calculate Results" succeeds, jump to the Results tab (its refresh runs on selection)
        if (liveTrackingController != null) {
            liveTrackingController.setOnResultsReady(() -> {
                if (mainTabPane != null && resultsTab != null) {
                    mainTabPane.getSelectionModel().select(resultsTab);
                }
            });
        }

        log.info("✅ Main View initialized successfully");

    } catch (Exception e) {
        log.error("❌ Error initializing Main View", e);
    }
}

    /**
     * ✅ Results is now a read-only view of whatever was last calculated on
     * Live Tracking — refresh it automatically whenever it's selected so the
     * user never has to remember to click "Refresh" by hand. Race Entries does
     * the same so newly registered participants show up in its table.
     */
    private void setupTabRefresh() {
        if (mainTabPane == null) {
            return;
        }
        mainTabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            if (newTab == registrationTab && registrationController != null) {
                registrationController.refreshReaderStatus();
            } else if (newTab == resultsTab && resultsController != null) {
                resultsController.refreshResults();
            } else if (newTab == raceEntriesTab && raceEntriesController != null) {
                raceEntriesController.refresh();
            } else if (newTab == configurationTab && configurationController != null) {
                // The fixed reader port follows the reader type chosen on Live Tracking
                configurationController.refresh();
            } else if (newTab == reportsTab && reportsController != null) {
                reportsController.refresh();
            }
        });
    }


    /**
     * Handle Setup button click - Open Setup Wizard
     */
    @FXML
    private void handleRunSetup() {
        try {
            log.info("🔧 Launching Setup Wizard from menu...");
            
            // Load Setup Wizard
            FxControllerAndView<SetupWizardController, Parent> setupWizard = 
                fxWeaver.load(SetupWizardController.class);
            
            Parent wizardRoot = setupWizard.getView()
                .orElseThrow(() -> new RuntimeException("Unable to load Setup Wizard"));
            
            // Create new stage for wizard
            Stage wizardStage = new Stage();
            wizardStage.setTitle("⚙️ System Setup Wizard");
            com.army.marathon.MarathonApplication.applyAppIcon(wizardStage);
            wizardStage.setScene(new Scene(wizardRoot, 600, 550));
            
            // Make it modal
            wizardStage.initModality(Modality.APPLICATION_MODAL);
            
            if (runSetupButton != null && runSetupButton.getScene() != null) {
                wizardStage.initOwner(runSetupButton.getScene().getWindow());
            }
            
            // Show wizard and wait
            wizardStage.showAndWait();
            
            // Refresh status after wizard closes
            updateSystemStatus();
            
            // Update window title if needed
            updateWindowTitle();
            
            log.info("✅ Setup Wizard closed, status refreshed");
            
        } catch (Exception e) {
            log.error("❌ Failed to launch Setup Wizard", e);
            showError("Error", "Failed to open Setup Wizard:\n\n" + e.getMessage());
        }
    }

    private <T> T loadViewIntoTab(Tab tab, String tabName, Class<T> controllerClass) {
        try {
            log.info("Loading view for tab: {}", tabName);
            FxControllerAndView<T, Node> controllerAndView = fxWeaver.load(controllerClass);
            tab.setContent(controllerAndView.getView().orElseThrow(
                () -> new RuntimeException("Unable to load view for " + tabName)
            ));
            log.info("✅ Loaded view for tab: {}", tabName);
            return controllerAndView.getController();
        } catch (Exception e) {
            log.error("❌ Failed to load view for tab: {}", tabName, e);
            throw e;
        }
    }

    private <T> T loadViewIntoTabSafely(Tab tab, String tabName, Class<T> controllerClass) {
        try {
            T controller = loadViewIntoTab(tab, tabName, controllerClass);
            // Enable tab after successful load
            if (tab != null) {
                tab.setDisable(false);
            }
            return controller;
        } catch (Exception e) {
            log.error("❌ Failed to load tab {}, showing placeholder", tabName);
            
            // Show placeholder instead of disabling
            javafx.scene.layout.VBox errorBox = new javafx.scene.layout.VBox(14);
            errorBox.setStyle("-fx-padding: 30; -fx-alignment: center; -fx-background-color: #eef2f6;");
            
            javafx.scene.control.Label iconLabel = new javafx.scene.control.Label("🚧");
            iconLabel.setStyle("-fx-font-size: 52px;");
            
            javafx.scene.control.Label errorLabel = new javafx.scene.control.Label(
                tabName + " - Coming Soon"
            );
            errorLabel.setStyle("-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: #1c3450;");
            
            javafx.scene.control.Label messageLabel = new javafx.scene.control.Label(
                "This feature is currently under development.\nPlease check back later."
            );
            messageLabel.setStyle("-fx-text-fill: #6b7680; -fx-font-size: 14px;");
            messageLabel.setWrapText(true);
            messageLabel.setMaxWidth(400);
            messageLabel.setAlignment(javafx.geometry.Pos.CENTER);
            
            errorBox.getChildren().addAll(iconLabel, errorLabel, messageLabel);
            
            if (tab != null) {
                tab.setContent(errorBox);
                tab.setDisable(false); // ✅ Keep tab enabled with placeholder
            }
            return null;
        }
    }

    private void enableAllTabs() {
        log.info("✅ Enabling all tabs");
        
        if (registrationTab != null) {
            registrationTab.setDisable(false);
        }
        
        if (configurationTab != null) {
            configurationTab.setDisable(false);
        }
        
        if (raceEntriesTab != null) {
            raceEntriesTab.setDisable(false);
        }
        
        if (liveTrackingTab != null) {
            liveTrackingTab.setDisable(false);
        }
        
        if (resultsTab != null) {
            resultsTab.setDisable(false);
        }

        if (reportsTab != null) {
            reportsTab.setDisable(false);
        }

        if (dataSyncTab != null) {
            dataSyncTab.setDisable(false);
        }
        
        // Auto-select first enabled tab
        if (mainTabPane != null && registrationTab != null) {
            mainTabPane.getSelectionModel().select(registrationTab);
        }
    }

    private void updateSystemStatus() {
        try {
            if (systemStatusLabel == null) {
                log.warn("systemStatusLabel is null");
                return;
            }
            
            if (deviceModeService.isConfigured()) {
                systemStatusLabel.setText("✅ System Configured");
                systemStatusLabel.setStyle("-fx-text-fill: #2ecc71; -fx-font-size: 13px; -fx-font-weight: bold;");
                
                if (deviceModeLabel != null) {
                    deviceModeLabel.setText(deviceModeService.getConfigurationSummary());
                    deviceModeLabel.setStyle("-fx-text-fill: #5dade2; -fx-font-size: 13px; -fx-font-weight: bold;");
                }
                
                // Once configured, Reconfigure isn't exposed during normal race-day operation
                if (runSetupButton != null) {
                    runSetupButton.setText("⚙️ Reconfigure");
                    runSetupButton.getStyleClass().setAll("btn-sm", "btn-primary");
                    runSetupButton.setVisible(false);
                    runSetupButton.setManaged(false);
                }
                
            } else {
                systemStatusLabel.setText("⚠️ System NOT Configured - Run Setup");
                systemStatusLabel.setStyle("-fx-text-fill: #f39c12; -fx-font-size: 13px; -fx-font-weight: bold;");
                
                if (deviceModeLabel != null) {
                    deviceModeLabel.setText("Device: NOT CONFIGURED");
                    deviceModeLabel.setStyle("-fx-text-fill: #e74c3c; -fx-font-size: 13px; -fx-font-weight: bold;");
                }
                
                // Not configured yet: Setup must stay reachable
                if (runSetupButton != null) {
                    runSetupButton.setText("⚙️ Setup");
                    runSetupButton.getStyleClass().setAll("btn-sm", "btn-orange");
                    runSetupButton.setVisible(true);
                    runSetupButton.setManaged(true);
                }
            }
        } catch (Exception e) {
            log.error("Error updating system status", e);
            if (systemStatusLabel != null) {
                systemStatusLabel.setText("❌ Error");
                systemStatusLabel.setStyle("-fx-text-fill: #e74c3c; -fx-font-size: 13px; -fx-font-weight: bold;");
            }
        }
    }
    
    /**
     * Update window title after configuration change
     */
    private void updateWindowTitle() {
        try {
            if (mainContainer != null && mainContainer.getScene() != null) {
                Stage stage = (Stage) mainContainer.getScene().getWindow();
                if (stage != null) {
                    String title = "Indian Defence Marathon - RFID Timing System";
                    if (deviceModeService.isConfigured()) {
                        title += " [" + deviceModeService.getConfigurationSummary() + "]";
                    } else {
                        title += " [NOT CONFIGURED]";
                    }
                    stage.setTitle(title);
                    log.info("Window title updated: {}", title);
                }
            }
        } catch (Exception e) {
            log.error("Failed to update window title", e);
        }
    }

    // Public method to refresh system status (called after configuration changes)
    public void refreshSystemStatus() {
        updateSystemStatus();
        updateWindowTitle();
    }
    
    private void showError(String title, String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }
}
