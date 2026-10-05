package com.army.marathon.service;

import com.army.marathon.model.CheckpointReading;
import com.army.marathon.repository.CheckpointReadingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class CheckpointDataSyncService {

    private final CheckpointReadingRepository readingRepository;
    private final DeviceModeService deviceModeService;
    private final ObjectMapper objectMapper;

    public CheckpointDataSyncService(
        CheckpointReadingRepository readingRepository,
        DeviceModeService deviceModeService
    ) {
        this.readingRepository = readingRepository;
        this.deviceModeService = deviceModeService;
        
        // Configure JSON mapper for LocalDateTime
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Export unsynced data to USB (for SLAVE systems)
     */
    @Transactional
    public File exportToUSB(String usbPath) throws Exception {
        String deviceId = deviceModeService.getDeviceId();
        
        // Get all unsynced readings
        List<CheckpointReading> unsyncedReadings = 
            readingRepository.findByDeviceIdAndUploadedToServerFalse(deviceId);
        
        if (unsyncedReadings.isEmpty()) {
            throw new RuntimeException("No pending data to export");
        }
        
        // Create filename with timestamp
        String timestamp = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String filename = String.format("checkpoint_data_%s_%s.json", deviceId, timestamp);
        
        File usbDir = new File(usbPath);
        if (!usbDir.exists() || !usbDir.isDirectory()) {
            throw new RuntimeException("Invalid USB path: " + usbPath);
        }
        
        File exportFile = new File(usbDir, filename);
        
        // Convert to export format (detach from JPA context)
        List<CheckpointReadingExport> exportData = new ArrayList<>();
        for (CheckpointReading reading : unsyncedReadings) {
            CheckpointReadingExport export = new CheckpointReadingExport();
            export.setRfidTag(reading.getRfidTag());
            export.setCheckpointName(reading.getCheckpointName());
            export.setReaderId(reading.getReaderId());
            export.setReadTime(reading.getReadTime());
            export.setRaceId(reading.getRaceId());
            export.setDeviceId(reading.getDeviceId());
            export.setCreatedAt(reading.getCreatedAt());
            exportData.add(export);
        }
        
        // Write to JSON file
        objectMapper.writeValue(exportFile, exportData);
        
        log.info("✅ Exported {} readings to: {}", exportData.size(), exportFile.getName());
        
        return exportFile;
    }

    /**
     * Import data from USB (for MASTER system)
     */
    @Transactional
    public ImportResult importFromUSB(File dataFile) throws Exception {
        if (!dataFile.exists() || !dataFile.isFile()) {
            throw new RuntimeException("Invalid file: " + dataFile.getName());
        }
        
        // Read JSON file
        CheckpointReadingExport[] exports = objectMapper.readValue(
            dataFile, CheckpointReadingExport[].class
        );
        
        int imported = 0;
        int skipped = 0;
        int errors = 0;
        List<String> errorMessages = new ArrayList<>();
        
        for (CheckpointReadingExport export : exports) {
            try {
                // Check for duplicates
                boolean exists = readingRepository.existsByRfidTagAndCheckpointNameAndReadTimeAndDeviceId(
                    export.getRfidTag(),
                    export.getCheckpointName(),
                    export.getReadTime(),
                    export.getDeviceId()
                );
                
                if (exists) {
                    skipped++;
                    log.debug("Skipping duplicate: {} at {} from {}",
                        export.getRfidTag(), export.getCheckpointName(), export.getDeviceId());
                    continue;
                }
                
                // Create new reading
                CheckpointReading reading = new CheckpointReading();
                reading.setRfidTag(export.getRfidTag());
                reading.setCheckpointName(export.getCheckpointName());
                reading.setReaderId(export.getReaderId());
                reading.setReadTime(export.getReadTime());
                reading.setRaceId(export.getRaceId());
                reading.setDeviceId(export.getDeviceId());
                reading.setCreatedAt(export.getCreatedAt());
                
                // Mark as uploaded
                reading.setUploadedToServer(true);
                reading.setUploadTimestamp(LocalDateTime.now());
                
                readingRepository.save(reading);
                imported++;
                
            } catch (Exception e) {
                errors++;
                errorMessages.add(String.format("Error importing tag %s: %s", 
                    export.getRfidTag(), e.getMessage()));
                log.error("Import error for tag {}: {}", export.getRfidTag(), e.getMessage());
            }
        }
        
        ImportResult result = new ImportResult();
        result.setTotalRecords(exports.length);
        result.setImported(imported);
        result.setSkipped(skipped);
        result.setErrors(errors);
        result.setErrorMessages(errorMessages);
        
        log.info("✅ Import completed: {} imported, {} skipped, {} errors from {}",
            imported, skipped, errors, dataFile.getName());
        
        return result;
    }

    /**
     * Get pending export count
     */
    public long getPendingExportCount() {
        String deviceId = deviceModeService.getDeviceId();
        return readingRepository.countByDeviceIdAndUploadedToServerFalse(deviceId);
    }

    /**
     * Get all pending exports
     */
    public List<CheckpointReading> getPendingExports() {
        String deviceId = deviceModeService.getDeviceId();
        return readingRepository.findByDeviceIdAndUploadedToServerFalse(deviceId);
    }

    /**
     * Clear exported data (after successful export)
     */
    @Transactional
    public void markAsExported(List<CheckpointReading> readings) {
        for (CheckpointReading reading : readings) {
            reading.setUploadedToServer(true);
            reading.setUploadTimestamp(LocalDateTime.now());
            readingRepository.save(reading);
        }
        log.info("✅ Marked {} readings as exported", readings.size());
    }

    // Inner class for export format (simpler than JPA entity)
    public static class CheckpointReadingExport {
        private String rfidTag;
        private String checkpointName;
        private String readerId;
        private LocalDateTime readTime;
        private Long raceId;
        private String deviceId;
        private LocalDateTime createdAt;

        // Getters and setters
        public String getRfidTag() { return rfidTag; }
        public void setRfidTag(String rfidTag) { this.rfidTag = rfidTag; }
        
        public String getCheckpointName() { return checkpointName; }
        public void setCheckpointName(String checkpointName) { this.checkpointName = checkpointName; }
        
        public String getReaderId() { return readerId; }
        public void setReaderId(String readerId) { this.readerId = readerId; }
        
        public LocalDateTime getReadTime() { return readTime; }
        public void setReadTime(LocalDateTime readTime) { this.readTime = readTime; }
        
        public Long getRaceId() { return raceId; }
        public void setRaceId(Long raceId) { this.raceId = raceId; }
        
        public String getDeviceId() { return deviceId; }
        public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
        
        public LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    }

    // Import result class
    public static class ImportResult {
        private int totalRecords;
        private int imported;
        private int skipped;
        private int errors;
        private List<String> errorMessages;

        // Getters and setters
        public int getTotalRecords() { return totalRecords; }
        public void setTotalRecords(int totalRecords) { this.totalRecords = totalRecords; }
        
        public int getImported() { return imported; }
        public void setImported(int imported) { this.imported = imported; }
        
        public int getSkipped() { return skipped; }
        public void setSkipped(int skipped) { this.skipped = skipped; }
        
        public int getErrors() { return errors; }
        public void setErrors(int errors) { this.errors = errors; }
        
        public List<String> getErrorMessages() { return errorMessages; }
        public void setErrorMessages(List<String> errorMessages) { this.errorMessages = errorMessages; }
        
        public String getSummary() {
            return String.format("Total: %d | Imported: %d | Skipped: %d | Errors: %d",
                totalRecords, imported, skipped, errors);
        }
    }
}
