package com.army.marathon.reader;

import com.mot.rfid.api3.Antennas;
import com.mot.rfid.api3.InvalidUsageException;
import com.mot.rfid.api3.OperationFailureException;
import com.mot.rfid.api3.RFIDReader;
import com.mot.rfid.api3.RfidEventsListener;
import com.mot.rfid.api3.RfidReadEvents;
import com.mot.rfid.api3.RfidStatusEvents;
import com.mot.rfid.api3.STATUS_EVENT_TYPE;
import com.mot.rfid.api3.TagData;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * Reader client for Zebra FX-series fixed readers (FX7500 / FX9600), built on
 * Zebra's official RFID3 Host Java SDK (com.mot.rfid.api3 — "Symbol"/"MOT"
 * naming is legacy Motorola Solutions -> Zebra branding, same SDK).
 *
 * Implements the shared {@link RfidReaderClient} interface (same one
 * {@code ClouReaderClient} implements) so {@code MultiReaderService} can use
 * either reader type interchangeably behind a config toggle. Returns the
 * shared {@link ReaderTestResult} from testConnectionAndAntennas() as well —
 * both reader clients use the same top-level class, not their own copies.
 *
 * Key difference from the CLOU client worth knowing before you read the rest of
 * this class: CLOU reads are pulled by polling GetEPC_TID_UserData() in a loop
 * every ~50ms. Zebra's SDK is event-driven — you tell the reader to start a
 * continuous inventory session (Actions.Inventory.perform()) and the reader
 * itself pushes eventReadNotify() callbacks whenever it has new tags, which you
 * then pull off with Actions.getReadTags(). No polling loop/thread needed here.
 */
@Slf4j
public class ZebraFxReaderClient implements RfidReaderClient {

    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 3000;

    /**
     * ⚠️ NEEDS FIELD VERIFICATION — do not treat this as a known-safe value.
     *
     * Zebra's SDK sets transmit power as an INDEX into the reader's own
     * regulatory-region power table (Antennas.Config.setTransmitPowerIndex),
     * NOT a raw dBm value like CLOU's rfid.reader.power=30 was. The valid index
     * range and what dBm each index maps to depends on the FX9600's configured
     * region/country setting. Pulling a number out of the air here and pushing
     * it to every antenna on connect could either under-power the antennas
     * (same "doesn't read tags" problem we're trying to fix) or push a value
     * outside the region's regulatory limit.
     *
     * On first real hardware test: read back getTransmitPowerIndex() per
     * antenna (this class logs it — see connect()) and confirm what index the
     * FX9600 is already configured with via Zebra's own admin UI, then set
     * this constant to match rather than guessing. Until then, this class only
     * powers on an antenna if it reads back as fully OFF (index <= 0) — it
     * does not override an existing, presumably-already-tuned power setting.
     */
    private static final short FALLBACK_TRANSMIT_POWER_INDEX = 200;

    private static final int ANTENNA_COUNT = 4;

