package com.army.marathon.service;

import com.army.marathon.model.Checkpoint;
import com.army.marathon.model.CheckpointReading;
import com.army.marathon.model.Participant;
import com.army.marathon.model.Race;
import com.army.marathon.model.RaceEntry;
import com.army.marathon.model.RaceResult;
import com.army.marathon.model.RaceSession;
import com.army.marathon.repository.CheckpointReadingRepository;
import com.army.marathon.repository.CheckpointRepository;
import com.army.marathon.repository.ParticipantRepository;
import com.army.marathon.repository.RaceEntryRepository;
import com.army.marathon.repository.RaceRepository;
import com.army.marathon.repository.RaceResultRepository;
import com.army.marathon.repository.RaceSessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Orphan-session closing, Reset/startup "fresh state" (history kept), and Reports Delete scoping.
 *
 * Every test runs in a rolled-back transaction, but resetToFreshState() touches race 1's row and
 * roster and the orphan test closes EVERY open session, so run this only against a throwaway
 * database, never a live installation.
 */
@SpringBootTest
@Transactional
@Rollback
class RaceLifecycleServiceTest {

    @Autowired private RaceLifecycleService lifecycleService;
    @Autowired private ReportService reportService;
    @Autowired private RaceRepository raceRepository;
    @Autowired private RaceSessionRepository sessionRepository;
    @Autowired private RaceResultRepository resultRepository;
    @Autowired private CheckpointReadingRepository readingRepository;
    @Autowired private CheckpointRepository checkpointRepository;
    @Autowired private ParticipantRepository participantRepository;
    @Autowired private RaceEntryRepository raceEntryRepository;

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 2, 1, 9, 0, 0);
    private long counter = 0;

    @Autowired private ParticipantService participantService;

    @Test
    void deleteParticipant_removesThemForGood_evenWithReadingsAndResults() {
        Race race = newRace(Race.RaceStatus.FINISHED);
        RaceSession s = session(race.getId(), T0, T0.plusHours(1));
        Checkpoint cp = checkpoint(race.getId());
        Participant doomed = participant();
        Participant kept = participant();
        reading(doomed, cp, race.getId(), T0.plusMinutes(5));
        reading(kept, cp, race.getId(), T0.plusMinutes(6));
        result(doomed, race.getId(), s.getId());
        result(kept, race.getId(), s.getId());

        participantService.deleteParticipant(doomed.getId());

        assertTrue(participantRepository.findById(doomed.getId()).isEmpty());
        assertTrue(readingRepository.findByParticipantIdOrderByReadTimeAsc(doomed.getId()).isEmpty());
        assertTrue(resultRepository.findByParticipantIdOrderByNetTimeSecondsAsc(doomed.getId()).isEmpty());
        // Other participants' history and the session itself are untouched
        assertTrue(participantRepository.findById(kept.getId()).isPresent());
        assertEquals(1, readingRepository.findByParticipantIdOrderByReadTimeAsc(kept.getId()).size());
        assertEquals(1, resultRepository.findByParticipantIdOrderByNetTimeSecondsAsc(kept.getId()).size());
        assertTrue(sessionRepository.findById(s.getId()).isPresent());
    }

    // ------------------------------------------------------------------ fixtures

    private Race newRace(Race.RaceStatus status) {
        Race race = new Race();
        race.setRaceName("JUNIT-LIFECYCLE-" + System.nanoTime());
        race.setCalculationMode(Race.CalculationMode.TWO_CHECKPOINT);
        race.setStatus(status);
        return raceRepository.save(race);
    }

    private RaceSession session(Long raceId, LocalDateTime start, LocalDateTime end) {
        RaceSession s = new RaceSession();
        s.setRaceId(raceId);
        s.setRaceName("JUNIT-SESSION");
        s.setStartedAt(start);
        s.setEndedAt(end);
        return sessionRepository.save(s);
    }

    private Checkpoint checkpoint(Long raceId) {
        Checkpoint cp = new Checkpoint();
        cp.setRaceId(raceId);
        cp.setLocationName("LC-CP-" + System.nanoTime());
        cp.setLocationNumber(1);
        cp.setDisplayOrder(1);
        cp.setEnabled(true);
        return checkpointRepository.save(cp);
    }

    private Participant participant() {
        String unique = System.nanoTime() + "-" + (counter++);
        Participant p = new Participant();
        p.setRfidTag("LC-CHIP-" + unique);
        p.setSerialNo("LC-TN-" + unique);
        p.setName("LC Runner " + unique);
        return participantRepository.save(p);
    }

    private void reading(Participant p, Checkpoint cp, Long raceId, LocalDateTime at) {
        CheckpointReading r = new CheckpointReading();
        r.setRfidTag(p.getRfidTag());
        r.setParticipant(p);
        r.setCheckpoint(cp);
        r.setCheckpointName(cp.getName());
        r.setRaceId(raceId);
        r.setReadTime(at);
        r.setDeviceId("JUNIT-DEVICE");
        readingRepository.save(r);
    }

    private void result(Participant p, Long raceId, Long sessionId) {
        RaceResult r = new RaceResult();
        r.setParticipant(p);
        r.setRaceId(raceId);
        r.setRaceSessionId(sessionId);
        r.setRfidTag(p.getRfidTag());
        r.setCheckpointCount(2);
        r.setCompletedRace(true);
        resultRepository.save(r);
    }

    // ------------------------------------------------------------------ orphan closing

    @Test
    void closeOrphanSessions_usesLastReading_cappedByNextSession_orStartedAtWhenNone_andIsIdempotent() {
        Race race = newRace(Race.RaceStatus.NOT_STARTED);
        Checkpoint cp = checkpoint(race.getId());
        Participant p = participant();

        RaceSession s1 = session(race.getId(), T0, null);                       // orphan, readings inside its window
        RaceSession s2 = session(race.getId(), T0.plusMinutes(10), null);       // orphan, has the LATER readings
        RaceSession s3 = session(race.getId(), T0.plusMinutes(30), null);       // orphan, no readings at all
        RaceSession closed = session(race.getId(), T0.minusHours(5), T0.minusHours(4)); // already closed: must not change

        reading(p, cp, race.getId(), T0.plusMinutes(1));
        reading(p, cp, race.getId(), T0.plusMinutes(5));    // s1's last reading
        reading(p, cp, race.getId(), T0.plusMinutes(11));   // belongs to s2, NOT s1 (next-session cap)
        reading(p, cp, race.getId(), T0.plusMinutes(20));   // s2's last reading

        int closedCount = lifecycleService.closeOrphanSessions();

        assertTrue(closedCount >= 3, "at least this test's 3 orphans were closed, got " + closedCount);
        assertEquals(T0.plusMinutes(5), sessionRepository.findById(s1.getId()).orElseThrow().getEndedAt(),
                "s1 ends at its last reading, never reaching into s2's window");
        assertEquals(T0.plusMinutes(20), sessionRepository.findById(s2.getId()).orElseThrow().getEndedAt(),
                "s2 ends at its last reading (no later session to cap it)");
        assertEquals(T0.plusMinutes(30), sessionRepository.findById(s3.getId()).orElseThrow().getEndedAt(),
                "a session with no readings ends at its own startedAt");
        assertEquals(T0.minusHours(4), sessionRepository.findById(closed.getId()).orElseThrow().getEndedAt(),
                "an already-closed session is untouched");

        assertEquals(0, lifecycleService.closeOrphanSessions(), "idempotent: nothing left to close");
    }

    // ------------------------------------------------------------------ reset

    @Test
    void resetToFreshState_keepsAllHistory_clearsRaceState_andClosesTheOpenCurrentSession() {
        Race race = raceRepository.findById(1L).orElseThrow();
        Participant p = participant();
        Checkpoint cp = checkpoint(race.getId());

        RaceSession current = session(race.getId(), T0, null); // open: a reset of a live race
        reading(p, cp, race.getId(), T0.plusMinutes(1));
        result(p, race.getId(), current.getId());
        RaceEntry entry = new RaceEntry();
        entry.setRaceId(race.getId());
        entry.setParticipantId(p.getId());
        raceEntryRepository.save(entry);

        race.setRaceName("Some Race");
        race.setStatus(Race.RaceStatus.ACTIVE);
        race.setCurrentSessionId(current.getId());
        race.setCurrentAttemptStartedAt(T0);
        race.setStartTime(T0.plusSeconds(5));
        race.setEndTime(T0.plusMinutes(2));
        race.setManualStartTime(T0);
        race.setManualEndTime(T0.plusMinutes(2));
        race.setAutoStartDetected(true);
        raceRepository.save(race);

        long sessions = sessionRepository.count();
        long results = resultRepository.count();
        long readings = readingRepository.count();
        long participants = participantRepository.count();
        long checkpoints = checkpointRepository.count();

        lifecycleService.resetToFreshState();

        Race after = raceRepository.findById(1L).orElseThrow();
        assertEquals("", after.getRaceName());
        assertEquals(Race.RaceStatus.NOT_STARTED, after.getStatus());
        assertNull(after.getCurrentSessionId());
        assertNull(after.getCurrentAttemptStartedAt());
        assertNull(after.getStartTime());
        assertNull(after.getEndTime());
        assertNull(after.getManualStartTime());
        assertNull(after.getManualEndTime());
        assertFalse(after.getAutoStartDetected());
        assertFalse(after.hasCurrentAttempt());
        assertFalse(raceEntryRepository.existsByRaceId(1L), "roster cleared");

        assertEquals(sessions, sessionRepository.count(), "no RaceSession deleted");
        assertEquals(results, resultRepository.count(), "no RaceResult deleted");
        assertEquals(readings, readingRepository.count(), "no CheckpointReading deleted");
        assertEquals(participants, participantRepository.count(), "participants kept");
        assertEquals(checkpoints, checkpointRepository.count(), "checkpoint configuration kept");
        assertNotNull(sessionRepository.findById(current.getId()).orElseThrow().getEndedAt(),
                "the open current session was closed, not left as an orphan");
    }

    // ------------------------------------------------------------------ delete

    @Test
    void deleteAttempt_removesOnlyThatAttempt_andNeverTheAdjacentOnesReadings() {
        Race race = newRace(Race.RaceStatus.FINISHED);
        Checkpoint cp = checkpoint(race.getId());
        Participant p = participant();

        // Two adjacent attempts: A ends exactly when B starts (the tightest possible overlap).
        RaceSession a = session(race.getId(), T0, T0.plusMinutes(10));
        RaceSession b = session(race.getId(), T0.plusMinutes(10), T0.plusMinutes(20));

        reading(p, cp, race.getId(), T0.plusMinutes(1));    // A
        reading(p, cp, race.getId(), T0.plusMinutes(9));    // A
        reading(p, cp, race.getId(), T0.plusMinutes(10));   // exactly A.endedAt == B.startedAt: belongs to B
        reading(p, cp, race.getId(), T0.plusMinutes(15));   // B
        result(p, race.getId(), a.getId());
        result(p, race.getId(), b.getId());

        ReportService.AttemptCounts preview = reportService.previewAttempt(a.getId());
        assertEquals(2, preview.readings(), "preview counts only A's readings");
        assertEquals(1, preview.results());

        ReportService.AttemptCounts deleted = reportService.deleteAttempt(a.getId());
        assertEquals(2, deleted.readings());
        assertEquals(1, deleted.results());

        assertTrue(sessionRepository.findById(a.getId()).isEmpty(), "A's session is gone");
        assertTrue(resultRepository.findByRaceSessionId(a.getId()).isEmpty(), "A's results are gone");

        // B is fully intact - including the reading that sits exactly on the A/B boundary.
        assertTrue(sessionRepository.findById(b.getId()).isPresent());
        assertEquals(1, resultRepository.findByRaceSessionId(b.getId()).size());
        assertEquals(2, readingRepository.findByRaceId(race.getId()).size(), "only B's 2 readings remain");
        assertEquals(2, reportService.previewAttempt(b.getId()).readings());
        assertTrue(participantRepository.findById(p.getId()).isPresent(), "participants are never deleted");
    }

    @Test
    void deleteAttempt_isRefusedForTheCurrentAttemptOfAnActiveOrArmedRace_andDeletesNothing() {
        Race race = raceRepository.findById(1L).orElseThrow();
        Checkpoint cp = checkpoint(race.getId());
        Participant p = participant();
        RaceSession s = session(race.getId(), T0, null);
        reading(p, cp, race.getId(), T0.plusMinutes(1));
        result(p, race.getId(), s.getId());

        race.setRaceName("Keep This Name");
        race.setCurrentSessionId(s.getId());
        race.setCurrentAttemptStartedAt(T0);
        race.setStartTime(T0.plusSeconds(3));
        race.setEndTime(T0.plusMinutes(2));
        race.setAutoStartDetected(true);
        RaceEntry rosterRow = new RaceEntry();
        rosterRow.setRaceId(race.getId());
        rosterRow.setParticipantId(p.getId());
        raceEntryRepository.save(rosterRow);

        for (Race.RaceStatus running : new Race.RaceStatus[]{Race.RaceStatus.ACTIVE, Race.RaceStatus.ARMED}) {
            race.setStatus(running);
            raceRepository.save(race);

            assertTrue(reportService.isRunningAttempt(s.getId()));
            assertThrows(IllegalStateException.class, () -> reportService.deleteAttempt(s.getId()),
                    "refused while " + running);
            assertTrue(sessionRepository.findById(s.getId()).isPresent());
            assertEquals(1, resultRepository.findByRaceSessionId(s.getId()).size());
            assertEquals(1, readingRepository.findInAttemptWindow(race.getId(), T0, T0.plusHours(1), T0.plusHours(2)).size());
        }

        // Once the race is FINISHED it may be deleted, and because it is still the race's current
        // attempt the shared reset routine clears the pointers so no view references a missing session.
        race.setStatus(Race.RaceStatus.FINISHED);
        raceRepository.save(race);
        assertFalse(reportService.isRunningAttempt(s.getId()));
        reportService.deleteAttempt(s.getId());

        assertTrue(sessionRepository.findById(s.getId()).isEmpty());
        Race after = raceRepository.findById(1L).orElseThrow();
        assertNull(after.getCurrentSessionId());
        assertNull(after.getCurrentAttemptStartedAt());
        assertNull(after.getStartTime());
        assertNull(after.getEndTime());
        assertFalse(after.getAutoStartDetected());
        assertEquals(Race.RaceStatus.NOT_STARTED, after.getStatus());
        // Delete must not touch the roster or the race name (only Reset/startup clear those).
        assertEquals("Keep This Name", after.getRaceName(), "race name survives a Reports delete");
        assertTrue(raceEntryRepository.existsByRaceIdAndParticipantId(race.getId(), p.getId()),
                "roster survives a Reports delete");
    }

    @Test
    void deleteAttempt_ofAnotherRace_clearsThatRacesPointers_notRace1() {
        Race race1 = raceRepository.findById(1L).orElseThrow();
        race1.setStatus(Race.RaceStatus.ACTIVE);
        race1.setRaceName("Race One");
        raceRepository.save(race1);

        Race other = newRace(Race.RaceStatus.FINISHED);
        RaceSession s = session(other.getId(), T0, T0.plusMinutes(5));
        other.setCurrentSessionId(s.getId());
        other.setCurrentAttemptStartedAt(T0);
        raceRepository.save(other);

        reportService.deleteAttempt(s.getId());

        assertNull(raceRepository.findById(other.getId()).orElseThrow().getCurrentSessionId());
        Race still1 = raceRepository.findById(1L).orElseThrow();
        assertEquals(Race.RaceStatus.ACTIVE, still1.getStatus(), "race 1 is untouched");
        assertEquals("Race One", still1.getRaceName());
    }
}
