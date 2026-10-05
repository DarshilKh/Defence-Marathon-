package com.army.marathon.service;

import com.army.marathon.model.Checkpoint;
import com.army.marathon.model.CheckpointReading;
import com.army.marathon.model.Participant;
import com.army.marathon.model.Race;
import com.army.marathon.model.RaceEntry;
import com.army.marathon.model.RaceResult;
import com.army.marathon.repository.CheckpointReadingRepository;
import com.army.marathon.repository.CheckpointRepository;
import com.army.marathon.repository.ParticipantRepository;
import com.army.marathon.repository.RaceEntryRepository;
import com.army.marathon.repository.RaceRepository;
import com.army.marathon.repository.RaceResultRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the mandatory/unique Tag No rules and the race-day roster (scan-time
 * rejection, results seeding, Excel template/upload) against a real database inside a
 * rolled-back transaction, like RaceTimingServiceCalculationModesTest.
 */
@SpringBootTest
@Transactional
@Rollback
class RaceEntryRosterTest {

    @Autowired private MultiReaderService multiReaderService;
    @Autowired private RaceTimingService timingService;
    @Autowired private RaceEntryService raceEntryService;
    @Autowired private ParticipantService participantService;
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
    @Autowired private RaceEntryRepository raceEntryRepository;
    @PersistenceContext private EntityManager em;

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 1, 1, 9, 0, 0);
    private long counter = 0;

    // ------------------------------------------------------------------ fixtures

    private Race createRace(Race.RaceStatus status) {
        Race race = new Race();
        race.setRaceName("JUNIT-ROSTER-" + System.nanoTime());
        race.setCalculationMode(Race.CalculationMode.TWO_CHECKPOINT);
        race.setStatus(status);
        return withAttempt(raceRepository.save(race));
    }

    private Checkpoint createCheckpoint(Long raceId, String name, int order, boolean startLine, boolean finishLine) {
        Checkpoint cp = new Checkpoint();
        cp.setRaceId(raceId);
        cp.setLocationName(name);
        cp.setLocationNumber(order);
        cp.setDisplayOrder(order);
        cp.setEnabled(true);
        cp.setStartLine(startLine);
        cp.setFinishLine(finishLine);
        return checkpointRepository.save(cp);
    }

    /** Registered participant: RFID chip id and Tag No are deliberately different values. */
    private Participant createParticipant() {
        String unique = System.nanoTime() + "-" + (counter++);
        Participant p = new Participant();
        p.setRfidTag("JUNIT-CHIP-" + unique);
        p.setSerialNo("JUNIT-TN-" + unique);
        p.setName("Runner " + unique);
        p.setServiceNumber("SVC-" + unique);
        return participantRepository.save(p);
    }

    private void reading(Participant p, Long raceId, Checkpoint cp, LocalDateTime time) {
        CheckpointReading r = new CheckpointReading();
        r.setRfidTag(p.getRfidTag());
        r.setParticipant(p);
        r.setCheckpoint(cp);
        r.setCheckpointName(cp.getName());
        r.setRaceId(raceId);
        r.setReadTime(time);
        r.setDeviceId("JUNIT-DEVICE");
        readingRepository.save(r);
    }

    private File workbookFile(Path dir, String name, Object[][] rows) throws Exception {
        File file = dir.resolve(name).toFile();
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Sheet1");
            for (int r = 0; r < rows.length; r++) {
                Row row = sheet.createRow(r);
                for (int c = 0; c < rows[r].length; c++) {
                    Object v = rows[r][c];
                    if (v instanceof Number n) row.createCell(c).setCellValue(n.doubleValue());
                    else if (v != null) row.createCell(c).setCellValue(v.toString());
                }
            }
            try (FileOutputStream out = new FileOutputStream(file)) {
                wb.write(out);
            }
        }
        return file;
    }

    // ------------------------------------------------------------------ Part 1: Tag No

    @Test
    void saveParticipant_requiresTagNo() {
        Participant p = new Participant();
        p.setRfidTag("JUNIT-CHIP-NOTAG-" + System.nanoTime());
        p.setName("No Tag No");

        RuntimeException ex = assertThrows(RuntimeException.class, () -> participantService.saveParticipant(p));
        assertTrue(ex.getMessage().contains("Tag No is required"), ex.getMessage());

        p.setSerialNo("   ");
        ex = assertThrows(RuntimeException.class, () -> participantService.saveParticipant(p));
        assertTrue(ex.getMessage().contains("Tag No is required"), ex.getMessage());
    }

    @Test
    void saveParticipant_rejectsDuplicateTagNo_namingTheExistingParticipant() {
        Participant holder = createParticipant();

        Participant dup = new Participant();
        dup.setRfidTag("JUNIT-CHIP-DUP-" + System.nanoTime());
        dup.setSerialNo(" " + holder.getSerialNo() + " ");   // surrounding spaces must not dodge the check
        dup.setName("Second Runner");

        RuntimeException ex = assertThrows(RuntimeException.class, () -> participantService.saveParticipant(dup));
        assertTrue(ex.getMessage().contains("Tag No already exists"), ex.getMessage());
        assertTrue(ex.getMessage().contains(holder.getName()), ex.getMessage());
    }

    @Test
    void updateParticipant_keepingOwnTagNoIsFine_takingSomeoneElsesIsNot() {
        Participant a = createParticipant();
        Participant b = createParticipant();
        // The UI hands updateParticipant() a detached entity (loaded in an earlier
        // transaction); mimic that so edits to `a` aren't auto-flushed into the query.
        em.flush();
        em.clear();

        a.setName("Renamed");
        assertDoesNotThrow(() -> participantService.updateParticipant(a));

        a.setSerialNo(b.getSerialNo());
        RuntimeException ex = assertThrows(RuntimeException.class, () -> participantService.updateParticipant(a));
        assertTrue(ex.getMessage().contains("Tag No already exists"), ex.getMessage());

        a.setSerialNo(null);
        ex = assertThrows(RuntimeException.class, () -> participantService.updateParticipant(a));
        assertTrue(ex.getMessage().contains("Tag No is required"), ex.getMessage());
    }

    @Test
    void excelImport_enforcesTagNoPresenceAndUniquenessIncludingWithinTheFile(@TempDir Path dir) throws Exception {
        String u = String.valueOf(System.nanoTime());
        Participant existing = createParticipant();

        Object[] header = {"RFID Tag*", "Name", "Service Number", "Rank", "Trade", "Branch", "Unit", "Section", "Tag No*"};
        File file = workbookFile(dir, "participants.xlsx", new Object[][]{
            header,
            {"IMP-R1-" + u, "Ok One", null, null, null, null, null, null, "IMP-T1-" + u},              // row 2 ok
            {"IMP-R2-" + u, "No Tag", null, null, null, null, null, null, null},                         // row 3 missing
            {"IMP-R3-" + u, "Dup In File", null, null, null, null, null, null, "IMP-T1-" + u},         // row 4 dup of row 2
            {"IMP-R4-" + u, "Dup In DB", null, null, null, null, null, null, existing.getSerialNo()},  // row 5 dup of DB
            {"IMP-R5-" + u, "Ok Two", null, null, null, null, null, null, "IMP-T2-" + u},              // row 6 ok
        });

        List<String> errors = participantService.importFromExcel(file);

        assertEquals(3, errors.size(), errors.toString());
        assertTrue(errors.get(0).startsWith("Row 3") && errors.get(0).contains("Missing Tag No"), errors.get(0));
        assertTrue(errors.get(1).startsWith("Row 4") && errors.get(1).contains("row 2 of this file"), errors.get(1));
        assertTrue(errors.get(2).startsWith("Row 5") && errors.get(2).contains("Tag No already exists"), errors.get(2));

        assertTrue(participantRepository.findBySerialNo("IMP-T1-" + u).isPresent());
        assertTrue(participantRepository.findBySerialNo("IMP-T2-" + u).isPresent());
        assertFalse(participantRepository.existsByRfidTag("IMP-R3-" + u), "the in-file duplicate must not be saved");
    }

    @Test
    void excelImport_readsColumnsByHeaderName_notPosition(@TempDir Path dir) throws Exception {
        String u = String.valueOf(System.nanoTime());
        // Reordered, partially filled, case/marker differences, and an export-only "Age" column
        File file = workbookFile(dir, "reordered.xlsx", new Object[][]{
            {"Unit", "tag no *", "Name", "rfid tag*", "Age"},
            {"3 Mahar", "RO-T1-" + u, "Reordered One", "RO-R1-" + u, 30},
        });

        List<String> errors = participantService.importFromExcel(file);

        assertTrue(errors.isEmpty(), errors.toString());
        Participant saved = participantRepository.findBySerialNo("RO-T1-" + u).orElseThrow();
        assertEquals("RO-R1-" + u, saved.getRfidTag());
        assertEquals("Reordered One", saved.getName());
        assertEquals("3 Mahar", saved.getUnit());
    }

    @Test
    void excelImport_rejectsFileWhoseHeaderDoesNotMatch(@TempDir Path dir) throws Exception {
        // Positional data that would previously have imported "silently misassigned"
        File noTagNo = workbookFile(dir, "no-tagno.xlsx", new Object[][]{
            {"RFID Tag*", "Name"}, {"HDR-R1-" + System.nanoTime(), "X"}});
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> participantService.importFromExcel(noTagNo));
        assertTrue(ex.getMessage().contains("Missing required column \"Tag No*\""), ex.getMessage());

        File unknown = workbookFile(dir, "unknown.xlsx", new Object[][]{
            {"RFID Tag*", "Tag No*", "Nmae"}, {"HDR-R2-" + System.nanoTime(), "HDR-T2", "X"}});
        ex = assertThrows(IllegalArgumentException.class, () -> participantService.importFromExcel(unknown));
        assertTrue(ex.getMessage().contains("\"Nmae\""), ex.getMessage());
        assertFalse(participantRepository.findBySerialNo("HDR-T2").isPresent(), "nothing may be imported");
    }

    @Test
    void excelImport_tagNoDuplicateWithinFileIsCaseInsensitive_andBlankRowsAreSkipped(@TempDir Path dir) throws Exception {
        String u = String.valueOf(System.nanoTime());
        File file = workbookFile(dir, "case.xlsx", new Object[][]{
            {"RFID Tag*", "Tag No*"},
            {"CI-R1-" + u, "ab-" + u},          // row 2 ok
            {},                                  // row 3 blank - skipped, not an error
            {"CI-R2-" + u, "AB-" + u},          // row 4 collides with row 2 ignoring case
        });

        List<String> errors = participantService.importFromExcel(file);

        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).startsWith("Row 4") && errors.get(0).contains("row 2 of this file"), errors.get(0));
    }

    @Test
    void excelImport_reportsUnparseableDobAsRowError(@TempDir Path dir) throws Exception {
        String u = String.valueOf(System.nanoTime());
        File file = workbookFile(dir, "dob.xlsx", new Object[][]{
            {"RFID Tag*", "Tag No*", "Date of Birth"},
            {"DOB-R1-" + u, "DOB-T1-" + u, "1990-04-15"},
            {"DOB-R2-" + u, "DOB-T2-" + u, "not a date"},
        });

        List<String> errors = participantService.importFromExcel(file);

        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).startsWith("Row 3") && errors.get(0).contains("Invalid Date of Birth"), errors.get(0));
        assertEquals(java.time.LocalDate.of(1990, 4, 15),
            participantRepository.findBySerialNo("DOB-T1-" + u).orElseThrow().getDateOfBirth());
    }

    @Test
    void excelExport_omitsCategoryAndRemarks_butKeepsTheOtherColumns(@TempDir Path dir) throws Exception {
        Participant p = createParticipant();
        p.setCategory("Open");
        p.setRemarks("note");
        participantRepository.save(p);
        em.flush();

        File file = dir.resolve("export.xlsx").toFile();
        participantService.exportToExcel(file);

        try (Workbook wb = new XSSFWorkbook(new java.io.FileInputStream(file))) {
            List<String> titles = new java.util.ArrayList<>();
            wb.getSheetAt(0).getRow(0).forEach(c -> titles.add(c.getStringCellValue()));
            assertFalse(titles.contains("Category"), titles.toString());
            assertFalse(titles.contains("Remarks"), titles.toString());
            assertTrue(titles.contains("RFID Tag") && titles.contains("Name") && titles.contains("Serial No"), titles.toString());
            assertEquals(12, titles.size(), titles.toString());
        }
        // The fields themselves are untouched on the entity
        assertEquals("Open", participantRepository.findById(p.getId()).orElseThrow().getCategory());
    }

    @Test
    void importTemplate_marksMandatoryColumns_andIsAcceptedByImport(@TempDir Path dir) throws Exception {
        File file = dir.resolve("template.xlsx").toFile();
        participantService.writeImportTemplate(file);

        try (Workbook wb = new XSSFWorkbook(new java.io.FileInputStream(file))) {
            Row header = wb.getSheetAt(0).getRow(0);
            List<String> titles = new java.util.ArrayList<>();
            header.forEach(c -> titles.add(c.getStringCellValue()));
            assertTrue(titles.contains("RFID Tag*"), titles.toString());
            assertTrue(titles.contains("Tag No*"), titles.toString());
            assertTrue(titles.contains("Name"), titles.toString());
            assertEquals(2, titles.stream().filter(t -> t.endsWith("*")).count(), "only the two mandatory columns are marked");
            // Template mirrors the Registration form fields, nothing more
            assertEquals(List.of("RFID Tag*", "Name", "Service Number", "Rank", "Unit", "Tag No*",
                    "Date of Birth", "Age", "Age Group"), titles);
        }
        // An untouched template imports cleanly (no rows, no header error)
        assertTrue(participantService.importFromExcel(file).isEmpty());
    }

    // ------------------------------------------------------------------ Part 2: scan-time rejection

    @Test
    void offRosterTag_isRejectedAtScanTime_noReadingSaved_noAutoStart() {
        Race race = createRace(Race.RaceStatus.ARMED);
        Checkpoint start = createCheckpoint(race.getId(), "Start", 1, true, false);
        Participant onRoster = createParticipant();
        Participant offRoster = createParticipant();

        raceEntryService.saveRoster(race.getId(), Map.of(onRoster.getId(), RaceEntry.Source.MANUAL));
        multiReaderService.armReaders(race.getId(), List.of());   // sets the current race id, connects nothing

        int offRosterBefore = multiReaderService.getOffRosterScanCount();
        int unregisteredBefore = multiReaderService.getUnregisteredScanCount();

        // Registered-but-not-rostered tag at the start line
        multiReaderService.handleTagRead(offRoster.getRfidTag(), start, BASE, "READER_A");

        assertEquals(0, readingRepository.countByRaceId(race.getId()), "off-roster scan must not create a CheckpointReading");
        assertEquals(Race.RaceStatus.ARMED, raceRepository.findById(race.getId()).orElseThrow().getStatus(),
            "off-roster scan must not trigger auto-start");
        assertEquals(offRosterBefore + 1, multiReaderService.getOffRosterScanCount());
        assertEquals(unregisteredBefore, multiReaderService.getUnregisteredScanCount(),
            "off-roster scans are counted separately from unregistered ones");
        assertEquals(offRoster.getRfidTag(), multiReaderService.getLastOffRosterTag());
        assertTrue(multiReaderService.getLastOffRosterParticipant().contains(offRoster.getSerialNo()));

        // Rostered tag is still accepted and still starts the race
        multiReaderService.handleTagRead(onRoster.getRfidTag(), start, BASE.plusSeconds(5), "READER_A");

        assertEquals(1, readingRepository.countByRaceId(race.getId()));
        assertEquals(Race.RaceStatus.ACTIVE, raceRepository.findById(race.getId()).orElseThrow().getStatus());
        assertEquals(offRosterBefore + 1, multiReaderService.getOffRosterScanCount());

        // A completely unknown chip still goes down the unregistered path, not the roster one
        multiReaderService.handleTagRead("JUNIT-UNKNOWN-" + System.nanoTime(), start, BASE.plusSeconds(9), "READER_A");
        assertEquals(unregisteredBefore + 1, multiReaderService.getUnregisteredScanCount());
        assertEquals(offRosterBefore + 1, multiReaderService.getOffRosterScanCount());
    }

    @Test
    void noRosterSaved_everyRegisteredTagIsScannable() {
        Race race = createRace(Race.RaceStatus.ARMED);
        Checkpoint start = createCheckpoint(race.getId(), "Start", 1, true, false);
        Participant anyone = createParticipant();

        multiReaderService.armReaders(race.getId(), List.of());
        int offRosterBefore = multiReaderService.getOffRosterScanCount();

        multiReaderService.handleTagRead(anyone.getRfidTag(), start, BASE, "READER_A");

        assertEquals(1, readingRepository.countByRaceId(race.getId()));
        assertEquals(offRosterBefore, multiReaderService.getOffRosterScanCount());
    }

    // ------------------------------------------------------------------ Part 2: results

    @Test
    void results_withRoster_seedFromRosterAndMarkNoShows_excludeNonRoster() {
        Race race = createRace(Race.RaceStatus.ACTIVE);
        Checkpoint start = createCheckpoint(race.getId(), "Start", 1, true, false);
        Checkpoint finish = createCheckpoint(race.getId(), "Finish", 2, false, true);

        Participant finisher = createParticipant();
        Participant noShow = createParticipant();
        Participant outsider = createParticipant();   // not on the roster, but has a reading

        reading(finisher, race.getId(), start, BASE);
        reading(finisher, race.getId(), finish, BASE.plusMinutes(30));
        reading(outsider, race.getId(), start, BASE);

        raceEntryService.saveRoster(race.getId(), Map.of(
            finisher.getId(), RaceEntry.Source.MANUAL,
            noShow.getId(), RaceEntry.Source.EXCEL_UPLOAD));

        timingService.calculateRaceResults(race.getId(), Race.CalculationMode.TWO_CHECKPOINT);

        List<RaceResult> results = resultRepository.findByRaceId(race.getId());
        assertEquals(2, results.size(), "results must be exactly the roster");

        RaceResult finished = results.stream().filter(r -> r.getParticipant().getId().equals(finisher.getId())).findFirst().orElseThrow();
        assertTrue(finished.isCompletedRace());
        assertFalse(finished.isNoShow());

        RaceResult missing = results.stream().filter(r -> r.getParticipant().getId().equals(noShow.getId())).findFirst().orElseThrow();
        assertFalse(missing.isCompletedRace());
        assertTrue(missing.isNoShow());
        assertEquals("DNF", missing.getFormattedTime());
        assertEquals(0, missing.getCheckpointCount());

        assertTrue(results.stream().noneMatch(r -> r.getParticipant().getId().equals(outsider.getId())));
    }

    @Test
    void results_withRoster_alsoSeedInReadCountModes() {
        Race race = createRace(Race.RaceStatus.ACTIVE);
        createCheckpoint(race.getId(), "Gate", 1, true, true);
        Participant noShow = createParticipant();

        raceEntryService.saveRoster(race.getId(), Map.of(noShow.getId(), RaceEntry.Source.MANUAL));

        timingService.calculateRaceResults(race.getId(), Race.CalculationMode.SAME_START_FINISH);

        List<RaceResult> results = resultRepository.findByRaceId(race.getId());
        assertEquals(1, results.size());
        assertTrue(results.get(0).isNoShow());
    }

    @Test
    void results_withoutRoster_behaviourUnchanged_onlyParticipantsWithReadingsAppear() {
        Race race = createRace(Race.RaceStatus.ACTIVE);
        Checkpoint start = createCheckpoint(race.getId(), "Start", 1, true, false);
        createCheckpoint(race.getId(), "Finish", 2, false, true);

        Participant started = createParticipant();
        Participant silent = createParticipant();
        reading(started, race.getId(), start, BASE);

        timingService.calculateRaceResults(race.getId(), Race.CalculationMode.TWO_CHECKPOINT);

        List<RaceResult> results = resultRepository.findByRaceId(race.getId());
        assertTrue(results.stream().anyMatch(r -> r.getParticipant().getId().equals(started.getId())));
        assertTrue(results.stream().noneMatch(r -> r.getParticipant().getId().equals(silent.getId())),
            "without a roster, participants with no readings are still omitted");
        assertTrue(results.stream().noneMatch(RaceResult::isNoShow));
    }

    // ------------------------------------------------------------------ Part 2: roster storage + Excel

    @Test
    void saveRoster_replacesFromScratch_andEmptySelectionClearsIt() {
        Race race = createRace(Race.RaceStatus.NOT_STARTED);
        Participant a = createParticipant();
        Participant b = createParticipant();

        raceEntryService.saveRoster(race.getId(), Map.of(a.getId(), RaceEntry.Source.MANUAL));
        assertTrue(raceEntryRepository.existsByRaceIdAndParticipantId(race.getId(), a.getId()));

        raceEntryService.saveRoster(race.getId(), Map.of(b.getId(), RaceEntry.Source.EXCEL_UPLOAD));
        assertFalse(raceEntryRepository.existsByRaceIdAndParticipantId(race.getId(), a.getId()));
        assertEquals(RaceEntry.Source.EXCEL_UPLOAD, raceEntryService.getRosterSources(race.getId()).get(b.getId()));
        assertEquals(1, raceEntryService.getRosterSize(race.getId()));

        raceEntryService.saveRoster(race.getId(), Map.of());
        assertFalse(raceEntryService.hasRoster(race.getId()));
    }

    @Test
    void deletingParticipant_removesTheirRosterEntries() {
        Race race = createRace(Race.RaceStatus.NOT_STARTED);
        Participant a = createParticipant();
        raceEntryService.saveRoster(race.getId(), Map.of(a.getId(), RaceEntry.Source.MANUAL));

        participantService.deleteParticipant(a.getId());

        assertFalse(raceEntryService.hasRoster(race.getId()));
    }

    @Test
    void tagNoTemplate_hasOnlyTheTagNoHeader_andReadsBackEmpty(@TempDir Path dir) throws Exception {
        File file = dir.resolve("template.xlsx").toFile();
        raceEntryService.writeTagNoTemplate(file);

        try (Workbook wb = new XSSFWorkbook(file)) {
            Sheet sheet = wb.getSheetAt(0);
            assertEquals("Tag No", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals(1, sheet.getRow(0).getLastCellNum());
            assertEquals(0, sheet.getLastRowNum());
        }
        assertTrue(raceEntryService.readTagNosFromExcel(file).tagNos().isEmpty());
    }

    @Test
    void tagNoUpload_parsesColumnByHeader_matchesAndReportsUnmatched(@TempDir Path dir) throws Exception {
        String u = String.valueOf(System.nanoTime());
        Participant a = createParticipant();
        Participant b = createParticipant();

        // Tag No is the 2nd column here, values are a mix of text and numeric cells, with a blank row and a repeat
        File file = workbookFile(dir, "roster.xlsx", new Object[][]{
            {"Name", " tag no "},
            {"x", a.getSerialNo()},
            {"y", b.getSerialNo().toLowerCase()},   // matching ignores case
            {"blank", null},
            {"z", "NOPE-1-" + u},
            {"w", a.getSerialNo()},                 // repeat of row 2
            {"v", 987654321},                       // numeric cell, not registered
        });

        RaceEntryService.TagNoFile parsed = raceEntryService.readTagNosFromExcel(file);
        assertEquals(4, parsed.tagNos().size(), parsed.tagNos().toString());
        assertEquals(1, parsed.duplicateRowsIgnored());
        assertTrue(parsed.tagNos().contains("987654321"), "numeric cells are read as whole numbers");

        RaceEntryService.TagNoMatch match = raceEntryService.matchTagNos(parsed.tagNos());
        assertEquals(2, match.matched().size());
        assertEquals(2, match.notFound().size());
        assertTrue(match.notFound().contains("NOPE-1-" + u));
        assertTrue(match.notFound().contains("987654321"));
    }

    @Test
    void tagNoUpload_withoutTagNoColumn_failsClearly(@TempDir Path dir) throws Exception {
        File file = workbookFile(dir, "wrong.xlsx", new Object[][]{{"Name", "Unit"}, {"a", "b"}});

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> raceEntryService.readTagNosFromExcel(file));
        assertTrue(ex.getMessage().contains("Tag No"));
    }
}
