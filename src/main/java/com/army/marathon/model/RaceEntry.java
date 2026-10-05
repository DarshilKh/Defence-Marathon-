package com.army.marathon.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * One row per participant who is running in a specific race (the "race-day roster").
 * A race with NO rows here has no roster: everyone registered is scannable and
 * appears in results, exactly as before rosters existed.
 *
 * Keyed by participant id (not RFID tag or Tag No) so the roster survives either of
 * those being edited on the Registration tab.
 */
@Data
@Entity
@Table(name = "race_entries",
       uniqueConstraints = @UniqueConstraint(name = "uk_race_entry_race_participant",
                                             columnNames = {"race_id", "participant_id"}),
       indexes = {@Index(name = "idx_race_entry_race", columnList = "race_id")})
public class RaceEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "race_id", nullable = false)
    private Long raceId;

    @Column(name = "participant_id", nullable = false)
    private Long participantId;

    @Column(name = "added_at")
    private LocalDateTime addedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 20, nullable = false)
    private Source source = Source.MANUAL;

    public enum Source {
        MANUAL,
        EXCEL_UPLOAD
    }

    @PrePersist
    protected void onCreate() {
        if (addedAt == null) {
            addedAt = LocalDateTime.now();
        }
        if (source == null) {
            source = Source.MANUAL;
        }
    }
}
