package com.army.marathon.service;

import com.army.marathon.model.SystemConfig;
import com.army.marathon.repository.SystemConfigRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@Service
public class DeviceModeService {
    
    private final SystemConfigRepository systemConfigRepository;
    private SystemConfig cachedConfig;

    public DeviceModeService(SystemConfigRepository systemConfigRepository) {
        this.systemConfigRepository = systemConfigRepository;
    }

    /**
     * Get current system configuration
     */
    public SystemConfig getSystemConfig() {
        if (cachedConfig == null) {
            cachedConfig = systemConfigRepository.findFirstByOrderByIdAsc().orElse(null);
        }
        return cachedConfig;
    }

    /**
     * Check if system is configured
     */
    public boolean isConfigured() {
        SystemConfig config = getSystemConfig();
        return config != null && !config.isFirstRun();
    }

    /**
     * Check if this is first run (setup wizard needed)
     */
    public boolean isFirstRun() {
        SystemConfig config = getSystemConfig();
        return config == null || config.isFirstRun();
    }

    /**
     * Check if device is in MASTER mode
     */
    public boolean isMasterMode() {
        SystemConfig config = getSystemConfig();
        return config != null && config.isMaster();
    }

    /**
     * Check if device is in SLAVE mode
     */
    public boolean isSlaveMode() {
        SystemConfig config = getSystemConfig();
        return config != null && config.isSlave();
    }

    /**
     * Get device ID
     */
    public String getDeviceId() {
        SystemConfig config = getSystemConfig();
        return config != null ? config.getDeviceId() : "UNKNOWN";
    }

    /**
     * Get device role
     */
    public SystemConfig.DeviceRole getDeviceRole() {
        SystemConfig config = getSystemConfig();
        return config != null ? config.getDeviceRole() : null;
    }

    /**
     * Save system configuration
     */
    @Transactional
    public SystemConfig saveConfiguration(
        String deviceId,
        SystemConfig.DeviceRole deviceRole,
        String checkpointName
    ) {
        Optional<SystemConfig> existingOpt = systemConfigRepository.findFirstByOrderByIdAsc();
        
        SystemConfig config;
        if (existingOpt.isPresent()) {
            config = existingOpt.get();
            log.info("Updating existing system configuration");
        } else {
            config = new SystemConfig();
            config.setCreatedAt(LocalDateTime.now());
            log.info("Creating new system configuration");
        }
        
        config.setDeviceId(deviceId);
        config.setDeviceRole(deviceRole);
        config.setCheckpointName(checkpointName);
        config.setFirstRun(false);
        config.setUpdatedAt(LocalDateTime.now());
        
        config = systemConfigRepository.save(config);
        
        // Update cache
        cachedConfig = config;
        
        log.info("✅ System configured as {} with ID: {}", deviceRole, deviceId);
        
        return config;
    }

    /**
     * Reset configuration (for testing)
     */
    @Transactional
    public void resetConfiguration() {
        systemConfigRepository.deleteAll();
        cachedConfig = null;
        log.warn("⚠️ System configuration reset!");
    }

    /**
     * Get configuration summary for display
     */
    public String getConfigurationSummary() {
        SystemConfig config = getSystemConfig();
        if (config == null) {
            return "❌ Not Configured";
        }
        
        return String.format("%s Mode | Device: %s | Checkpoint: %s",
            config.getDeviceRole(),
            config.getDeviceId(),
            config.getCheckpointName() != null ? config.getCheckpointName() : "N/A"
        );
    }
    /**
     * Test database connectivity
     * @return true if database is accessible, false otherwise
     */
    public boolean testDatabaseConnection() {
        try {
            // Simple query to test connection
            long count = systemConfigRepository.count();
            log.info("✅ Database connection test successful - Found {} config records", count);
            return true;
        } catch (Exception e) {
            log.error("❌ Database connection test failed", e);
            return false;
        }
    }


}

