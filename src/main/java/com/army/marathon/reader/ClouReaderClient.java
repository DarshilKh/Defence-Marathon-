package com.army.marathon.reader;

import com.clou.uhf.G3Lib.CLReader;
import com.clou.uhf.G3Lib.ClouInterface.IAsynchronousMessage;
import com.clou.uhf.G3Lib.Enumeration.eReadType;
import com.clou.uhf.G3Lib.Protocol.Tag_Model;
import com.clou.uhf.G3Lib.Tag6C;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

@Slf4j
public class ClouReaderClient implements RfidReaderClient {

    // Connection state
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean reading = new AtomicBoolean(false);

    private String endpoint;
    private String readerName;
    private BiConsumer<String, LocalDateTime> tagCallback;

    // ✅ Every raw read is passed straight to MultiReaderService, which is now
    // the ONLY place that decides "is this a duplicate" (see task: consolidate
    // duplicate-read filtering into a single configurable window).

    // Power (0-30 scale) applied to any antenna that reports no configured power
    // on connect - matches the existing rfid.reader.power=30 setting.
    private static final int DEFAULT_ANTENNA_POWER = 30;

    // A reader that just had its previous TCP session closed (e.g. by the
    // Configuration page's "Test All") can take a moment to free that session -
    // retry a few times with a short backoff instead of failing immediately.
    private static final int CONNECT_RETRY_ATTEMPTS = 3;
    private static final long CONNECT_RETRY_DELAY_MS = 400;

    private Thread inventoryThread;
    
    // Reader callback handler
    private final IAsynchronousMessage callback = new IAsynchronousMessage() {
        @Override
        public void OutPutTags(Tag_Model tag_model) {
            try {
                if (tag_model != null && tag_model._EPC != null && !tag_model._EPC.isEmpty()) {
                    handleTagRead(tag_model._EPC);
                }
            } catch (Exception e) {
                log.error("{} Error processing tag: {}", readerName, e.getMessage());
            }
        }
        
        @Override
        public void OutPutTagsOver() {
            // Tag reading cycle completed
        }
        
        @Override
        public void GPIControlMsg(int gpi, int state, int src) {
            // GPI control message
        }
        
        @Override
        public void PortConnecting(String info) {
            log.info("🔌 {} Connecting: {}", readerName, info);
        }
        
        @Override
        public void PortClosing(String info) {
            log.warn("🔴 {} Port closing: {}", readerName, info);
            connected.set(false);
        }
        
        @Override
        public void WriteDebugMsg(String msg) {
            // Silent - no spam
        }
        
        @Override
        public void WriteLog(String msg) {
            log.debug("{} {}", readerName, msg);
        }
    };
    
