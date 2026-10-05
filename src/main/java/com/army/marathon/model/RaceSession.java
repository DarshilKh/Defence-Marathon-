package com.army.marathon.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * One race "attempt" on the Live Tracking tab: from a fresh Start Race (either the
 * very first start, or the first start after a Reset) to the following Stop Race.
 *
 * <p>The app keeps a single {@link Race} row for the life of the installation (see
 * LiveTrackingController.currentRace()); Reset no longer deletes readings or results,
 * it only rearms that one race for another attempt. This table is what lets the
 * Reports tab list those attempts individually by date, since the Race row itself
 * only ever reflects the latest one.
 */
@Data
@Entity
@Table(name = "race_sessions")
public class RaceSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "race_id")
    private Long raceId;

    @Column(name = "race_name", length = 200)
    private String raceName;

    @Enumerated(EnumType.STRING)
    @Column(name = "calculation_mode", length = 30)
    private Race.CalculationMode calculationMode;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    // Set when Stop Race is pressed; null while the attempt is still armed/active,
    // or if it was reset without ever being stopped.
    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @PrePersist
    protected void onCreate() {
        if (startedAt == null) {
            startedAt = LocalDateTime.now();
        }
    }
}
