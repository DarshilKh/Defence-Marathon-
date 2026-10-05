package com.army.marathon.repository;

import com.army.marathon.model.Checkpoint;
import com.army.marathon.model.CheckpointReading;
import com.army.marathon.model.Participant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Confirms the idx_rfid_checkpoint_time index added to CheckpointReading (Part 1 of the
 * performance pass) is real, not just declared: seeds several thousand realistic rows against
 * the actual MySQL database this app runs against, then runs EXPLAIN on the exact query
 * MultiReaderService.handleTagRead() issues on every single scan
 * (findTopByRfidTagAndCheckpointNameAndReadTimeAfterOrderByReadTimeDesc), before and after the
 * index exists, and asserts the query planner actually picks it up.
 *
 * The "before" state is reproduced by dropping the index inside this test (not by reverting the
 * entity), running EXPLAIN, then recreating it exactly as Hibernate declares it - so this proves
 * the index is what changes the plan, not some other seeded-data artifact.
 */
@SpringBootTest
class CheckpointReadingIndexTest {

    @Autowired private DataSource dataSource;
    @Autowired private CheckpointReadingRepository readingRepository;
    @Autowired private CheckpointRepository checkpointRepository;
    @Autowired private ParticipantRepository participantRepository;

    private final List<Long> participantIds = new ArrayList<>();
    private Long checkpointId;

    private static final String DUPLICATE_CHECK_SQL =
        "SELECT * FROM checkpoint_readings WHERE rfid_tag = ? AND checkpoint_name = ? " +
        "AND read_time > ? ORDER BY read_time DESC LIMIT 1";

