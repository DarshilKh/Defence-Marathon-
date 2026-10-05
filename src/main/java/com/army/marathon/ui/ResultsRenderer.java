package com.army.marathon.ui;

import com.army.marathon.model.Checkpoint;
import com.army.marathon.model.CheckpointReading;
import com.army.marathon.model.Participant;
import com.army.marathon.model.Race;
import com.army.marathon.model.RaceResult;
import com.army.marathon.service.RaceTimingService;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Shared results-rendering logic: which checkpoint columns a calculation mode shows, and how a
 * {@link RaceResult} plus its {@link CheckpointReading}s become a table row. Used by both the
 * Results tab (the just-calculated race) and the Reports tab (a historical race attempt), so the
 * two never drift into showing the data differently.
 */
@Component
public class ResultsRenderer {

    private static final String NO_TIME = "--:--:--";
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DATE_CLOCK = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final RaceTimingService timingService;

    public ResultsRenderer(RaceTimingService timingService) {
        this.timingService = timingService;
    }

    /** One checkpoint column: its header and, for role-based modes, the checkpoint it reads. */
    public record CheckpointColumn(String header, Checkpoint checkpoint) {}

    /**
     * The checkpoint columns to show for a mode.
     *
     * <p>Long Distance modes are role-based: Start Line, the midpoint(s), Finish Line, each read from
     * the configured checkpoint that plays that role (the same roles RaceTimingService uses).
     */
    public List<CheckpointColumn> resolveCheckpointColumns(Race race, Race.CalculationMode mode) {
        List<CheckpointColumn> columns = new ArrayList<>();

        if (mode == Race.CalculationMode.LONG_DISTANCE_MANUAL_START
                || mode == Race.CalculationMode.LONG_DISTANCE_2_MANUAL_START) {
            return resolveLongDistanceColumns(race, mode);
        }

        int count = computeCheckpointCount(race, mode);
        for (int i = 1; i <= count; i++) {
            columns.add(new CheckpointColumn(getCheckpointName(mode, i, count), null));
        }
        return columns;
    }

    private List<CheckpointColumn> resolveLongDistanceColumns(Race race, Race.CalculationMode mode) {
        List<Checkpoint> enabled = timingService.enabledCheckpointsFor(race.getId());

        Checkpoint start = enabled.stream().filter(Checkpoint::isStartLine).findFirst().orElse(null);
        Checkpoint finish = enabled.stream().filter(Checkpoint::isFinishLine).findFirst()
                .orElse(enabled.isEmpty() ? null : enabled.get(enabled.size() - 1));
        List<Checkpoint> mids = enabled.stream()
                .filter(cp -> cp != start && cp != finish)
                .limit(mode == Race.CalculationMode.LONG_DISTANCE_2_MANUAL_START ? 2 : 1)
                .collect(Collectors.toList());

        List<CheckpointColumn> columns = new ArrayList<>();
        if (start != null) {
            columns.add(new CheckpointColumn("Start Line", start));
        }
        for (int i = 0; i < mids.size(); i++) {
            String header = mode == Race.CalculationMode.LONG_DISTANCE_2_MANUAL_START
                    ? "Midpoint " + (i + 1) : "Midpoint";
            columns.add(new CheckpointColumn(header, mids.get(i)));
        }
        if (finish != null) {
            columns.add(new CheckpointColumn("Finish Line", finish));
        }
        return columns;
    }

    private int computeCheckpointCount(Race race, Race.CalculationMode mode) {
        if (mode == Race.CalculationMode.LAP_RACE) {
            // An N-lap race needs N+1 total crossings: crossing #1 is the start reference (its
            // own "Scan Time" column, not a completed lap) plus one column per lap - see
            // RaceTimingService.calculateLapRaceResults and getCheckpointName() below.
            int lapCount = race.getLapCount() != null && race.getLapCount() > 0 ? race.getLapCount() : 4;
            return lapCount + 1;
        }
        if (mode == Race.CalculationMode.SAME_START_FINISH) {
            return 2;
        }
        if (mode == Race.CalculationMode.SINGLE_FILE_MANUAL_START) {
            return 1;
        }
        if (mode == Race.CalculationMode.THREE_CHECKPOINT) return 3;
        if (mode == Race.CalculationMode.FOUR_CHECKPOINT) return 4;
        if (mode == Race.CalculationMode.FIVE_CHECKPOINT) return 5;
        return 2;
    }

