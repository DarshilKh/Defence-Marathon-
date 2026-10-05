package com.army.marathon.service;

import com.army.marathon.model.Checkpoint;
import com.army.marathon.model.Participant;
import com.army.marathon.model.Race;
import com.army.marathon.model.RaceResult;
import com.army.marathon.repository.CheckpointReadingRepository;
import com.army.marathon.repository.CheckpointRepository;
import com.army.marathon.repository.ParticipantRepository;
import com.army.marathon.repository.RaceRepository;
import com.army.marathon.repository.RaceResultRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The whole race-day sequence, once per mode, through the real services (no hardware, so the reader
 * connect step is stood in for by armReaders(raceId, List.of()) which only records the race):
 *
 * <pre>
 *   Start Race  -> race ARMED, readers listening
 *   first scan at the start-line checkpoint -> race ACTIVE, official start = that scan (auto-detected)
 *   scans along the course, Stop Race       -> FINISHED (what enables Calculate Results)
 *   Calculate Results                       -> results in the shape that mode needs
 * </pre>
 *
 * Runs in a rolled-back transaction, like the other service tests, so no race data survives.
 */
@SpringBootTest
@Transactional
@Rollback
class RaceDayFlowTest {

    @Autowired private MultiReaderService multiReaderService;
    @Autowired private RaceTimingService timingService;
    @Autowired private RaceRepository raceRepository;

    @Autowired private com.army.marathon.repository.RaceSessionRepository raceSessionRepository;

    /**
     * Calculating results now requires a current attempt (a RaceSession plus its start boundary) -
     * with none, calculateRaceResults refuses rather than treating "no boundary" as "every reading
     * on file". The boundary is set well before any fixture reading so all of them are in scope.
     */
    private Race withAttempt(Race race) {
        com.army.marathon.model.RaceSession session = new com.army.marathon.model.RaceSession();
        session.setRaceId(race.getId());
        session.setRaceName(race.getRaceName());
        session.setStartedAt(LocalDateTime.of(2000, 1, 1, 0, 0));
        session = raceSessionRepository.save(session);
        race.setCurrentSessionId(session.getId());
        race.setCurrentAttemptStartedAt(session.getStartedAt());
        return raceRepository.save(race);
    }
    @Autowired private CheckpointRepository checkpointRepository;
    @Autowired private ParticipantRepository participantRepository;
    @Autowired private CheckpointReadingRepository readingRepository;
    @Autowired private RaceResultRepository resultRepository;

    private static final LocalDateTime GUN = LocalDateTime.of(2026, 3, 8, 6, 0, 0);
    private long counter = 0;

    private Race newRace(Race.CalculationMode mode) {
        Race race = new Race();
        race.setRaceName("JUNIT-FLOW-" + mode + "-" + System.nanoTime());
        race.setCalculationMode(mode);
        race.setStatus(Race.RaceStatus.NOT_STARTED);
        return withAttempt(raceRepository.save(race));
    }

    private Checkpoint checkpoint(Long raceId, String name, int order, boolean start, boolean finish) {
        Checkpoint cp = new Checkpoint();
        cp.setRaceId(raceId);
        cp.setLocationName(name);
        cp.setLocationNumber(order);
        cp.setDisplayOrder(order);
        cp.setEnabled(true);
        cp.setStartLine(start);
        cp.setFinishLine(finish);
        return checkpointRepository.save(cp);
    }

    private Participant runner(String name) {
        String unique = System.nanoTime() + "-" + (counter++);
        Participant p = new Participant();
        p.setName(name);
        p.setRfidTag("FLOW-CHIP-" + unique);
        p.setSerialNo("FLOW-TN-" + unique);
        return participantRepository.save(p);
    }

    private void scan(Participant p, Checkpoint cp, LocalDateTime at) {
        multiReaderService.handleTagRead(p.getRfidTag(), cp, at, "READER_A");
    }

    /** "Start Race": readers listening, race ARMED, no start time yet. */
    private Race startRace(Race race) {
        multiReaderService.armReaders(race.getId(), List.of());
        race.setStatus(Race.RaceStatus.ARMED);
        return raceRepository.save(race);
    }

    /** "Stop Race": what handleStopRace records. */
    private Race stopRace(Long raceId, LocalDateTime at) {
        Race race = raceRepository.findById(raceId).orElseThrow();
        race.setStatus(Race.RaceStatus.FINISHED);
        race.setEndTime(at);
        return raceRepository.save(race);
    }

