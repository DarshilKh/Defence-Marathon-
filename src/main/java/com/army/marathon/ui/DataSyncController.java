package com.army.marathon.ui;

import com.army.marathon.model.CheckpointReading;
import com.army.marathon.model.SystemConfig;
import com.army.marathon.service.CheckpointDataSyncService;
import com.army.marathon.service.DeviceModeService;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import lombok.extern.slf4j.Slf4j;
import net.rgielen.fxweaver.core.FxmlView;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@Profile("!test")  // ✅ CRITICAL FIX: Don't load in tests!
@FxmlView("DataSyncView.fxml")
public class DataSyncController {

    @FXML private TextField usbPathField;
    @FXML private Button browseButton;
    @FXML private Button exportButton;
    @FXML private Button importButton;
    @FXML private Label pendingCountLabel;
    @FXML private Label deviceIdLabel;
    @FXML private Label deviceRoleLabel;
    @FXML private Label masterDeviceLabel;
    @FXML private Label statusLabel;
    @FXML private ProgressBar progressBar;
    
    @FXML private TableView<CheckpointReading> pendingTable;
    @FXML private TableColumn<CheckpointReading, String> rfidTagColumn;
    @FXML private TableColumn<CheckpointReading, String> checkpointColumn;
    @FXML private TableColumn<CheckpointReading, LocalDateTime> timeColumn;
    
    @FXML private TextArea logArea;
    
    private final CheckpointDataSyncService syncService;
    private final DeviceModeService deviceModeService;
    private final ObservableList<CheckpointReading> pendingData = FXCollections.observableArrayList();
    private ScheduledExecutorService scheduler;
    
    // ✅ JavaFX availability check
    private volatile boolean javaFxAvailable = false;

    public DataSyncController(
        CheckpointDataSyncService syncService,
        DeviceModeService deviceModeService
    ) {
        this.syncService = syncService;
        this.deviceModeService = deviceModeService;
    }

    @PostConstruct
    @FXML
    public void initialize() {
        log.info("🔧 Initializing Data Sync Controller");
        
        try {
            // ✅ Check if JavaFX is available
            javaFxAvailable = checkJavaFxAvailability();
            
            if (!javaFxAvailable) {
                log.warn("⚠️ JavaFX not available - running in headless mode");
                return;
            }
            
            setupTable();
            loadDeviceInfo();
            loadPendingData();
            startAutoRefresh();
            
            // Set default USB path
            if (usbPathField != null) {
                usbPathField.setText("E:/");
            }
            
            // Hide import button if in SLAVE mode (only export)
            String deviceRole = getDeviceRoleString();
            if ("SLAVE".equalsIgnoreCase(deviceRole)) {
                if (importButton != null) {
                    importButton.setVisible(false);
                    importButton.setManaged(false);
                }
                addLog("📍 SLAVE MODE: Only export is available");
            } else {
                addLog("📍 MASTER MODE: Import and export available");
            }
            
            log.info("✅ Data Sync Controller initialized");
            
        } catch (Exception e) {
            log.error("❌ Failed to initialize Data Sync Controller", e);
            addLog("❌ Initialization error: " + e.getMessage());
        }
    }

    /**
     * ✅ Check if JavaFX Platform is available
     */
    private boolean checkJavaFxAvailability() {
        try {
            // Try to check if we're on FX thread
            Platform.isFxApplicationThread();
            return true;
        } catch (IllegalStateException e) {
            // JavaFX not initialized
            return false;
        } catch (Exception e) {
            log.error("Error checking JavaFX availability", e);
            return false;
        }
    }

    /**
     * ✅ Setup table with NULL checks for FXML columns
     */
    private void setupTable() {
        try {
            if (pendingTable == null) {
                log.warn("⚠️ pendingTable is null - FXML not loaded properly");
                return;
            }
            
            // ✅ Setup columns only if they exist
            if (rfidTagColumn != null) {
                rfidTagColumn.setCellValueFactory(new PropertyValueFactory<>("rfidTag"));
            } else {
                log.warn("⚠️ rfidTagColumn is null");
            }
            
            if (checkpointColumn != null) {
                checkpointColumn.setCellValueFactory(new PropertyValueFactory<>("checkpointName"));
            } else {
                log.warn("⚠️ checkpointColumn is null");
            }
            
            if (timeColumn != null) {
                timeColumn.setCellValueFactory(new PropertyValueFactory<>("readTime"));
                timeColumn.setCellFactory(column -> new TableCell<CheckpointReading, LocalDateTime>() {
                    private DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd-MMM HH:mm:ss");
                    
                    @Override
                    protected void updateItem(LocalDateTime item, boolean empty) {
                        super.updateItem(item, empty);
                        if (empty || item == null) {
                            setText(null);
                        } else {
                            setText(formatter.format(item));
                        }
                    }
                });
            } else {
                log.warn("⚠️ timeColumn is null");
            }
            
            pendingTable.setItems(pendingData);
            log.info("✅ Table setup complete");
            
        } catch (Exception e) {
            log.error("❌ Error setting up table", e);
        }
    }

