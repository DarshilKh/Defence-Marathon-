// src/main/java/com/army/marathon/model/RaceResult.java
package com.army.marathon.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "race_results",
       indexes = {@Index(name = "idx_race_participant", columnList = "raceId,participant_id")})
public class RaceResult {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "participant_id")
    private Participant participant;
    
    @Column(name = "race_id")
    private Long raceId;

    /**
     * Which RaceSession (attempt) this result belongs to — set at calculation time
     * from Race.currentSessionId. This is the DEFINITIVE link the Reports tab uses to
     * find a session's results; do not try to infer session membership from
     * createdAt (when Calculate Results happened to be clicked) — that has no
     * reliable relationship to when the race itself ran, since Calculate Results is
     * a separate manual action that can be clicked long after Stop Race.
     * Null for rows created before this field existed.
     */
    @Column(name = "race_session_id")
    private Long raceSessionId;

    private String rfidTag;
    
    @Column(name = "start_time")
    private LocalDateTime startTime;
    
    @Column(name = "finish_time")
    private LocalDateTime finishTime;
    
    @Column(name = "net_time_seconds")
    private Long netTimeSeconds;
    
    @Column(name = "formatted_time")
    private String formattedTime;
    
    @Column(name = "overall_rank")
    private Integer overallRank;
    
    @Column(name = "category_rank")
    private Integer categoryRank;
    
    @Column(name = "age_group_rank")
    private Integer ageGroupRank;
    
    @Column(name = "checkpoint_count")
    private Integer checkpointCount;
    
    @Column(name = "completed_race")
    private boolean completedRace = false;
    
    @Column(name = "split_times_json", length = 2000)
    private String splitTimesJson;
    
    // MANUAL_START_DUAL_TIMING only: the race's single Declared Start, copied onto each row so
    // the Declared-Start duration stays consistent with the calculation even if the operator
    // re-declares the start afterwards. Null in every other mode.
    @Column(name = "declared_start_time")
    private LocalDateTime declaredStartTime;

    // Free-text operator annotation (shown as "Note" in the MANUAL_START_DUAL_TIMING results)
    @Column(name = "remarks", length = 500)
    private String remarks;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    // ✅ DNF with zero readings: a race-day roster member who never showed up on the course
    @Transient
    public boolean isNoShow() {
        return !completedRace && checkpointCount != null && checkpointCount == 0;
    }
}
