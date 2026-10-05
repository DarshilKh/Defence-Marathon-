package com.army.marathon.repository;

import com.army.marathon.model.RaceResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface RaceResultRepository extends JpaRepository<RaceResult, Long> {

    // ========================================
    // ✅ BASIC QUERIES (ResultsController needs these)
    // ========================================

    // ✅ NEW: Find all results by race ID (for ResultsController)
    List<RaceResult> findByRaceId(Long raceId);

    // ========================================
    // ATTEMPT / SESSION SCOPING (Reports tab, and keeping the live Results tab
    // showing only the current attempt now that Reset no longer deletes history)
    // ========================================

    // ✅ CORRECT way to scope results to one attempt: RaceResult.raceSessionId is
    // stamped at calculation time from Race.currentSessionId (see
    // RaceTimingService.resolveSessionId). Use this, not the createdAt-window query
    // below, wherever "this attempt's results" is actually what's needed.
    List<RaceResult> findByRaceSessionId(Long raceSessionId);

    // ⚠️ UNRELIABLE for scoping to an attempt — kept only for the recalculation
    // cleanup below, do not use this to answer "what results belong to attempt X".
    // createdAt is stamped whenever Calculate Results happens to be clicked, which
    // is NOT guaranteed to fall inside [session.startedAt, session.endedAt) — Stop
    // Race (which sets endedAt) and Calculate Results are separate manual actions,
    // and the app deliberately disables Calculate Results until Stop Race has
    // already been pressed, so createdAt is often AFTER endedAt, not inside the
    // window. This was the root cause of the Reports tab showing 0 results for real
    // historical attempts — use findByRaceSessionId instead.
    List<RaceResult> findByRaceIdAndCreatedAtBetween(Long raceId, LocalDateTime start, LocalDateTime end);

    // Deletes only the CURRENT attempt's previously-calculated results before a
    // recalculation, leaving every earlier attempt's results untouched.
    @Modifying
    @Transactional
    void deleteByRaceIdAndCreatedAtGreaterThanEqual(Long raceId, LocalDateTime since);
    
    // Find results by race ID, sorted by time
    List<RaceResult> findByRaceIdOrderByNetTimeSecondsAsc(Long raceId);
    
    // Find completed results only
    List<RaceResult> findByRaceIdAndCompletedRaceTrueOrderByNetTimeSecondsAsc(Long raceId);

    // Same as above, but scoped to one attempt via raceSessionId - used by
    // RaceTimingService.assignRankings so a recalculation only ranks THIS attempt's
    // finishers, instead of ranking every completed result ever recorded for the
    // raceId (which, now that Reset no longer deletes history, would silently
    // include every past attempt's finishers too and offset every rank by however
    // many old rows exist).
    List<RaceResult> findByRaceSessionIdAndCompletedRaceTrueOrderByNetTimeSecondsAsc(Long raceSessionId);
    
    // Find DNF results only
    List<RaceResult> findByRaceIdAndCompletedRaceFalseOrderByNetTimeSecondsAsc(Long raceId);
    
    // ✅ NEW: Find DNF ordered by name
    List<RaceResult> findByRaceIdAndCompletedRaceFalseOrderByParticipantNameAsc(Long raceId);
    
    // ========================================
    // PARTICIPANT QUERIES
    // ========================================
    
    // ✅ Find by participant and race
    Optional<RaceResult> findByRaceIdAndParticipantId(Long raceId, Long participantId);
    
    // ✅ Find results by participant
    List<RaceResult> findByParticipantIdOrderByNetTimeSecondsAsc(Long participantId);
    
    // ========================================
    // COUNT METHODS
    // ========================================
    
    long countByRaceId(Long raceId);

    // Per-attempt count for the Reports delete confirmation.
    long countByRaceSessionId(Long raceSessionId);

    // Legacy rows written before every result carried a session id: they show in no attempt
    // and can't be deleted from the UI. Counted for reporting only, never deleted.
    long countByRaceSessionIdIsNull();
    
    long countByRaceIdAndCompletedRaceTrue(Long raceId);
    
    long countByRaceIdAndCompletedRaceFalse(Long raceId);
    
    // ========================================
    // DELETE OPERATIONS
    // ========================================
    
    // ✅ Delete results by race ID (for recalculation)
    @Modifying
    @Transactional
    void deleteByRaceId(Long raceId);

    // Used only when a participant is deleted for good from Registration.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM RaceResult r WHERE r.participant.id = :participantId")
    int deleteAllByParticipantId(@Param("participantId") Long participantId);

    // Clears THIS attempt's previously-calculated results before a recalculation
    // (clicking "Calculate Results" twice for the same live attempt), leaving every
    // other attempt's results — past or, once sessions exist for them, future —
    // completely untouched. Use this instead of deleteByRaceIdAndCreatedAtGreaterThanEqual.
    @Modifying
    @Transactional
    void deleteByRaceSessionId(Long raceSessionId);
    
    // ========================================
    // CATEGORY QUERIES
    // ========================================
    
    // ✅ Find by category
    @Query("SELECT r FROM RaceResult r WHERE r.raceId = :raceId " +
           "AND r.participant.category = :category " +
           "AND r.completedRace = true " +
           "ORDER BY r.netTimeSeconds ASC")
    List<RaceResult> findByRaceIdAndParticipantCategoryOrderByNetTimeSecondsAsc(
        @Param("raceId") Long raceId, 
        @Param("category") String category);
    
    // ✅ Count by category
    @Query("SELECT COUNT(r) FROM RaceResult r WHERE r.raceId = :raceId " +
           "AND r.participant.category = :category AND r.completedRace = true")
    long countByRaceIdAndCategoryAndCompleted(
        @Param("raceId") Long raceId, 
        @Param("category") String category);
    
    // ========================================
    // RANKING QUERIES
    // ========================================
    
    // ✅ Get top N finishers
    @Query("SELECT r FROM RaceResult r WHERE r.raceId = :raceId " +
           "AND r.completedRace = true " +
           "ORDER BY r.netTimeSeconds ASC")
    List<RaceResult> findTopFinishers(@Param("raceId") Long raceId);
    
    // ✅ Get winner (first place)
    @Query("SELECT r FROM RaceResult r WHERE r.raceId = :raceId " +
           "AND r.completedRace = true AND r.overallRank = 1")
    Optional<RaceResult> findWinner(@Param("raceId") Long raceId);
    
    // ========================================
    // ANALYTICS QUERIES
    // ========================================
    
    // ✅ Get average time for completed participants
    @Query("SELECT AVG(r.netTimeSeconds) FROM RaceResult r " +
           "WHERE r.raceId = :raceId AND r.completedRace = true")
    Double getAverageTimeByRaceId(@Param("raceId") Long raceId);
    
    // ✅ Get fastest time
    @Query("SELECT MIN(r.netTimeSeconds) FROM RaceResult r " +
           "WHERE r.raceId = :raceId AND r.completedRace = true")
    Integer getFastestTimeByRaceId(@Param("raceId") Long raceId);
    
    // ✅ Get slowest time
    @Query("SELECT MAX(r.netTimeSeconds) FROM RaceResult r " +
           "WHERE r.raceId = :raceId AND r.completedRace = true")
    Integer getSlowestTimeByRaceId(@Param("raceId") Long raceId);
}
