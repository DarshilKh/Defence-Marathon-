package com.army.marathon.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "system_config")
public class SystemConfig {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(unique = true, nullable = false, length = 50)
    private String deviceId; // "MASTER", "CHECKPOINT_1", "CHECKPOINT_2", etc.
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeviceRole deviceRole; // MASTER or SLAVE
    
    @Column(name = "checkpoint_name", length = 100)
    private String checkpointName; // "Start Line", "2KM Point", etc.
    
    @Column(name = "is_first_run")
    private boolean isFirstRun = true; // Show setup wizard
    
    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();
    
    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();
    
    // Device role enum
    public enum DeviceRole {
        MASTER,    // Master control system (Start line)
        SLAVE      // Checkpoint system (Auto-start)
    }
    
    // Helper methods
    public boolean isMaster() {
        return deviceRole == DeviceRole.MASTER;
    }
    
    public boolean isSlave() {
        return deviceRole == DeviceRole.SLAVE;
    }
}