    /**
     * ⚠️ CONFIRMED IN THE FIELD (2026-08-31 log): RFIDReader(ip, port, timeoutMs)'s
     * timeout is NOT reliably honored by connect() when the host never responds at
     * all — a connect() call to an unreachable reader blocked for 10+ minutes with
     * no exception, well past its stated 3000ms timeout, freezing both "Arm
     * Readers" (Live Tracking) and the Configuration page's "Test All" button
     * (whichever reader it reached first just never returned, so every reader
     * after it in the sequential test loop never got tested either).
     *
     * connect() is therefore run on this watchdog pool and hard-abandoned at
     * DEFAULT_CONNECT_TIMEOUT_MS + CONNECT_TIMEOUT_MARGIN_MS instead of trusted to
     * time out on its own. If the underlying SDK call never returns, that one
     * watchdog thread leaks (daemon, so it doesn't block JVM exit) - an accepted
     * tradeoff over an indefinite UI freeze.
     */
    private static final ExecutorService CONNECT_WATCHDOG = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "zebra-fx-connect-watchdog");
        t.setDaemon(true);
        return t;
    });

    private static final long CONNECT_TIMEOUT_MARGIN_MS = 2000;

    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean reading = new AtomicBoolean(false);

    private final Map<Integer, Boolean> lastAntennaStatus = new java.util.concurrent.ConcurrentHashMap<>();

    private RFIDReader reader;
    private String readerName;
    private BiConsumer<String, LocalDateTime> tagCallback;

    private final RfidEventsListener listener = new RfidEventsListener() {
        @Override
        public void eventReadNotify(RfidReadEvents rfidReadEvents) {
            try {
                TagData[] tags = reader.Actions.getReadTags(100);
                if (tags == null) {
                    return;
                }
                LocalDateTime timestamp = LocalDateTime.now();
                for (TagData tag : tags) {
                    if (tag == null) {
                        continue;
                    }
                    String epc = tag.getTagID();
                    if (epc == null || epc.isEmpty()) {
                        continue;
                    }
                    log.info("📥 {} - raw EPC from SDK: {} (antenna {})",
                            readerName, epc, tag.getAntennaID());
                    if (tagCallback != null) {
                        tagCallback.accept(epc, timestamp);
                    }
                }
            } catch (Exception e) {
                log.error("❌ {} Error processing read event: {}", readerName, e.getMessage());
            }
        }

        @Override
        public void eventStatusNotify(RfidStatusEvents rfidStatusEvents) {
            try {
                STATUS_EVENT_TYPE type = rfidStatusEvents.StatusEventData.getStatusEventType();
                if (type == STATUS_EVENT_TYPE.DISCONNECTION_EVENT) {
                    log.warn("🔴 {} Reader-initiated disconnect", readerName);
                    connected.set(false);
                    reading.set(false);
                } else if (type == STATUS_EVENT_TYPE.INVENTORY_START_EVENT
                        || type == STATUS_EVENT_TYPE.INVENTORY_STOP_EVENT
                        || type == STATUS_EVENT_TYPE.ANTENNA_EVENT) {
                    log.info("{} Status event: {}", readerName, type);
                } else {
                    log.debug("{} Status event: {}", readerName, type);
                }
            } catch (Exception e) {
                log.debug("{} Error reading status event: {}", readerName, e.getMessage());
            }
        }
    };

    /**
     * Runs RFIDReader.connect() on the watchdog pool and hard-cancels it at
     * timeoutMs if it hasn't returned - see CONNECT_WATCHDOG's javadoc for why
     * this exists instead of trusting the SDK's own timeout param.
     */
    private static void connectWithTimeout(RFIDReader r, long timeoutMs)
            throws InvalidUsageException, OperationFailureException, TimeoutException {
        Future<?> future = CONNECT_WATCHDOG.submit(() -> {
            r.connect();
            return null;
        });
        try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new TimeoutException("connect() did not return within " + timeoutMs + "ms");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof InvalidUsageException iue) throw iue;
            if (cause instanceof OperationFailureException ofe) throw ofe;
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException(cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    /**
     * Connect to a Zebra FX-series reader over TCP/IP and power on any
     * antenna that's reporting fully off. Mirrors ClouReaderClient.connect().
     */
    @Override
    public boolean connect(String ip, int port, String name) {
        this.readerName = name != null ? name : "READER";

        try {
            log.info("🔌 Connecting {} at {}:{}...", readerName, ip, port);

            reader = new RFIDReader(ip, port, DEFAULT_CONNECT_TIMEOUT_MS);
            connectWithTimeout(reader, DEFAULT_CONNECT_TIMEOUT_MS + CONNECT_TIMEOUT_MARGIN_MS);
            connected.set(true);
            log.info("✅ {} CONNECTED: {}:{}", readerName, ip, port);

            // Tag-read + disconnect are the events the app acts on. Inventory start/stop
            // and antenna events are subscribed only so the log shows what the reader is
            // really doing (they mirror Zebra's own J_RFIDHostSample1). GPI, buffer,
            // temperature etc. stay off - nothing here consumes them.
            reader.Events.setTagReadEvent(true);

            // ⚠️ REQUIRED - this is what makes eventReadNotify() below work at all.
            // The SDK's default is setAttachTagDataWithReadEvent(TRUE): it pulls each tag off
            // its internal queue itself and hands it to the listener INSIDE the event
            // (rfidReadEvents.getReadEventData().tagData). Our listener instead drains the
            // queue with Actions.getReadTags(), which only has anything to return when this
            // flag is FALSE. Left at the default, getReadTags() always comes back empty and
            // every tag is silently dropped - the reader connects, the antenna is powered,
            // inventory "starts", and no tag ever reaches the app. Zebra's sample sets this
            // right after setTagReadEvent(true) for exactly this reason.
            reader.Events.setAttachTagDataWithReadEvent(false);

            reader.Events.setInventoryStartEvent(true);
            reader.Events.setInventoryStopEvent(true);
            reader.Events.setAntennaEvent(true);
            reader.Events.setReaderDisconnectEvent(true);
            reader.Events.addEventsListener(listener);

            powerOnAntennasIfNeeded();

            return true;

        } catch (InvalidUsageException e) {
            connected.set(false);
            log.error("❌ {} Connection FAILED (invalid usage): {}", readerName, e.getVendorMessage());
            return false;
        } catch (OperationFailureException e) {
            connected.set(false);
            log.error("❌ {} Connection FAILED: {}", readerName, e.getStatusDescription());
            return false;
        } catch (TimeoutException e) {
            connected.set(false);
            log.error("❌ {} Connection TIMED OUT after {}ms — reader at {}:{} never responded " +
                            "(check IP/port/cable; nothing else is likely holding the connection). " +
                            "Verify the port against the reader's own network settings — Zebra FX readers " +
                            "commonly use 5084 for the RFID3 host connection.",
                    readerName, DEFAULT_CONNECT_TIMEOUT_MS + CONNECT_TIMEOUT_MARGIN_MS, ip, port);
            return false;
        } catch (Exception e) {
            connected.set(false);
            log.error("❌ {} Connection Exception: {}", readerName, e.getMessage());
            return false;
        }
    }

    /**
     * For every one of the 4 fixed antenna ports this app models, check the
     * reader's PHYSICALLY CONNECTED signal (Antennas.PhysicalProperties —
     * same as the reader's own green/red "Antenna Status" display) and power
     * it on if it's currently reading 0. Ports with nothing plugged in are
     * skipped entirely rather than powered on blind. Already-nonzero antennas
     * are left alone rather than overridden — see the warning on
     * FALLBACK_TRANSMIT_POWER_INDEX above.
     *
     * Deliberately does NOT gate this on getAvailableAntennas() first — that
     * call reports which ports the reader currently "declares," which is a
     * separate thing from physical connection and can come back empty/wrong
     * on a freshly opened connection, silently skipping every antenna even
     * when one is genuinely plugged in. Checking all 4 fixed ports directly
     * removes that dependency.
     */
    private void powerOnAntennasIfNeeded() {
        for (int antennaId = 1; antennaId <= ANTENNA_COUNT; antennaId++) {
            try {
                Antennas.PhysicalProperties props = reader.Config.Antennas.getPhysicalProperties(antennaId);
                lastAntennaStatus.put(antennaId, props.isConnected());
                if (!props.isConnected()) {
                    log.info("📶 {} - Antenna {} reports NOT CONNECTED (nothing plugged in) — skipping",
                            readerName, antennaId);
                    continue;
                }

                Antennas.Config antennaConfig = reader.Config.Antennas.getAntennaConfig(antennaId);
                short currentPower = antennaConfig.getTransmitPowerIndex();

                if (currentPower > 0) {
                    log.info("📶 {} - Antenna {} connected and already powered (index {})",
                            readerName, antennaId, currentPower);
                } else {
                    log.warn("📶 {} - Antenna {} connected but reads 0 power, powering on to fallback index {} " +
                                    "(VERIFY this against the reader's actual region/power table)",
                            readerName, antennaId, FALLBACK_TRANSMIT_POWER_INDEX);
                    antennaConfig.setTransmitPowerIndex(FALLBACK_TRANSMIT_POWER_INDEX);
                    reader.Config.Antennas.setAntennaConfig(antennaId, antennaConfig);
                }
            } catch (InvalidUsageException e) {
                log.warn("⚠️ {} - Antenna {} power check failed (invalid usage): {}",
                        readerName, antennaId, e.getVendorMessage());
            } catch (OperationFailureException e) {
                log.warn("⚠️ {} - Antenna {} power check failed: {}",
                        readerName, antennaId, e.getStatusDescription());
            } catch (Exception e) {
                log.warn("⚠️ {} - Antenna {} power check failed: {}", readerName, antennaId, e.getMessage());
            }
        }
    }

    @Override
    public boolean isConnected() {
        return connected.get();
    }

    /**
     * Start continuous tag inventory. Unlike ClouReaderClient, this doesn't
     * spin up a polling thread — Actions.Inventory.perform() with no
     * arguments starts a continuous read using the reader's own configured
     * trigger/filter settings, and tags stream in via eventReadNotify()
     * until stopInventory() is called.
     */
    @Override
    public void startInventory(BiConsumer<String, LocalDateTime> callback) {
        if (!isConnected()) {
            log.error("❌ {} Cannot start inventory - NOT CONNECTED!", readerName);
            return;
        }

        this.tagCallback = callback;

        try {
            reader.Actions.purgeTags();
            reader.Actions.Inventory.perform();
            reading.set(true);
            log.info("🧵 {} Inventory started (event-driven, no polling thread)", readerName);
        } catch (InvalidUsageException e) {
            reading.set(false);
            log.error("❌ {} Failed to start inventory (invalid usage): {}", readerName, e.getVendorMessage());
        } catch (OperationFailureException e) {
            reading.set(false);
            log.error("❌ {} Failed to start inventory: {}", readerName, e.getStatusDescription());
        }
    }

    @Override
    public void stopInventory() {
        if (!isConnected() || !reading.get()) {
            reading.set(false);
            return;
        }

        try {
            reader.Actions.Inventory.stop();
            log.info("⏹️ {} Inventory stopped", readerName);
        } catch (InvalidUsageException e) {
            log.warn("{} Stop inventory failed (invalid usage): {}", readerName, e.getVendorMessage());
        } catch (OperationFailureException e) {
            log.warn("{} Stop inventory failed: {}", readerName, e.getStatusDescription());
        } finally {
            reading.set(false);
        }
    }

    @Override
    public void disconnect() {
        log.info("🔴 Disconnecting {}...", readerName);

        try {
            stopInventory();

            if (reader != null) {
                reader.Events.removeEventsListener(listener);
                reader.disconnect();
            }

            connected.set(false);
            log.info("✅ {} Disconnected", readerName);

        } catch (InvalidUsageException e) {
            log.error("❌ {} Disconnect error (invalid usage): {}", readerName, e.getVendorMessage());
        } catch (OperationFailureException e) {
            log.error("❌ {} Disconnect error: {}", readerName, e.getStatusDescription());
        } catch (Exception e) {
            log.error("❌ {} Disconnect error: {}", readerName, e.getMessage());
        }
    }

    @Override
    public String getReaderName() {
        return readerName;
    }

    @Override
    public boolean isReading() {
        return reading.get();
    }

    /**
     * Real reader + per-antenna connectivity test, matching
     * ClouReaderClient.testConnectionAndAntennas()'s exact contract:
     * opens a short-lived connection, queries per-antenna status, closes.
     *
     * Uses Antennas.PhysicalProperties.isConnected() — the same cable-
     * detection signal the reader's own web UI shows as the green/red
     * "Antenna Status" dots (Reader Parameters -> Antenna Status). This is
     * a direct, ground-truth signal from the reader hardware, not a proxy —
     * unlike an earlier version of this method that inferred "working" from
     * transmit power index, which only tells you the antenna is powered,
     * not whether anything is actually plugged into that port.
     */
    public static ReaderTestResult testConnectionAndAntennas(String ip, int port, String name) {
        String readerName = name != null ? name : "READER";

        Map<Integer, Boolean> antennaStatus = new HashMap<>();
        for (int i = 1; i <= ANTENNA_COUNT; i++) {
            antennaStatus.put(i, false);
        }

        RFIDReader testReader = null;
        boolean connectedOk = false;

        try {
            testReader = new RFIDReader(ip, port, DEFAULT_CONNECT_TIMEOUT_MS);
            connectWithTimeout(testReader, DEFAULT_CONNECT_TIMEOUT_MS + CONNECT_TIMEOUT_MARGIN_MS);
            connectedOk = true;

            // Deliberately check all 4 fixed antenna ports directly rather than
            // gating on getAvailableAntennas() first — that call reports which
            // ports the reader currently "declares," a separate thing from
            // physical connection, and can come back empty/wrong on a freshly
            // opened connection (this was the actual bug behind "reader shows
            // connected, all antennas show red" even with a real antenna
            // plugged in and confirmed working via Zebra's own 123RFID Desktop
            // tool). Checking all 4 ports directly removes that dependency.
            for (int antennaId = 1; antennaId <= ANTENNA_COUNT; antennaId++) {
                try {
                    Antennas.PhysicalProperties props =
                            testReader.Config.Antennas.getPhysicalProperties(antennaId);
                    antennaStatus.put(antennaId, props.isConnected());
                } catch (Exception e) {
                    log.warn("{} Antenna {} query failed: {}", readerName, antennaId, describe(e));
                }
            }

        } catch (Exception e) {
            // Log at WARN, not DEBUG: "why did my reader test fail" should never
            // require flipping the whole app to DEBUG logging to find out.
            log.warn("{} Test connection failed: {}", readerName, describe(e));
            connectedOk = false;
        } finally {
            // Only disconnect if connect() actually completed - on a timeout, the
            // watchdog thread may still be inside connect() on this same testReader
            // object, and calling disconnect() concurrently from here risks racing
            // it (the vendor SDK isn't known to be thread-safe for that).
            if (testReader != null && connectedOk) {
                try {
                    testReader.disconnect();
                } catch (Exception ignored) {
                    // best-effort cleanup for a short-lived test connection
                }
            }
        }

        // Uses the shared com.army.marathon.reader.ReaderTestResult (same package,
        // no import needed) — NOT a nested class here. If your repo's shared
        // ReaderTestResult constructor signature differs from
        // (boolean readerConnected, Map<Integer,Boolean> antennaStatus), update
        // this call to match rather than re-adding a nested class.
        return new ReaderTestResult(connectedOk, antennaStatus);
    }

    @Override
    public Map<Integer, Boolean> getLastAntennaStatus() {
        return new HashMap<>(lastAntennaStatus);
    }

    /**
     * Build a never-null, human-readable description of an exception.
     *
     * Zebra's SDK exceptions (InvalidUsageException / OperationFailureException)
     * do NOT populate the standard getMessage() - it returns null, which is why
     * failures previously logged as the useless "Test connection failed: null".
     * The real text lives in getVendorMessage() / getStatusDescription() /
     * getInfo(). Always include the exception class name too, so even a truly
     * message-less exception still tells you what kind of failure it was.
     */
    private static String describe(Throwable e) {
        if (e == null) {
            return "unknown error (null exception)";
        }

        StringBuilder sb = new StringBuilder(e.getClass().getName());

        String detail = null;
        if (e instanceof InvalidUsageException) {
            InvalidUsageException iue = (InvalidUsageException) e;
            detail = iue.getVendorMessage();
            if (detail == null || detail.isEmpty()) {
                detail = iue.getInfo();
            }
        } else if (e instanceof OperationFailureException) {
            OperationFailureException ofe = (OperationFailureException) e;
            detail = ofe.getStatusDescription();
            if (detail == null || detail.isEmpty()) {
                detail = ofe.getVendorMessage();
            }
        }

        if (detail == null || detail.isEmpty()) {
            detail = e.getMessage();
        }
        if (detail != null && !detail.isEmpty()) {
            sb.append(": ").append(detail);
        }

        Throwable cause = e.getCause();
        if (cause != null && cause != e) {
            sb.append(" (caused by ").append(cause.getClass().getSimpleName());
            if (cause.getMessage() != null) {
                sb.append(": ").append(cause.getMessage());
            }
            sb.append(")");
        }

        return sb.toString();
    }
}