    private RaceResult resultOf(Long raceId, Participant p) {
        return resultRepository.findByRaceId(raceId).stream()
                .filter(r -> r.getParticipant() != null && r.getParticipant().getId().equals(p.getId()))
                .findFirst().orElseThrow(() -> new AssertionError("no result for " + p.getName()));
    }

    @Test
    void standardMode_twoCheckpoint_startRace_firstScanStarts_stop_calculate_ranks() {
        Race race = newRace(Race.CalculationMode.TWO_CHECKPOINT);
        Checkpoint start = checkpoint(race.getId(), "Start", 1, true, false);
        Checkpoint finish = checkpoint(race.getId(), "Finish", 2, false, true);
        Participant slower = runner("Slower");
        Participant faster = runner("Faster");
        Participant quitter = runner("Quitter");

        // Before Start Race nothing has started and nothing is listening
        assertEquals(Race.RaceStatus.NOT_STARTED, raceRepository.findById(race.getId()).orElseThrow().getStatus());

        race = startRace(race);
        assertEquals(Race.RaceStatus.ARMED, race.getStatus());
        assertNull(race.getStartTime(), "Start Race itself stamps no start time");

        // First valid scan at the start line makes it official
        scan(slower, start, GUN.plusSeconds(5));
        Race active = raceRepository.findById(race.getId()).orElseThrow();
        assertEquals(Race.RaceStatus.ACTIVE, active.getStatus());
        assertEquals(GUN.plusSeconds(5), active.getStartTime());
        assertTrue(active.getAutoStartDetected());

        scan(faster, start, GUN.plusSeconds(9));
        scan(quitter, start, GUN.plusSeconds(11));
        scan(faster, finish, GUN.plusMinutes(28));   // 27:51
        scan(slower, finish, GUN.plusMinutes(30));   // 29:55
        assertEquals(GUN.plusSeconds(5), raceRepository.findById(race.getId()).orElseThrow().getStartTime(),
                "later start-line scans don't move the official start");

        Race stopped = stopRace(race.getId(), GUN.plusMinutes(45));
        assertEquals(Race.RaceStatus.FINISHED, stopped.getStatus());

        timingService.calculateRaceResults(race.getId(), Race.CalculationMode.TWO_CHECKPOINT);
        timingService.assignRankings(race.getId());

        RaceResult first = resultOf(race.getId(), faster);
        RaceResult second = resultOf(race.getId(), slower);
        RaceResult dnf = resultOf(race.getId(), quitter);
        assertTrue(first.isCompletedRace());
        assertEquals(1, first.getOverallRank());
        assertEquals("00:27:51", first.getFormattedTime());
        assertEquals(2, second.getOverallRank());
        assertFalse(dnf.isCompletedRace());
        assertNull(dnf.getOverallRank(), "a DNF gets no rank");
        assertNull(first.getDeclaredStartTime(), "the standard modes carry no declared start");
    }

    @Test
    void manualStartMode_declaredStart_startRace_scans_stop_calculate_dualTimings() {
        Race race = newRace(Race.CalculationMode.MANUAL_START_DUAL_TIMING);
        Checkpoint start = checkpoint(race.getId(), "Start", 1, true, false);
        Checkpoint finish = checkpoint(race.getId(), "Finish", 2, false, true);
        Participant one = runner("One");
        Participant two = runner("Two");
        Participant dnfRunner = runner("Dnf");

        // Operator presses Set: the declared start is a stamped date+time on the race
        race.setManualStartTime(GUN);
        raceRepository.save(race);

        race = startRace(race);
        assertEquals(GUN, race.getManualStartTime());

        // The gun goes at 06:00:00; runners cross the start mat over the next minute or so
        scan(one, start, GUN.plusSeconds(20));
        Race active = raceRepository.findById(race.getId()).orElseThrow();
        assertEquals(Race.RaceStatus.ACTIVE, active.getStatus(), "start-line scan capture still works alongside the declared start");
        assertEquals(GUN.plusSeconds(20), active.getStartTime());
        assertEquals(GUN, active.getManualStartTime(), "the declared start is not replaced by the scan");

        scan(two, start, GUN.plusSeconds(45));
        scan(dnfRunner, start, GUN.plusSeconds(50));
        scan(one, finish, GUN.plusMinutes(30).plusSeconds(20));   // chip 30:00, gun 30:20
        scan(two, finish, GUN.plusMinutes(31));                   // chip 30:15, gun 31:00

        stopRace(race.getId(), GUN.plusMinutes(60));

        timingService.calculateRaceResults(race.getId(), Race.CalculationMode.MANUAL_START_DUAL_TIMING);

        RaceResult r1 = resultOf(race.getId(), one);
        assertTrue(r1.isCompletedRace());
        assertEquals(GUN, r1.getDeclaredStartTime());
        assertEquals(GUN.plusSeconds(20), r1.getStartTime());
        assertEquals(1800L, r1.getNetTimeSeconds(), "Duration (Start Time)");
        assertEquals(1820L, java.time.Duration.between(r1.getDeclaredStartTime(), r1.getFinishTime()).getSeconds(),
                "Duration (Declared Start)");

        RaceResult r2 = resultOf(race.getId(), two);
        assertEquals(1815L, r2.getNetTimeSeconds());
        assertEquals(1860L, java.time.Duration.between(r2.getDeclaredStartTime(), r2.getFinishTime()).getSeconds());

        RaceResult d = resultOf(race.getId(), dnfRunner);
        assertFalse(d.isCompletedRace());
        assertEquals(GUN.plusSeconds(50), d.getStartTime());
        assertNull(d.getFinishTime());
        assertNull(r1.getOverallRank(), "no ranking in this mode");
    }