    /**
     * Connect to CLOU UHF Reader via TCP/IP, retrying briefly if the endpoint's
     * previous session (e.g. from Configuration's "Test All") hasn't been freed yet.
     */
    @Override
    public boolean connect(String ip, int port, String name) {
        this.endpoint = ip + ":" + port;
        this.readerName = name != null ? name : "READER";

        for (int attempt = 1; attempt <= CONNECT_RETRY_ATTEMPTS; attempt++) {
            try {
                log.info("🔌 {} Connecting (attempt {}/{}) at {}...", readerName, attempt, CONNECT_RETRY_ATTEMPTS, endpoint);

                if (CLReader.CreateTcpConn(endpoint, callback)) {
                    connected.set(true);
                    log.info("✅ {} CONNECTED: {}", readerName, endpoint);
                    ensureAntennasPowered();
                    return true;
                }

                log.warn("⚠️ {} Connection attempt {}/{} failed at {} - the reader may still be releasing a previous session",
                        readerName, attempt, CONNECT_RETRY_ATTEMPTS, endpoint);

            } catch (Exception e) {
                log.error("❌ {} Connection Exception (attempt {}/{}): {}", readerName, attempt, CONNECT_RETRY_ATTEMPTS, e.getMessage());
            }

            if (attempt < CONNECT_RETRY_ATTEMPTS) {
                try {
                    Thread.sleep(CONNECT_RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        connected.set(false);
        log.error("❌ {} Connection FAILED after {} attempts: {}", readerName, CONNECT_RETRY_ATTEMPTS, endpoint);
        return false;
    }

    /**
     * ✅ connect() only opens the TCP socket - it does NOT guarantee any antenna
     * is actually powered on. Query current antenna power and explicitly power on
     * any antenna reporting none, so inventory has something to read from.
     */
    private void ensureAntennasPowered() {
        try {
            HashMap<Integer, Integer> currentPower = CLReader._Config.GetANTPowerParam(endpoint);
            HashMap<Integer, Integer> toPowerOn = new HashMap<>();

            for (int antenna = 1; antenna <= 4; antenna++) {
                Integer power = currentPower != null ? currentPower.get(antenna) : null;
                if (power == null || power <= 0) {
                    toPowerOn.put(antenna, DEFAULT_ANTENNA_POWER);
                }
            }

            if (!toPowerOn.isEmpty()) {
                log.info("📶 {} Powering on antennas with no configured power {} -> {}", readerName, toPowerOn.keySet(), DEFAULT_ANTENNA_POWER);
                CLReader._Config.SetANTPowerParam(endpoint, toPowerOn);
            } else {
                log.info("📶 {} All antennas already powered: {}", readerName, currentPower);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("⚠️ {} Could not verify/set antenna power: {}", readerName, e.getMessage());
        }
    }

    /**
     * Check connection status
     */
    @Override
    public boolean isConnected() {
        return connected.get();
    }

    /**
     * Start continuous tag inventory
     */
    @Override
    public void startInventory(BiConsumer<String, LocalDateTime> callback) {
        if (!isConnected()) {
            log.error("❌ {} Cannot start inventory - NOT CONNECTED!", readerName);
            return;
        }

        this.tagCallback = callback;
        reading.set(true);

        inventoryThread = new Thread(() -> {
            log.info("🧵 {} Inventory thread started - polling every 50ms", readerName);

            while (reading.get() && isConnected()) {
                try {
                    // Read tags using CLOU SDK
                    Tag6C.GetEPC_TID_UserData(endpoint, 1, eReadType.Inventory, 0, 32);
                    Thread.sleep(50);

                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    log.debug("{} Read error: {}", readerName, e.getMessage());
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
            }

            log.info("🔴 {} Inventory thread stopped", readerName);

        }, "RFID-Inventory-" + readerName);

        inventoryThread.setDaemon(true);
        inventoryThread.start();
    }
    
    /**
     * Stop inventory reading
     */
    @Override
    public void stopInventory() {
        reading.set(false);
        
        if (inventoryThread != null && inventoryThread.isAlive()) {
            try {
                inventoryThread.join(2000);
            } catch (InterruptedException e) {
                log.warn("{} Interrupted waiting for inventory thread", readerName);
            }
        }
        
        log.info("⏹️ {} Inventory stopped", readerName);
    }
    
    /**
     * Disconnect reader
     */
    @Override
    public void disconnect() {
        log.info("🔴 Disconnecting {}...", readerName);
        
        try {
            stopInventory();
            
            if (endpoint != null && !endpoint.isEmpty()) {
                CLReader.CloseConn(endpoint);
            }
            
            connected.set(false);
            log.info("✅ {} Disconnected", readerName);
            
        } catch (Exception e) {
            log.error("❌ {} Disconnect error: {}", readerName, e.getMessage());
        }
    }
    
    /**
     * ✅ Every raw EPC from the SDK is forwarded as-is - MultiReaderService is the
     * single place that decides whether a read is a duplicate (see the
     * duplicateReadWindowMinutes setting on the Configuration page).
     */
    private void handleTagRead(String epc) {
        if (epc == null || epc.isEmpty()) {
            return;
        }

        log.info("📥 {} Raw EPC from SDK: {}", readerName, epc);

        if (tagCallback != null) {
            LocalDateTime timestamp = LocalDateTime.now();
            try {
                tagCallback.accept(epc, timestamp);
            } catch (Exception e) {
                log.error("❌ {} Callback error: {}", readerName, e.getMessage());
            }
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
     * ✅ Real reader + per-antenna connectivity test.
     * Opens a short-lived CLOU connection, queries per-antenna power config
     * (the closest signal this SDK exposes to antenna presence), then closes.
     * Antennas 1-4 are reported "up" if the reader returned a positive power
     * value for that port; if the reader itself is unreachable, all 4 are down.
     */
    public static ReaderTestResult testConnectionAndAntennas(String ip, int port, String name) {
        String endpoint = ip + ":" + port;
        String readerName = name != null ? name : "READER";

        Map<Integer, Boolean> antennaStatus = new HashMap<>();
        for (int i = 1; i <= 4; i++) {
            antennaStatus.put(i, false);
        }

        IAsynchronousMessage noopCallback = new IAsynchronousMessage() {
            @Override public void OutPutTags(Tag_Model tag_model) { }
            @Override public void OutPutTagsOver() { }
            @Override public void GPIControlMsg(int gpi, int state, int src) { }
            @Override public void PortConnecting(String info) { }
            @Override public void PortClosing(String info) { }
            @Override public void WriteDebugMsg(String msg) { }
            @Override public void WriteLog(String msg) { }
        };

        boolean connected = false;
        try {
            connected = CLReader.CreateTcpConn(endpoint, noopCallback);

            if (connected) {
                try {
                    HashMap<Integer, Integer> power = CLReader._Config.GetANTPowerParam(endpoint);
                    if (power != null) {
                        for (int i = 1; i <= 4; i++) {
                            Integer p = power.get(i);
                            antennaStatus.put(i, p != null && p > 0);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.debug("{} Antenna query interrupted", readerName);
                } catch (Exception e) {
                    log.debug("{} Antenna query failed: {}", readerName, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.debug("{} Test connection failed: {}", readerName, e.getMessage());
            connected = false;
        } finally {
            try {
                CLReader.CloseConn(endpoint);
            } catch (Exception ignored) {
            }
        }

        return new ReaderTestResult(connected, antennaStatus);
    }
}