    private String getCheckpointName(Race.CalculationMode mode, int index, int total) {
        if (mode == Race.CalculationMode.LAP_RACE) {
            // Column 1 is crossing #1 (the start reference), not a completed lap - see
            // computeCheckpointCount() above and the ParticipantResultRow LAP_RACE branch below.
            return index == 1 ? "Scan Time" : "Lap " + (index - 1);
        }
        if (mode == Race.CalculationMode.SAME_START_FINISH) {
            return index == 1 ? "Start" : "Finish";
        }
        if (mode == Race.CalculationMode.SINGLE_FILE_MANUAL_START) {
            return "Finish Line";
        }
        if (index == 1) return "Start Line";
        if (index == total) return "Finish Line";
        return "CP " + (index - 1);
    }

    /** Finishers by net time, then rank, then finish moment; everybody else after them. */
    public static final Comparator<RaceResult> FINISHING_ORDER = (a, b) -> {
        if (a.isCompletedRace() != b.isCompletedRace()) {
            return a.isCompletedRace() ? -1 : 1;
        }
        return Comparator
                .comparing(RaceResult::getNetTimeSeconds, Comparator.nullsLast(Comparator.<Long>naturalOrder()))
                .thenComparing(RaceResult::getOverallRank, Comparator.nullsLast(Comparator.<Integer>naturalOrder()))
                .thenComparing(RaceResult::getFinishTime, Comparator.nullsLast(Comparator.<LocalDateTime>naturalOrder()))
                .compare(a, b);
    };

    /**
     * The single overall winner: the finisher with the best finishing time in the entire race.
     * Deliberately not scoped by category, age group or unit.
     */
    public static Optional<RaceResult> findWinner(List<RaceResult> results) {
        return results.stream()
                .filter(RaceResult::isCompletedRace)
                .filter(r -> r.getNetTimeSeconds() != null)
                .min(FINISHING_ORDER);
    }

    /** Name is optional at registration (only Tag No is required), so fall back to the Tag No. */
    public static String displayName(Participant p) {
        if (p.getName() != null && !p.getName().isBlank()) {
            return p.getName();
        }
        return p.getSerialNo() != null && !p.getSerialNo().isBlank() ? "Tag No " + p.getSerialNo() : "(unnamed)";
    }

    /** "HH:mm:ss" of a span, with a leading "-" if negative. */
    public static String formatSpan(Duration duration) {
        String sign = duration.isNegative() ? "-" : "";
        long seconds = duration.abs().getSeconds();
        return String.format("%s%02d:%02d:%02d", sign, seconds / 3600, (seconds % 3600) / 60, seconds % 60);
    }

    // ------------------------------------------------------------------
    // Rows
    // ------------------------------------------------------------------

    public static class ParticipantResultRow {
        private final Integer rank;
        private final String name;
        private final String serviceNo;
        private final String armyRank;
        private final String unit;
        private final String category;
        private final Map<Integer, String> checkpointTimes = new HashMap<>();
        private final String totalTime;
        private final String status;

