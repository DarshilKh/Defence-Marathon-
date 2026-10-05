package com.army.marathon.service;

import com.army.marathon.model.Participant;
import com.army.marathon.repository.CheckpointReadingRepository;
import com.army.marathon.repository.ParticipantRepository;
import com.army.marathon.repository.RaceEntryRepository;
import com.army.marathon.repository.RaceResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ParticipantService {

    private final ParticipantRepository participantRepository;
    private final RaceEntryRepository raceEntryRepository;
    private final RaceResultRepository raceResultRepository;
    private final CheckpointReadingRepository checkpointReadingRepository;

    @Transactional
    public Participant saveParticipant(Participant participant) {
        // ✅ RFID Tag and Tag No are mandatory and checked for duplicates
        String rfidTag = participant.getRfidTag();
        if (rfidTag == null || rfidTag.trim().isEmpty()) {
            throw new RuntimeException("RFID Tag is required!");
        }
        
        // ✅ Check RFID duplicate
        if (participantRepository.existsByRfidTag(rfidTag.trim())) {
            throw new RuntimeException("RFID Tag already exists: " + rfidTag);
        }
        
        // ✅ Tag No: mandatory + unique
        String serialNo = requireSerialNo(participant.getSerialNo());
        participant.setSerialNo(serialNo);
        assertSerialNoAvailable(serialNo, null);

        // ✅ NO validation for any other fields - all optional!
        
        return participantRepository.save(participant);
    }

    @Transactional
    public Participant updateParticipant(Participant participant) {
        // Check if participant exists
        Participant existing = participantRepository.findById(participant.getId())
            .orElseThrow(() -> new RuntimeException("Participant not found with ID: " + participant.getId()));
        
        // ✅ ONLY validate RFID if changed
        String rfidTag = participant.getRfidTag();
        if (rfidTag != null && !rfidTag.trim().isEmpty()) {
            String existingRfid = existing.getRfidTag();
            if (existingRfid == null || !rfidTag.equals(existingRfid)) {
                if (participantRepository.existsByRfidTag(rfidTag.trim())) {
                    throw new RuntimeException("RFID Tag already exists: " + rfidTag);
                }
            }
        }
        
        // ✅ Tag No: mandatory + unique (excluding this participant's own row)
        String serialNo = requireSerialNo(participant.getSerialNo());
        participant.setSerialNo(serialNo);
        assertSerialNoAvailable(serialNo, participant.getId());

        // ✅ NO validation for any other fields - all optional!
        
        return participantRepository.save(participant);
    }

    /**
     * Tag No must be present. Returns the trimmed value.
     */
    private String requireSerialNo(String serialNo) {
        if (serialNo == null || serialNo.trim().isEmpty()) {
            throw new RuntimeException("Tag No is required!");
        }
        return serialNo.trim();
    }

    /**
     * Rejects a Tag No already held by a different participant, naming that participant.
     *
     * @param ownerId id of the participant being saved (null for a new one), so a
     *                participant re-saving its own Tag No isn't flagged as a conflict
     */
    private void assertSerialNoAvailable(String serialNo, Long ownerId) {
        Optional<Participant> holder = participantRepository.findBySerialNo(serialNo);
        if (holder.isPresent() && !holder.get().getId().equals(ownerId)) {
            Participant other = holder.get();
            throw new RuntimeException("Tag No already exists: " + serialNo
                + " (assigned to " + (other.getName() != null ? other.getName() : "unnamed participant")
                + ", RFID Tag " + other.getRfidTag() + ")");
        }
    }

    /**
     * Permanently deletes a participant, including their scanned readings and race results,
     * which hold foreign keys to the participant and would otherwise block the delete.
     */
    @Transactional
    public void deleteParticipant(Long id) {
        // Drop any race-day roster rows first so a deleted participant can't leave a
        // dangling roster entry (which would still count toward the roster size).
        raceEntryRepository.deleteByParticipantId(id);
        int results = raceResultRepository.deleteAllByParticipantId(id);
        int readings = checkpointReadingRepository.deleteAllByParticipantId(id);
        participantRepository.deleteById(id);
        log.info("Deleted participant with ID: {} (also removed {} readings, {} results)", id, readings, results);
    }

    /** Readings and results that deleting this participant would also remove (for the confirm dialog). */
    public long[] countParticipantHistory(Long id) {
        return new long[] {
            checkpointReadingRepository.findByParticipantIdOrderByReadTimeAsc(id).size(),
            raceResultRepository.findByParticipantIdOrderByNetTimeSecondsAsc(id).size()
        };
    }

    public List<Participant> getAllParticipants() {
        return participantRepository.findAll();
    }

    public Participant getByRfidTag(String rfidTag) {
        return participantRepository.findByRfidTag(rfidTag).orElse(null);
    }

    public Participant getByServiceNumber(String serviceNumber) {
        return participantRepository.findByServiceNumber(serviceNumber).orElse(null);
    }

    /**
     * ✅ UPDATED: Search by RFID Tag OR Serial No
     * 
     * Searches for a participant by either RFID Tag or Serial No (NOT Service Number).
     * First tries RFID Tag, then Serial No if not found.
     * 
     * @param searchText The RFID Tag or Serial No to search for
     * @return Participant if found, null otherwise
     */
    public Participant searchByRfidOrSerial(String searchText) {
        if (searchText == null || searchText.trim().isEmpty()) {
            log.warn("⚠️ Empty search text provided");
            return null;
        }
        
        String trimmed = searchText.trim();
        
        // First try RFID Tag
        Participant byRfid = participantRepository.findByRfidTag(trimmed).orElse(null);
        if (byRfid != null) {
            log.info("✅ Found participant by RFID Tag: {}", trimmed);
            return byRfid;
        }
        
        // ✅ FIXED: Then try Serial No (using findBySerialNo, NOT findByServiceNumber)
        Participant bySerial = participantRepository.findBySerialNo(trimmed).orElse(null);
        if (bySerial != null) {
            log.info("✅ Found participant by Serial No: {}", trimmed);
            return bySerial;
        }
        
        log.warn("⚠️ No participant found for search text: {}", trimmed);
        return null;
    }

    public List<Participant> searchParticipants(String searchText) {
        return participantRepository.searchParticipants(searchText);
    }

    public long getTotalCount() {
        return participantRepository.count();
    }

    public long getCountWithRfid() {
        return participantRepository.countWithRfidTag();
    }

    public long getCountWithoutRfid() {
        return participantRepository.countWithoutRfidTag();
    }

    // ------------------------------------------------------------------
    // Excel import / template
    // ------------------------------------------------------------------

    /**
     * Columns the bulk-import understands, in template order. The import locates each one by
     * its header text (not its position), so a reordered or partially-filled sheet still maps
     * correctly. Header matching ignores case, spacing, punctuation and the "*" mandatory marker.
     */
    enum ImportColumn {
        RFID_TAG("RFID Tag", true, "rfidtag"),
        NAME("Name", false, "name"),
        SERVICE_NUMBER("Service Number", false, "servicenumber", "serviceno"),
        RANK("Rank", false, "rank"),
        TRADE("Trade", false, "trade"),
        BRANCH("Branch", false, "branch"),
        UNIT("Unit", false, "unit"),
        SECTION("Section", false, "section"),
        TAG_NO("Tag No", true, "tagno", "serialno", "sno"),
        DATE_OF_BIRTH("Date of Birth", false, "dateofbirth", "dob"),
        CATEGORY("Category", false, "category"),
        REMARKS("Remarks", false, "remarks");

        final String header;
        final boolean mandatory;
        final List<String> aliases;

        ImportColumn(String header, boolean mandatory, String... aliases) {
            this.header = header;
            this.mandatory = mandatory;
            this.aliases = List.of(aliases);
        }

        /** Header text as written into the template, e.g. "RFID Tag*". */
        String templateHeader() {
            return mandatory ? header + "*" : header;
        }
    }

    /**
     * Columns written into the downloadable template: exactly the fields on the Registration
     * form, in form order. The import still accepts the other ImportColumn headers (Trade,
     * Branch, Section, Category, Remarks) so files exported earlier keep importing.
     */
    private static final List<ImportColumn> TEMPLATE_COLUMNS = List.of(
        ImportColumn.RFID_TAG, ImportColumn.NAME, ImportColumn.SERVICE_NUMBER,
        ImportColumn.RANK, ImportColumn.UNIT, ImportColumn.TAG_NO,
        ImportColumn.DATE_OF_BIRTH);

    /** Auto-calculated columns from the Registered Participants table; header-only in the template. */
    private static final List<String> TEMPLATE_COMPUTED_HEADERS = List.of("Age", "Age Group");

    /** Computed columns that appear in an Export file; tolerated (and ignored) on import. */
    private static final List<String> IGNORED_IMPORT_HEADERS = List.of("age", "agegroup");

    private static final DateTimeFormatter[] IMPORT_DATE_FORMATS = {
        DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("d/M/uuuu"),
        DateTimeFormatter.ofPattern("d-M-uuuu"),
    };

    private static String normalizeHeader(String header) {
        return header.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /**
     * Writes the bulk-registration template: one header row naming every importable field,
     * with the mandatory ones ("RFID Tag*", "Tag No*") marked. Tag columns are formatted as
     * text so Excel doesn't strip leading zeros from tag numbers.
     */
    public void writeImportTemplate(File file) throws Exception {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Participants");

            Font optionalFont = workbook.createFont();
            optionalFont.setBold(true);
            CellStyle optionalStyle = workbook.createCellStyle();
            optionalStyle.setFont(optionalFont);

            Font mandatoryFont = workbook.createFont();
            mandatoryFont.setBold(true);
            mandatoryFont.setColor(IndexedColors.DARK_RED.getIndex());
            CellStyle mandatoryStyle = workbook.createCellStyle();
            mandatoryStyle.setFont(mandatoryFont);

            DataFormat dataFormat = workbook.createDataFormat();
            CellStyle textColumn = workbook.createCellStyle();
            textColumn.setDataFormat(dataFormat.getFormat("@"));
            CellStyle dateColumn = workbook.createCellStyle();
            dateColumn.setDataFormat(dataFormat.getFormat("yyyy-mm-dd"));

            Row header = sheet.createRow(0);
            for (int i = 0; i < TEMPLATE_COLUMNS.size(); i++) {
                ImportColumn col = TEMPLATE_COLUMNS.get(i);
                Cell cell = header.createCell(i);
                cell.setCellValue(col.templateHeader());
                cell.setCellStyle(col.mandatory ? mandatoryStyle : optionalStyle);

                if (col == ImportColumn.DATE_OF_BIRTH) {
                    sheet.setDefaultColumnStyle(i, dateColumn);
                } else if (col == ImportColumn.RFID_TAG || col == ImportColumn.TAG_NO
                        || col == ImportColumn.SERVICE_NUMBER) {
                    sheet.setDefaultColumnStyle(i, textColumn);
                }
                sheet.setColumnWidth(i, Math.max(col.templateHeader().length() + 4, 14) * 256);
            }
            // Age / Age Group are shown in the Registered Participants table but are worked out
            // from Date of Birth, so the template carries the headers only and the import ignores them.
            for (int j = 0; j < TEMPLATE_COMPUTED_HEADERS.size(); j++) {
                int i = TEMPLATE_COLUMNS.size() + j;
                Cell cell = header.createCell(i);
                cell.setCellValue(TEMPLATE_COMPUTED_HEADERS.get(j));
                cell.setCellStyle(optionalStyle);
                sheet.setColumnWidth(i, 14 * 256);
            }
            sheet.createFreezePane(0, 1);

            try (FileOutputStream fos = new FileOutputStream(file)) {
                workbook.write(fos);
            }
        }
    }

    /**
     * Resolves the header row to column positions. Fails - rather than guessing - when a
     * mandatory column is missing, a header isn't recognised, or a column appears twice.
     */
    private Map<ImportColumn, Integer> readImportHeader(Row headerRow) {
        Map<ImportColumn, Integer> positions = new EnumMap<>(ImportColumn.class);
        List<String> unknown = new ArrayList<>();
        List<String> duplicated = new ArrayList<>();

        if (headerRow != null) {
            for (Cell cell : headerRow) {
                String text = getCellValueAsString(cell);
                if (text == null) continue;
                String key = normalizeHeader(text);
                if (key.isEmpty() || IGNORED_IMPORT_HEADERS.contains(key)) continue;

                ImportColumn match = null;
                for (ImportColumn col : ImportColumn.values()) {
                    if (col.aliases.contains(key)) {
                        match = col;
                        break;
                    }
                }
                if (match == null) {
                    unknown.add("\"" + text + "\"");
                } else if (positions.putIfAbsent(match, cell.getColumnIndex()) != null) {
                    duplicated.add("\"" + match.header + "\"");
                }
            }
        }

        List<String> problems = new ArrayList<>();
        for (ImportColumn col : ImportColumn.values()) {
            if (col.mandatory && !positions.containsKey(col)) {
                problems.add("Missing required column \"" + col.templateHeader() + "\"");
            }
        }
        if (!unknown.isEmpty()) {
            problems.add("Unrecognised column(s): " + String.join(", ", unknown));
        }
        if (!duplicated.isEmpty()) {
            problems.add("Column(s) appear more than once: " + String.join(", ", duplicated));
        }
        if (!problems.isEmpty()) {
            StringBuilder expected = new StringBuilder();
            for (ImportColumn col : TEMPLATE_COLUMNS) {
                if (expected.length() > 0) expected.append(", ");
                expected.append(col.templateHeader());
            }
            throw new IllegalArgumentException("The header row (row 1) does not match the template.\n"
                + String.join("\n", problems)
                + "\n\nExpected columns: " + expected
                + "\nUse \"Download Template\" to get a correct file.");
        }
        return positions;
    }

    @Transactional
    public List<String> importFromExcel(File file) {
        List<String> errors = new ArrayList<>();
        // Tag No (case-insensitive, like roster matching) -> row number of the first accepted
        // row carrying it, so two rows in the same file that collide are reported as such
        // rather than as a DB conflict.
        Map<String, Integer> tagNosInFile = new HashMap<>();
        int rowsProcessed = 0;

        try (FileInputStream fis = new FileInputStream(file);
             Workbook workbook = new XSSFWorkbook(fis)) {

            Sheet sheet = workbook.getSheetAt(0);
            if (sheet.getPhysicalNumberOfRows() == 0) {
                throw new IllegalArgumentException("The file is empty - it has no header row.");
            }
            int headerIndex = sheet.getFirstRowNum();
            Map<ImportColumn, Integer> cols = readImportHeader(sheet.getRow(headerIndex));

            for (int r = headerIndex + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null || isBlankImportRow(row, cols)) continue;

                int rowNum = r + 1; // 1-based, as shown in Excel
                rowsProcessed++;

                try {
                    Participant participant = new Participant();
                    participant.setRfidTag(importText(row, cols, ImportColumn.RFID_TAG));
                    participant.setName(importText(row, cols, ImportColumn.NAME));
                    participant.setServiceNumber(importText(row, cols, ImportColumn.SERVICE_NUMBER));
                    participant.setRank(importText(row, cols, ImportColumn.RANK));
                    participant.setTrade(importText(row, cols, ImportColumn.TRADE));
                    participant.setBranch(importText(row, cols, ImportColumn.BRANCH));
                    participant.setUnit(importText(row, cols, ImportColumn.UNIT));
                    participant.setSection(importText(row, cols, ImportColumn.SECTION));
                    participant.setSerialNo(importText(row, cols, ImportColumn.TAG_NO));
                    participant.setCategory(importText(row, cols, ImportColumn.CATEGORY));
                    participant.setRemarks(importText(row, cols, ImportColumn.REMARKS));

                    // ✅ RFID Tag and Tag No are mandatory
                    String rfidTag = participant.getRfidTag();
                    if (rfidTag == null || rfidTag.trim().isEmpty()) {
                        errors.add("Row " + rowNum + ": Missing RFID Tag (mandatory field)");
                        continue;
                    }
                    rfidTag = rfidTag.trim();
                    participant.setRfidTag(rfidTag);

                    String tagNo = participant.getSerialNo();
                    if (tagNo == null || tagNo.trim().isEmpty()) {
                        errors.add("Row " + rowNum + ": Missing Tag No (mandatory field)");
                        continue;
                    }
                    tagNo = tagNo.trim();
                    participant.setSerialNo(tagNo);

                    // Optional, but a malformed date is reported rather than silently dropped
                    Integer dobColumn = cols.get(ImportColumn.DATE_OF_BIRTH);
                    if (dobColumn != null) {
                        participant.setDateOfBirth(readImportDate(row.getCell(dobColumn)));
                    }

                    // ✅ RFID duplicate check
                    if (participantRepository.existsByRfidTag(rfidTag)) {
                        errors.add("Row " + rowNum + ": RFID Tag already exists - " + rfidTag);
                        continue;
                    }

                    // ✅ Tag No duplicate: within this file first, then against the DB
                    String tagKey = tagNo.toLowerCase(Locale.ROOT);
                    Integer earlierRow = tagNosInFile.get(tagKey);
                    if (earlierRow != null) {
                        errors.add("Row " + rowNum + ": Duplicate Tag No " + tagNo
                            + " (already used on row " + earlierRow + " of this file)");
                        continue;
                    }
                    if (participantRepository.existsBySerialNo(tagNo)) {
                        errors.add("Row " + rowNum + ": Tag No already exists - " + tagNo);
                        continue;
                    }

                    participantRepository.save(participant);
                    tagNosInFile.put(tagKey, rowNum);

                } catch (Exception e) {
                    errors.add("Row " + rowNum + ": " + e.getMessage());
                    log.error("Error importing row {}", rowNum, e);
                }
            }

            log.info("Excel import completed. Total rows processed: {}, Errors: {}",
                     rowsProcessed, errors.size());

        } catch (IllegalArgumentException e) {
            // Header / format problem: the message is already user-facing
            log.warn("Excel import rejected: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Error reading Excel file", e);
            throw new RuntimeException("Failed to import Excel: " + e.getMessage());
        }

        return errors;
    }

    private static String importText(Row row, Map<ImportColumn, Integer> cols, ImportColumn col) {
        Integer index = cols.get(col);
        return index == null ? null : getCellValueAsString(row.getCell(index));
    }

    private static boolean isBlankImportRow(Row row, Map<ImportColumn, Integer> cols) {
        for (int index : cols.values()) {
            Cell cell = row.getCell(index);
            if (cell != null && cell.getCellType() != CellType.BLANK
                    && !(cell.getCellType() == CellType.STRING && cell.getStringCellValue().trim().isEmpty())) {
                return false;
            }
        }
        return true;
    }

    /** Numeric (real Excel date) or text (yyyy-MM-dd, d/M/yyyy, d-M-yyyy); blank -> null. */
    private static LocalDate readImportDate(Cell cell) {
        if (cell == null || cell.getCellType() == CellType.BLANK) return null;

        if (cell.getCellType() == CellType.NUMERIC) {
            Date date = cell.getDateCellValue();
            return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        }
        String text = getCellValueAsString(cell);
        if (text == null) return null;
        for (DateTimeFormatter format : IMPORT_DATE_FORMATS) {
            try {
                return LocalDate.parse(text.trim(), format);
            } catch (DateTimeParseException ignored) {
                // try the next accepted format
            }
        }
        throw new IllegalArgumentException("Invalid Date of Birth \"" + text
            + "\" (use a real Excel date or yyyy-MM-dd)");
    }

    // Excel Export
    public void exportToExcel(File file) {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Participants");
            
            // Create header row
            Row headerRow = sheet.createRow(0);
            String[] headers = {
                "RFID Tag", "Name", "Service Number", "Rank", "Trade", "Branch",
                "Unit", "Section", "Serial No", "DOB", "Age", "Age Group"
            };
            
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                
                // Bold header
                CellStyle style = workbook.createCellStyle();
                Font font = workbook.createFont();
                font.setBold(true);
                style.setFont(font);
                cell.setCellStyle(style);
            }
            
            // Fill data
            List<Participant> participants = participantRepository.findAll();
            int rowNum = 1;
            
            for (Participant p : participants) {
                Row row = sheet.createRow(rowNum++);
                
                row.createCell(0).setCellValue(p.getRfidTag() != null ? p.getRfidTag() : "");
                row.createCell(1).setCellValue(p.getName() != null ? p.getName() : "");
                row.createCell(2).setCellValue(p.getServiceNumber() != null ? p.getServiceNumber() : "");
                row.createCell(3).setCellValue(p.getRank() != null ? p.getRank() : "");
                row.createCell(4).setCellValue(p.getTrade() != null ? p.getTrade() : "");
                row.createCell(5).setCellValue(p.getBranch() != null ? p.getBranch() : "");
                row.createCell(6).setCellValue(p.getUnit() != null ? p.getUnit() : "");
                row.createCell(7).setCellValue(p.getSection() != null ? p.getSection() : "");
                row.createCell(8).setCellValue(p.getSerialNo() != null ? p.getSerialNo() : "");
                row.createCell(9).setCellValue(p.getDateOfBirth() != null ? 
                                                p.getDateOfBirth().toString() : "");
                row.createCell(10).setCellValue(p.getAge() != null ? p.getAge() : 0);
                row.createCell(11).setCellValue(p.getAgeGroup() != null ? p.getAgeGroup() : "");
            }
            
            // Auto-size columns
            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
            }
            
            // Write to file
            try (FileOutputStream fos = new FileOutputStream(file)) {
                workbook.write(fos);
            }
            
            log.info("Exported {} participants to Excel", participants.size());
            
        } catch (Exception e) {
            log.error("Error exporting to Excel", e);
            throw new RuntimeException("Failed to export Excel: " + e.getMessage());
        }
    }

    static String getCellValueAsString(Cell cell) {
        if (cell == null) return null;
        
        switch (cell.getCellType()) {
            case STRING:
                String value = cell.getStringCellValue().trim();
                return value.isEmpty() ? null : value; // ✅ Return null for empty strings
            case NUMERIC:
                return String.valueOf((long) cell.getNumericCellValue());
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            default:
                return null;
        }
    }
}
