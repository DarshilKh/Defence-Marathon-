package com.army.marathon.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "races")
public class Race {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "race_name", nullable = false, length = 200)
    private String raceName;
    
    @Column(name = "description", length = 500)
    private String description;
    
    @Column(name = "race_date")
    private LocalDateTime raceDate;
    
    @Column(name = "start_time")
    private LocalDateTime startTime;
    
    @Column(name = "end_time")
    private LocalDateTime endTime;
    
    // Manual time entry for master system ⭐
    @Column(name = "manual_start_time")
    private LocalDateTime manualStartTime;
    
    @Column(name = "manual_end_time")
    private LocalDateTime manualEndTime;
    
    // ✅ FIXED: Explicitly define column properties for enum
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private RaceStatus status = RaceStatus.PENDING;
    
    @Column(name = "total_participants")
    private Integer totalParticipants = 0;
    
    @Column(name = "finished_participants")
    private Integer finishedParticipants = 0;
    
    // Device that started the race (master system) ⭐
    @Column(name = "master_device_id", length = 50)
    private String masterDeviceId;
    
    // Checkpoint configuration for this race ⭐
    @Enumerated(EnumType.STRING)
    @Column(name = "calculation_mode", length = 30) // ✅ INCREASED length for new mode
    private CalculationMode calculationMode = CalculationMode.AUTO;
    
    @Column(name = "required_checkpoints")
    private Integer requiredCheckpoints = 2; // Minimum: Start + Finish

    // Lap configuration (for lap mode)
    @Column(name = "lap_count")
    private Integer lapCount = 0;

    @Column(name = "lap_distance_km")
    private Double lapDistanceKm = 0.0;

    // ✅ True when startTime was captured automatically from the first
    // registered-tag scan at the start-line checkpoint, rather than from the
    // manual "Start Race" / "Set Start Time" override.
    @Column(name = "auto_start_detected")
    private Boolean autoStartDetected = false;

    // The moment the CURRENT attempt (since the last fresh Start Race - i.e. the
    // first start, or the first start after a Reset) began. Readings and results
    // from an earlier attempt on this same race keep their rows (Reset never
    // deletes them) but are excluded from live views and from recalculation by
    // comparing their timestamp against this marker. Null on a race that has
    // never been started.
    @Column(name = "current_attempt_started_at")
    private LocalDateTime currentAttemptStartedAt;

    // The RaceSession row (see RaceSession) that records this same attempt for
    // the Reports tab's history list. Null until the first Start Race.
    @Column(name = "current_session_id")
    private Long currentSessionId;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
    
    // ✅ ADD: PrePersist to ensure proper initialization
    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null) {
            status = RaceStatus.PENDING;
        }
        if (calculationMode == null) {
            calculationMode = CalculationMode.AUTO;
        }
        if (totalParticipants == null) {
            totalParticipants = 0;
        }
        if (finishedParticipants == null) {
            finishedParticipants = 0;
        }
        if (requiredCheckpoints == null) {
            requiredCheckpoints = 2;
        }
        if (lapCount == null) {
            lapCount = 0;
        }
        if (lapDistanceKm == null) {
            lapDistanceKm = 0.0;
        }
        if (autoStartDetected == null) {
            autoStartDetected = false;
        }
    }

    // ✅✅✅ UPDATED: Enum definitions with NEW MODE
    public enum RaceStatus {
        PENDING,
        ACTIVE,
        FINISHED,
        CANCELLED,
        NOT_STARTED,
        // ✅ Readers connected and scanning, waiting for the first start-line
        // scan to auto-detect the official race start.
        ARMED
    }
    
    public enum CalculationMode {
        AUTO,                         // Automatic based on enabled checkpoints
        TWO_CHECKPOINT,               // Start + Finish only
        THREE_CHECKPOINT,             // Start + 1 Middle + Finish
        FOUR_CHECKPOINT,              // Start + 2 Middle + Finish
        FIVE_CHECKPOINT,              // Start + 3 Middle + Finish
        SINGLE_FILE_MANUAL_START,     // ✅✅✅ NEW: Single file + manual start time
        SAME_START_FINISH,            // ✅✅✅ Same point (first read=start, second read=finish)
        LAP_RACE,                     // ✅✅✅ Lap architecture (same point, N laps)
        LONG_DISTANCE_MANUAL_START,   // ✅✅✅ Manual/auto start + 1 midpoint + finish
        LONG_DISTANCE_2_MANUAL_START, // ✅✅✅ NEW: Manual/auto start + 2 midpoints + finish ("Long Distance 2")
        // Start + finish checkpoints, with TWO timings per runner: a single operator-declared
        // race start (manualStartTime -> "gun time") AND each runner's own start-line scan
        // ("chip time"). Its results table is shaped differently from every other mode.
        MANUAL_START_DUAL_TIMING
    }
    
    // ✅ Helper methods
    @Transient
    public LocalDateTime getEffectiveStartTime() {
        return manualStartTime != null ? manualStartTime : startTime;
    }
    
    @Transient
    public LocalDateTime getEffectiveEndTime() {
        return manualEndTime != null ? manualEndTime : endTime;
    }
    
    /**
     * True only while there is a live "current attempt" to show: a session id, its start
     * boundary, and a status other than NOT_STARTED. This is the explicit "no current attempt"
     * state (NOT_STARTED, no session, no boundary) that Live Tracking and Results render as
     * EMPTY - a null session/boundary must never be read as "no filter, show everything", or
     * every past attempt's readings and results would merge into the live views. A legacy row
     * that is ACTIVE/FINISHED with a null session also counts as "no attempt". Only Reports,
     * which iterates RaceSession rows, ever shows history.
     */
    @Transient
    public boolean hasCurrentAttempt() {
        return currentSessionId != null
                && currentAttemptStartedAt != null
                && status != RaceStatus.NOT_STARTED;
    }

    // ✅ Additional helper methods for status checking
    @Transient
    public boolean isPending() {
        return status == RaceStatus.PENDING || status == RaceStatus.NOT_STARTED;
    }
    
    @Transient
    public boolean isActive() {
        return status == RaceStatus.ACTIVE;
    }
    
    @Transient
    public boolean isFinished() {
        return status == RaceStatus.FINISHED;
    }
    
    @Transient
    public boolean isCancelled() {
        return status == RaceStatus.CANCELLED;
    }
}