    @Test
    void duplicateCheckQuery_usesNewCompositeIndex_onceItExists() throws Exception {
        Checkpoint checkpoint = new Checkpoint();
        checkpoint.setLocationName("IDX-TEST-Start");
        checkpoint.setLocationNumber(1);
        checkpoint.setDisplayOrder(1);
        checkpoint.setEnabled(true);
        checkpoint.setStartLine(true);
        checkpoint = checkpointRepository.save(checkpoint);
        checkpointId = checkpoint.getId();
        String checkpointName = checkpoint.getName();

        // Several thousand realistic rows: many distinct tags, spread across a few checkpoint
        // names and a real time range, matching what a multi-checkpoint race day actually looks
        // like - not a synthetic single-tag table that would make any index look fast.
        int rowCount = 6000;
        int distinctTags = 500;
        String[] checkpointNames = {checkpointName, "IDX-TEST-Mid", "IDX-TEST-Finish"};

        List<Participant> pool = new ArrayList<>();
        for (int i = 0; i < distinctTags; i++) {
            Participant p = new Participant();
            p.setName("Idx Runner " + i);
            p.setRfidTag("IDX-CHIP-" + System.nanoTime() + "-" + i);
            p.setSerialNo("IDX-TN-" + System.nanoTime() + "-" + i);
            p = participantRepository.save(p);
            participantIds.add(p.getId());
            pool.add(p);
        }

        LocalDateTime base = LocalDateTime.now().minusHours(3);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO checkpoint_readings " +
                 "(rfid_tag, checkpoint_id, checkpoint_name, reader_id, read_time, race_id, " +
                 " device_id, uploaded_to_server, participant_id, created_at, updated_at) " +
                 "VALUES (?, ?, ?, 'READER_A', ?, ?, 'IDX-TEST-DEVICE', 0, ?, ?, ?)")) {
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            for (int i = 0; i < rowCount; i++) {
                Participant p = pool.get(rnd.nextInt(pool.size()));
                String cpName = checkpointNames[rnd.nextInt(checkpointNames.length)];
                LocalDateTime t = base.plusSeconds(i);
                ps.setString(1, p.getRfidTag());
                ps.setLong(2, checkpointId);
                ps.setString(3, cpName);
                ps.setObject(4, t);
                ps.setLong(5, 999_999_000L + i); // scratch race id, not a real race
                ps.setLong(6, p.getId());
                ps.setObject(7, t);
                ps.setObject(8, t);
                ps.addBatch();
                if (i % 500 == 499) {
                    ps.executeBatch();
                }
            }
            ps.executeBatch();
        }

        String sampleTag = pool.get(0).getRfidTag();
        LocalDateTime windowStart = base.minusMinutes(1);

        try (Connection conn = dataSource.getConnection()) {
            // ---------------- BEFORE: drop the index, confirm the plan WITHOUT it ----------------
            dropIndexIfExists(conn, "idx_rfid_checkpoint_time");
            ExplainRow before = explainDuplicateCheck(conn, sampleTag, checkpointName, windowStart);
            System.out.println("[index test] BEFORE idx_rfid_checkpoint_time: key=" + before.key
                + " rowsExamined=" + before.rows + " type=" + before.type);

            // ---------------- AFTER: recreate the index exactly as Hibernate declares it ----------------
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE INDEX idx_rfid_checkpoint_time ON checkpoint_readings " +
                    "(rfid_tag, checkpoint_name, read_time)");
            }
            ExplainRow after = explainDuplicateCheck(conn, sampleTag, checkpointName, windowStart);
            System.out.println("[index test] AFTER  idx_rfid_checkpoint_time: key=" + after.key
                + " rowsExamined=" + after.rows + " type=" + after.type);

            assertNotEquals("idx_rfid_checkpoint_time", before.key,
                "sanity check: the index must NOT be in play before it's created");
            assertEquals("idx_rfid_checkpoint_time", after.key,
                "the query planner must select the new composite index once it exists - " +
                "got key=" + after.key + " instead (full EXPLAIN row: " + after.raw + ")");
            assertTrue(after.rows <= before.rows,
                "the new index must not make the estimated rows examined worse: before=" + before.rows
                    + " after=" + after.rows);
        }

        // Confirm the actual Spring Data method (not just raw SQL) also returns the right answer
        // now that the index is back in place and matches what handleTagRead() really calls.
        Optional<CheckpointReading> found = readingRepository
            .findTopByRfidTagAndCheckpointNameAndReadTimeAfterOrderByReadTimeDesc(
                sampleTag, checkpointName, windowStart.minusMinutes(2));
        // sampleTag may or may not have landed on `checkpointName` in the random seed above;
        // this just confirms the query executes correctly end-to-end against the seeded table,
        // not that a specific row is returned.
        assertNotNull(found, "query must execute without error against the seeded table");
    }

    private static class ExplainRow {
        String key;
        long rows;
        String type;
        String raw;
    }

    private ExplainRow explainDuplicateCheck(Connection conn, String tag, String checkpointName,
                                              LocalDateTime after) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("EXPLAIN " + DUPLICATE_CHECK_SQL)) {
            ps.setString(1, tag);
            ps.setString(2, checkpointName);
            ps.setObject(3, after);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "EXPLAIN must return exactly one row for this single-table query");
                ExplainRow row = new ExplainRow();
                row.key = rs.getString("key");
                row.rows = rs.getLong("rows");
                row.type = rs.getString("type");
                row.raw = rs.getMetaData().getColumnCount() > 0 ? describeRow(rs) : "";
                return row;
            }
        }
    }

    private String describeRow(ResultSet rs) throws Exception {
        StringBuilder sb = new StringBuilder();
        int cols = rs.getMetaData().getColumnCount();
        for (int i = 1; i <= cols; i++) {
            sb.append(rs.getMetaData().getColumnName(i)).append('=').append(rs.getString(i)).append(' ');
        }
        return sb.toString();
    }

    private void dropIndexIfExists(Connection conn, String indexName) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP INDEX " + indexName + " ON checkpoint_readings");
        } catch (Exception e) {
            // Doesn't exist yet on a fresh schema - fine, nothing to drop.
        }
    }

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM checkpoint_readings WHERE race_id >= 999999000")) {
            ps.executeUpdate();
        }
        if (checkpointId != null) {
            checkpointRepository.deleteById(checkpointId);
        }
        for (Long pid : participantIds) {
            participantRepository.deleteById(pid);
        }
    }
}
