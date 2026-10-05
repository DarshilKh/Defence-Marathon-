package com.army.marathon.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "checkpoints")
public class Checkpoint {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "location_name", nullable = false, length = 100)
    private String locationName;
    
    @Column(name = "location_number", nullable = false)
    private Integer locationNumber;
    
    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;
    
    @Column(name = "enabled")
    private Boolean enabled = false;
    
    // ✅ NEW: Fields for ResultsController compatibility
    @Column(name = "race_id")
    private Long raceId; // ✅ Added for race association
    
    @Column(name = "device_id", length = 50)
    private String deviceId; // ✅ Added for device tracking
    
    // ========================================
    // MULTI-READER CONFIGURATION (Existing)
    // ========================================
    @Column(name = "reader_a_ip", length = 50)
    private String readerAIp;
    
    // Zebra's standard RFID3 port. The port actually used at connect time is fixed per
    // reader type (RaceConfiguration.ReaderType#getFixedPort), not read from here.
    @Column(name = "reader_a_port")
    private Integer readerAPort = 5084;
    
    @Column(name = "reader_b_ip", length = 50)
    private String readerBIp;
    
    @Column(name = "reader_b_port")
    private Integer readerBPort = 5084;
    
    @Column(name = "reader_a_com_port", length = 20)
    private String readerAComPort;
    
    @Column(name = "reader_b_com_port", length = 20)
    private String readerBComPort;
    
    @Column(name = "reader_c_com_port", length = 20)
    private String readerCComPort;
    
    @Column(name = "reader_d_com_port", length = 20)
    private String readerDComPort;
    
    @Column(name = "reader_e_com_port", length = 20)
    private String readerEComPort;
    
    // ========================================
    // ✅ SIMPLIFIED READER CONFIGURATION (NEW)
    // ========================================
    @Column(name = "reader_ip", length = 50)
    private String readerIp;
    
    @Column(name = "reader_port")
    private Integer readerPort;
    
    // ========================================
    // CHECKPOINT PROPERTIES
    // ========================================
    @Column(name = "distance_km")
    private Double distanceKm = 0.0;
    
    @Column(name = "start_line")
    private Boolean startLine = false;
    
    @Column(name = "finish_line")
    private Boolean finishLine = false;
    
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
    
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    
    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (updatedAt == null) {
            updatedAt = LocalDateTime.now();
        }
        if (enabled == null) {
            enabled = false;
        }
        if (startLine == null) {
            startLine = false;
        }
        if (finishLine == null) {
            finishLine = false;
        }
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
        if (enabled == null) {
            enabled = false;
        }
        if (startLine == null) {
            startLine = false;
        }
        if (finishLine == null) {
            finishLine = false;
        }
    }
    
    // ========================================
    // GETTERS AND SETTERS (With Fallbacks)
    // ========================================
    
    public Boolean getEnabled() {
        return enabled != null ? enabled : false;
    }
    
    public Boolean getStartLine() {
        return startLine != null ? startLine : false;
    }
    
    public Boolean getFinishLine() {
        return finishLine != null ? finishLine : false;
    }
    
    public void setEnabled(Boolean enabled) {
        this.enabled = enabled != null ? enabled : false;
    }
    
    public void setStartLine(Boolean startLine) {
        this.startLine = startLine != null ? startLine : false;
    }
    
    public void setFinishLine(Boolean finishLine) {
        this.finishLine = finishLine != null ? finishLine : false;
    }
    
    /**
     * ✅ Get reader IP with proper fallback
     */
    public String getReaderIp() {
        if (readerIp != null && !readerIp.trim().isEmpty()) {
            return readerIp;
        }
        if (readerAIp != null && !readerAIp.trim().isEmpty()) {
            return readerAIp;
        }
        return null;
    }
    
    public void setReaderIp(String readerIp) {
        this.readerIp = readerIp;
    }
    
    /**
     * ✅ Get reader port with proper fallback
     */
    public Integer getReaderPort() {
        if (readerPort != null && readerPort > 0) {
            return readerPort;
        }
        if (readerAPort != null && readerAPort > 0) {
            return readerAPort;
        }
        return null;
    }
    
    public void setReaderPort(Integer readerPort) {
        this.readerPort = readerPort;
    }
    
    // ========================================
    // ✅ NEW: NAME ALIAS METHODS (for ResultsController)
    // ========================================
    
    /**
     * ✅ Get name (alias for locationName)
     */
    public String getName() {
        return locationName;
    }
    
    /**
     * ✅ Set name (updates locationName)
     */
    public void setName(String name) {
        this.locationName = name;
    }
    
    // ========================================
    // ✅ NEW: RACE ID METHODS (for ResultsController)
    // ========================================
    
    public Long getRaceId() {
        return raceId;
    }
    
    public void setRaceId(Long raceId) {
        this.raceId = raceId;
    }
    
    // ========================================
    // ✅ NEW: DEVICE ID METHODS (for ResultsController)
    // ========================================
    
    public String getDeviceId() {
        return deviceId;
    }
    
    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }
    
    // ========================================
    // BOOLEAN CONVENIENCE METHODS
    // ========================================
    
    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }
    
    public boolean isStartLine() {
        return Boolean.TRUE.equals(startLine);
    }
    
    public boolean isFinishLine() {
        return Boolean.TRUE.equals(finishLine);
    }
    
    // ========================================
    // READER CONFIGURATION HELPERS
    // ========================================
    
    public int getActiveReaderCount() {
        int count = 0;
        if (readerAIp != null && !readerAIp.trim().isEmpty()) {
            count++;
        }
        if (readerBIp != null && !readerBIp.trim().isEmpty()) {
            count++;
        }
        return Math.max(1, count);
    }
    
    public boolean isReaderAConfigured() {
        return readerAIp != null && !readerAIp.trim().isEmpty() && readerAPort != null;
    }
    
    public boolean isReaderBConfigured() {
        return readerBIp != null && !readerBIp.trim().isEmpty() && readerBPort != null;
    }
    
    public String getReaderAConnection() {
        if (isReaderAConfigured()) {
            return readerAIp + ":" + readerAPort;
        }
        return null;
    }
    
    public String getReaderBConnection() {
        if (isReaderBConfigured()) {
            return readerBIp + ":" + readerBPort;
        }
        return null;
    }
    
    public List<String> getActiveReaderPorts() {
        List<String> ports = new ArrayList<>();
        
        if (isReaderAConfigured()) {
            ports.add("READER_A:" + readerAIp + ":" + readerAPort);
        }
        if (isReaderBConfigured()) {
            ports.add("READER_B:" + readerBIp + ":" + readerBPort);
        }
        
        if (ports.isEmpty()) {
            if (readerAComPort != null && !readerAComPort.trim().isEmpty()) {
                ports.add(readerAComPort);
            }
            if (readerBComPort != null && !readerBComPort.trim().isEmpty()) {
                ports.add(readerBComPort);
            }
        }
        
        return ports;
    }
    
    public String getReaderIdentifier(String connectionString) {
        if (connectionString.startsWith("READER_A:")) {
            return locationName + "_READER_A";
        } else if (connectionString.startsWith("READER_B:")) {
            return locationName + "_READER_B";
        } else {
            return locationName + "_" + connectionString;
        }
    }
    
    @Transient
    private Map<String, String> readerStatusMap = new ConcurrentHashMap<>();
    
    public void setReaderStatus(String connectionString, String status) {
        if (readerStatusMap == null) {
            readerStatusMap = new ConcurrentHashMap<>();
        }
        readerStatusMap.put(connectionString, status);
    }
    
    public String getReaderStatus(String connectionString) {
        if (readerStatusMap == null) {
            return "UNKNOWN";
        }
        return readerStatusMap.getOrDefault(connectionString, "UNKNOWN");
    }
    
    public Map<String, String> getAllReaderStatuses() {
        if (readerStatusMap == null) {
            return new HashMap<>();
        }
        return new HashMap<>(readerStatusMap);
    }
    
    @Override
    public String toString() {
        return "Checkpoint{" +
                "id=" + id +
                ", locationName='" + locationName + '\'' +
                ", locationNumber=" + locationNumber +
                ", enabled=" + isEnabled() +
                ", readerIp=" + getReaderIp() +
                ", readerPort=" + getReaderPort() +
                ", raceId=" + raceId +
                ", deviceId='" + deviceId + '\'' +
                ", distanceKm=" + distanceKm +
                ", startLine=" + isStartLine() +
                ", finishLine=" + isFinishLine() +
                '}';
    }
}
