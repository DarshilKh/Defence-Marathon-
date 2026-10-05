package com.army.marathon.service;

import com.army.marathon.model.Checkpoint;
import com.army.marathon.model.CheckpointReading;
import com.army.marathon.model.Participant;
import com.army.marathon.model.Race;
import com.army.marathon.model.RaceResult;
import com.army.marathon.model.RaceSession;
import com.army.marathon.repository.CheckpointReadingRepository;
import com.army.marathon.repository.CheckpointRepository;
import com.army.marathon.repository.RaceRepository;
import com.army.marathon.repository.RaceResultRepository;
import com.army.marathon.repository.RaceSessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.FileOutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ReportService {

    private final RaceResultRepository resultRepository;
    private final RaceRepository raceRepository;
    private final CheckpointRepository checkpointRepository;
    private final CheckpointReadingRepository readingRepository;
    private final RaceSessionRepository raceSessionRepository;
    private final RaceLifecycleService raceLifecycleService;

    public ReportService(
            RaceResultRepository resultRepository,
            RaceRepository raceRepository,
            CheckpointRepository checkpointRepository,
            CheckpointReadingRepository readingRepository,
            RaceSessionRepository raceSessionRepository,
            RaceLifecycleService raceLifecycleService
    ) {
        this.resultRepository = resultRepository;
        this.raceRepository = raceRepository;
        this.checkpointRepository = checkpointRepository;
        this.readingRepository = readingRepository;
        this.raceSessionRepository = raceSessionRepository;
        this.raceLifecycleService = raceLifecycleService;
    }

    // ------------------------------------------------------------------
    // Attempt window: the ONE definition of "this attempt's readings", used by the Reports
    // display, the Excel export and Delete, so the three can never disagree.
    // ------------------------------------------------------------------

    /**
     * A reading belongs to an attempt when readTime is in [from, toInclusive] and strictly before
     * capExclusive. toInclusive is endedAt (or now while still running); capExclusive is the NEXT
     * attempt's startedAt, so a reading can never belong to two attempts (when there is no next
     * attempt it is just past toInclusive and has no effect). endedAt is inclusive on purpose: an
     * orphaned attempt is closed at its last reading's time, which must stay inside the window.
     */
    public record AttemptWindow(LocalDateTime from, LocalDateTime toInclusive, LocalDateTime capExclusive) {}

    /** What deleting an attempt would remove, for the confirmation dialog. */
    public record AttemptCounts(long readings, long results) {}

    public AttemptWindow readingWindow(RaceSession session) {
        LocalDateTime from = session.getStartedAt();
        LocalDateTime to = session.getEndedAt() != null ? session.getEndedAt() : LocalDateTime.now();
        LocalDateTime cap = raceSessionRepository
                .findFirstByRaceIdAndStartedAtAfterOrderByStartedAtAsc(session.getRaceId(), from)
                .map(RaceSession::getStartedAt)
                .orElse(to.plusSeconds(1));
        return new AttemptWindow(from, to, cap);
    }

    /** This attempt's readings, oldest first - what the Reports tab shows for it. */
    public List<CheckpointReading> readingsForAttempt(RaceSession session) {
        AttemptWindow w = readingWindow(session);
        return readingRepository.findInAttemptWindow(session.getRaceId(), w.from(), w.toInclusive(), w.capExclusive());
    }

    public AttemptCounts previewAttempt(Long sessionId) {
        RaceSession session = raceSessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Race attempt not found"));
        AttemptWindow w = readingWindow(session);
        return new AttemptCounts(
                readingRepository.countInAttemptWindow(session.getRaceId(), w.from(), w.toInclusive(), w.capExclusive()),
                resultRepository.countByRaceSessionId(sessionId));
    }

    /** True when this attempt is the current attempt of a race that is ARMED or ACTIVE. */
    public boolean isRunningAttempt(Long sessionId) {
        return raceSessionRepository.findById(sessionId)
                .flatMap(s -> raceRepository.findById(s.getRaceId()))
                .map(r -> sessionId.equals(r.getCurrentSessionId())
                        && (r.getStatus() == Race.RaceStatus.ARMED || r.getStatus() == Race.RaceStatus.ACTIVE))
                .orElse(false);
    }

    /**
     * Deletes one attempt from history, in ONE transaction: its RaceResult rows (by
     * raceSessionId), its CheckpointReading rows (by the same window the Reports tab displays),
     * then the RaceSession row. Participants, other attempts and the roster are untouched.
     * This (via the Reports Delete button) is the ONLY place allowed to delete race history -
     * Reset and startup never call it. Refused for the current attempt of an ARMED/ACTIVE race.
     *
     * @return what was removed
     */
    @Transactional
    public AttemptCounts deleteAttempt(Long sessionId) {
        RaceSession session = raceSessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Race attempt not found"));

        if (isRunningAttempt(sessionId)) {
            throw new IllegalStateException(
                    "This is the current attempt of a race that is running. Stop the race before deleting it.");
        }

        AttemptWindow w = readingWindow(session);
        long results = resultRepository.countByRaceSessionId(sessionId);
        resultRepository.deleteByRaceSessionId(sessionId);
        int readings = readingRepository.deleteInAttemptWindow(
                session.getRaceId(), w.from(), w.toInclusive(), w.capExclusive());

        // A finished current attempt is still referenced by the Race row: clear only its
        // current-attempt fields so no live view points at a session that no longer exists. The
        // race name and roster are deliberately left alone - Delete must not touch them.
        raceRepository.findById(session.getRaceId()).ifPresent(race -> {
            if (sessionId.equals(race.getCurrentSessionId())) {
                raceLifecycleService.clearCurrentAttempt(session.getRaceId());
            }
        });

        raceSessionRepository.deleteById(sessionId);
        log.warn("Deleted race attempt {} ('{}'): {} reading(s), {} result(s)",
                sessionId, session.getRaceName(), readings, results);
        return new AttemptCounts(readings, results);
    }

    /** Exports every result ever calculated for this race, with no attempt boundary. */
    public void exportRaceResults(Long raceId, File file) throws Exception {
        exportRaceResults(raceId, null, null, null, file);
    }

    /**
     * Exports results for one attempt: RaceResult rows via RaceResult.raceSessionId (the reliable
     * link) and CheckpointReading rows via {@link #readingWindow}, the same window the Reports tab
     * displays and Delete removes. {@code since} is only a required marker that a boundary
     * exists (a sessionId with no boundary is a caller bug); there is no createdAt fallback.
     * The only whole-race export is the explicit legacy one, {@link #exportRaceResults(Long, File)},
     * which passes neither.
     */
    public void exportRaceResults(Long raceId, Long sessionId, LocalDateTime since, LocalDateTime until, File file) throws Exception {
        Race race = raceRepository.findById(raceId)
                .orElseThrow(() -> new RuntimeException("Race not found"));
        if ((sessionId == null) != (since == null)) {
            throw new IllegalArgumentException("An attempt export needs both a session id and its start boundary");
        }
        LocalDateTime effectiveUntil = until != null ? until : LocalDateTime.now();

        // Manual Start (dual timing) has its own table shape - same columns as the Results tab
        if (race.getCalculationMode() == Race.CalculationMode.MANUAL_START_DUAL_TIMING) {
            exportDualTimingResults(raceId, sessionId, since, effectiveUntil, file);
            return;
        }

        List<RaceResult> scoped = resolveResultsForExport(raceId, sessionId, since, effectiveUntil);

        List<RaceResult> completedResults = scoped.stream()
                .filter(RaceResult::isCompletedRace)
                .sorted(Comparator.comparing(RaceResult::getNetTimeSeconds,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .collect(Collectors.toList());

        List<RaceResult> dnfResults = scoped.stream()
                .filter(r -> !r.isCompletedRace())
                .collect(Collectors.toList());

        List<CheckpointReading> allReadings = sessionId == null
                ? readingRepository.findByRaceIdOrderByReadTimeAsc(raceId)   // explicit legacy whole-race export
                : readingsForAttempt(raceSessionRepository.findById(sessionId)
                        .orElseThrow(() -> new IllegalArgumentException("Race attempt not found")));

        try (Workbook workbook = new XSSFWorkbook()) {

            // Sheet 1: Final Results (Completed only)
            createFinalResultsSheet(workbook, race, completedResults, allReadings);

            // Sheet 2: DNF List
            createDNFSheet(workbook, race, dnfResults);

            // Sheet 3: Checkpoint Details
            // createCheckpointDetailsSheet(workbook, raceId);

            // Sheet 4: Summary
            createSummarySheet(workbook, race, completedResults, dnfResults);

            try (FileOutputStream fos = new FileOutputStream(file)) {
                workbook.write(fos);
            }
        }

        log.info("Race report exported successfully");
    }

    /**
     * sessionId, when present, is the definitive scope — a time window is never used to
     * second-guess it, even an empty result for a genuinely empty session is correct and
     * should not silently fall back to "every result on file".
     */
    private List<RaceResult> resolveResultsForExport(Long raceId, Long sessionId, LocalDateTime since, LocalDateTime effectiveUntil) {
        if (sessionId != null) {
            return resultRepository.findByRaceSessionId(sessionId);
        }
        // Only the explicit legacy whole-race export (exportRaceResults(raceId, file)) gets here.
        return resultRepository.findByRaceId(raceId);
    }

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DATE_CLOCK = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * MANUAL_START_DUAL_TIMING export: one sheet, every runner, both timings side by side.
     * No rank or winner - this mode doesn't have them.
     */
    private void exportDualTimingResults(Long raceId, Long sessionId, LocalDateTime since, LocalDateTime until, File file) throws Exception {
        List<RaceResult> scoped = resolveResultsForExport(raceId, sessionId, since, until);

        List<RaceResult> results = scoped.stream()
                .filter(r -> r.getParticipant() != null)
                .sorted(Comparator
                        .comparing((RaceResult r) -> !r.isCompletedRace())
                        .thenComparing(RaceResult::getFinishTime, Comparator.nullsLast(Comparator.<LocalDateTime>naturalOrder())))
                .collect(Collectors.toList());

        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Results");
            String[] headers = {"Name", "Tag No", "Unit", "Status", "Declared Start", "Start Time", "Finish Time",
                    "Duration (Declared Start)", "Duration (Start Time)", "Note"};

            CellStyle headerStyle = createHeaderStyle(workbook);
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            int rowNum = 1;
            for (RaceResult result : results) {
                Participant p = result.getParticipant();
                LocalDateTime declared = result.getDeclaredStartTime();
                LocalDateTime start = result.getStartTime();
                LocalDateTime finish = result.getFinishTime();

                Row row = sheet.createRow(rowNum++);
                int col = 0;
                row.createCell(col++).setCellValue(p.getName() != null ? p.getName() : "");
                row.createCell(col++).setCellValue(p.getSerialNo() != null ? p.getSerialNo() : "");
                row.createCell(col++).setCellValue(p.getUnit() != null ? p.getUnit() : "");
                row.createCell(col++).setCellValue(result.isCompletedRace() ? "COMPLETED"
                        : (result.isNoShow() ? "DNF (No-Show)" : "DNF"));
                row.createCell(col++).setCellValue(declared != null ? declared.format(DATE_CLOCK) : "--:--:--");
                row.createCell(col++).setCellValue(start != null ? start.format(CLOCK) : "--:--:--");
                row.createCell(col++).setCellValue(finish != null ? finish.format(CLOCK) : "--:--:--");
                row.createCell(col++).setCellValue(declared != null && finish != null
                        ? formatSpan(java.time.Duration.between(declared, finish)) : "--:--:--");
                row.createCell(col++).setCellValue(start != null && finish != null
                        ? formatSpan(java.time.Duration.between(start, finish)) : "--:--:--");
                row.createCell(col).setCellValue(result.getRemarks() != null ? result.getRemarks() : "");
            }

            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
            }
            try (FileOutputStream fos = new FileOutputStream(file)) {
                workbook.write(fos);
            }
        }
        log.info("Manual start (dual timing) results exported");
    }

    private static String formatSpan(java.time.Duration duration) {
        long seconds = duration.abs().getSeconds();
        return String.format("%s%02d:%02d:%02d", duration.isNegative() ? "-" : "",
                seconds / 3600, (seconds % 3600) / 60, seconds % 60);
    }

    /**
     * Columns match the Results tab exactly: Rank, Name, Tag No, Army Rank, Unit, Category,
     * [one column per checkpoint], Total Time, Status. Rank 1-3 rows are bolded, same as a
     * podium finish is called out on screen.
     */
    private void createFinalResultsSheet(Workbook workbook, Race race, List<RaceResult> results,
                                          List<CheckpointReading> allReadings) {
        Sheet sheet = workbook.createSheet("Final Results");

        Race.CalculationMode mode = race.getCalculationMode() != null
                ? race.getCalculationMode()
                : Race.CalculationMode.TWO_CHECKPOINT;

        List<CheckpointColumnSpec> checkpointColumns = resolveCheckpointColumns(race, mode);

        Map<Long, List<CheckpointReading>> readingsByParticipant = allReadings.stream()
                .filter(r -> r.getParticipant() != null && r.getParticipant().getId() != null)
                .collect(Collectors.groupingBy(r -> r.getParticipant().getId()));

        List<String> headers = new ArrayList<>();
        headers.add("Rank");
        headers.add("Name");
        headers.add("Tag No");
        headers.add("Army Rank");
        headers.add("Unit");
        headers.add("Category");
        for (CheckpointColumnSpec checkpoint : checkpointColumns) {
            headers.add(checkpoint.header() + " Time");
        }
        headers.add("Total Time");
        headers.add("Status");

        Row headerRow = sheet.createRow(0);
        CellStyle headerStyle = createHeaderStyle(workbook);
        for (int i = 0; i < headers.size(); i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers.get(i));
            cell.setCellStyle(headerStyle);
        }

        CellStyle podiumStyle = createPodiumStyle(workbook);

        int rowNum = 1;
        for (RaceResult result : results) {
            Row row = sheet.createRow(rowNum);
            Participant p = result.getParticipant();

            List<CheckpointReading> participantReadings = readingsByParticipant
                    .getOrDefault(p.getId(), new ArrayList<>());
            participantReadings.sort(Comparator.comparing(CheckpointReading::getReadTime));

            LocalDateTime effectiveStart = result.getStartTime() != null
                    ? result.getStartTime()
                    : race.getManualStartTime();

            Map<Integer, String> checkpointTimes = buildCheckpointTimes(
                    participantReadings,
                    checkpointColumns,
                    effectiveStart,
                    mode
            );

            Integer rank = result.getOverallRank();

            int col = 0;
            row.createCell(col++).setCellValue(rank != null ? rank : rowNum);
            row.createCell(col++).setCellValue(p.getName() != null ? p.getName() : "");
            row.createCell(col++).setCellValue(p.getSerialNo() != null ? p.getSerialNo() : "");
            row.createCell(col++).setCellValue(p.getRank() != null ? p.getRank() : "");
            row.createCell(col++).setCellValue(p.getUnit() != null ? p.getUnit() : "");
            row.createCell(col++).setCellValue(p.getCategory() != null ? p.getCategory() : "");

            for (int i = 1; i <= checkpointColumns.size(); i++) {
                row.createCell(col++).setCellValue(checkpointTimes.getOrDefault(i, "--:--:--"));
            }

            row.createCell(col++).setCellValue(result.getFormattedTime() != null ? result.getFormattedTime() : "");
            row.createCell(col).setCellValue("Finished");

            if (rank != null && rank <= 3) {
                for (Cell cell : row) {
                    cell.setCellStyle(podiumStyle);
                }
            }

            rowNum++;
        }

        for (int i = 0; i < headers.size(); i++) {
            sheet.autoSizeColumn(i);
        }
    }

    private List<CheckpointColumnSpec> resolveCheckpointColumns(Race race, Race.CalculationMode mode) {
        List<CheckpointColumnSpec> columns = new ArrayList<>();

        if (mode == Race.CalculationMode.SAME_START_FINISH) {
            columns.add(new CheckpointColumnSpec("Start", 1));
            columns.add(new CheckpointColumnSpec("Finish", 2));
            return columns;
        }

        if (mode == Race.CalculationMode.LAP_RACE) {
            // An N-lap race needs N+1 total crossings: crossing #1 is the start reference (not a
            // completed lap - "Scan" here, "Scan Time" once " Time" is appended below), crossing
            // #2 completes Lap 1, ..., crossing #(N+1) completes Lap N and is also the finish.
            int lapCount = race.getLapCount() != null && race.getLapCount() > 0 ? race.getLapCount() : 4;
            columns.add(new CheckpointColumnSpec("Scan", 1));
            for (int i = 1; i <= lapCount; i++) {
                columns.add(new CheckpointColumnSpec("Lap " + i, i + 1));
            }
            return columns;
        }

        List<Checkpoint> checkpoints = checkpointRepository.findByRaceIdOrderByDisplayOrderAsc(race.getId());
        for (Checkpoint checkpoint : checkpoints) {
            if (checkpoint.getLocationNumber() != null) {
                columns.add(new CheckpointColumnSpec(checkpoint.getName(), checkpoint.getLocationNumber()));
            }
        }

        if (columns.isEmpty()) {
            columns.add(new CheckpointColumnSpec("Start Line", 1));
            columns.add(new CheckpointColumnSpec("Finish Line", 2));
        }

        return columns;
    }

    private Map<Integer, String> buildCheckpointTimes(
            List<CheckpointReading> readings,
            List<CheckpointColumnSpec> checkpointColumns,
            LocalDateTime effectiveStart,
            Race.CalculationMode mode
    ) {
        Map<Integer, String> checkpointTimes = new HashMap<>();
        int checkpointCount = checkpointColumns.size();

        if (mode == Race.CalculationMode.SAME_START_FINISH) {
            if (readings.size() >= 2) {
                LocalDateTime start = readings.get(0).getReadTime();
                LocalDateTime finish = readings.get(1).getReadTime();
                checkpointTimes.put(1, start.toLocalTime().format(CLOCK));
                checkpointTimes.put(2, finish.toLocalTime().format(CLOCK));
            } else {
                checkpointTimes.put(1, "--:--:--");
                checkpointTimes.put(2, "--:--:--");
            }
            return checkpointTimes;
        }

        if (mode == Race.CalculationMode.LAP_RACE) {
            // Every column - "Scan"/"Scan Time" (crossing #1) and each "Lap N" - shows the RAW
            // crossing timestamp, HH:mm:ss (no sub-second precision), same convention as every
            // other mode's checkpoint columns. (A split-duration rendering was tried here first
            // and reverted - explicitly flagged as its own assumption and asked back to raw
            // timestamps; Total Time remains the one duration this mode shows.)
            for (int i = 1; i <= checkpointCount; i++) {
                checkpointTimes.put(i, i <= readings.size()
                        ? readings.get(i - 1).getReadTime().toLocalTime().format(CLOCK) : "--:--:--");
            }
            return checkpointTimes;
        }

        for (int i = 1; i <= checkpointCount; i++) {
            final int checkpointNum = checkpointColumns.get(i - 1).index();
            Optional<CheckpointReading> reading = readings.stream()
                    .filter(r -> r.getCheckpoint() != null &&
                            r.getCheckpoint().getLocationNumber() != null &&
                            r.getCheckpoint().getLocationNumber() == checkpointNum)
                    .findFirst();

            if (reading.isPresent() && effectiveStart != null) {
                LocalDateTime readTime = reading.get().getReadTime();
                checkpointTimes.put(i, readTime.toLocalTime().format(CLOCK));
            } else {
                checkpointTimes.put(i, "--:--:--");
            }
        }

        return checkpointTimes;
    }

    private void createDNFSheet(Workbook workbook, Race race, List<RaceResult> dnfResults) {
        Sheet sheet = workbook.createSheet("DNF List");

        Row headerRow = sheet.createRow(0);
        String[] headers = {
                "Name", "Tag No", "Army Rank", "Unit", "RFID Tag",
                "Checkpoints Passed", "Last Checkpoint", "Status"
        };

        CellStyle headerStyle = createHeaderStyle(workbook);

        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        int rowNum = 1;

        for (RaceResult result : dnfResults) {
            Row row = sheet.createRow(rowNum++);
            Participant p = result.getParticipant();

            row.createCell(0).setCellValue(p.getName());
            row.createCell(1).setCellValue(p.getSerialNo());
            row.createCell(2).setCellValue(p.getRank() != null ? p.getRank() : "");
            row.createCell(3).setCellValue(p.getUnit() != null ? p.getUnit() : "");
            row.createCell(4).setCellValue(result.getRfidTag());
            row.createCell(5).setCellValue(result.getCheckpointCount());
            row.createCell(6).setCellValue(extractLastCheckpoint(result.getSplitTimesJson()));
            row.createCell(7).setCellValue(result.isNoShow() ? "DNF (No-Show)" : "DNF");
        }

        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
        }
    }

    private void createCheckpointDetailsSheet(Workbook workbook, Long raceId) {
        Sheet sheet = workbook.createSheet("Checkpoint Details");

        List<CheckpointReading> readings = readingRepository.findByRaceIdOrderByReadTimeDesc(raceId);

        Row headerRow = sheet.createRow(0);
        String[] headers = {"RFID Tag", "Checkpoint", "Reader", "Time", "Device ID"};

        CellStyle headerStyle = createHeaderStyle(workbook);

        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        int rowNum = 1;

        for (CheckpointReading reading : readings) {
            Row row = sheet.createRow(rowNum++);
            row.createCell(0).setCellValue(reading.getRfidTag());
            row.createCell(1).setCellValue(reading.getCheckpointName());
            row.createCell(2).setCellValue(reading.getReaderId());
            row.createCell(3).setCellValue(reading.getReadTime().format(formatter));
            row.createCell(4).setCellValue(reading.getDeviceId() != null ? reading.getDeviceId() : "");
        }

        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
        }
    }

    private void createSummarySheet(Workbook workbook, Race race,
                                    List<RaceResult> completed, List<RaceResult> dnf) {
        Sheet sheet = workbook.createSheet("Summary");

        int rowNum = 0;

        Row row1 = sheet.createRow(rowNum++);
        row1.createCell(0).setCellValue("Race Name:");
        row1.createCell(1).setCellValue(race.getRaceName());

        Row row2 = sheet.createRow(rowNum++);
        row2.createCell(0).setCellValue("Race Date:");
        row2.createCell(1).setCellValue(
                race.getRaceDate() != null ?
                        race.getRaceDate().format(DateTimeFormatter.ofPattern("dd-MMM-yyyy")) :
                        "N/A"
        );

        Row row3 = sheet.createRow(rowNum++);
        row3.createCell(0).setCellValue("Total Finishers:");
        row3.createCell(1).setCellValue(completed.size());

        Row row4 = sheet.createRow(rowNum++);
        row4.createCell(0).setCellValue("Total DNF:");
        row4.createCell(1).setCellValue(dnf.size());

        Row row5 = sheet.createRow(rowNum++);
        row5.createCell(0).setCellValue("Total Participants:");
        row5.createCell(1).setCellValue(completed.size() + dnf.size());

        rowNum++;

        if (!completed.isEmpty()) {
            RaceResult winner = completed.get(0);
            Row row6 = sheet.createRow(rowNum++);
            row6.createCell(0).setCellValue("Winner:");
            row6.createCell(1).setCellValue(winner.getParticipant().getSerialNo());

            Row row7 = sheet.createRow(rowNum++);
            row7.createCell(0).setCellValue("Winning Time:");
            row7.createCell(1).setCellValue(winner.getFormattedTime());
        }

        sheet.autoSizeColumn(0);
        sheet.autoSizeColumn(1);
    }

    private CellStyle createHeaderStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    /** Bold, every cell - used on Rank 1/2/3's rows in the Final Results sheet. */
    private CellStyle createPodiumStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }

    private String extractLastCheckpoint(String splitTimesJson) {
        if (splitTimesJson == null || splitTimesJson.isEmpty()) {
            return "N/A";
        }
        String[] parts = splitTimesJson.split(",");
        if (parts.length > 0) {
            String last = parts[parts.length - 1];
            return last.split("=")[0].replace("{", "").trim();
        }
        return "N/A";
    }

    private record CheckpointColumnSpec(String header, int index) {
    }
}
