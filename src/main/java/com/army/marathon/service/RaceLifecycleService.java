package com.army.marathon.service;

import com.army.marathon.model.Race;
import com.army.marathon.model.RaceSession;
import com.army.marathon.repository.CheckpointReadingRepository;
import com.army.marathon.repository.RaceEntryRepository;
import com.army.marathon.repository.RaceRepository;
import com.army.marathon.repository.RaceSessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * The race's lifecycle transitions that must behave identically wherever they are triggered:
 * app startup, the Reset button, the close-window handler and the Reports Delete button.
 *
 * <p>Nothing here ever deletes a RaceSession, RaceResult or CheckpointReading row. History is
 * only ever deleted by {@link ReportService#deleteAttempt}, from the Reports tab's Delete button.
 */
@Slf4j
@Service
public class RaceLifecycleService {

    /** The app keeps a single Race row for the life of the installation (see LiveTrackingController.currentRace()). */
    public static final Long RACE_ID = 1L;

    private final RaceRepository raceRepository;
    private final RaceSessionRepository raceSessionRepository;
    private final CheckpointReadingRepository readingRepository;
    private final RaceEntryRepository raceEntryRepository;

    public RaceLifecycleService(
            RaceRepository raceRepository,
            RaceSessionRepository raceSessionRepository,
            CheckpointReadingRepository readingRepository,
            RaceEntryRepository raceEntryRepository
    ) {
        this.raceRepository = raceRepository;
        this.raceSessionRepository = raceSessionRepository;
        this.readingRepository = readingRepository;
        this.raceEntryRepository = raceEntryRepository;
    }

    /**
     * Closes every RaceSession left open (endedAt == null) by a crash, kill or power loss where
     * the close dialog never ran. endedAt becomes the time of the session's last CheckpointReading
     * in its window, or its startedAt if it has none. The window ends at the NEXT session's
     * startedAt (exclusive) so an orphan never claims a later attempt's readings. Reports treats
     * endedAt == null as "still running until now", so an orphan would otherwise keep growing and
     * absorb later readings into itself. Idempotent: closed sessions are never touched.
     *
     * @return how many sessions were closed
     */
    @Transactional
    public int closeOrphanSessions() {
        List<RaceSession> orphans = raceSessionRepository.findByEndedAtIsNull();
        for (RaceSession session : orphans) {
            LocalDateTime startedAt = session.getStartedAt();
            Optional<RaceSession> next = raceSessionRepository
                    .findFirstByRaceIdAndStartedAtAfterOrderByStartedAtAsc(session.getRaceId(), startedAt);

            LocalDateTime lastReading = next.isPresent()
                    ? readingRepository.findMaxReadTimeBefore(session.getRaceId(), startedAt, next.get().getStartedAt())
                    : readingRepository.findMaxReadTimeFrom(session.getRaceId(), startedAt);

            LocalDateTime endedAt = lastReading != null ? lastReading : startedAt;
            session.setEndedAt(endedAt);
            raceSessionRepository.save(session);
            log.warn("Closed orphaned race session {} ('{}'): endedAt set to {} ({})",
                    session.getId(), session.getRaceName(), endedAt,
                    lastReading != null ? "last reading" : "no readings, used startedAt");
        }
        return orphans.size();
    }

    /**
     * ONE routine for "come up as if no race exists", called by both app startup and the Reset
     * button so the two cannot drift apart. Clears the current attempt (see
     * {@link #clearCurrentAttempt}), blanks the race name and empties the roster. Registered
     * participants, checkpoints, reader type, the duplicate-read window and every RaceSession /
     * RaceResult / CheckpointReading row are left exactly as they are.
     */
    @Transactional
    public void resetToFreshState() {
        clearCurrentAttempt(RACE_ID);

        Race race = raceRepository.findById(RACE_ID)
                .orElseThrow(() -> new IllegalStateException("Race " + RACE_ID + " not found"));
        // races.race_name is NOT NULL, so "blank" is the empty string, never null.
        race.setRaceName("");
        raceRepository.save(race);

        raceEntryRepository.deleteByRaceId(RACE_ID);
        log.info("Race {} reset to fresh state (history kept)", RACE_ID);
    }

    /**
     * Sets the explicit no-current-attempt state (see {@link Race#hasCurrentAttempt()}) on one
     * race row: NOT_STARTED, no current session/boundary, no start/end/manual times,
     * autoStartDetected false. The current session, if still open, is closed first so it is not
     * left as an orphan. Leaves the race name, the roster and all history rows alone - this is
     * also what deleting a finished current attempt from Reports uses, which must not touch the
     * roster.
     */
    @Transactional
    public void clearCurrentAttempt(Long raceId) {
        Race race = raceRepository.findById(raceId)
                .orElseThrow(() -> new IllegalStateException("Race " + raceId + " not found"));

        if (race.getCurrentSessionId() != null) {
            raceSessionRepository.findById(race.getCurrentSessionId()).ifPresent(session -> {
                if (session.getEndedAt() == null) {
                    session.setEndedAt(LocalDateTime.now());
                    raceSessionRepository.save(session);
                }
            });
        }

        race.setStatus(Race.RaceStatus.NOT_STARTED);
        race.setCurrentSessionId(null);
        race.setCurrentAttemptStartedAt(null);
        race.setStartTime(null);
        race.setEndTime(null);
        race.setManualStartTime(null);
        race.setManualEndTime(null);
        race.setAutoStartDetected(false);
        raceRepository.save(race);
    }

    /**
     * Ends the race's current attempt without clearing it: status FINISHED, endTime set, and the
     * current session's endedAt set if it is still open. Used when the window is closed during a
     * race, so the attempt is a clean, finished one in Reports instead of an orphan.
     */
    @Transactional
    public void endCurrentAttempt(Long raceId, LocalDateTime endedAt) {
        Race race = raceRepository.findById(raceId)
                .orElseThrow(() -> new IllegalStateException("Race " + raceId + " not found"));

        if (race.getCurrentSessionId() != null) {
            raceSessionRepository.findById(race.getCurrentSessionId()).ifPresent(session -> {
                if (session.getEndedAt() == null) {
                    session.setEndedAt(endedAt);
                    raceSessionRepository.save(session);
                }
            });
        }

        race.setStatus(Race.RaceStatus.FINISHED);
        race.setEndTime(endedAt);
        raceRepository.save(race);
        log.info("Race {} attempt ended at {}", raceId, endedAt);
    }
}
