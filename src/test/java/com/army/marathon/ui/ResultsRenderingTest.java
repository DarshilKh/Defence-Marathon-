package com.army.marathon.ui;

import com.army.marathon.model.Checkpoint;
import com.army.marathon.model.CheckpointReading;
import com.army.marathon.model.Participant;
import com.army.marathon.model.Race;
import com.army.marathon.model.RaceResult;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure (no Spring, no database) checks of how results are ordered, ranked and rendered:
 * the standard table's finishing order / rank / winner / Long Distance columns, and the
 * separate Manual Start (dual timing) rows.
 */
class ResultsRenderingTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 1, 1, 9, 0, 0);

    private static Participant person(String name, String tagNo, String category) {
        Participant p = new Participant();
        p.setId((long) tagNo.hashCode());
        p.setName(name);
        p.setSerialNo(tagNo);
        p.setCategory(category);
        p.setRank("Sgt");
        return p;
    }

    private static RaceResult finisher(Participant p, long netSeconds, Integer overallRank) {
        RaceResult r = new RaceResult();
        r.setParticipant(p);
        r.setCompletedRace(true);
        r.setNetTimeSeconds(netSeconds);
        r.setFormattedTime(String.format("%02d:%02d:%02d", netSeconds / 3600, (netSeconds % 3600) / 60, netSeconds % 60));
        r.setOverallRank(overallRank);
        r.setStartTime(BASE);
        r.setFinishTime(BASE.plusSeconds(netSeconds));
        r.setCheckpointCount(2);
        return r;
    }

    private static RaceResult dnf(Participant p, int readings) {
        RaceResult r = new RaceResult();
        r.setParticipant(p);
        r.setCompletedRace(false);
        r.setFormattedTime("DNF");
        r.setCheckpointCount(readings);
        return r;
    }

    // ------------------------------------------------------------------ Section 9

    @Test
    void finishingOrder_isByTime_thenRank_andDnfAndNoShowNeverPrecedeAFinisher() {
        RaceResult slow = finisher(person("Slow", "T1", "Open"), 3000, 3);
        RaceResult fastB = finisher(person("FastB", "T2", "Veteran"), 1800, 2);   // tied with FastA on time...
        RaceResult fastA = finisher(person("FastA", "T3", "Open"), 1800, 1);      // ...but holds rank 1
        RaceResult dnf = dnf(person("Dnf", "T4", "Open"), 1);
        RaceResult noShow = dnf(person("NoShow", "T5", "Open"), 0);

        List<RaceResult> rows = new ArrayList<>(List.of(dnf, slow, noShow, fastB, fastA));
        rows.sort(ResultsRenderer.FINISHING_ORDER);

        assertEquals(List.of("FastA", "FastB", "Slow"),
                rows.subList(0, 3).stream().map(r -> r.getParticipant().getName()).toList(),
                "finishers by net time, tie broken by assigned rank so numbers ascend downwards");
        assertTrue(rows.subList(3, 5).stream().noneMatch(RaceResult::isCompletedRace), "DNF/No-Show come after every finisher");
    }

    @Test
    void winner_isTheSingleBestTimeOverall_notScopedByCategory() {
        // Best time belongs to a "Veteran"; a per-category winner would have picked someone in "Open" too
        RaceResult open = finisher(person("Open Best", "T1", "Open"), 1900, 2);
        RaceResult veteran = finisher(person("Veteran Best", "T2", "Veteran"), 1750, 1);
        RaceResult dnf = dnf(person("Dnf", "T3", "Veteran"), 1);

        Optional<RaceResult> winner = ResultsRenderer.findWinner(List.of(open, dnf, veteran));

        assertTrue(winner.isPresent());
        assertEquals("Veteran Best", winner.get().getParticipant().getName());
        assertTrue(ResultsRenderer.findWinner(List.of(dnf)).isEmpty(), "no finisher, no winner");
    }

    @Test
    void winnerLabel_showsTheName_notTheTagNo_withTagNoOnlyAsAFallback() {
        assertEquals("Asha Rao", ResultsRenderer.displayName(person("Asha Rao", "TAG-77", "Open")));
        assertEquals("Tag No TAG-77", ResultsRenderer.displayName(person(null, "TAG-77", "Open")));
        assertEquals("Tag No TAG-77", ResultsRenderer.displayName(person("  ", "TAG-77", "Open")));
    }

    @Test
    void rankColumn_countsOnlyFinishers_dnfShowsNoNumber_statusSaysFinishedOrDnf() {
        List<ResultsRenderer.CheckpointColumn> columns = List.of(
                new ResultsRenderer.CheckpointColumn("Start Line", null),
                new ResultsRenderer.CheckpointColumn("Finish Line", null));

        var finished = new ResultsRenderer.ParticipantResultRow(
                finisher(person("A", "T1", "Open"), 1800, 1), new ArrayList<>(), columns, Race.CalculationMode.TWO_CHECKPOINT);
        var dnfRow = new ResultsRenderer.ParticipantResultRow(
                dnf(person("B", "T2", "Open"), 1), new ArrayList<>(), columns, Race.CalculationMode.TWO_CHECKPOINT);
        var noShow = new ResultsRenderer.ParticipantResultRow(
                dnf(person("C", "T3", "Open"), 0), new ArrayList<>(), columns, Race.CalculationMode.TWO_CHECKPOINT);

        assertEquals(1, finished.getRank());
        assertEquals("Finished", finished.getStatus());
        assertNull(dnfRow.getRank(), "a DNF must not show a rank (not even 0)");
        assertEquals("DNF", dnfRow.getStatus());
        assertNull(noShow.getRank());
        assertEquals("DNF (No-Show)", noShow.getStatus());
        assertEquals("Sgt", finished.getArmyRank());
    }

    // ------------------------------------------------------------------ Section 10

    private static Checkpoint checkpoint(String name, int location) {
        Checkpoint cp = new Checkpoint();
        cp.setLocationName(name);
        cp.setLocationNumber(location);
        return cp;
    }

    private static CheckpointReading scan(Checkpoint cp, LocalDateTime at) {
        CheckpointReading r = new CheckpointReading();
        r.setCheckpoint(cp);
        r.setCheckpointName(cp.getName());
        r.setReadTime(at);
        return r;
    }

    @Test
    void longDistanceColumns_includeTheStartCheckpoint_andReadEachRoleFromItsOwnCheckpoint() {
        // Start sits at location 1 - the old location-number lookup would have shown its scan
        // under the "Midpoint" header and the midpoint's scan under "Finish".
        Checkpoint start = checkpoint("Gate", 1);
        Checkpoint mid = checkpoint("Bridge", 2);
        Checkpoint finish = checkpoint("Stadium", 3);
        List<ResultsRenderer.CheckpointColumn> columns = List.of(
                new ResultsRenderer.CheckpointColumn("Start Line", start),
                new ResultsRenderer.CheckpointColumn("Midpoint", mid),
                new ResultsRenderer.CheckpointColumn("Finish Line", finish));

        List<CheckpointReading> readings = new ArrayList<>(List.of(
                scan(start, BASE.plusMinutes(1)), scan(mid, BASE.plusMinutes(15)), scan(finish, BASE.plusMinutes(30))));
        var row = new ResultsRenderer.ParticipantResultRow(
                finisher(person("A", "T1", "Open"), 1800, 1), readings, columns, Race.CalculationMode.LONG_DISTANCE_MANUAL_START);

        assertEquals("09:01:00", row.getCheckpointTime(1), "Start Line column shows the start-checkpoint scan");
        assertEquals("09:15:00", row.getCheckpointTime(2));
        assertEquals("09:30:00", row.getCheckpointTime(3));

        // Started and reached the midpoint only
        List<CheckpointReading> partial = new ArrayList<>(List.of(scan(start, BASE.plusMinutes(1)), scan(mid, BASE.plusMinutes(15))));
        var dnfRow = new ResultsRenderer.ParticipantResultRow(
                dnf(person("B", "T2", "Open"), 2), partial, columns, Race.CalculationMode.LONG_DISTANCE_MANUAL_START);
        assertEquals("09:01:00", dnfRow.getCheckpointTime(1), "a DNF's scans still show");
        assertEquals("09:15:00", dnfRow.getCheckpointTime(2));
        assertEquals("--:--:--", dnfRow.getCheckpointTime(3));
    }

    // ------------------------------------------------------------------ Section 7 (Manual Start rows)

    @Test
    void dualTimingRow_showsBothDurations_fromDeclaredStartAndFromOwnStart() {
        RaceResult r = finisher(person("Asha", "T1", "Open"), 1800, null);
        r.setDeclaredStartTime(BASE);                 // gun 09:00:00
        r.setStartTime(BASE.plusMinutes(2));          // crossed the start line at 09:02:00
        r.setFinishTime(BASE.plusMinutes(32));        // finished 09:32:00
        r.setRemarks("wheelchair category");

        var row = new ResultsRenderer.DualTimingRow(r);

        assertEquals("COMPLETED", row.status);
        assertEquals("2026-01-01 09:00:00", row.declaredStart);
        assertEquals("09:02:00", row.startTime);
        assertEquals("09:32:00", row.finishTime);
        assertEquals("00:32:00", row.declaredDuration, "Finish - Declared Start (gun time)");
        assertEquals("00:30:00", row.startDuration, "Finish - own Start Time (chip time)");
        assertEquals("wheelchair category", row.noteProperty().get());
    }

    @Test
    void dualTimingRow_dnfConventions_andPartialTimings() {
        // Finish scan but no start scan: not COMPLETED, yet the gun-time duration is still computable
        RaceResult noStart = dnf(person("NoStart", "T2", "Open"), 1);
        noStart.setDeclaredStartTime(BASE);
        noStart.setFinishTime(BASE.plusMinutes(40));
        var a = new ResultsRenderer.DualTimingRow(noStart);
        assertEquals("DNF", a.status);
        assertEquals("--:--:--", a.startTime);
        assertEquals("00:40:00", a.declaredDuration);
        assertEquals("--:--:--", a.startDuration);

        // Roster no-show
        RaceResult none = dnf(person("None", "T3", "Open"), 0);
        none.setDeclaredStartTime(BASE);
        var b = new ResultsRenderer.DualTimingRow(none);
        assertEquals("DNF (No-Show)", b.status);
        assertEquals("--:--:--", b.finishTime);
        assertEquals("--:--:--", b.declaredDuration);
        assertEquals("", b.noteProperty().get(), "Note is optional");

        // Finish before a mis-declared start keeps its sign visible
        RaceResult early = finisher(person("Early", "T4", "Open"), 600, null);
        early.setDeclaredStartTime(BASE.plusMinutes(30));
        early.setStartTime(BASE);
        early.setFinishTime(BASE.plusMinutes(10));
        assertEquals("-00:20:00", new ResultsRenderer.DualTimingRow(early).declaredDuration);
    }

    // ------------------------------------------------------------------ Lap race columns

    @Test
    void lapRaceColumns_needLapCountPlusOne_headerIsScanTimeThenOneLapPerCrossing() {
        Race race = new Race();
        race.setLapCount(3);
        ResultsRenderer renderer = new ResultsRenderer(null); // LAP_RACE never touches timingService

        List<ResultsRenderer.CheckpointColumn> columns = renderer.resolveCheckpointColumns(race, Race.CalculationMode.LAP_RACE);

        assertEquals(4, columns.size(), "a 3-lap race needs 4 columns: Scan Time + one per lap, not 3");
        assertEquals("Scan Time", columns.get(0).header());
        assertEquals("Lap 1", columns.get(1).header());
        assertEquals("Lap 2", columns.get(2).header());
        assertEquals("Lap 3", columns.get(3).header());
    }

    @Test
    void lapRaceRow_everyColumnIsARawTimestamp_noSubSecondPrecision() {
        // Split durations for Lap N were tried in an earlier session and explicitly reversed:
        // every column here, including Lap N, shows the raw crossing timestamp, HH:mm:ss - same
        // convention as every other mode's checkpoint columns. Only Total Time is a duration.
        Checkpoint lapPoint = checkpoint("LapPoint", 1);
        List<ResultsRenderer.CheckpointColumn> columns = List.of(
                new ResultsRenderer.CheckpointColumn("Scan Time", null),
                new ResultsRenderer.CheckpointColumn("Lap 1", null),
                new ResultsRenderer.CheckpointColumn("Lap 2", null),
                new ResultsRenderer.CheckpointColumn("Lap 3", null));

        // 4 crossings for a 3-lap race: #1 start, #2/#3/#4 complete laps 1/2/3. A nonzero
        // sub-second component on purpose, to prove it's stripped from the displayed column.
        LocalDateTime start = BASE.plusNanos(24_943_000);
        List<CheckpointReading> readings = new ArrayList<>(List.of(
                scan(lapPoint, start),
                scan(lapPoint, start.plusMinutes(10)),
                scan(lapPoint, start.plusMinutes(20)),
                scan(lapPoint, start.plusMinutes(35))));

        var row = new ResultsRenderer.ParticipantResultRow(
                finisher(person("A", "T1", "Open"), 2100, 1), readings, columns, Race.CalculationMode.LAP_RACE);

        assertEquals("09:00:00", row.getCheckpointTime(1), "Scan Time is the raw crossing #1 timestamp, no sub-second precision");
        assertEquals("09:10:00", row.getCheckpointTime(2), "Lap 1 is the raw crossing #2 timestamp, not a split duration");
        assertEquals("09:20:00", row.getCheckpointTime(3), "Lap 2 is the raw crossing #3 timestamp");
        assertEquals("09:35:00", row.getCheckpointTime(4), "Lap 3 is the raw crossing #4 timestamp");
        assertEquals("00:35:00", row.getTotalTime(), "Total Time is still a duration - only the Lap N columns changed back");

        // One crossing short of the required 4 - Lap 3 needs crossing #4, which doesn't exist.
        List<CheckpointReading> partial = new ArrayList<>(List.of(
                scan(lapPoint, start), scan(lapPoint, start.plusMinutes(10)), scan(lapPoint, start.plusMinutes(20))));
        var dnfRow = new ResultsRenderer.ParticipantResultRow(
                dnf(person("B", "T2", "Open"), 3), partial, columns, Race.CalculationMode.LAP_RACE);
        assertEquals("09:00:00", dnfRow.getCheckpointTime(1));
        assertEquals("09:10:00", dnfRow.getCheckpointTime(2));
        assertEquals("09:20:00", dnfRow.getCheckpointTime(3));
        assertEquals("--:--:--", dnfRow.getCheckpointTime(4), "Lap 3 needs crossing #4, which never happened");
    }
}
