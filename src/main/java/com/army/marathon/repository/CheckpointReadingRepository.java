package com.army.marathon.repository;

import com.army.marathon.model.Checkpoint;
import com.army.marathon.model.CheckpointReading;
import com.army.marathon.model.Participant;
import org.springframework.data.domain.Pageable;
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
public interface CheckpointReadingRepository extends JpaRepository<CheckpointReading, Long> {
    
    // ========================================
    // ✅✅✅ DUPLICATE PREVENTION (NEW - CRITICAL FOR DUAL READERS)
    // ========================================
    
    /**
     * ✅ Find most recent reading for tag at checkpoint within time window
     * Used to prevent duplicate reads from Reader A and Reader B
     */
    Optional<CheckpointReading> findTopByRfidTagAndCheckpointNameAndReadTimeAfterOrderByReadTimeDesc(
        String rfidTag, 
        String checkpointName, 
        LocalDateTime afterTime
    );
    
    // ========================================
    // ✅ BASIC QUERIES (ResultsController needs these)
    // ========================================
    
    // ✅ NEW: Find all readings by race ID (for ResultsController)
    List<CheckpointReading> findByRaceId(Long raceId);
    
    List<CheckpointReading> findByRaceIdOrderByReadTimeDesc(Long raceId);
    
    List<CheckpointReading> findByRaceIdOrderByReadTimeAsc(Long raceId);
    
    List<CheckpointReading> findByRfidTagAndRaceIdOrderByReadTimeAsc(String rfidTag, Long raceId);
    
    Optional<CheckpointReading> findFirstByRfidTagAndCheckpointAndRaceIdOrderByReadTimeAsc(
        String rfidTag, Checkpoint checkpoint, Long raceId
    );
    
    List<CheckpointReading> findByCheckpointAndRaceIdOrderByReadTimeAsc(
        Checkpoint checkpoint, Long raceId
    );
    
    long countByRaceId(Long raceId);
    
    // ========================================
    // ✅ PARTICIPANT QUERIES (for ResultsController)
    // ========================================
    
    // ✅ Find by participant ID and race ID
    List<CheckpointReading> findByParticipantIdAndRaceIdOrderByReadTimeAsc(
        Long participantId, Long raceId);
    
    // ✅ Find by participant ordered by time
    List<CheckpointReading> findByParticipantOrderByReadTimeAsc(Participant participant);
    
    List<CheckpointReading> findByParticipantIdOrderByReadTimeAsc(Long participantId);
    
    // ========================================
    // DELETE OPERATIONS
    // ========================================
    
    // Used only when a participant is deleted for good from Registration.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM CheckpointReading cr WHERE cr.participant.id = :participantId")
    int deleteAllByParticipantId(@Param("participantId") Long participantId);

    // ✅ Delete by race ID (for recalculation)
    @Modifying
    @Transactional
    void deleteByRaceId(Long raceId);
    
    // ========================================
    // CHECKPOINT QUERIES
    // ========================================
    
    // ✅ Find by checkpoint ID
    List<CheckpointReading> findByCheckpointIdOrderByReadTimeAsc(Long checkpointId);
    
    // ✅ Find by checkpoint name and race
    List<CheckpointReading> findByCheckpointNameAndRaceIdOrderByReadTimeAsc(
        String checkpointName, Long raceId);
    
    long countByCheckpointIdAndRaceId(Long checkpointId, Long raceId);
    
    // ========================================
    // USB EXPORT/IMPORT QUERIES
    // ========================================
    
    List<CheckpointReading> findByUploadedToServerFalse();
    
    List<CheckpointReading> findByDeviceIdAndUploadedToServerFalse(String deviceId);
    
    // ✅ Find unuploaded readings ordered by time
    List<CheckpointReading> findByUploadedToServerFalseOrderByReadTimeAsc();
    
    long countByUploadedToServerFalse();
    
    long countByDeviceIdAndUploadedToServerFalse(String deviceId);
    
    // Check for duplicate readings (prevent duplicate imports)
    boolean existsByRfidTagAndCheckpointNameAndReadTimeAndDeviceId(
        String rfidTag, String checkpointName, LocalDateTime readTime, String deviceId
    );
    
    // ========================================
    // DEVICE & DEBUGGING QUERIES
    // ========================================
    
    List<CheckpointReading> findByDeviceIdOrderByReadTimeDesc(String deviceId);
    
    // ✅ Find recent readings (top 100)
    List<CheckpointReading> findTop100ByOrderByReadTimeDesc();
    
    // ✅ Find by RFID tag
    List<CheckpointReading> findByRfidTagOrderByReadTimeDesc(String rfidTag);
    
    // ========================================
    // TIME RANGE QUERIES
    // ========================================
    
    @Query("SELECT cr FROM CheckpointReading cr WHERE cr.raceId = :raceId " +
           "AND cr.readTime BETWEEN :startTime AND :endTime " +
           "ORDER BY cr.readTime ASC")
    List<CheckpointReading> findByRaceIdAndTimeRange(
        @Param("raceId") Long raceId,
        @Param("startTime") LocalDateTime startTime,
        @Param("endTime") LocalDateTime endTime
    );
    
    // ✅ NEW: Find by race and time range (alternate method)
    List<CheckpointReading> findByRaceIdAndReadTimeBetweenOrderByReadTimeAsc(
        Long raceId, LocalDateTime start, LocalDateTime end);
    
