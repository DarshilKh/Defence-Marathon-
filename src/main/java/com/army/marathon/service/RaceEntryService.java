package com.army.marathon.service;

import com.army.marathon.model.Participant;
import com.army.marathon.model.Race;
import com.army.marathon.model.RaceEntry;
import com.army.marathon.repository.ParticipantRepository;
import com.army.marathon.repository.RaceEntryRepository;
import com.army.marathon.repository.RaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Race-day roster: which registered participants are running in a given race.
 * A race with no saved roster behaves exactly as before rosters existed (everyone
 * registered is scannable and appears in results).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RaceEntryService {

    /** Column header used by both the downloadable template and the upload parser. */
    public static final String TAG_NO_HEADER = "Tag No";

    /**
     * The app has one live race, and it is always looked up by this id
     * (see LiveTrackingController.currentRace()). MultiReaderService stamps
     * CheckpointReading.raceId with the id of that same race.
     */
    private static final long CURRENT_RACE_ID = 1L;

    private final RaceEntryRepository raceEntryRepository;
    private final RaceRepository raceRepository;
    private final ParticipantRepository participantRepository;

    /** The race the roster applies to, or empty if Live Tracking hasn't created it yet. */
    public Optional<Race> getCurrentRace() {
        return raceRepository.findById(CURRENT_RACE_ID);
    }

    public boolean hasRoster(Long raceId) {
        return raceId != null && raceEntryRepository.existsByRaceId(raceId);
    }

    public long getRosterSize(Long raceId) {
        return raceId == null ? 0 : raceEntryRepository.countByRaceId(raceId);
    }

    /** participantId -> how that entry was added, for pre-checking the roster table. */
    public Map<Long, RaceEntry.Source> getRosterSources(Long raceId) {
        Map<Long, RaceEntry.Source> sources = new HashMap<>();
        for (RaceEntry entry : raceEntryRepository.findByRaceId(raceId)) {
            sources.put(entry.getParticipantId(), entry.getSource());
        }
        return sources;
    }

    public Set<Long> getRosterParticipantIds(Long raceId) {
        Set<Long> ids = new HashSet<>();
        for (RaceEntry entry : raceEntryRepository.findByRaceId(raceId)) {
            ids.add(entry.getParticipantId());
        }
        return ids;
    }

    /**
     * Replaces the race's roster with exactly {@code selection}
     * (participantId -> source). An empty selection clears the roster, which puts
     * the race back in "everyone registered is eligible" mode.
     */
    @Transactional
    public void saveRoster(Long raceId, Map<Long, RaceEntry.Source> selection) {
        if (raceId == null || !raceRepository.existsById(raceId)) {
            throw new IllegalStateException("No current race to save a roster for");
        }

        raceEntryRepository.deleteByRaceId(raceId);

        LocalDateTime now = LocalDateTime.now();
        List<RaceEntry> entries = new ArrayList<>(selection.size());
        selection.forEach((participantId, source) -> {
            RaceEntry entry = new RaceEntry();
            entry.setRaceId(raceId);
            entry.setParticipantId(participantId);
            entry.setSource(source != null ? source : RaceEntry.Source.MANUAL);
            entry.setAddedAt(now);
            entries.add(entry);
        });
        raceEntryRepository.saveAll(entries);

        log.info("📋 Roster saved for race {}: {} participant(s)", raceId, entries.size());
    }

    // ------------------------------------------------------------------
    // Tag No Excel: template + upload
    // ------------------------------------------------------------------

    /** Distinct Tag No values read from an upload, plus how many repeated rows were folded away. */
    public record TagNoFile(List<String> tagNos, int duplicateRowsIgnored) {}

    /** Outcome of resolving uploaded Tag Nos against registered participants. */
    public record TagNoMatch(List<Participant> matched, List<String> notFound) {}

    /**
     * Writes a single-column workbook whose only content is the "Tag No" header.
     */
    public void writeTagNoTemplate(File file) throws Exception {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Roster");

            CellStyle style = workbook.createCellStyle();
            Font font = workbook.createFont();
            font.setBold(true);
            style.setFont(font);

            Cell header = sheet.createRow(0).createCell(0);
            header.setCellValue(TAG_NO_HEADER);
            header.setCellStyle(style);
            sheet.setColumnWidth(0, 16 * 256);

            try (FileOutputStream fos = new FileOutputStream(file)) {
                workbook.write(fos);
            }
        }
    }

    /**
     * Reads the "Tag No" column of the first sheet. The header is located in the first
     * row (case/spacing-insensitive), so extra columns are ignored.
     *
     * @throws IllegalArgumentException if the file has no "Tag No" column
     */
    public TagNoFile readTagNosFromExcel(File file) throws Exception {
        try (FileInputStream fis = new FileInputStream(file);
             Workbook workbook = new XSSFWorkbook(fis)) {

            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(sheet.getFirstRowNum());

            int tagNoColumn = -1;
            if (headerRow != null) {
                for (Cell cell : headerRow) {
                    String text = ParticipantService.getCellValueAsString(cell);
                    if (text != null && normalizeHeader(text).equals(normalizeHeader(TAG_NO_HEADER))) {
                        tagNoColumn = cell.getColumnIndex();
                        break;
                    }
                }
            }
            if (tagNoColumn < 0) {
                throw new IllegalArgumentException(
                    "Column \"" + TAG_NO_HEADER + "\" not found in the first row of the first sheet");
            }

            Set<String> distinct = new LinkedHashSet<>();
            int duplicates = 0;
            for (int r = sheet.getFirstRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;

                String value = ParticipantService.getCellValueAsString(row.getCell(tagNoColumn));
                if (value == null || value.trim().isEmpty()) continue;

                if (!distinct.add(value.trim())) {
                    duplicates++;
                }
            }
            return new TagNoFile(new ArrayList<>(distinct), duplicates);
        }
    }

    /**
     * Resolves Tag Nos against Participant.serialNo. Comparison ignores case and
     * surrounding whitespace. Every input value ends up either matched or in
     * {@code notFound} - nothing is dropped.
     */
    public TagNoMatch matchTagNos(Collection<String> tagNos) {
        Map<String, Participant> byTagNo = new HashMap<>();
        for (Participant p : participantRepository.findAll()) {
            if (p.getSerialNo() != null) {
                byTagNo.putIfAbsent(normalizeTagNo(p.getSerialNo()), p);
            }
        }

        // Keyed by id so two spellings of the same Tag No ("A1" / "a1") count once
        Map<Long, Participant> matched = new LinkedHashMap<>();
        List<String> notFound = new ArrayList<>();
        for (String tagNo : tagNos) {
            Participant p = byTagNo.get(normalizeTagNo(tagNo));
            if (p != null) {
                matched.putIfAbsent(p.getId(), p);
            } else {
                notFound.add(tagNo);
            }
        }
        return new TagNoMatch(new ArrayList<>(matched.values()), notFound);
    }

    private static String normalizeTagNo(String tagNo) {
        return tagNo.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeHeader(String header) {
        return header.toLowerCase(Locale.ROOT).replaceAll("[\\s._-]", "");
    }
}
