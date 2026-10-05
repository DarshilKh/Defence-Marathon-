package com.army.marathon.service;

import com.army.marathon.model.Checkpoint;
import com.army.marathon.model.CheckpointReading;
import com.army.marathon.model.Participant;
import com.army.marathon.model.Race;
import com.army.marathon.model.RaceResult;
import com.army.marathon.model.RaceSession;
import com.army.marathon.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/** QA adversarial checks for ReportService.deleteAttempt. Throwaway database only; rolled back. */
@SpringBootTest
@Transactional
@Rollback
class ReportDeleteAdversarialTest {

    @Autowired private ReportService reportService;
    @Autowired private RaceRepository raceRepository;
    @Autowired private RaceSessionRepository sessionRepository;
    @Autowired private RaceResultRepository resultRepository;
    @Autowired private CheckpointReadingRepository readingRepository;
    @Autowired private CheckpointRepository checkpointRepository;
    @Autowired private ParticipantRepository participantRepository;

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 3, 1, 9, 0, 0);

    private Race race(Race.RaceStatus st) {
        Race r = new Race();
        r.setRaceName("QA-" + System.nanoTime());
        r.setCalculationMode(Race.CalculationMode.TWO_CHECKPOINT);
        r.setStatus(st);
        return raceRepository.save(r);
    }

    private RaceSession session(Long raceId, LocalDateTime s, LocalDateTime e) {
        RaceSession x = new RaceSession();
        x.setRaceId(raceId);
        x.setRaceName("QA-S");
        x.setStartedAt(s);
        x.setEndedAt(e);
        return sessionRepository.save(x);
    }

    private Participant p() {
        String u = "QA" + System.nanoTime();
        Participant q = new Participant();
        q.setRfidTag(u);
        q.setSerialNo(u);
        q.setName(u);
        return participantRepository.save(q);
    }

    private Checkpoint cp(Long raceId) {
        Checkpoint c = new Checkpoint();
        c.setRaceId(raceId);
        c.setLocationName("QA-CP" + System.nanoTime());
        c.setLocationNumber(1);
        c.setDisplayOrder(1);
        c.setEnabled(true);
        return checkpointRepository.save(c);
    }

    private void rd(Participant q, Checkpoint c, Long raceId, LocalDateTime at) {
        CheckpointReading r = new CheckpointReading();
        r.setRfidTag(q.getRfidTag());
        r.setParticipant(q);
        r.setCheckpoint(c);
        r.setCheckpointName(c.getName());
        r.setRaceId(raceId);
        r.setReadTime(at);
        r.setDeviceId("QA");
        readingRepository.save(r);
    }

    private void res(Participant q, Long raceId, Long sid) {
        RaceResult r = new RaceResult();
        r.setParticipant(q);
        r.setRaceId(raceId);
        r.setRaceSessionId(sid);
        r.setRfidTag(q.getRfidTag());
        r.setCheckpointCount(2);
        r.setCompletedRace(true);
        resultRepository.save(r);
    }

    @Test
    void identicalReadTimeOnBoundary_belongsToNextSessionOnly() {
        Race r = race(Race.RaceStatus.FINISHED);
        Participant q = p(); Checkpoint c = cp(r.getId());
        LocalDateTime boundary = T0.plusMinutes(10);
        RaceSession a = session(r.getId(), T0, boundary);          // endedAt == B.startedAt (overlap edge)
        RaceSession b = session(r.getId(), boundary, boundary.plusMinutes(10));
        rd(q, c, r.getId(), T0.plusMinutes(1));
        rd(q, c, r.getId(), boundary);                              // ambiguous reading
        rd(q, c, r.getId(), boundary.plusMinutes(1));

        assertEquals(1, reportService.previewAttempt(a.getId()).readings(), "A must not claim the boundary reading");
        assertEquals(2, reportService.previewAttempt(b.getId()).readings());
        assertEquals(1, reportService.deleteAttempt(a.getId()).readings());
        assertEquals(2, readingRepository.findByRaceIdOrderByReadTimeAsc(r.getId()).size());
        assertTrue(sessionRepository.findById(b.getId()).isPresent());
    }

    @Test
    void openSessionWhileRaceFinished_isDeletableAndBoundedByNextSession() {
        Race r = race(Race.RaceStatus.FINISHED);
        Participant q = p(); Checkpoint c = cp(r.getId());
        RaceSession a = session(r.getId(), T0, null);               // endedAt null, race FINISHED
        RaceSession b = session(r.getId(), T0.plusMinutes(10), T0.plusMinutes(20));
        rd(q, c, r.getId(), T0.plusMinutes(2));
        rd(q, c, r.getId(), T0.plusMinutes(12));                    // belongs to B
        res(q, r.getId(), a.getId());
        res(q, r.getId(), b.getId());

        assertFalse(reportService.isRunningAttempt(a.getId()));
        var counts = reportService.deleteAttempt(a.getId());
        assertEquals(1, counts.readings());
        assertEquals(1, counts.results());
        assertEquals(1, readingRepository.findByRaceIdOrderByReadTimeAsc(r.getId()).size());
        assertEquals(1, resultRepository.findByRaceSessionId(b.getId()).size());
    }

    @Test
    void openSessionWhileRaceActive_isRefused_nothingDeleted() {
        Race r = race(Race.RaceStatus.ACTIVE);
        Participant q = p(); Checkpoint c = cp(r.getId());
        RaceSession a = session(r.getId(), T0, null);
        r.setCurrentSessionId(a.getId());
        r.setCurrentAttemptStartedAt(T0);
        raceRepository.save(r);
        rd(q, c, r.getId(), T0.plusMinutes(1));
        assertThrows(IllegalStateException.class, () -> reportService.deleteAttempt(a.getId()));
        assertEquals(1, readingRepository.findByRaceIdOrderByReadTimeAsc(r.getId()).size());
        assertTrue(sessionRepository.findById(a.getId()).isPresent());
    }

    @Test
    void deletingCurrentFinishedAttempt_resetsRace1AndRemovesOnlyThatAttempt() {
        Race r = raceRepository.findById(1L).orElseGet(() -> race(Race.RaceStatus.NOT_STARTED));
        Long rid = r.getId();
        Participant q = p(); Checkpoint c = cp(rid);
        LocalDateTime base = LocalDateTime.of(2031, 1, 1, 9, 0);
        RaceSession old = session(rid, base, base.plusMinutes(5));
        RaceSession cur = session(rid, base.plusHours(1), base.plusHours(1).plusMinutes(5));
        rd(q, c, rid, base.plusMinutes(1));
        rd(q, c, rid, base.plusHours(1).plusMinutes(1));
        res(q, rid, old.getId());
        res(q, rid, cur.getId());
        r.setStatus(Race.RaceStatus.FINISHED);
        r.setRaceName("X");
        r.setCurrentSessionId(cur.getId());
        r.setCurrentAttemptStartedAt(cur.getStartedAt());
        raceRepository.save(r);

        var counts = reportService.deleteAttempt(cur.getId());
        assertEquals(1, counts.readings());
        assertEquals(1, counts.results());
        Race after = raceRepository.findById(rid).get();
        assertFalse(after.hasCurrentAttempt());
        assertEquals(Race.RaceStatus.NOT_STARTED, after.getStatus());
        assertTrue(sessionRepository.findById(old.getId()).isPresent());
        assertFalse(sessionRepository.findById(cur.getId()).isPresent());
        assertEquals(1, resultRepository.findByRaceSessionId(old.getId()).size());
        assertEquals(1, readingRepository.findByRaceIdOrderByReadTimeAsc(rid).stream()
                .filter(x -> x.getReadTime().isBefore(base.plusHours(2)) && x.getReadTime().isAfter(base.minusHours(1))).count());
    }

    @Test
    void readingsOfAnotherRaceId_inSameWindow_areUntouched() {
        Race r1 = race(Race.RaceStatus.FINISHED);
        Race r2 = race(Race.RaceStatus.FINISHED);
        Participant q = p(); Checkpoint c1 = cp(r1.getId()); Checkpoint c2 = cp(r2.getId());
        RaceSession a = session(r1.getId(), T0, T0.plusMinutes(10));
        rd(q, c1, r1.getId(), T0.plusMinutes(3));
        rd(q, c2, r2.getId(), T0.plusMinutes(3));
        assertEquals(1, reportService.deleteAttempt(a.getId()).readings());
        assertEquals(1, readingRepository.findByRaceIdOrderByReadTimeAsc(r2.getId()).size());
    }
}