    // ========================================
    // ATTEMPT WINDOW QUERIES (Reports display, Reports delete, orphan-session closing)
    // ========================================
    // CheckpointReading has no session FK, so one attempt's readings are the race's readings with
    // readTime in [from, toInclusive] AND readTime < capExclusive. capExclusive is the NEXT
    // attempt's startedAt (so a reading can never belong to two attempts) or, when there is
    // none, anything at/after toInclusive. Display and delete use the SAME window on purpose.

    @Query("SELECT cr FROM CheckpointReading cr WHERE cr.raceId = :raceId " +
           "AND cr.readTime >= :from AND cr.readTime <= :toInclusive AND cr.readTime < :capExclusive " +
           "ORDER BY cr.readTime ASC")
    List<CheckpointReading> findInAttemptWindow(
        @Param("raceId") Long raceId,
        @Param("from") LocalDateTime from,
        @Param("toInclusive") LocalDateTime toInclusive,
        @Param("capExclusive") LocalDateTime capExclusive);

    @Query("SELECT COUNT(cr) FROM CheckpointReading cr WHERE cr.raceId = :raceId " +
           "AND cr.readTime >= :from AND cr.readTime <= :toInclusive AND cr.readTime < :capExclusive")
    long countInAttemptWindow(
        @Param("raceId") Long raceId,
        @Param("from") LocalDateTime from,
        @Param("toInclusive") LocalDateTime toInclusive,
        @Param("capExclusive") LocalDateTime capExclusive);

    // Only ever called from ReportService.deleteAttempt (the Reports Delete button).
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("DELETE FROM CheckpointReading cr WHERE cr.raceId = :raceId " +
           "AND cr.readTime >= :from AND cr.readTime <= :toInclusive AND cr.readTime < :capExclusive")
    int deleteInAttemptWindow(
        @Param("raceId") Long raceId,
        @Param("from") LocalDateTime from,
        @Param("toInclusive") LocalDateTime toInclusive,
        @Param("capExclusive") LocalDateTime capExclusive);

    // Last reading of an orphaned attempt: [from, capExclusive) when a later attempt exists...
    @Query("SELECT MAX(cr.readTime) FROM CheckpointReading cr WHERE cr.raceId = :raceId " +
           "AND cr.readTime >= :from AND cr.readTime < :capExclusive")
    LocalDateTime findMaxReadTimeBefore(
        @Param("raceId") Long raceId,
        @Param("from") LocalDateTime from,
        @Param("capExclusive") LocalDateTime capExclusive);

    // ...or [from, unbounded) when it is the latest attempt.
    @Query("SELECT MAX(cr.readTime) FROM CheckpointReading cr WHERE cr.raceId = :raceId " +
           "AND cr.readTime >= :from")
    LocalDateTime findMaxReadTimeFrom(
        @Param("raceId") Long raceId,
        @Param("from") LocalDateTime from);

    // ========================================
    // ANALYTICS QUERIES
    // ========================================
    
    @Query("SELECT DISTINCT cr.rfidTag FROM CheckpointReading cr WHERE cr.raceId = :raceId")
    List<String> findDistinctRfidTagsByRaceId(@Param("raceId") Long raceId);
    
    @Query("SELECT COUNT(DISTINCT cr.rfidTag) FROM CheckpointReading cr WHERE cr.raceId = :raceId")
    long countDistinctRfidTagsByRaceId(@Param("raceId") Long raceId);
    
    @Query("SELECT COUNT(cr) FROM CheckpointReading cr WHERE cr.raceId = :raceId " +
           "AND cr.checkpoint.id = :checkpointId")
    long countByRaceIdAndCheckpointId(
        @Param("raceId") Long raceId,
        @Param("checkpointId") Long checkpointId);

    // ========================================
    // LIVE TRACKING (2-second poll from LiveTrackingController)
    // ========================================
    // The old approach loaded EVERY reading ever saved for the race, filtered and .limit(100)'d
    // it in Java, on every single 2-second tick - a cost that grows with total readings already
    // in the race (fine early on, laggy by reading #800+). These push the same filtering and the
    // LIMIT down into SQL so each tick's cost stays flat regardless of how many readings the race
    // has accumulated so far. "In current attempt" = readTime >= attemptStart (see
    // LiveTrackingController.inCurrentAttempt); a null attemptStart never matches (no current
    // attempt), same as the Java-side filter it replaces.

    @Query("SELECT cr FROM CheckpointReading cr WHERE cr.raceId = :raceId " +
           "AND cr.participant IS NOT NULL AND cr.uploadedToServer = false " +
           "AND cr.readTime >= :attemptStart ORDER BY cr.readTime DESC")
    List<CheckpointReading> findRecentForLiveTracking(
        @Param("raceId") Long raceId,
        @Param("attemptStart") LocalDateTime attemptStart,
        Pageable pageable);

    @Query("SELECT COUNT(cr) FROM CheckpointReading cr WHERE cr.raceId = :raceId " +
           "AND cr.participant IS NOT NULL AND cr.uploadedToServer = false " +
           "AND cr.readTime >= :attemptStart")
    long countInCurrentAttempt(
        @Param("raceId") Long raceId,
        @Param("attemptStart") LocalDateTime attemptStart);

    @Query("SELECT COUNT(DISTINCT cr.rfidTag) FROM CheckpointReading cr WHERE cr.raceId = :raceId " +
           "AND cr.participant IS NOT NULL AND cr.uploadedToServer = false " +
           "AND cr.readTime >= :attemptStart")
    long countDistinctTagsInCurrentAttempt(
        @Param("raceId") Long raceId,
        @Param("attemptStart") LocalDateTime attemptStart);
}
