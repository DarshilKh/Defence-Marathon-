package com.army.marathon.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.army.marathon.model.Checkpoint;
import com.army.marathon.model.CheckpointReading;
import com.army.marathon.model.Participant;
import com.army.marathon.model.Race;
import com.army.marathon.repository.CheckpointReadingRepository;
import com.army.marathon.repository.CheckpointRepository;
import com.army.marathon.repository.ParticipantRepository;
import com.army.marathon.repository.RaceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Part 5 of the live tag-scan performance pass: an UNPACED flood, against a table pre-seeded to
 * the same scale as the real 9,397-deep incident, genuinely faster than the pipeline can drain -
 * unlike TagProcessingLoadTest's own burst (paced in waves specifically so the queue never fills,
 * to isolate a different question: "does accepted work get lost"). This test asks the opposite
 * question: when the queue DOES fill, are rejections clean (every one logged, counted, and
 * matched to a real dropped read - nothing silently lost or double-counted), and do the two
 * per-tick queries the live UI depends on (duplicate-check, live-tracking refresh) stay fast at
 * this table scale, not just at the small scale most other tests seed.
 */
@SpringBootTest
class LiveScanScalePerformanceTest {

    @Autowired private DataSource dataSource;
    @Autowired private MultiReaderService multiReaderService;
    @Autowired private RaceRepository raceRepository;
    @Autowired private CheckpointRepository checkpointRepository;
    @Autowired private ParticipantRepository participantRepository;
    @Autowired private CheckpointReadingRepository readingRepository;

    private final List<Long> participantIds = new ArrayList<>();
    private Long raceId;
    private Long checkpointId;

    private Object unwrapTarget(Object bean) throws Exception {
        if (bean instanceof org.springframework.aop.framework.Advised advised) {
            return advised.getTargetSource().getTarget();
        }
        return bean;
    }

    @SuppressWarnings("unchecked")
    private ThreadPoolExecutor realTagProcessingExecutor() throws Exception {
        Field f = MultiReaderService.class.getDeclaredField("tagProcessingExecutor");
        f.setAccessible(true);
        return (ThreadPoolExecutor) f.get(unwrapTarget(multiReaderService));
    }

