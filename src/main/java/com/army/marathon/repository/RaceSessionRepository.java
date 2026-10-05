package com.army.marathon.repository;

import com.army.marathon.model.RaceSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface RaceSessionRepository extends JpaRepository<RaceSession, Long> {

    List<RaceSession> findByRaceIdOrderByStartedAtDesc(Long raceId);

    List<RaceSession> findByRaceIdAndStartedAtBetweenOrderByStartedAtDesc(
            Long raceId, LocalDateTime start, LocalDateTime end);

    // Attempts never closed (crash, kill, power loss before the close dialog ran) - see
    // RaceLifecycleService.closeOrphanSessions.
    List<RaceSession> findByEndedAtIsNull();

    // The attempt that started right after this one on the same race: caps this attempt's
    // reading window so it can never reach into the next attempt's readings.
    Optional<RaceSession> findFirstByRaceIdAndStartedAtAfterOrderByStartedAtAsc(
            Long raceId, LocalDateTime startedAt);
}