        public ParticipantResultRow(RaceResult result, List<CheckpointReading> readings,
                             List<CheckpointColumn> columns, Race.CalculationMode mode) {
            Participant p = result.getParticipant();
            int checkpointCount = columns.size();

            // Only a finisher has a position; a DNF / No-Show shows no number at all
            this.rank = result.isCompletedRace() ? result.getOverallRank() : null;
            this.name = p.getName();
            this.serviceNo = p.getSerialNo() != null ? p.getSerialNo() : "";
            this.armyRank = p.getRank() != null ? p.getRank() : "";
            this.unit = p.getUnit() != null ? p.getUnit() : "";
            this.category = p.getCategory() != null ? p.getCategory() : "";
            this.totalTime = result.isCompletedRace() ? result.getFormattedTime() : NO_TIME;
            this.status = result.isCompletedRace() ? "Finished" : (result.isNoShow() ? "DNF (No-Show)" : "DNF");

            readings.sort((a, b) -> a.getReadTime().compareTo(b.getReadTime()));

            if (mode == Race.CalculationMode.SAME_START_FINISH) {
                if (readings.size() >= 2) {
                    LocalDateTime start = readings.get(0).getReadTime();
                    LocalDateTime finish = readings.get(1).getReadTime();
                    checkpointTimes.put(1, start.format(CLOCK));
                    checkpointTimes.put(2, finish.format(CLOCK));
                } else {
                    checkpointTimes.put(1, NO_TIME);
                    checkpointTimes.put(2, NO_TIME);
                }
                return;
            }

            if (mode == Race.CalculationMode.LAP_RACE) {
                // Every column - "Scan Time" (crossing #1) and each "Lap N" - shows the RAW
                // crossing timestamp, same HH:mm:ss convention as every other mode's checkpoint
                // columns. (A split-duration rendering was tried here first and reverted - the
                // request explicitly flagged that as its own assumption, and asked for raw
                // timestamps instead; Total Time remains the one duration this mode shows.)
                for (int i = 1; i <= checkpointCount; i++) {
                    checkpointTimes.put(i, i <= readings.size()
                            ? readings.get(i - 1).getReadTime().format(CLOCK) : NO_TIME);
                }
                return;
            }

            for (int i = 1; i <= checkpointCount; i++) {
                final int checkpointNum = i;
                final Checkpoint roleCheckpoint = columns.get(i - 1).checkpoint();

                Optional<CheckpointReading> reading;
                if (roleCheckpoint != null) {
                    // Role-based column (Long Distance): the checkpoint that plays this role, matched by
                    // name exactly as RaceTimingService does when it calculates the result
                    reading = readings.stream()
                            .filter(r -> r.getCheckpointName() != null
                                    && r.getCheckpointName().equals(roleCheckpoint.getName()))
                            .findFirst();
                } else {
                    reading = readings.stream()
                            .filter(r -> r.getCheckpoint() != null &&
                                    r.getCheckpoint().getLocationNumber() != null &&
                                    r.getCheckpoint().getLocationNumber() == checkpointNum)
                            .findFirst();
                }

                checkpointTimes.put(i, reading
                        .map(r -> r.getReadTime().format(CLOCK))
                        .orElse(NO_TIME));
            }
        }

        public Integer getRank() { return rank; }
        public String getName() { return name; }
        public String getServiceNo() { return serviceNo; }
        public String getArmyRank() { return armyRank; }
        public String getUnit() { return unit; }
        public String getCategory() { return category; }
        public String getTotalTime() { return totalTime; }
        public String getStatus() { return status; }

        public String getCheckpointTime(int checkpoint) {
            return checkpointTimes.getOrDefault(checkpoint, NO_TIME);
        }
    }

    /** One MANUAL_START_DUAL_TIMING result row. */
    public static class DualTimingRow {
        public final Long resultId;
        public final String name;
        public final String tagNo;
        public final String unit;
        public final String status;
        public final String declaredStart;
        public final String startTime;
        public final String finishTime;
        public final String declaredDuration;
        public final String startDuration;
        private final StringProperty note;

        public DualTimingRow(RaceResult result) {
            Participant p = result.getParticipant();
            this.resultId = result.getId();
            this.name = p.getName() != null ? p.getName() : "";
            this.tagNo = p.getSerialNo() != null ? p.getSerialNo() : "";
            this.unit = p.getUnit() != null ? p.getUnit() : "";
            this.status = result.isCompletedRace() ? "COMPLETED" : (result.isNoShow() ? "DNF (No-Show)" : "DNF");

            LocalDateTime declared = result.getDeclaredStartTime();
            LocalDateTime start = result.getStartTime();
            LocalDateTime finish = result.getFinishTime();

            this.declaredStart = declared != null ? declared.format(DATE_CLOCK) : NO_TIME;
            this.startTime = start != null ? start.format(CLOCK) : NO_TIME;
            this.finishTime = finish != null ? finish.format(CLOCK) : NO_TIME;

            // Each duration needs only its own two ends, so a runner with a finish scan but no start
            // scan still shows a Declared Start (gun) time even though the status isn't COMPLETED.
            this.declaredDuration = declared != null && finish != null
                    ? formatSpan(Duration.between(declared, finish)) : NO_TIME;
            this.startDuration = start != null && finish != null
                    ? formatSpan(Duration.between(start, finish)) : NO_TIME;

            this.note = new SimpleStringProperty(result.getRemarks() != null ? result.getRemarks() : "");
        }

        public StringProperty noteProperty() { return note; }
    }
}
