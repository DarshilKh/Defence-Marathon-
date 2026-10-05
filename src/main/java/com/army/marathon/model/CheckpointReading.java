package com.army.marathon.model;

import lombok.Data;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "checkpoint_readings", 
       indexes = {
           @Index(name = "idx_rfid_checkpoint", columnList = "rfidTag,checkpoint_id"),
           @Index(name = "idx_device_uploaded", columnList = "device_id,uploaded_to_server"),
           @Index(name = "idx_race_id", columnList = "race_id"),
           @Index(name = "idx_read_time", columnList = "readTime"),
           @Index(name = "idx_participant", columnList = "participant_id"), // ✅ NEW INDEX
           // Covers the actual per-scan duplicate-read query
           // (findTopByRfidTagAndCheckpointNameAndReadTimeAfterOrderByReadTimeDesc, run on every
           // tag read) - idx_rfid_checkpoint above is (rfidTag, checkpoint_id), which does NOT
           // cover a filter on checkpointName (a separate string column). Equality columns
           // (rfidTag, checkpointName) first, range column (readTime) last, so MySQL can use it
           // for both the range scan and the ORDER BY readTime DESC.
           @Index(name = "idx_rfid_checkpoint_time", columnList = "rfidTag,checkpointName,readTime")
       })
public class CheckpointReading {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false, length = 50)
    private String rfidTag;
    
    // ✅ FIXED: EAGER fetch to prevent LazyInitializationException in JavaFX
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "checkpoint_id")
    private Checkpoint checkpoint;
    
    @Column(name = "checkpoint_name", length = 100)
    private String checkpointName;
    
    // ✅ NEW: Participant relationship (for Excel export)
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "participant_id")
    private Participant participant;
    
    @Column(name = "reader_id", length = 50)
    private String readerId;
    
    @Column(nullable = false)
    private LocalDateTime readTime;
    
    @Column(name = "race_id")
    private Long raceId;
    
    // Device identification for multi-system deployment
    @Column(name = "device_id", length = 50)
    private String deviceId;
    
    @Column(name = "uploaded_to_server")
    private boolean uploadedToServer = false;
    
    @Column(name = "upload_timestamp")
    private LocalDateTime uploadTimestamp;
    
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
    
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        
        // ✅ Auto-set participant from RFID tag if not already set
        if (participant == null && rfidTag != null) {
            // Will be handled by service layer
        }
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
    
    // ✅ Helper methods for JavaFX display
    @Transient
    public String getCheckpointDisplayName() {
        if (checkpoint != null) {
            return checkpoint.getName();
        }
        return checkpointName != null ? checkpointName : "Unknown";
    }
    
    // ✅ Helper method to get participant name safely
    @Transient
    public String getParticipantName() {
        if (participant != null && participant.getName() != null) {
            return participant.getName();
        }
        return "Unknown";
    }
    
    // ✅ Helper method to get service number safely
    @Transient
    public String getParticipantServiceNumber() {
        if (participant != null && participant.getServiceNumber() != null) {
            return participant.getServiceNumber();
        }
        return "";
    }
    
    // ✅ Helper method to get unit safely
    @Transient
    public String getParticipantUnit() {
        if (participant != null && participant.getUnit() != null) {
            return participant.getUnit();
        }
        return "";
    }
}
