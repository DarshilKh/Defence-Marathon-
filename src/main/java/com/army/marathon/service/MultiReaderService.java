package com.army.marathon.service;

import com.army.marathon.model.Checkpoint;
import com.army.marathon.model.CheckpointReading;
import com.army.marathon.model.Participant;
import com.army.marathon.model.Race;
import com.army.marathon.model.RaceConfiguration;
import com.army.marathon.reader.ClouReaderClient;
import com.army.marathon.reader.ReaderTestResult;
import com.army.marathon.reader.RfidReaderClient;
import com.army.marathon.reader.ZebraFxReaderClient;
import com.army.marathon.repository.CheckpointReadingRepository;
import com.army.marathon.repository.ParticipantRepository;
import com.army.marathon.repository.RaceConfigurationRepository;
import com.army.marathon.repository.RaceEntryRepository;
import com.army.marathon.repository.RaceRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class MultiReaderService {

    private static final int DEFAULT_DUPLICATE_READ_WINDOW_MINUTES = 2;

    private final CheckpointReadingRepository readingRepository;
    private final ParticipantRepository participantRepository;
    private final RaceRepository raceRepository;
    private final RaceConfigurationRepository raceConfigRepository;
    private final RaceEntryRepository raceEntryRepository;
    private final DeviceModeService deviceModeService;

    // ✅ Store readers with unique keys (checkpoint_READER_A, checkpoint_READER_B).
    // Typed to the shared RfidReaderClient interface so CLOU and Zebra readers can
    // be mixed transparently - see createReaderClient() for the one place that
    // branches on which vendor is configured.
    private final Map<String, RfidReaderClient> activeReaders = new ConcurrentHashMap<>();

    /** readerKey -> "ip:port", so the Configuration page can tell whether a given
     *  endpoint is already armed rather than opening a competing connection. */
    private final Map<String, String> armedEndpoints = new ConcurrentHashMap<>();

    /** Latest result of the Configuration page's "Test" for each ip:port (true = connected). */
    private final Map<String, Boolean> testedEndpoints = new ConcurrentHashMap<>();

    // "Armed" = readers are connected and scanning. This is intentionally decoupled
    // from Race.status (ARMED/ACTIVE/...) - readers get armed as soon as the Live
    // Tracking page loads, well before the race is officially declared started.
    private volatile boolean readersArmed = false;
    private Long currentRaceId;

    // Surfacing unregistered tag scans (previously only a WARN log line) so a
    // stray/foreign tag scanned during a real race isn't invisible.
    private final AtomicInteger unregisteredScanCount = new AtomicInteger(0);
    private volatile String lastUnregisteredTag;
    private volatile LocalDateTime lastUnregisteredScanTime;

    // Registered tags that were scanned but are NOT on the current race's saved roster.
    // Kept separate from the unregistered counters above: "unknown chip" and "known
    // runner who isn't running today" call for different operator responses.
    private final AtomicInteger offRosterScanCount = new AtomicInteger(0);
    private volatile String lastOffRosterTag;
    private volatile String lastOffRosterParticipant;
    private volatile LocalDateTime lastOffRosterScanTime;

    // ✅ handleTagRead() does several synchronous DB round-trips per tag (participant
    // lookup, roster check, duplicate-window query, save). Both reader SDKs invoke their
    // tag callback directly on their OWN thread - Zebra's eventReadNotify() runs on a
    // thread owned by the RFID3 host SDK's event dispatch, and CLOU's OutPutTags()
    // likewise fires from the vendor SDK, not from the 50ms polling thread this app
    // starts. If handleTagRead() ran there, a burst of simultaneous reads (a pack of
    // runners crossing together) would back up behind each tag's DB round-trips and
    // delay - or under sustained load, silently queue - later reads. Every tag callback
    // is submitted here instead, so the SDK's own thread returns immediately after
    // capturing the raw tag id + timestamp; single-threaded so reads are still processed
    // in the order they arrived (duplicate detection and auto-start rely on that).
    //
    // Executors.newSingleThreadExecutor() was tried first and is the wrong call here: its
    // internal queue is UNBOUNDED. With the DB connections then being unpooled
    // (DriverManagerDataSource - see DatabaseConfig, now fixed to a pooled HikariDataSource),
    // every queued task's DB round-trip could stall, and a real run of this app backed up
    // to 9,397 queued tasks with only 5,933 completed - the exact "cache builds up, app gets
    // slower" failure mode this class's own comments already warned about. The pooled
    // DataSource is the actual fix for the underlying slowness; this bounded queue is the
    // safety net so a stall - from a slow DB, a network hiccup, anything - fails loudly
    // instead of growing forever. corePoolSize/maximumPoolSize both stay at 1 to keep
    // processing order intact.
    private static final int TAG_QUEUE_CAPACITY = 500;
    private final AtomicInteger rejectedTagReadCount = new AtomicInteger(0);
    private final ExecutorService tagProcessingExecutor = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(TAG_QUEUE_CAPACITY),
            r -> {
                Thread t = new Thread(r, "tag-read-processor");
                t.setDaemon(true);
                return t;
            },
            (task, executor) -> {
                int total = rejectedTagReadCount.incrementAndGet();
                log.error("❌❌❌ TAG READ DROPPED - processing queue is full ({} deep, {} rejected " +
                        "so far). A real tag scan just failed to be recorded. This means something " +
                        "downstream of the queue (a DB call, the executor thread) is stuck or too " +
                        "slow for the current read rate - investigate immediately, don't just raise " +
                        "the queue capacity.", TAG_QUEUE_CAPACITY, total);
            }
    );

    public MultiReaderService(
        CheckpointReadingRepository readingRepository,
        ParticipantRepository participantRepository,
        RaceRepository raceRepository,
        RaceConfigurationRepository raceConfigRepository,
        RaceEntryRepository raceEntryRepository,
        DeviceModeService deviceModeService
    ) {
        this.readingRepository = readingRepository;
        this.participantRepository = participantRepository;
        this.raceRepository = raceRepository;
        this.raceConfigRepository = raceConfigRepository;
        this.raceEntryRepository = raceEntryRepository;
        this.deviceModeService = deviceModeService;
    }

    /**
     * ✅ Arm (connect + start scanning) readers for every enabled checkpoint.
     * This is purely a connection step - it does NOT touch Race.status or
     * Race.startTime. Safe to call repeatedly: a reader that's already
     * connected and scanning is left alone rather than being reconnected.
     */
    public ArmResult armReaders(Long raceId, List<Checkpoint> enabledCheckpoints) {
        log.info("🔌 Arming readers for race {} across {} checkpoint(s)", raceId, enabledCheckpoints.size());
        this.currentRaceId = raceId;

        int attempted = 0;
        int connected = 0;
        List<String> failures = new ArrayList<>();

        for (Checkpoint checkpoint : enabledCheckpoints) {
            if (!checkpoint.isEnabled()) {
                continue;
            }
            CheckpointArmOutcome outcome = armCheckpointReaders(checkpoint);
            attempted += outcome.attempted;
            connected += outcome.connected;
            failures.addAll(outcome.failures);
        }

        readersArmed = connected > 0;
        log.info("✅ Arm complete: {}/{} readers connected ({} active total)", connected, attempted, activeReaders.size());
        return new ArmResult(attempted, connected, failures);
    }

    /**
     * ✅ Connect both configured readers for one checkpoint, skipping any that
     * are already connected and scanning.
     */
    private CheckpointArmOutcome armCheckpointReaders(Checkpoint checkpoint) {
        log.info("📍 Arming readers for checkpoint: {}", checkpoint.getName());

        int attempted = 0;
        int connected = 0;
        List<String> failures = new ArrayList<>();

        if (checkpoint.isReaderAConfigured()) {
            attempted++;
            if (startSingleReader(checkpoint, checkpoint.getReaderAIp(), checkpoint.getReaderAPort(), "READER_A")) {
                connected++;
            } else {
                failures.add(checkpoint.getName() + " Reader A (" + checkpoint.getReaderAIp() + ":" + getConfiguredReaderType().getFixedPort() + ")");
            }
        } else {
            log.warn("⚠️ {} - Reader A not configured", checkpoint.getName());
        }

        if (checkpoint.isReaderBConfigured()) {
            attempted++;
            if (startSingleReader(checkpoint, checkpoint.getReaderBIp(), checkpoint.getReaderBPort(), "READER_B")) {
                connected++;
            } else {
                failures.add(checkpoint.getName() + " Reader B (" + checkpoint.getReaderBIp() + ":" + getConfiguredReaderType().getFixedPort() + ")");
            }
        } else {
            log.warn("⚠️ {} - Reader B not configured", checkpoint.getName());
        }

        if (attempted == 0) {
            log.error("❌ {} - NO READERS CONFIGURED!", checkpoint.getName());
            failures.add(checkpoint.getName() + " (no readers configured)");
        } else {
            log.info("✅ {} - {}/{} reader(s) connected", checkpoint.getName(), connected, attempted);
        }

        return new CheckpointArmOutcome(attempted, connected, failures);
    }

    /**
     * ✅ Connect one reader and start its inventory loop. Returns true if the
     * reader ends up connected (including if it was already armed).
     */
    private boolean startSingleReader(Checkpoint checkpoint, String ip, Integer port, String readerLabel) {
        if (ip == null || ip.isEmpty()) {
            log.warn("⚠️ {} {} - No IP configured", checkpoint.getName(), readerLabel);
            return false;
        }

        // The port is fixed per reader vendor (Zebra 5084, CLOU 6000), the same value the
        // Configuration page displays read-only. Resolving it here rather than trusting the
        // stored column keeps checkpoints saved before the port was locked (6000) working
        // once the reader type is Zebra.
        int fixedPort = getConfiguredReaderType().getFixedPort();
        if (port != null && port != 0 && port != fixedPort) {
            log.info("ℹ️ {} {} - stored port {} ignored, using fixed {} port {}",
                checkpoint.getName(), readerLabel, port, getConfiguredReaderType(), fixedPort);
        }
        port = fixedPort;

        String readerKey = checkpoint.getName() + "_" + readerLabel;

        RfidReaderClient existing = activeReaders.get(readerKey);
        if (existing != null && existing.isConnected()) {
            log.info("🔌 {} {} already armed and scanning - skipping reconnect", checkpoint.getName(), readerLabel);
            return true;
        }

        log.info("🔌 Connecting {} {} at {}:{}", checkpoint.getName(), readerLabel, ip, port);

        try {
            RfidReaderClient reader = createReaderClient();
            boolean connected = reader.connect(ip, port, readerKey);

            if (connected) {
                activeReaders.put(readerKey, reader);
                armedEndpoints.put(readerKey, ip.trim() + ":" + port);

                reader.startInventory((tagId, timestamp) -> tagProcessingExecutor.submit(() -> {
                    try {
                        handleTagRead(tagId, checkpoint, timestamp, readerLabel);
                    } catch (Exception e) {
                        log.error("❌ Error processing tag read off the SDK callback thread: {}", e.getMessage(), e);
                    }
                }));

                log.info("✅ {} {} - CONNECTED and scanning", checkpoint.getName(), readerLabel);
                return true;
            } else {
                log.error("❌ {} {} - FAILED to connect", checkpoint.getName(), readerLabel);
                return false;
            }

        } catch (Exception e) {
            log.error("❌ {} {} - Error: {}", checkpoint.getName(), readerLabel, e.getMessage());
            return false;
        }
    }

    /**
     * ✅✅✅ Handle tag read: match to participant, reject if off-roster, dedupe,
     * save reading, and (if this is the start-line checkpoint) auto-detect the
     * official race start.
     */
    @Transactional
    void handleTagRead(String tagId, Checkpoint checkpoint, LocalDateTime timestamp, String readerLabel) {
        try {
            String readerIdentifier = checkpoint.getName() + "_" + readerLabel;

            // STEP 1: Check if participant is registered
            Optional<Participant> participantOpt = participantRepository.findByRfidTag(tagId);

            if (participantOpt.isEmpty()) {
                unregisteredScanCount.incrementAndGet();
                lastUnregisteredTag = tagId;
                lastUnregisteredScanTime = timestamp;
                log.warn("⚠️ {} - REJECTED unregistered tag: {}", readerIdentifier, tagId);
                return; // Skip unregistered tags
            }

            Participant participant = participantOpt.get();
            // No "matched to participant" log line here: everything it would say (name, service
            // number) is repeated by the "reading saved" log a few lines down on the success
            // path, and the roster/duplicate rejection paths already log their own reason -
            // logging it here too was pure confirmation with no added debugging value, on every
            // single scan.

            // STEP 1b: ROSTER CHECK - if this race has a saved roster, only rostered
            // participants count. Runs before the duplicate check, the save and the
            // auto-start hook, so an off-roster scan can't leave any trace in the
            // readings or start the race clock. No roster saved = check is skipped.
            if (isOffRoster(participant)) {
                offRosterScanCount.incrementAndGet();
                lastOffRosterTag = tagId;
                String who = (participant.getName() != null ? participant.getName() : "unnamed")
                    + " (" + participant.getSerialNo() + ")";
                lastOffRosterParticipant = who;
                lastOffRosterScanTime = timestamp;
                log.warn("🚫 Off-roster scan rejected: {} is not in today's roster for race {} [{}, tag {}]",
                    who, currentRaceId, readerIdentifier, tagId);
                return;
            }

            // STEP 2: DUPLICATE CHECK - has this tag been read at this checkpoint recently?
            int windowMinutes = getDuplicateReadWindowMinutes();
            Optional<CheckpointReading> recentReading = readingRepository
                .findTopByRfidTagAndCheckpointNameAndReadTimeAfterOrderByReadTimeDesc(
                    tagId,
                    checkpoint.getName(),
                    timestamp.minusMinutes(windowMinutes)
                );

            if (recentReading.isPresent()) {
                String previousReader = recentReading.get().getReaderId();
                log.info("🔁 {} - DUPLICATE SUPPRESSED for {} (already read by {} within {} min window)",
                    readerIdentifier, tagId, previousReader, windowMinutes);
                return; // Skip duplicate read
            }

            // STEP 3: Save new reading
            CheckpointReading reading = new CheckpointReading();
            reading.setRfidTag(tagId);
            reading.setCheckpoint(checkpoint);
            reading.setCheckpointName(checkpoint.getName());
            reading.setReadTime(timestamp);
            reading.setRaceId(currentRaceId);
            reading.setDeviceId(deviceModeService.getDeviceId());
            reading.setReaderId(readerIdentifier); // Store which reader detected it
            reading.setUploadedToServer(false);
            reading.setParticipant(participant);

            readingRepository.save(reading);

            log.info("✅ {} - reading saved for {} ({}) at {}",
                readerIdentifier,
                participant.getName(),
                participant.getServiceNumber(),
                timestamp.toLocalTime()
            );

            // STEP 4: Auto-detect official race start from the first start-line scan
            if (checkpoint.isStartLine()) {
                maybeAutoStartRace(timestamp);
            }

        } catch (Exception e) {
            log.error("❌ Error saving tag read: {}", e.getMessage(), e);
        }
    }

    /**
     * ✅ True only when the current race HAS a roster and this participant isn't on it.
     * A race with no roster rows (or no current race) never rejects anyone.
     */
    private boolean isOffRoster(Participant participant) {
        if (currentRaceId == null) {
            return false;
        }
        return raceEntryRepository.existsByRaceId(currentRaceId)
            && !raceEntryRepository.existsByRaceIdAndParticipantId(currentRaceId, participant.getId());
    }

    /**
     * ✅ The moment the start-line checkpoint gets its first valid registered-tag
     * read while the race is ARMED (readers connected, waiting), that read's
     * timestamp becomes the official Race.startTime - no manual button timing needed.
     */
    private void maybeAutoStartRace(LocalDateTime firstScanTime) {
        if (currentRaceId == null) {
            return;
        }

        raceRepository.findById(currentRaceId).ifPresent(race -> {
            if (race.getStatus() == Race.RaceStatus.ARMED) {
                race.setStatus(Race.RaceStatus.ACTIVE);
                race.setStartTime(firstScanTime);
                race.setAutoStartDetected(true);
                raceRepository.save(race);
                log.info("🏁 AUTO-START detected for race {} - official start time set to {} (first start-line scan)",
                    currentRaceId, firstScanTime);
            }
        });
    }

    /**
     * ✅ Single source of truth for "is this a duplicate": the configurable
     * window from the latest RaceConfiguration, falling back to 2 minutes.
     */
    private int getDuplicateReadWindowMinutes() {
        try {
            RaceConfiguration config = raceConfigRepository.findTopByOrderByCreatedAtDesc();
            if (config != null && config.getDuplicateReadWindowMinutes() > 0) {
                return config.getDuplicateReadWindowMinutes();
            }
        } catch (Exception e) {
            log.debug("Could not load duplicate read window from configuration: {}", e.getMessage());
        }
        return DEFAULT_DUPLICATE_READ_WINDOW_MINUTES;
    }

    /**
     * ✅ One global reader-vendor setting for the whole race (RaceConfiguration.readerType,
     * default CLOU). This is the single place that decides which concrete reader
     * client to construct - everything else in this class operates on the shared
     * RfidReaderClient interface and doesn't need to know which vendor is active.
     */
    private RfidReaderClient createReaderClient() {
        return getConfiguredReaderType() == RaceConfiguration.ReaderType.ZEBRA
                ? new ZebraFxReaderClient()
                : new ClouReaderClient();
    }

    private RaceConfiguration.ReaderType getConfiguredReaderType() {
        try {
            RaceConfiguration config = raceConfigRepository.findTopByOrderByCreatedAtDesc();
            if (config != null && config.getReaderType() != null) {
                return config.getReaderType();
            }
        } catch (Exception e) {
            log.debug("Could not load reader type from configuration: {}", e.getMessage());
        }
        return RaceConfiguration.ReaderType.CLOU;
    }

    /**
     * ✅ Number of unregistered tag scans since the app started, for the Live
     * Tracking UI to surface instead of leaving them invisible in the log.
     */
    public int getUnregisteredScanCount() {
        return unregisteredScanCount.get();
    }

    public String getLastUnregisteredTag() {
        return lastUnregisteredTag;
    }

    public LocalDateTime getLastUnregisteredScanTime() {
        return lastUnregisteredScanTime;
    }

    /**
     * ✅ Tag reads dropped because tagProcessingExecutor's bounded queue was full - should stay
     * 0 in normal operation. Any non-zero value means a real scan was lost and the queue's
     * RejectedExecutionHandler already logged it at ERROR; exposed here too for load testing
     * and for a future status indicator, without needing to grep logs.
     */
    public int getRejectedTagReadCount() {
        return rejectedTagReadCount.get();
    }

    /** Current backlog waiting to be processed - should return to ~0 shortly after any burst. */
    public int getTagProcessingQueueDepth() {
        return ((ThreadPoolExecutor) tagProcessingExecutor).getQueue().size();
    }

    /**
     * ✅ Off-roster scans (registered tag, but not in the current race's saved roster)
     * since the app started, for the Live Tracking status bar.
     */
    public int getOffRosterScanCount() {
        return offRosterScanCount.get();
    }

    public String getLastOffRosterTag() {
        return lastOffRosterTag;
    }

    /** "Name (Tag No)" of the participant behind the last off-roster scan. */
    public String getLastOffRosterParticipant() {
        return lastOffRosterParticipant;
    }

    public LocalDateTime getLastOffRosterScanTime() {
        return lastOffRosterScanTime;
    }

    /**
     * Zeroes the in-memory scan counters (unregistered, off-roster, rejected) and forgets the
     * last-rejected-tag details, so the status bar reads as if nothing had been scanned. Called
     * by Reset; a fresh JVM already starts at zero.
     */
    public void resetScanCounters() {
        unregisteredScanCount.set(0);
        offRosterScanCount.set(0);
        rejectedTagReadCount.set(0);
        lastUnregisteredTag = null;
        lastUnregisteredScanTime = null;
        lastOffRosterTag = null;
        lastOffRosterParticipant = null;
        lastOffRosterScanTime = null;
    }

    /**
     * ✅ Stop and disconnect all readers (used by "Stop Race" and "Reset").
     */
    public void stopRace() {
        log.info("⏹️ Disarming and stopping all readers...");
        readersArmed = false;

        activeReaders.forEach((readerKey, reader) -> {
            try {
                log.info("🔴 Stopping reader: {}", readerKey);
                reader.stopInventory();
                reader.disconnect();
            } catch (Exception e) {
                log.error("Error stopping reader {}: {}", readerKey, e.getMessage());
            }
        });

        activeReaders.clear();
        armedEndpoints.clear();
        log.info("✅ All readers stopped");
    }

    @PreDestroy
    public void shutdown() {
        tagProcessingExecutor.shutdown();
        try {
            if (!tagProcessingExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                tagProcessingExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            tagProcessingExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * ✅ Are readers currently connected and scanning?
     */
    public boolean areReadersArmed() {
        return readersArmed;
    }

    /** Remembers the outcome of a Configuration-page test so other pages can show reader status. */
    public void recordTestResult(String ip, int port, boolean connected) {
        if (ip != null) {
            testedEndpoints.put(ip.trim() + ":" + port, connected);
        }
    }

    /**
     * True when at least one reader is connected right now (armed), or its latest Configuration
     * test succeeded. One good reader is enough; the others don't have to be up.
     */
    public boolean isAnyReaderConnected() {
        return getActiveReaderCount() > 0 || testedEndpoints.containsValue(Boolean.TRUE);
    }

    /**
     * ✅ Get active reader count
     */
    public int getActiveReaderCount() {
        return (int) activeReaders.values().stream()
            .filter(reader -> reader != null && reader.isConnected())
            .count();
    }

    /**
     * ✅ Get reader statuses
     */
    /**
     * If a reader at this exact ip:port is already armed and connected, return its
     * live status (including the per-antenna map captured when it connected);
     * otherwise return null so the caller can perform a real test connection.
     *
     * Zebra FX readers allow only ONE host session. Once Live Tracking arms a
     * reader, a second connection from the Configuration page's "Test" button is
     * rejected with "Connection Already exists", which made an actively-scanning
     * reader display as red/failed. Reporting the armed reader's own state is both
     * accurate and avoids disturbing a live race.
     */
    public ReaderTestResult getArmedReaderStatus(String ip, int port) {
        if (ip == null) {
            return null;
        }
        String endpoint = ip.trim() + ":" + port;

        for (Map.Entry<String, String> entry : armedEndpoints.entrySet()) {
            if (!endpoint.equals(entry.getValue())) {
                continue;
            }
            RfidReaderClient reader = activeReaders.get(entry.getKey());
            if (reader != null && reader.isConnected()) {
                Map<Integer, Boolean> antennas = reader.getLastAntennaStatus();
                return new ReaderTestResult(true,
                        antennas != null ? new HashMap<>(antennas) : new HashMap<>());
            }
        }
        return null;
    }

    public Map<String, String> getReaderStatuses() {
        Map<String, String> statuses = new HashMap<>();

        activeReaders.forEach((readerKey, reader) -> {
            if (reader == null) {
                statuses.put(readerKey, "NULL");
            } else if (!reader.isConnected()) {
                statuses.put(readerKey, "DISCONNECTED");
            } else if (reader.isReading()) {
                statuses.put(readerKey, "SCANNING");
            } else {
                statuses.put(readerKey, "CONNECTED");
            }
        });

        return statuses;
    }

    /**
     * ✅ Get specific reader status (updated for dual readers)
     */
    public String getReaderStatus(String checkpointName) {
        // Check both readers for this checkpoint
        String readerAKey = checkpointName + "_READER_A";
        String readerBKey = checkpointName + "_READER_B";

        RfidReaderClient readerA = activeReaders.get(readerAKey);
        RfidReaderClient readerB = activeReaders.get(readerBKey);

        // If both connected, return "SCANNING"
        if (isReaderActive(readerA) && isReaderActive(readerB)) {
            return "SCANNING (2 READERS)";
        }

        // If one connected
        if (isReaderActive(readerA) || isReaderActive(readerB)) {
            return "SCANNING (1 READER)";
        }

        return "NOT_STARTED";
    }

    /**
     * ✅ Helper: Check if reader is active
     */
    private boolean isReaderActive(RfidReaderClient reader) {
        return reader != null && reader.isConnected() && reader.isReading();
    }

    /**
     * ✅ Reconnect specific checkpoint readers
     */
    public boolean reconnectReader(Checkpoint checkpoint) {
        log.info("🔄 Reconnecting readers for: {}", checkpoint.getName());

        // Stop existing readers for this checkpoint
        String readerAKey = checkpoint.getName() + "_READER_A";
        String readerBKey = checkpoint.getName() + "_READER_B";

        stopReaderByKey(readerAKey);
        stopReaderByKey(readerBKey);

        // Start new connections
        CheckpointArmOutcome outcome = armCheckpointReaders(checkpoint);
        boolean success = outcome.connected > 0;

        if (success) {
            log.info("✅ Reconnection successful");
        } else {
            log.error("❌ Reconnection failed");
        }

        return success;
    }

    /**
     * ✅ Helper: Stop single reader by key
     */
    private void stopReaderByKey(String readerKey) {
        RfidReaderClient reader = activeReaders.get(readerKey);
        if (reader != null) {
            try {
                reader.stopInventory();
                reader.disconnect();
                activeReaders.remove(readerKey);
                armedEndpoints.remove(readerKey);
                log.info("🔴 Stopped: {}", readerKey);
            } catch (Exception e) {
                log.warn("Error stopping {}: {}", readerKey, e.getMessage());
            }
        }
    }

    /**
     * ✅ Test reader connection without starting race
     */
    public boolean testReaderConnection(String ip, int port, String name) {
        log.info("🧪 Testing reader connection: {} at {}:{}", name, ip, port);

        try {
            RfidReaderClient testReader = createReaderClient();
            boolean connected = testReader.connect(ip, port, name);

            if (connected) {
                log.info("✅ Test connection successful");
                testReader.disconnect();
                return true;
            } else {
                log.error("❌ Test connection failed");
                return false;
            }

        } catch (Exception e) {
            log.error("❌ Test connection error: {}", e.getMessage());
            return false;
        }
    }

    /**
     * ✅ Outcome of arming one checkpoint's readers.
     */
    private static class CheckpointArmOutcome {
        final int attempted;
        final int connected;
        final List<String> failures;

        CheckpointArmOutcome(int attempted, int connected, List<String> failures) {
            this.attempted = attempted;
            this.connected = connected;
            this.failures = failures;
        }
    }

    /**
     * ✅ Outcome of arming readers across all enabled checkpoints - lets the UI
     * show a clear error alert instead of failing silently in the background.
     */
    public static class ArmResult {
        public final int attempted;
        public final int connected;
        public final List<String> failures;

        public ArmResult(int attempted, int connected, List<String> failures) {
            this.attempted = attempted;
            this.connected = connected;
            this.failures = failures;
        }

        public boolean anyConnected() {
            return connected > 0;
        }

        public boolean allConnected() {
            return attempted > 0 && failures.isEmpty();
        }
    }
}
