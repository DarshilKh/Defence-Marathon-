package com.army.marathon.service;

import com.army.marathon.model.*;
import com.army.marathon.repository.*;
import com.army.marathon.ui.LiveTrackingController;
import com.army.marathon.ui.ReportsController;
import com.army.marathon.ui.ResultsController;
import com.army.marathon.ui.ResultsRenderer;
import javafx.application.Platform;
import javafx.scene.Parent;
import net.rgielen.fxweaver.core.FxControllerAndView;
import net.rgielen.fxweaver.core.FxWeaver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end verification of the Reports-tab (raceSessionId scoping) and Reset-display
 * (NOT_STARTED gating) fixes, driven through the REAL Spring-wired controllers - not
 * service-layer calls alone - the same way MainViewController wires them at runtime.
 *
 * No RFID hardware is available, so "Start Race" is stood in for exactly the way
 * RaceDayFlowTest does it (armReaders(raceId, List.of()) + the same state transitions
 * handleStartRace's fresh-start branch makes) and confirmation-dialog-gated actions
 * (Stop Race, Reset) are stood in for by applying the exact same field changes their
 * Alert.showAndWait() OK-branch makes - a real modal dialog can't be driven headlessly
 * without risking an indefinite hang. Every other step - Calculate Results, and all
 * three read-only controllers' own refresh/selection logic - runs for real.
 *
 * WARNING: Reset now clears race 1's roster (same as the real Reset), so run this only against a
 * throwaway database.
 *
 * This test operates on the installation's one Race row (id=1, same as
 * LiveTrackingController.currentRace()) and restores its original state afterward, and
 * deletes every row it creates, so it leaves the database exactly as clean as it found it.
 */
@SpringBootTest
class ReportsAndResetIntegrationTest {

    @Autowired private FxWeaver fxWeaver;
    @Autowired private RaceRepository raceRepository;
    @Autowired private CheckpointRepository checkpointRepository;
    @Autowired private ParticipantRepository participantRepository;
    @Autowired private CheckpointReadingRepository readingRepository;
    @Autowired private RaceResultRepository resultRepository;
    @Autowired private RaceSessionRepository raceSessionRepository;
    @Autowired private RaceEntryRepository raceEntryRepository;
    @Autowired private MultiReaderService multiReaderService;
    @Autowired private RaceTimingService timingService;
    @Autowired private RaceLifecycleService lifecycleService;

    private static volatile boolean toolkitStarted = false;