    @Test
    void unpacedFloodAgainstNineThousandRowTable_rejectsCleanlyAndKeepsHotQueriesFlat() throws Exception {
        // ---------------- Capture every "TAG READ DROPPED" log line for real ----------------
        Logger serviceLogger = (Logger) LoggerFactory.getLogger(MultiReaderService.class);
        ListAppender<ILoggingEvent> logCapture = new ListAppender<>();
        logCapture.start();
        serviceLogger.addAppender(logCapture);

        try {
            Race race = new Race();
            race.setRaceName("SCALE-TEST-" + System.nanoTime());
            race.setStatus(Race.RaceStatus.NOT_STARTED);
            race.setCalculationMode(Race.CalculationMode.TWO_CHECKPOINT);
            race = raceRepository.save(race);
            raceId = race.getId();
            LocalDateTime attemptStart = LocalDateTime.now().minusHours(2);
            race.setCurrentAttemptStartedAt(attemptStart);
            raceRepository.save(race);

            Checkpoint start = new Checkpoint();
            start.setRaceId(raceId);
            start.setLocationName("SCALE-Start");
            start.setLocationNumber(1);
            start.setDisplayOrder(1);
            start.setEnabled(true);
            start.setStartLine(true);
            start = checkpointRepository.save(start);
            checkpointId = start.getId();
            String checkpointName = start.getName();

            // ---------------- Pre-seed to the SAME scale as the real incident (9,397 deep) ----------------
            int backgroundPoolSize = 300;
            List<Participant> backgroundPool = new ArrayList<>();
            for (int i = 0; i < backgroundPoolSize; i++) {
                Participant p = new Participant();
                p.setName("Scale Runner " + i);
                p.setRfidTag("SCALE-CHIP-" + System.nanoTime() + "-" + i);
                p.setSerialNo("SCALE-TN-" + System.nanoTime() + "-" + i);
                p = participantRepository.save(p);
                participantIds.add(p.getId());
                backgroundPool.add(p);
            }

            int backgroundRows = 9_397;
            LocalDateTime backgroundBase = attemptStart.plusMinutes(1);
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO checkpoint_readings " +
                     "(rfid_tag, checkpoint_id, checkpoint_name, reader_id, read_time, race_id, " +
                     " device_id, uploaded_to_server, participant_id, created_at, updated_at) " +
                     "VALUES (?, ?, ?, 'READER_A', ?, ?, 'SCALE-TEST-DEVICE', 0, ?, ?, ?)")) {
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                for (int i = 0; i < backgroundRows; i++) {
                    Participant p = backgroundPool.get(rnd.nextInt(backgroundPool.size()));
                    LocalDateTime t = backgroundBase.plusSeconds(i);
                    ps.setString(1, p.getRfidTag());
                    ps.setLong(2, checkpointId);
                    ps.setString(3, checkpointName);
                    ps.setObject(4, t);
                    ps.setLong(5, raceId);
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

            // ---------------- Hot-query latency BEFORE the burst, at 9,397-row scale ----------------
            String sampleTag = backgroundPool.get(0).getRfidTag();
            double dupCheckMsBefore = timeDuplicateCheckMs(sampleTag, checkpointName, attemptStart, 20);
            double liveTrackMsBefore = timeLiveTrackingMs(20);
            System.out.println("[scale test] BEFORE burst (table already has " + backgroundRows
                + " rows): duplicate-check avg=" + fmt(dupCheckMsBefore)
                + "ms, live-tracking-refresh avg=" + fmt(liveTrackMsBefore) + "ms");

            multiReaderService.armReaders(raceId, List.of());
            int rejectedBaseline = multiReaderService.getRejectedTagReadCount();

            // ---------------- Genuinely UNPACED flood: no waves, no sleeps, no throttling ----------------
            int burstSize = 3_000; // comfortably over the "at least 2,000" ask
            List<Participant> burstPool = new ArrayList<>();
            for (int i = 0; i < burstSize; i++) {
                Participant p = new Participant();
                p.setName("Scale Burst Runner " + i);
                p.setRfidTag("SCALE-BURST-CHIP-" + System.nanoTime() + "-" + i);
                p.setSerialNo("SCALE-BURST-TN-" + System.nanoTime() + "-" + i);
                p = participantRepository.save(p);
                participantIds.add(p.getId());
                burstPool.add(p);
            }

            ThreadPoolExecutor realExecutor = realTagProcessingExecutor();
            LocalDateTime burstTime = LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
            Checkpoint startCp = start;

            long submitStartNs = System.nanoTime();
            for (int i = 0; i < burstSize; i++) {
                Participant p = burstPool.get(i);
                LocalDateTime t = burstTime.plusNanos((long) i * 1_000_000);
                realExecutor.submit(() -> multiReaderService.handleTagRead(p.getRfidTag(), startCp, t, "READER_A"));
                // Deliberately no sleep, no wave gap - a real submission-loop-speed flood, so
                // arrival genuinely outruns single-threaded processing and the queue fills.
            }
            long submitElapsedMs = (System.nanoTime() - submitStartNs) / 1_000_000;

            long deadline = System.currentTimeMillis() + 90_000;
            while (System.currentTimeMillis() < deadline
                    && (realExecutor.getQueue().size() > 0 || realExecutor.getActiveCount() > 0)) {
                Thread.sleep(50);
            }
            long drainElapsedMs = 90_000 - Math.max(0, deadline - System.currentTimeMillis());

            int rejectedDuringBurst = multiReaderService.getRejectedTagReadCount() - rejectedBaseline;
            int acceptedDuringBurst = burstSize - rejectedDuringBurst;

            // Matched by the burst pool's own distinctive tag prefix, not by a time threshold -
            // the 9,397-row background seed (timestamped hours in the past, relative to "now" at
            // seed time) and this burst can otherwise overlap in wall-clock time on a slow CI
            // box, which would silently count unrelated background rows as "saved from burst".
            long savedFromBurst = readingRepository.findByRaceId(raceId).stream()
                .filter(r -> r.getRfidTag() != null && r.getRfidTag().startsWith("SCALE-BURST-CHIP-"))
                .count();

            long droppedLogLines = logCapture.list.stream()
                .filter(e -> e.getFormattedMessage() != null
                    && e.getFormattedMessage().contains("TAG READ DROPPED"))
                .count();

            System.out.println("[scale test] submitted " + burstSize + " tasks in " + submitElapsedMs
                + "ms (unpaced - no waves/sleeps), drained in ~" + drainElapsedMs + "ms");
            System.out.println("[scale test] accepted=" + acceptedDuringBurst
                + " rejected=" + rejectedDuringBurst + " saved-to-db=" + savedFromBurst
                + " dropped-log-lines=" + droppedLogLines
                + " (queue capacity=500)");

            // ---------------- Hot-query latency AFTER the burst, table now ~12k+ rows ----------------
            double dupCheckMsAfter = timeDuplicateCheckMs(sampleTag, checkpointName, attemptStart, 20);
            double liveTrackMsAfter = timeLiveTrackingMs(20);
            System.out.println("[scale test] AFTER burst (table now has " + (backgroundRows + savedFromBurst)
                + "+ rows): duplicate-check avg=" + fmt(dupCheckMsAfter)
                + "ms, live-tracking-refresh avg=" + fmt(liveTrackMsAfter) + "ms");

            // ---------------- Assertions: this is a real load test, not a smoke test ----------------
            assertTrue(rejectedDuringBurst > 0,
                "an UNPACED flood of " + burstSize + " tasks against a 500-deep queue with a " +
                "single-threaded ~80-90/sec drain rate must genuinely overflow the queue - if this " +
                "is 0, the burst wasn't actually unpaced and this test isn't validating anything");
            assertEquals(acceptedDuringBurst, savedFromBurst,
                "every ACCEPTED task must have produced a saved reading - nothing silently dropped or duplicated");
            assertEquals(rejectedDuringBurst, droppedLogLines,
                "every rejection must be logged exactly once - operators must be able to see this in real logs, " +
                "not just in the in-memory counter");
            assertEquals(0, realExecutor.getQueue().size(),
                "queue must fully drain back to 0 after the burst, not stay backed up");

            // Flatness: the duplicate-check query is an index range scan scoped to ONE tag/checkpoint
            // pair (Part 1), so it must not meaningfully slow down as unrelated rows pile up in the
            // table. 5x is a generous slack for real MySQL/JVM jitter on a shared dev machine, not a
            // tight bound - the point is "flat", not "identical to the millisecond".
            assertTrue(dupCheckMsAfter < Math.max(dupCheckMsBefore * 5, 5.0),
                "duplicate-check latency must stay roughly flat as the race accumulates readings: "
                    + "before=" + fmt(dupCheckMsBefore) + "ms after=" + fmt(dupCheckMsAfter) + "ms");
            assertTrue(liveTrackMsAfter < Math.max(liveTrackMsBefore * 5, 20.0),
                "live-tracking refresh latency must stay roughly flat (SQL-level LIMIT 100, not " +
                "fetch-all-then-limit-in-Java): before=" + fmt(liveTrackMsBefore)
                    + "ms after=" + fmt(liveTrackMsAfter) + "ms");

        } finally {
            serviceLogger.detachAppender(logCapture);
        }
    }