    /**
     * ✅ Load device info with NULL safety
     */
    private void loadDeviceInfo() {
        try {
            // Device ID with NULL check
            String deviceId = deviceModeService.getDeviceId();
            if (deviceIdLabel != null) {
                if (deviceId != null && !deviceId.trim().isEmpty()) {
                    deviceIdLabel.setText(deviceId);
                } else {
                    deviceIdLabel.setText("Not Set");
                }
            }
            
            // Device Role with NULL check
            String deviceRole = getDeviceRoleString();
            if (deviceRoleLabel != null) {
                deviceRoleLabel.setText(deviceRole);
                log.info("Device Role: {}", deviceRole);
            }
            
        } catch (Exception e) {
            log.error("❌ Error loading device info", e);
            if (deviceRoleLabel != null) {
                deviceRoleLabel.setText("ERROR");
            }
            if (deviceIdLabel != null) {
                deviceIdLabel.setText("ERROR");
            }
            addLog("❌ Failed to load device info: " + e.getMessage());
        }
    }

    /**
     * ✅ Get device role as String with NULL safety
     */
    private String getDeviceRoleString() {
        try {
            Object role = deviceModeService.getDeviceRole();
            if (role == null) {
                return "NOT_CONFIGURED";
            }
            
            // Handle both SystemConfig.DeviceRole enum and String
            if (role instanceof SystemConfig.DeviceRole) {
                return ((SystemConfig.DeviceRole) role).name();
            } else if (role instanceof String) {
                return (String) role;
            } else {
                return role.toString();
            }
        } catch (Exception e) {
            log.error("Error getting device role", e);
            return "ERROR";
        }
    }

    private void loadPendingData() {
        try {
            List<CheckpointReading> pending = syncService.getPendingExports();
            pendingData.clear();
            pendingData.addAll(pending);
            
            if (pendingCountLabel != null) {
                pendingCountLabel.setText(String.valueOf(pending.size()));
            }
            
            if (statusLabel != null) {
                if (pending.isEmpty()) {
                    statusLabel.setText("✅ No pending data");
                    statusLabel.setStyle("-fx-text-fill: green;");
                } else {
                    statusLabel.setText("⚠️ " + pending.size() + " records pending");
                    statusLabel.setStyle("-fx-text-fill: orange;");
                }
            }
            
        } catch (Exception e) {
            log.error("Failed to load pending data", e);
            addLog("❌ Error loading pending data: " + e.getMessage());
        }
    }

    @FXML
    private void handleBrowse() {
        try {
            DirectoryChooser dirChooser = new DirectoryChooser();
            dirChooser.setTitle("Select USB Drive");
            
            File initialDir = new File("E:/");
            if (initialDir.exists()) {
                dirChooser.setInitialDirectory(initialDir);
            }
            
            File selectedDir = dirChooser.showDialog(browseButton.getScene().getWindow());
            if (selectedDir != null) {
                usbPathField.setText(selectedDir.getAbsolutePath());
                addLog("📁 Selected path: " + selectedDir.getAbsolutePath());
            }
        } catch (Exception e) {
            log.error("Browse error", e);
            addLog("❌ Browse error: " + e.getMessage());
        }
    }

    @FXML
    private void handleExport() {
        String usbPath = usbPathField.getText().trim();
        
        if (usbPath.isEmpty()) {
            showError("No USB Path", "Please select USB drive location");
            return;
        }
        
        File usbDir = new File(usbPath);
        if (!usbDir.exists() || !usbDir.isDirectory()) {
            showError("Invalid Path", "USB path does not exist or is not a directory");
            return;
        }
        
        exportButton.setDisable(true);
        if (progressBar != null) progressBar.setVisible(true);
        if (statusLabel != null) {
            statusLabel.setText("🔄 Exporting data...");
            statusLabel.setStyle("-fx-text-fill: orange;");
        }
        
        // Run export in background thread
        new Thread(() -> {
            try {
                File exportFile = syncService.exportToUSB(usbPath);
                
                Platform.runLater(() -> {
                    if (progressBar != null) progressBar.setVisible(false);
                    exportButton.setDisable(false);
                    
                    if (statusLabel != null) {
                        statusLabel.setText("✅ Export successful");
                        statusLabel.setStyle("-fx-text-fill: green;");
                    }
                    
                    addLog("✅ Exported to: " + exportFile.getName());
                    
                    showInfo("Export Successful", 
                        "Data exported successfully!\n\n" +
                        "File: " + exportFile.getName() + "\n" +
                        "Location: " + exportFile.getAbsolutePath());
                    
                    loadPendingData();
                });
                
            } catch (Exception e) {
                Platform.runLater(() -> {
                    if (progressBar != null) progressBar.setVisible(false);
                    exportButton.setDisable(false);
                    
                    if (statusLabel != null) {
                        statusLabel.setText("❌ Export failed");
                        statusLabel.setStyle("-fx-text-fill: red;");
                    }
                    
                    addLog("❌ Export failed: " + e.getMessage());
                    showError("Export Failed", e.getMessage());
                });
                log.error("Export failed", e);
            }
        }).start();
    }