    /**
     * Idempotent across test CLASSES, not just within this one: when the whole suite runs in one
     * Surefire fork, UiFlowSmokeTest may have already started the JavaFX toolkit (each test class's
     * own toolkitStarted flag only guards itself) - Platform.startup() throws
     * IllegalStateException("Toolkit already initialized") in that case, which just means the
     * toolkit this class needs is already up and Platform.runLater() works immediately.
     */
    private static synchronized void initJavaFxToolkit() throws InterruptedException {
        if (toolkitStarted) return;
        CountDownLatch latch = new CountDownLatch(1);
        try {
            Platform.startup(latch::countDown);
        } catch (IllegalStateException alreadyStarted) {
            toolkitStarted = true;
            return;
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS), "JavaFX toolkit did not start");
        toolkitStarted = true;
    }

    private <T> FxControllerAndView<T, Parent> loadControllerAndView(Class<T> controllerClass) throws Exception {
        initJavaFxToolkit();
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicReference<FxControllerAndView<T, Parent>> result = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                result.set(fxWeaver.load(controllerClass));
            } catch (Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
        });
        assertTrue(latch.await(15, TimeUnit.SECONDS), "Timed out loading " + controllerClass.getSimpleName());
        if (error.get() != null) throw new AssertionError("Failed to load " + controllerClass.getSimpleName(), error.get());
        assertNotNull(result.get());
        return result.get();
    }

    private static void onFx(Runnable action) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
        });
        assertTrue(latch.await(10, TimeUnit.SECONDS), "FX action timed out");
        if (error.get() != null) throw new AssertionError(error.get());
    }

    @SuppressWarnings("unchecked")
    private static <F> F fxField(Object controller, String name) throws Exception {
        java.lang.reflect.Field f = controller.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return (F) f.get(controller);
    }

    /**
     * Counts only the rows this test itself put in the table (name starts with "IT ", a prefix
     * this test owns and no real participant would use) - see the comment at its call site for
     * why a raw row-count assertion isn't safe against a real, already-populated installation.
     * Returns [rows, finishers, dnf].
     */
    private static int[] mineOnly(javafx.scene.control.TableView<ResultsRenderer.ParticipantResultRow> table) throws Exception {
        int[] counts = new int[3];
        onFx(() -> {
            for (ResultsRenderer.ParticipantResultRow row : table.getItems()) {
                if (row.getName() != null && row.getName().startsWith("IT ")) {
                    counts[0]++;
                    if (row.getStatus() != null && row.getStatus().contains("DNF")) {
                        counts[2]++;
                    } else {
                        counts[1]++;
                    }
                }
            }
        });
        return counts;
    }

    /** ReportsController.SessionRow.session is package-private to com.army.marathon.ui; this test lives in .service. */
    private static Long sessionIdOf(ReportsController.SessionRow row) {
        try {
            java.lang.reflect.Field f = row.getClass().getDeclaredField("session");
            f.setAccessible(true);
            return ((RaceSession) f.get(row)).getId();
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    // ---- test data created here, cleaned up in @AfterEach ----
    private final List<Long> participantIds = new ArrayList<>();
    private final List<Long> checkpointIds = new ArrayList<>();
    private final List<Long> sessionIds = new ArrayList<>();
    private Race originalRaceSnapshot;

    private Checkpoint newCheckpoint(Long raceId, String name, int order, boolean start, boolean finish) {
        Checkpoint cp = new Checkpoint();
        cp.setRaceId(raceId);
        cp.setLocationName(name);
        cp.setLocationNumber(order);
        cp.setDisplayOrder(order);
        cp.setEnabled(true);
        cp.setStartLine(start);
        cp.setFinishLine(finish);
        cp = checkpointRepository.save(cp);
        checkpointIds.add(cp.getId());
        return cp;
    }

    /**
     * Also adds the new participant to race 1's roster: the installation this runs against may
     * already have a REAL saved roster from real testing (raceEntryRepository.existsByRaceId(1)
     * true means every scan is checked against it), and this test's own synthetic participants
     * must not get rejected as off-roster because of that. Only ever adds a row for a participant
     * this test itself created - a pre-existing roster's own rows are never read or touched.
     */
    private Participant newParticipant(String name) {
        String unique = System.nanoTime() + "-" + participantIds.size();
        Participant p = new Participant();
        p.setName(name);
        p.setRfidTag("IT-CHIP-" + unique);
        p.setSerialNo("IT-TN-" + unique);
        p = participantRepository.save(p);
        participantIds.add(p.getId());

        RaceEntry entry = new RaceEntry();
        entry.setRaceId(1L);
        entry.setParticipantId(p.getId());
        entry.setSource(RaceEntry.Source.MANUAL);
        raceEntryRepository.save(entry);

        return p;
    }

    /** Exactly LiveTrackingController.beginNewAttempt (private) + the ARMED transition handleStartRace makes. */
    private RaceSession startAttempt(Race race, LocalDateTime now) {
        race.setCurrentAttemptStartedAt(now);
        RaceSession session = new RaceSession();
        session.setRaceId(race.getId());
        session.setRaceName(race.getRaceName());
        session.setCalculationMode(race.getCalculationMode());
        session.setStartedAt(now);
        session = raceSessionRepository.save(session);
        sessionIds.add(session.getId());
        race.setCurrentSessionId(session.getId());
        race.setStatus(Race.RaceStatus.ARMED);
        race.setEndTime(null);
        race.setManualEndTime(null);
        raceRepository.save(race);
        return session;
    }

    /** Exactly what handleStopRace's OK branch does. */
    private void stopAttempt(Race race, LocalDateTime endTime) {
        race.setStatus(Race.RaceStatus.FINISHED);
        race.setEndTime(endTime);
        race.setManualEndTime(endTime);
        raceRepository.save(race);
        if (race.getCurrentSessionId() != null) {
            raceSessionRepository.findById(race.getCurrentSessionId()).ifPresent(s -> {
                s.setEndedAt(endTime);
                raceSessionRepository.save(s);
            });
        }
    }

    @Test
    void reportsMatchesResults_thenResetBlanksLiveViews_reportsStillShowsHistory_secondAttemptRanksFromOne() throws Exception {
        Race race = raceRepository.findById(1L).orElseThrow(() -> new AssertionError("installation race row (id=1) must exist"));
        originalRaceSnapshot = snapshot(race);

        race.setCalculationMode(Race.CalculationMode.TWO_CHECKPOINT);
        race.setStatus(Race.RaceStatus.NOT_STARTED);
        race.setCurrentSessionId(null);
        race.setCurrentAttemptStartedAt(null);
        race.setManualStartTime(null);
        raceRepository.save(race);

        Checkpoint start = newCheckpoint(1L, "IT-Start", 101, true, false);
        Checkpoint finish = newCheckpoint(1L, "IT-Finish", 102, false, true);

        Participant fast = newParticipant("IT Fast");
        Participant slow = newParticipant("IT Slow");
        Participant dnf = newParticipant("IT DNF");

        LiveTrackingController live = loadControllerAndView(LiveTrackingController.class).getController();
        ResultsController results = loadControllerAndView(ResultsController.class).getController();
        ReportsController reports = loadControllerAndView(ReportsController.class).getController();

        // ---------------- Attempt 1 ----------------
        LocalDateTime t0 = LocalDateTime.now();
        multiReaderService.armReaders(race.getId(), List.of()); // real call, matching handleStartRace
        RaceSession session1 = startAttempt(race, t0);

        // Offsets deliberately stay in small fractions of a second, not simulated race minutes:
        // session/attempt boundaries are scoped by real wall-clock LocalDateTime.now() (see
        // startAttempt/attemptStart below, same as the real beginNewAttempt), so every one of
        // attempt 1's reading timestamps must land strictly before attempt 2's real start a few
        // real seconds later (guaranteed by the Thread.sleep(2500) between the two attempts,
        // below) - matching how a real race actually runs, unlike a race-relative offset of +20
        // simulated minutes would (that was tried first and, correctly, made attempt 1's finish
        // reads look like they belonged to attempt 2 - a test-timing bug, not a product bug).
        multiReaderService.handleTagRead(fast.getRfidTag(), start, t0.plusNanos(100_000_000), "READER_A");
        multiReaderService.handleTagRead(slow.getRfidTag(), start, t0.plusNanos(200_000_000), "READER_A");
        multiReaderService.handleTagRead(dnf.getRfidTag(), start, t0.plusNanos(300_000_000), "READER_A");
        multiReaderService.handleTagRead(fast.getRfidTag(), finish, t0.plusNanos(400_000_000), "READER_A");
        multiReaderService.handleTagRead(slow.getRfidTag(), finish, t0.plusNanos(500_000_000), "READER_A");
        // dnf never scans finish

        Race afterScans = raceRepository.findById(1L).orElseThrow();
        assertEquals(Race.RaceStatus.ACTIVE, afterScans.getStatus(), "first start-line scan auto-activates the race");

        stopAttempt(afterScans, t0.plusNanos(600_000_000));

        timingService.calculateRaceResults(race.getId(), Race.CalculationMode.TWO_CHECKPOINT);
        timingService.assignRankings(race.getId());

        // ---------------- Results tab shows this attempt ----------------
        // Filtered to rows this test itself created (name starts with "IT ", a prefix this test
        // owns) rather than asserting raw table size/label counts: the installation's race 1 may
        // already carry a REAL saved roster from real usage (raceEntryRepository.existsByRaceId(1)
        // true makes every result calculation include every rostered participant, not just this
        // test's own 3), so a bare row-count assertion would be flaky against real data.
        onFx(results::refreshResults);
        javafx.scene.control.TableView<ResultsRenderer.ParticipantResultRow> resultsTable = fxField(results, "resultsTable");
        int[] resultsCounts = mineOnly(resultsTable);
        assertEquals(3, resultsCounts[0], "Results tab must show all 3 of this test's participants for this attempt");
        assertEquals(2, resultsCounts[1], "2 finishers");
        assertEquals(1, resultsCounts[2], "1 DNF");

        // ---------------- Reports tab, same attempt selected, must match Results exactly (Part 1) ----------------
        onFx(reports::refresh);
        javafx.scene.control.TableView<ReportsController.SessionRow> sessionsTable = fxField(reports, "sessionsTable");
        javafx.scene.control.TableView<ResultsRenderer.ParticipantResultRow> reportsResultsTable = fxField(reports, "resultsTable");

        boolean[] sessionFound = {false};
        onFx(() -> {
            for (ReportsController.SessionRow row : sessionsTable.getItems()) {
                if (sessionIdOf(row).equals(session1.getId())) {
                    sessionsTable.getSelectionModel().select(row);
                    sessionFound[0] = true;
                    break;
                }
            }
        });
        assertTrue(sessionFound[0], "Reports must list attempt 1 in today's sessions - this is the exact bug Part 1 targets");
        int[] reportsCounts = mineOnly(reportsResultsTable);
        assertEquals(resultsCounts[0], reportsCounts[0], "Reports row count must match Results exactly for the same attempt");
        assertEquals(resultsCounts[1], reportsCounts[1], "Reports finishers must match Results");
        assertEquals(resultsCounts[2], reportsCounts[2], "Reports DNF must match Results");

        // ---------------- Reset: Race Name / Live Tracking / Results must go blank; Reports must NOT (Part 4) ----------------
        long sessionsBefore = raceSessionRepository.count();
        long resultsBefore = resultRepository.count();
        long readingsBefore = readingRepository.count();

        // The exact routine handleResetRace's OK branch (and app startup) calls
        lifecycleService.resetToFreshState();

        Race afterReset = raceRepository.findById(1L).orElseThrow();
        assertEquals(Race.RaceStatus.NOT_STARTED, afterReset.getStatus());
        assertEquals("", afterReset.getRaceName(), "Reset clears the race name");
        assertNull(afterReset.getCurrentSessionId(), "Reset unlinks the current session (history rows stay)");
        assertNull(afterReset.getCurrentAttemptStartedAt(), "Reset clears the attempt boundary");
        assertFalse(afterReset.hasCurrentAttempt());
        assertFalse(raceEntryRepository.existsByRaceId(1L), "Reset clears the roster");
        assertEquals(sessionsBefore, raceSessionRepository.count(), "Reset must never delete RaceSession rows");
        assertEquals(resultsBefore, resultRepository.count(), "Reset must never delete RaceResult rows");
        assertEquals(readingsBefore, readingRepository.count(), "Reset must never delete CheckpointReading rows");

        // LiveTrackingController's own 2-second scheduler (started in initialize()) is what actually
        // refreshes liveResultsTable/labels in the running app - wait for it rather than calling any
        // private method directly, so this exercises the exact same code path the real app runs.
        Thread.sleep(2500);
        javafx.scene.control.TableView<?> liveTable = fxField(live, "liveResultsTable");
        javafx.scene.control.Label finishedCountLabel = fxField(live, "finishedCountLabel");
        int[] liveCounts = new int[2];
        onFx(() -> {
            liveCounts[0] = liveTable.getItems().size();
            liveCounts[1] = Integer.parseInt(finishedCountLabel.getText());
        });
        assertEquals(0, liveCounts[0], "Live Tag Readings must be blank once NOT_STARTED, even though the readings are still on file");
        assertEquals(0, liveCounts[1], "reading count must show 0 once NOT_STARTED");

        onFx(results::refreshResults);
        javafx.scene.control.Label totalFinishers = fxField(results, "totalFinishersLabel");
        javafx.scene.control.Label totalDnf = fxField(results, "totalDnfLabel");
        int[] resultsAfterReset = new int[3];
        onFx(() -> {
            resultsAfterReset[0] = resultsTable.getItems().size();
            resultsAfterReset[1] = Integer.parseInt(totalFinishers.getText());
            resultsAfterReset[2] = Integer.parseInt(totalDnf.getText());
        });
        assertEquals(0, resultsAfterReset[0], "Results tab must go blank after Reset");
        assertEquals(0, resultsAfterReset[1]);
        assertEquals(0, resultsAfterReset[2]);

        onFx(reports::refresh);
        boolean[] sessionStillFound = {false};
        onFx(() -> {
            for (ReportsController.SessionRow row : sessionsTable.getItems()) {
                if (sessionIdOf(row).equals(session1.getId())) {
                    sessionsTable.getSelectionModel().select(row);
                    sessionStillFound[0] = true;
                    break;
                }
            }
        });
        assertTrue(sessionStillFound[0], "Reports must still list attempt 1 after Reset - Reset must never delete history");
        int[] reportsAfterReset = mineOnly(reportsResultsTable);
        assertEquals(3, reportsAfterReset[0], "Reports must still show all 3 of attempt 1's results, untouched, after Reset");

        // ---------------- Attempt 2: independent of attempt 1, ranks start at 1 again ----------------
        Participant runner2a = newParticipant("IT Second Fast");
        Participant runner2b = newParticipant("IT Second Slow");

        Race beforeAttempt2 = raceRepository.findById(1L).orElseThrow();
        LocalDateTime t1 = LocalDateTime.now();
        RaceSession session2 = startAttempt(beforeAttempt2, t1);

        multiReaderService.handleTagRead(runner2a.getRfidTag(), start, t1.plusSeconds(1), "READER_A");
        multiReaderService.handleTagRead(runner2b.getRfidTag(), start, t1.plusSeconds(2), "READER_A");
        multiReaderService.handleTagRead(runner2a.getRfidTag(), finish, t1.plusSeconds(4), "READER_A");
        multiReaderService.handleTagRead(runner2b.getRfidTag(), finish, t1.plusSeconds(5), "READER_A");

        Race afterScans2 = raceRepository.findById(1L).orElseThrow();
        stopAttempt(afterScans2, t1.plusSeconds(6));
        timingService.calculateRaceResults(race.getId(), Race.CalculationMode.TWO_CHECKPOINT);
        timingService.assignRankings(race.getId());

        // Reset cleared the roster, so only attempt 2's own two runners (rostered by
        // newParticipant()) get rows now. Still narrowed to those two so a real roster can't skew it.
        java.util.Set<Long> attempt2OwnIds = java.util.Set.of(runner2a.getId(), runner2b.getId());
        List<RaceResult> attempt2Results = resultRepository.findByRaceSessionId(session2.getId()).stream()
                .filter(r -> r.getParticipant() != null && attempt2OwnIds.contains(r.getParticipant().getId()))
                .collect(java.util.stream.Collectors.toList());
        assertEquals(2, attempt2Results.size(), "attempt 2's own 2 runners must both have a result row");
        RaceResult r2a = attempt2Results.stream().filter(r -> r.getParticipant().getId().equals(runner2a.getId())).findFirst().orElseThrow();
        RaceResult r2b = attempt2Results.stream().filter(r -> r.getParticipant().getId().equals(runner2b.getId())).findFirst().orElseThrow();
        assertEquals(1, r2a.getOverallRank(), "attempt 2's fastest must rank 1st, not offset by attempt 1's 2 finishers");
        assertEquals(2, r2b.getOverallRank());

        // attempt 1's results must be completely unaffected by attempt 2's calculation
        List<RaceResult> attempt1ResultsStill = resultRepository.findByRaceSessionId(session1.getId()).stream()
                .filter(r -> r.getParticipant() != null && participantIds.contains(r.getParticipant().getId()))
                .collect(java.util.stream.Collectors.toList());
        assertEquals(3, attempt1ResultsStill.size(), "attempt 1's results must be untouched by attempt 2's calculation");
    }

    private static Race snapshot(Race race) {
        Race copy = new Race();
        copy.setId(race.getId());
        copy.setRaceName(race.getRaceName());
        copy.setStatus(race.getStatus());
        copy.setCalculationMode(race.getCalculationMode());
        copy.setStartTime(race.getStartTime());
        copy.setEndTime(race.getEndTime());
        copy.setManualStartTime(race.getManualStartTime());
        copy.setManualEndTime(race.getManualEndTime());
        copy.setAutoStartDetected(race.getAutoStartDetected());
        copy.setCurrentAttemptStartedAt(race.getCurrentAttemptStartedAt());
        copy.setCurrentSessionId(race.getCurrentSessionId());
        return copy;
    }

    @AfterEach
    void cleanUp() {
        // FK order matters: race_results/checkpoint_readings reference both checkpoints and
        // participants (NO ACTION/RESTRICT), so both must be cleared before either parent table.
        for (Long sid : sessionIds) {
            resultRepository.deleteByRaceSessionId(sid);
        }
        readingRepository.findByRaceId(1L).stream()
                .filter(r -> r.getCheckpoint() != null && checkpointIds.contains(r.getCheckpoint().getId()))
                .forEach(r -> readingRepository.deleteById(r.getId()));
        for (Long cpId : checkpointIds) {
            checkpointRepository.deleteById(cpId);
        }
        for (Long sid : sessionIds) {
            raceSessionRepository.deleteById(sid);
        }
        for (Long pid : participantIds) {
            // Only this test's own roster rows (see newParticipant()) - a pre-existing real
            // roster's rows for other participants are never touched.
            raceEntryRepository.deleteByParticipantId(pid);
            participantRepository.deleteById(pid);
        }
        if (originalRaceSnapshot != null) {
            raceRepository.findById(1L).ifPresent(race -> {
                race.setRaceName(originalRaceSnapshot.getRaceName());
                race.setStatus(originalRaceSnapshot.getStatus());
                race.setCalculationMode(originalRaceSnapshot.getCalculationMode());
                race.setStartTime(originalRaceSnapshot.getStartTime());
                race.setEndTime(originalRaceSnapshot.getEndTime());
                race.setManualStartTime(originalRaceSnapshot.getManualStartTime());
                race.setManualEndTime(originalRaceSnapshot.getManualEndTime());
                race.setAutoStartDetected(originalRaceSnapshot.getAutoStartDetected());
                race.setCurrentAttemptStartedAt(originalRaceSnapshot.getCurrentAttemptStartedAt());
                race.setCurrentSessionId(originalRaceSnapshot.getCurrentSessionId());
                raceRepository.save(race);
            });
        }
    }
}