    /**
     * A burst of simultaneous reads (a pack of runners crossing the same checkpoint
     * together) must not drop or delay any reading. handleTagRead() is exactly what
     * MultiReaderService's tag-processing executor invokes for every tag the reader SDK
     * hands it (see MultiReaderService.startSingleReader) - fired here directly, back to
     * back with no delay, standing in for the SDK delivering many near-simultaneous reads
     * with no real reader hardware available. Runs on this test's single thread/
     * transaction deliberately - it's what MultiReaderService's own single-threaded
     * executor does for real (processes every callback one at a time, off the SDK's
     * thread), so a tight sequential loop is the faithful simulation, not a multi-threaded
     * one (which would also race against this test class's @Transactional/@Rollback
     * wrapper being bound to a single connection on this thread).
     */
    @Test
    void burstOfSimultaneousReads_noneDroppedOrDuplicated() {
        Race race = newRace(Race.CalculationMode.TWO_CHECKPOINT);
        Checkpoint start = checkpoint(race.getId(), "Start", 1, true, false);
        startRace(race);

        int runnerCount = 60;
        List<Participant> runners = new java.util.ArrayList<>();
        for (int i = 0; i < runnerCount; i++) {
            runners.add(runner("Burst-" + i));
        }

        LocalDateTime burstTime = GUN.plusSeconds(5);
        long startNanos = System.nanoTime();
        for (Participant p : runners) {
            scan(p, start, burstTime);
        }
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        long savedReadings = readingRepository.findByRaceIdOrderByReadTimeAsc(race.getId()).stream()
                .filter(r -> "Start".equals(r.getCheckpointName()))
                .count();
        assertEquals(runnerCount, savedReadings, "every read in the burst must be saved - none dropped");

        for (Participant p : runners) {
            assertEquals(1, readingRepository.findByRfidTagAndRaceIdOrderByReadTimeAsc(p.getRfidTag(), race.getId()).size(),
                    "each runner must have exactly one reading - no drops and no duplicates from " + p.getRfidTag());
        }

        System.out.println("[burst test] " + runnerCount + " simultaneous reads processed in " + elapsedMs + "ms");
    }

    /**
     * Reader A and Reader B at the same checkpoint both picking up the same tag within
     * the duplicate window must still collapse to a single saved reading - the DB-backed
     * duplicate check (readingRepository.findTopBy...ReadTimeAfter...), not an in-memory
     * per-reader cache, is what's relied on here. See MultiReaderService.handleTagRead
     * STEP 2 and the class-level note confirming no such in-memory cache exists.
     */
    @Test
    void duplicateReadsFromTwoReaders_collapseToOneReading() {
        Race race = newRace(Race.CalculationMode.TWO_CHECKPOINT);
        Checkpoint start = checkpoint(race.getId(), "Start", 1, true, false);
        startRace(race);
        Participant p = runner("DualReader");

        LocalDateTime t = GUN.plusSeconds(5);
        multiReaderService.handleTagRead(p.getRfidTag(), start, t, "READER_A");
        multiReaderService.handleTagRead(p.getRfidTag(), start, t.plusNanos(50_000_000), "READER_B");

        List<com.army.marathon.model.CheckpointReading> readings = readingRepository
                .findByRfidTagAndRaceIdOrderByReadTimeAsc(p.getRfidTag(), race.getId());
        assertEquals(1, readings.size(), "two readers hitting the same tag within the duplicate window must collapse to one reading");
    }
}
