package com.army.marathon.repository;

import com.army.marathon.model.RaceEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface RaceEntryRepository extends JpaRepository<RaceEntry, Long> {

    List<RaceEntry> findByRaceId(Long raceId);

    long countByRaceId(Long raceId);

    // Does this race have a roster at all? (no rows = everyone registered is eligible)
    boolean existsByRaceId(Long raceId);

    boolean existsByRaceIdAndParticipantId(Long raceId, Long participantId);

    // ✅ Re-saving a roster from scratch. Single bulk DELETE (not the derived
    // load-then-delete-each form) since rosters can be hundreds of rows.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("DELETE FROM RaceEntry e WHERE e.raceId = :raceId")
    void deleteByRaceId(@Param("raceId") Long raceId);

    // ✅ Cleanup when a participant is deleted from Registration
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("DELETE FROM RaceEntry e WHERE e.participantId = :participantId")
    void deleteByParticipantId(@Param("participantId") Long participantId);
}
