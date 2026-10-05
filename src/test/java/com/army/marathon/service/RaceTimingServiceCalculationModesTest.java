package com.army.marathon.service;

import com.army.marathon.model.Checkpoint;
import com.army.marathon.model.CheckpointReading;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end coverage of every Race.CalculationMode against a real database,
 * wrapped in a rolled-back transaction so none of the fixture data (races,
 * checkpoints, participants, readings, results) survives the test run or
 * touches real race data. Exercises the actual RaceTimingService entry point
 * used by the Live Tracking "Calculate" button, not a mocked shortcut.
 */
@SpringBootTest
@Transactional
@Rollback
class RaceTimingServiceCalculationModesTest {

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

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 1, 1, 9, 0, 0);
    private long tagCounter = 0;

    private Race createRace(Race.CalculationMode mode) {
        Race race = new Race();
        race.setRaceName("JUNIT-" + mode + "-" + System.nanoTime());
        race.setCalculationMode(mode);
        race.setStatus(Race.RaceStatus.ACTIVE);
        return withAttempt(raceRepository.save(race));
    }

    private Checkpoint createCheckpoint(Long raceId, String name, int locationNumber, int displayOrder,
                                         boolean startLine, boolean finishLine) {
        Checkpoint cp = new Checkpoint();
        cp.setRaceId(raceId);
        cp.setLocationName(name);
        cp.setLocationNumber(locationNumber);
        cp.setDisplayOrder(displayOrder);
        cp.setEnabled(true);
        cp.setStartLine(startLine);
        cp.setFinishLine(finishLine);
        return checkpointRepository.save(cp);
    }

    private String nextTag() {
        return "JUNIT-TAG-" + System.nanoTime() + "-" + (tagCounter++);
    }

    private Participant createParticipant(String tag) {
        Participant p = new Participant();
        p.setRfidTag(tag);
        p.setName("Runner " + tag);
        p.setServiceNumber(tag + "-SVC");
        p.setSerialNo(tag);
        p.setCategory("Open");
        return participantRepository.save(p);
    }

    private void reading(String tag, Long raceId, Checkpoint checkpoint, LocalDateTime time) {
        CheckpointReading r = new CheckpointReading();
        r.setRfidTag(tag);
        r.setCheckpoint(checkpoint);
        r.setCheckpointName(checkpoint.getName());
        r.setRaceId(raceId);
        r.setReadTime(time);
        r.setDeviceId("JUNIT-DEVICE");
        readingRepository.save(r);
    }

    private RaceResult resultFor(Long raceId, String tag) {
        return resultRepository.findByRaceId(raceId).stream()
                .filter(r -> tag.equals(r.getRfidTag()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No result saved for tag " + tag));
    }

    @Test
    void twoCheckpointMode_finisherAndDnf() {
        Race race = createRace(Race.CalculationMode.TWO_CHECKPOINT);
        Checkpoint start = createCheckpoint(race.getId(), "Start", 1, 1, true, false);
        Checkpoint finish = createCheckpoint(race.getId(), "Finish", 2, 2, false, true);

        String finisherTag = nextTag();
        createParticipant(finisherTag);
        reading(finisherTag, race.getId(), start, BASE);
        reading(finisherTag, race.getId(), finish, BASE.plusMinutes(30));

        String dnfTag = nextTag();
        createParticipant(dnfTag);
        reading(dnfTag, race.getId(), start, BASE);

        timingService.calculateRaceResults(race.getId(), Race.CalculationMode.TWO_CHECKPOINT);
        timingService.assignRankings(race.getId());

        RaceResult finisher = resultFor(race.getId(), finisherTag);
        assertTrue(finisher.isCompletedRace());
        assertEquals(1800L, finisher.getNetTimeSeconds());
        assertEquals("00:30:00", finisher.getFormattedTime());
        assertEquals(1, finisher.getOverallRank());

        RaceResult dnf = resultFor(race.getId(), dnfTag);
        assertFalse(dnf.isCompletedRace());
        assertEquals("DNF", dnf.getFormattedTime());
    }

    @Test
    void fourCheckpointMode_startTwoMiddleFinish() {
        Race race = createRace(Race.CalculationMode.FOUR_CHECKPOINT);
        Checkpoint start = createCheckpoint(race.getId(), "Start", 1, 1, true, false);
        Checkpoint mid1 = createCheckpoint(race.getId(), "CP1", 2, 2, false, false);
        Checkpoint mid2 = createCheckpoint(race.getId(), "CP2", 3, 3, false, false);
        Checkpoint finish = createCheckpoint(race.getId(), "Finish", 4, 4, false, true);

        String tag = nextTag();
        createParticipant(tag);
        reading(tag, race.getId(), start, BASE);
        reading(tag, race.getId(), mid1, BASE.plusMinutes(10));
        reading(tag, race.getId(), mid2, BASE.plusMinutes(20));
        reading(tag, race.getId(), finish, BASE.plusMinutes(45));

        timingService.calculateRaceResults(race.getId(), Race.CalculationMode.FOUR_CHECKPOINT);

        RaceResult result = resultFor(race.getId(), tag);
        assertTrue(result.isCompletedRace());
        assertEquals(2700L, result.getNetTimeSeconds());
    }

    @Test
    void sameStartFinishMode_firstReadStartSecondReadFinish() {
        Race race = createRace(Race.CalculationMode.SAME_START_FINISH);

        String tag = nextTag();
        createParticipant(tag);
        // SAME_START_FINISH matches purely by read order for the tag/race, no checkpoint needed
        Checkpoint gate = createCheckpoint(race.getId(), "Gate", 1, 1, true, true);
        reading(tag, race.getId(), gate, BASE);
        reading(tag, race.getId(), gate, BASE.plusMinutes(20));

        timingService.calculateRaceResults(race.getId(), Race.CalculationMode.SAME_START_FINISH);

        RaceResult result = resultFor(race.getId(), tag);
        assertTrue(result.isCompletedRace());
        assertEquals(1200L, result.getNetTimeSeconds());
        assertEquals("00:20:00", result.getFormattedTime());
    }

    @Test
    void lapRaceMode_needsLapCountPlusOneCrossings_lastReadingIsFinishNotLapCountTh() {
        // A 3-lap race needs 4 total crossings of the lap point: #1 is the start (elapsed zero,
        // not a completed lap), #2 completes Lap 1, #3 completes Lap 2, #4 completes Lap 3 and is
        // also the finish. This mode used to require only lapCount (3) readings - completing a
        // 3-lap race off 3 scans and mislabeling the very first scan as "Lap 1" - confirmed from a
        // real result whose Total Time exactly equalled Lap3Time minus Lap1Time.
        Race race = createRace(Race.CalculationMode.LAP_RACE);
        race.setLapCount(3);
        race.setLapDistanceKm(1.6);
        raceRepository.save(race);

        Checkpoint lapPoint = createCheckpoint(race.getId(), "LapPoint", 1, 1, true, true);

        String finisherTag = nextTag();
        createParticipant(finisherTag);
        reading(finisherTag, race.getId(), lapPoint, BASE);                 // crossing #1: start
        reading(finisherTag, race.getId(), lapPoint, BASE.plusMinutes(10)); // crossing #2: Lap 1
        reading(finisherTag, race.getId(), lapPoint, BASE.plusMinutes(20)); // crossing #3: Lap 2
        reading(finisherTag, race.getId(), lapPoint, BASE.plusMinutes(35)); // crossing #4: Lap 3 / finish

        // Exactly one crossing short of the required 4 (lapCount + 1) - this is precisely the old
        // bug's boundary: under the old code this alone would complete the race off 3 scans.
        String dnfTag = nextTag();
        createParticipant(dnfTag);
        reading(dnfTag, race.getId(), lapPoint, BASE);
        reading(dnfTag, race.getId(), lapPoint, BASE.plusMinutes(10));
        reading(dnfTag, race.getId(), lapPoint, BASE.plusMinutes(20));

        timingService.calculateRaceResults(race.getId(), Race.CalculationMode.LAP_RACE);

        RaceResult finisher = resultFor(race.getId(), finisherTag);
        assertTrue(finisher.isCompletedRace());
        assertEquals(2100L, finisher.getNetTimeSeconds(), "Total Time must span the full race - start (crossing #1) to finish (crossing #4) - not a partial window");
        assertEquals("00:35:00", finisher.getFormattedTime());
        // Splits are each lap's own duration (crossing N - crossing N-1), not elapsed-since-start -
        // they must sum exactly to Total Time.
        assertEquals("{Lap 1=00:10:00, Lap 2=00:10:00, Lap 3=00:15:00}", finisher.getSplitTimesJson());

        RaceResult dnf = resultFor(race.getId(), dnfTag);
        assertFalse(dnf.isCompletedRace(),
                "3 crossings for a 3-lap race is one short of the required 4 (lapCount + 1) - must be DNF, not silently completed");
    }

    @Test
    void longDistanceMode_autoDetectedStartPlusMidpointPlusFinish() {
        Race race = createRace(Race.CalculationMode.LONG_DISTANCE_MANUAL_START);
        race.setStartTime(BASE);
        raceRepository.save(race);

        Checkpoint midpoint = createCheckpoint(race.getId(), "Midpoint", 1, 1, false, false);
        Checkpoint finish = createCheckpoint(race.getId(), "Finish", 2, 2, false, true);

        String tag = nextTag();
        createParticipant(tag);
        reading(tag, race.getId(), midpoint, BASE.plusMinutes(15));
        reading(tag, race.getId(), finish, BASE.plusMinutes(30));

        timingService.calculateRaceResults(race.getId(), Race.CalculationMode.LONG_DISTANCE_MANUAL_START);

        RaceResult result = resultFor(race.getId(), tag);
        assertTrue(result.isCompletedRace());
        assertEquals(1800L, result.getNetTimeSeconds());
        assertTrue(result.getSplitTimesJson().contains("Midpoint"));
    }

    /**
     * Regression test for the auto-start wiring: a start-line checkpoint exists
     * purely to trigger MultiReaderService's auto-start detection (race.startTime
     * set from the first scan, no manual entry), and must NOT be picked up as the
     * midpoint. Long Distance mode must accept this auto-detected start instead
     * of demanding a manually-typed one.
     */
    @Test
    void longDistanceMode_usesAutoDetectedStartTime_ignoresStartLineCheckpointAsSplit() {
        Race race = createRace(Race.CalculationMode.LONG_DISTANCE_MANUAL_START);
        race.setManualStartTime(null);
        race.setStartTime(BASE);
        race.setAutoStartDetected(true);
        raceRepository.save(race);

        Checkpoint startTrigger = createCheckpoint(race.getId(), "StartTrigger", 0, 1, true, false);
        Checkpoint midpoint = createCheckpoint(race.getId(), "Midpoint", 1, 2, false, false);
        Checkpoint finish = createCheckpoint(race.getId(), "Finish", 2, 3, false, true);

        String tag = nextTag();
        createParticipant(tag);
        // No reading at startTrigger on purpose - only the auto-detected race.startTime is used.
        reading(tag, race.getId(), midpoint, BASE.plusMinutes(15));
        reading(tag, race.getId(), finish, BASE.plusMinutes(30));

        timingService.calculateRaceResults(race.getId(), Race.CalculationMode.LONG_DISTANCE_MANUAL_START);

        RaceResult result = resultFor(race.getId(), tag);
        assertTrue(result.isCompletedRace());
        assertEquals(1800L, result.getNetTimeSeconds());
    }

    @Test
    void longDistanceMode_throwsClearErrorWhenNoStartTimeAvailable() {
        Race race = createRace(Race.CalculationMode.LONG_DISTANCE_MANUAL_START);
        // No manual start time, no auto-detected start time either.
        createCheckpoint(race.getId(), "Midpoint", 1, 1, false, false);
        createCheckpoint(race.getId(), "Finish", 2, 2, false, true);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> timingService.calculateRaceResults(race.getId(), Race.CalculationMode.LONG_DISTANCE_MANUAL_START));
        assertTrue(ex.getMessage().toLowerCase().contains("start time"));
    }

    @Test
    void longDistance2Mode_startTwoMidpointsFinish() {
        Race race = createRace(Race.CalculationMode.LONG_DISTANCE_2_MANUAL_START);
        race.setStartTime(BASE);
        raceRepository.save(race);

        Checkpoint mid1 = createCheckpoint(race.getId(), "Midpoint1", 1, 1, false, false);
        Checkpoint mid2 = createCheckpoint(race.getId(), "Midpoint2", 2, 2, false, false);
        Checkpoint finish = createCheckpoint(race.getId(), "Finish", 3, 3, false, true);

        String finisherTag = nextTag();
        createParticipant(finisherTag);
        reading(finisherTag, race.getId(), mid1, BASE.plusMinutes(10));
        reading(finisherTag, race.getId(), mid2, BASE.plusMinutes(20));
        reading(finisherTag, race.getId(), finish, BASE.plusMinutes(40));

        // Skips Midpoint2 entirely - must count as DNF even though Finish was read.
        String dnfTag = nextTag();
        createParticipant(dnfTag);
        reading(dnfTag, race.getId(), mid1, BASE.plusMinutes(10));
        reading(dnfTag, race.getId(), finish, BASE.plusMinutes(40));

        timingService.calculateRaceResults(race.getId(), Race.CalculationMode.LONG_DISTANCE_2_MANUAL_START);

        RaceResult finisher = resultFor(race.getId(), finisherTag);
        assertTrue(finisher.isCompletedRace());
        assertEquals(2400L, finisher.getNetTimeSeconds());
        assertEquals("00:40:00", finisher.getFormattedTime());
        assertTrue(finisher.getSplitTimesJson().contains("Midpoint 1"));
        assertTrue(finisher.getSplitTimesJson().contains("Midpoint 2"));

        RaceResult dnf = resultFor(race.getId(), dnfTag);
        assertFalse(dnf.isCompletedRace());
        assertEquals("DNF", dnf.getFormattedTime());
    }

    @Test
    void longDistance2Mode_throwsWhenOnlyOneMidpointConfigured() {
        Race race = createRace(Race.CalculationMode.LONG_DISTANCE_2_MANUAL_START);
        race.setStartTime(BASE);
        raceRepository.save(race);

        createCheckpoint(race.getId(), "OnlyMidpoint", 1, 1, false, false);
        createCheckpoint(race.getId(), "Finish", 2, 2, false, true);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> timingService.calculateRaceResults(race.getId(), Race.CalculationMode.LONG_DISTANCE_2_MANUAL_START));
        assertTrue(ex.getMessage().toLowerCase().contains("2 midpoint"));
    }

    /** Section 7: no manual override in the long-distance modes - a stray manual time is ignored. */
    @Test
    void longDistanceModes_ignoreManualStartTime_andRefuseToRunOnItAlone() {
        Race race = createRace(Race.CalculationMode.LONG_DISTANCE_MANUAL_START);
        race.setManualStartTime(BASE.plusHours(5)); // e.g. left over from the manual-start mode
        raceRepository.save(race);
        Checkpoint midpoint = createCheckpoint(race.getId(), "Midpoint", 1, 1, false, false);
        Checkpoint finish = createCheckpoint(race.getId(), "Finish", 2, 2, false, true);
        String tag = nextTag();
        createParticipant(tag);
        reading(tag, race.getId(), midpoint, BASE.plusMinutes(15));
        reading(tag, race.getId(), finish, BASE.plusMinutes(30));

        // Only a manual time exists -> must refuse, not silently use it
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> timingService.calculateRaceResults(race.getId(), Race.CalculationMode.LONG_DISTANCE_MANUAL_START));
        assertTrue(ex.getMessage().toLowerCase().contains("start time not detected"), ex.getMessage());

        // Once the auto-detected start exists, that - not the manual value - is used
        race.setStartTime(BASE);
        raceRepository.save(race);
        timingService.calculateRaceResults(race.getId(), Race.CalculationMode.LONG_DISTANCE_MANUAL_START);
        assertEquals(1800L, resultFor(race.getId(), tag).getNetTimeSeconds());
        assertEquals(BASE, resultFor(race.getId(), tag).getStartTime());
    }

    /** Section 7: the new mode - Declared Start (gun) and each runner's own start scan (chip) coexist. */
    @Test
    void manualStartDualTiming_tracksDeclaredStartAndOwnStartScan() {
        Race race = createRace(Race.CalculationMode.MANUAL_START_DUAL_TIMING);
        race.setManualStartTime(BASE); // Declared Start 09:00:00 for everybody
        raceRepository.save(race);
        Checkpoint start = createCheckpoint(race.getId(), "Start", 1, 1, true, false);
        Checkpoint finish = createCheckpoint(race.getId(), "Finish", 2, 2, false, true);

        // Crosses the start line 2 min after the gun, finishes 32 min after the gun
        String finisherTag = nextTag();
        createParticipant(finisherTag);
        reading(finisherTag, race.getId(), start, BASE.plusMinutes(2));
        reading(finisherTag, race.getId(), finish, BASE.plusMinutes(32));

        // Started but never finished
        String noFinishTag = nextTag();
        createParticipant(noFinishTag);
        reading(noFinishTag, race.getId(), start, BASE.plusMinutes(1));

        // Finish scan but no start scan -> not COMPLETED
        String noStartTag = nextTag();
        createParticipant(noStartTag);
        reading(noStartTag, race.getId(), finish, BASE.plusMinutes(40));

        timingService.calculateRaceResults(race.getId(), Race.CalculationMode.MANUAL_START_DUAL_TIMING);

        RaceResult finisher = resultFor(race.getId(), finisherTag);
        assertTrue(finisher.isCompletedRace());
        assertEquals(BASE, finisher.getDeclaredStartTime());
        assertEquals(BASE.plusMinutes(2), finisher.getStartTime(), "Start Time = own scan, NOT the declared start");
        assertEquals(BASE.plusMinutes(32), finisher.getFinishTime());
        assertEquals(1800L, finisher.getNetTimeSeconds(), "Duration (Start Time) = finish - own start scan");
        assertEquals("00:30:00", finisher.getFormattedTime());
        // Duration (Declared Start) = finish - declared start, derived from the stored values
        assertEquals(1920L, java.time.Duration.between(finisher.getDeclaredStartTime(), finisher.getFinishTime()).getSeconds());
        assertNull(finisher.getOverallRank(), "this mode assigns no ranks");

        RaceResult noFinish = resultFor(race.getId(), noFinishTag);
        assertFalse(noFinish.isCompletedRace());
        assertEquals(BASE.plusMinutes(1), noFinish.getStartTime());
        assertNull(noFinish.getFinishTime());
        assertEquals("DNF", noFinish.getFormattedTime());

        RaceResult noStart = resultFor(race.getId(), noStartTag);
        assertFalse(noStart.isCompletedRace());
        assertNull(noStart.getStartTime());
        assertEquals(BASE.plusMinutes(40), noStart.getFinishTime());
        assertEquals(BASE, noStart.getDeclaredStartTime());
    }

    @Test
    void manualStartDualTiming_requiresDeclaredStart_andSeparateStartAndFinishCheckpoints() {
        Race race = createRace(Race.CalculationMode.MANUAL_START_DUAL_TIMING);
        createCheckpoint(race.getId(), "Start", 1, 1, true, false);
        createCheckpoint(race.getId(), "Finish", 2, 2, false, true);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> timingService.calculateRaceResults(race.getId(), Race.CalculationMode.MANUAL_START_DUAL_TIMING));
        assertTrue(ex.getMessage().contains("Declared Start"), ex.getMessage());

        Race onePoint = createRace(Race.CalculationMode.MANUAL_START_DUAL_TIMING);
        onePoint.setManualStartTime(BASE);
        raceRepository.save(onePoint);
        createCheckpoint(onePoint.getId(), "Only", 1, 1, true, true);
        ex = assertThrows(RuntimeException.class,
                () -> timingService.calculateRaceResults(onePoint.getId(), Race.CalculationMode.MANUAL_START_DUAL_TIMING));
        assertTrue(ex.getMessage().contains("separate finish checkpoint"), ex.getMessage());
    }

    /** The Configuration page creates checkpoints with no race id - they must still be found. */
    @Test
    void checkpointsWithoutARaceId_areUsedWhenTheRaceHasNoneOfItsOwn() {
        Race race = createRace(Race.CalculationMode.TWO_CHECKPOINT);
        Checkpoint global = createCheckpoint(null, "GlobalStart", 1, 1, true, false);

        List<Checkpoint> found = timingService.enabledCheckpointsFor(race.getId());
        assertTrue(found.stream().anyMatch(cp -> cp.getId().equals(global.getId())),
                "an enabled checkpoint with no race id must apply to the race");

        // ...but once the race has checkpoints of its own, only those apply
        Checkpoint own = createCheckpoint(race.getId(), "OwnStart", 1, 1, true, false);
        List<Checkpoint> foundNow = timingService.enabledCheckpointsFor(race.getId());
        assertEquals(List.of(own.getId()), foundNow.stream().map(Checkpoint::getId).toList());
    }
}