    private double timeDuplicateCheckMs(String tag, String checkpointName, LocalDateTime windowStart, int samples) {
        long totalNs = 0;
        for (int i = 0; i < samples; i++) {
            long t0 = System.nanoTime();
            readingRepository.findTopByRfidTagAndCheckpointNameAndReadTimeAfterOrderByReadTimeDesc(
                tag, checkpointName, windowStart);
            totalNs += System.nanoTime() - t0;
        }
        return totalNs / (double) samples / 1_000_000.0;
    }

    private double timeLiveTrackingMs(int samples) {
        long totalNs = 0;
        for (int i = 0; i < samples; i++) {
            long t0 = System.nanoTime();
            readingRepository.findRecentForLiveTracking(raceId, LocalDateTime.now().minusHours(3),
                PageRequest.of(0, 100));
            totalNs += System.nanoTime() - t0;
        }
        return totalNs / (double) samples / 1_000_000.0;
    }

    private static String fmt(double ms) {
        return String.format(java.util.Locale.ROOT, "%.2f", ms);
    }

    @AfterEach
    void cleanUp() {
        if (raceId != null) {
            readingRepository.findByRaceId(raceId).forEach(r -> readingRepository.deleteById(r.getId()));
        }
        if (checkpointId != null) {
            checkpointRepository.deleteById(checkpointId);
        }
        for (Long pid : participantIds) {
            participantRepository.deleteById(pid);
        }
        if (raceId != null) {
            raceRepository.deleteById(raceId);
        }
    }
}