    @FXML
    private void handleImport() {
        String deviceRole = getDeviceRoleString();
        if ("SLAVE".equalsIgnoreCase(deviceRole)) {
            showError("Not Allowed", "Import is only available in MASTER mode");
            return;
        }
        
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select Checkpoint Data Files");
        
        File initialDir = new File(usbPathField.getText());
        if (initialDir.exists()) {
            fileChooser.setInitialDirectory(initialDir);
        }
        
        fileChooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("JSON Files", "*.json")
        );
        
        List<File> files = fileChooser.showOpenMultipleDialog(importButton.getScene().getWindow());
        
        if (files == null || files.isEmpty()) {
            return;
        }
        
        importButton.setDisable(true);
        if (progressBar != null) progressBar.setVisible(true);
        if (statusLabel != null) {
            statusLabel.setText("🔄 Importing data...");
            statusLabel.setStyle("-fx-text-fill: orange;");
        }
        
        // Run import in background thread
        new Thread(() -> {
            int successCount = 0;
            int totalRecordsImported = 0;
            StringBuilder summary = new StringBuilder();
            
            for (File file : files) {
                try {
                    addLog("📥 Importing: " + file.getName());
                    
                    CheckpointDataSyncService.ImportResult result = syncService.importFromUSB(file);
                    
                    successCount++;
                    totalRecordsImported += result.getImported();
                    
                    addLog("✅ " + file.getName() + ": " + result.getSummary());
                    summary.append(file.getName()).append("\n");
                    summary.append("  ").append(result.getSummary()).append("\n\n");
                    
                } catch (Exception e) {
                    addLog("❌ Failed: " + file.getName() + " - " + e.getMessage());
                    log.error("Import failed for file: " + file.getName(), e);
                }
            }
            
            final int finalSuccessCount = successCount;
            final int finalTotalRecords = totalRecordsImported;
            final String finalSummary = summary.toString();
            
            Platform.runLater(() -> {
                if (progressBar != null) progressBar.setVisible(false);
                importButton.setDisable(false);
                
                if (statusLabel != null) {
                    statusLabel.setText(String.format("✅ Imported %d files", finalSuccessCount));
                    statusLabel.setStyle("-fx-text-fill: green;");
                }
                
                showInfo("Import Complete",
                    String.format("Successfully imported %d/%d files\n" +
                        "Total records: %d\n\n%s",
                        finalSuccessCount, files.size(), finalTotalRecords, finalSummary)
                );
                
                loadPendingData();
            });
            
        }).start();
    }

    @FXML
    private void handleRefresh() {
        loadPendingData();
        addLog("🔄 Refreshed pending data");
    }

    private void startAutoRefresh() {
        try {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "DataSync-AutoRefresh");
                t.setDaemon(true);
                return t;
            });
            
            scheduler.scheduleAtFixedRate(() -> {
                try {
                    if (javaFxAvailable) {
                        Platform.runLater(this::loadPendingData);
                    }
                } catch (Exception e) {
                    log.error("Auto-refresh error", e);
                }
            }, 10, 10, TimeUnit.SECONDS);
            
            log.info("✅ Auto-refresh started (10s interval)");
            
        } catch (Exception e) {
            log.error("Failed to start auto-refresh", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
                log.info("✅ Data Sync scheduler stopped");
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * ✅ FIXED: Safe addLog that checks JavaFX availability
     */
    private void addLog(String message) {
        if (!javaFxAvailable) {
            log.info(message); // Fallback to logger
            return;
        }
        
        try {
            Platform.runLater(() -> {
                if (logArea != null) {
                    String timestamp = LocalDateTime.now().format(
                        DateTimeFormatter.ofPattern("HH:mm:ss")
                    );
                    logArea.appendText(String.format("[%s] %s\n", timestamp, message));
                }
            });
        } catch (IllegalStateException e) {
            // JavaFX not available - use logger
            log.info(message);
        }
    }

    private void showInfo(String title, String message) {
        if (!javaFxAvailable) {
            log.info("{}: {}", title, message);
            return;
        }
        
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }

    private void showError(String title, String message) {
        if (!javaFxAvailable) {
            log.error("{}: {}", title, message);
            return;
        }
        
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }
}
