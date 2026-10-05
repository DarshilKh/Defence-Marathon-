package com.army.marathon.service;

import com.army.marathon.model.Checkpoint;
import com.army.marathon.model.CheckpointReading;
import com.army.marathon.model.Participant;
import com.army.marathon.model.Race;
import com.army.marathon.repository.CheckpointReadingRepository;
import com.army.marathon.repository.CheckpointRepository;
import com.army.marathon.repository.ParticipantRepository;
import com.army.marathon.repository.RaceRepository;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Proves two fixes reach the Excel export, not just the on-screen Results/Reports tabs -
 * ReportService has its own, separately-duplicated column-resolution logic
 * (resolveCheckpointColumns/buildCheckpointTimes), not shared with ResultsRenderer, so each
 * needed its own fix and its own real, exported-and-read-back proof:
 * <ul>
 *   <li>Lap N columns show the raw crossing timestamp (not a split duration - that was tried in
 *       an earlier session and explicitly reversed).</li>
 *   <li>Every displayed timestamp is HH:mm:ss with no sub-second precision, even when the
 *       underlying reading carries microseconds (confirmed from a real screenshot showing
 *       "15:44:57.024943").</li>
 * </ul>
 */
@SpringBootTest
@Transactional
@Rollback
class ReportServiceLapExportTest {

    @Autowired private ReportService reportService;
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

    // Sub-second component on purpose (matches the real screenshot's "15:44:57.024943") - proves
    // the export strips it rather than just happening to pass with a round timestamp.
    private static final LocalDateTime BASE = LocalDateTime.of(2026, 1, 1, 9, 0, 0).plusNanos(24_943_000);

    @Test
    void lapRaceExport_headerShowsScanTimeThenLapColumns_dataShowsRawTimestampsWithNoSubSecondPrecision() throws Exception {
        Race race = new Race();
        race.setRaceName("JUNIT-EXPORT-LAP-" + System.nanoTime());
        race.setCalculationMode(Race.CalculationMode.LAP_RACE);
        race.setStatus(Race.RaceStatus.ACTIVE);
        race.setLapCount(3);
        race.setLapDistanceKm(1.6);
        race = withAttempt(raceRepository.save(race));

        Checkpoint lapPoint = new Checkpoint();
        lapPoint.setRaceId(race.getId());
        lapPoint.setLocationName("LapPoint");
        lapPoint.setLocationNumber(1);
        lapPoint.setDisplayOrder(1);
        lapPoint.setEnabled(true);
        lapPoint.setStartLine(true);
        lapPoint.setFinishLine(true);
        lapPoint = checkpointRepository.save(lapPoint);

        String tag = "JUNIT-EXPORT-TAG-" + System.nanoTime();
        Participant p = new Participant();
        p.setRfidTag(tag);
        p.setName("Export Runner");
        p.setServiceNumber(tag + "-SVC");
        p.setSerialNo(tag);
        p = participantRepository.save(p);

        // 4 crossings for a 3-lap race: #1 start, #2/#3/#4 complete laps 1/2/3 (#4 is also finish).
        saveReading(tag, race.getId(), lapPoint, BASE, p);
        saveReading(tag, race.getId(), lapPoint, BASE.plusMinutes(10), p);
        saveReading(tag, race.getId(), lapPoint, BASE.plusMinutes(20), p);
        saveReading(tag, race.getId(), lapPoint, BASE.plusMinutes(35), p);

        timingService.calculateRaceResults(race.getId(), Race.CalculationMode.LAP_RACE);
        timingService.assignRankings(race.getId());

        File file = File.createTempFile("lap-export-test", ".xlsx");
        file.deleteOnExit();
        try {
            reportService.exportRaceResults(race.getId(), file);

            try (Workbook workbook = WorkbookFactory.create(file)) {
                Sheet sheet = workbook.getSheet("Final Results");
                Row header = sheet.getRow(0);

                // Rank, Name, Tag No, Army Rank, Unit, Category = 6 fixed columns, then checkpoint
                // columns, then Total Time, Status.
                assertEquals("Scan Time", cellText(header, 6), "column 7: crossing #1, not \"Lap 1\"");
                assertEquals("Lap 1 Time", cellText(header, 7));
                assertEquals("Lap 2 Time", cellText(header, 8));
                assertEquals("Lap 3 Time", cellText(header, 9));
                assertEquals("Total Time", cellText(header, 10));

                Row dataRow = sheet.getRow(1);
                assertEquals("09:00:00", cellText(dataRow, 6), "Scan Time is the raw crossing #1 timestamp, sub-seconds stripped");
                assertEquals("09:10:00", cellText(dataRow, 7), "Lap 1 Time is the raw crossing #2 timestamp, not a split duration");
                assertEquals("09:20:00", cellText(dataRow, 8), "Lap 2 Time is the raw crossing #3 timestamp");
                assertEquals("09:35:00", cellText(dataRow, 9), "Lap 3 Time is the raw crossing #4 timestamp");
                assertEquals("00:35:00", cellText(dataRow, 10), "Total Time is still a duration - only the Lap N columns changed back");
            }
        } finally {
            file.delete();
        }
    }

    private void saveReading(String tag, Long raceId, Checkpoint checkpoint, LocalDateTime time, Participant participant) {
        CheckpointReading r = new CheckpointReading();
        r.setRfidTag(tag);
        r.setCheckpoint(checkpoint);
        r.setCheckpointName(checkpoint.getName());
        r.setRaceId(raceId);
        r.setReadTime(time);
        r.setDeviceId("JUNIT-DEVICE");
        r.setParticipant(participant); // ReportService groups readings by participant id for export
        readingRepository.save(r);
    }

    private static String cellText(Row row, int col) {
        Cell cell = row.getCell(col);
        return cell == null ? null : cell.getStringCellValue();
    }
}
