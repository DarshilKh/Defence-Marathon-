// src/main/java/com/army/marathon/service/UhfReaderService.java
package com.army.marathon.service;

import com.army.marathon.reader.UhfDll;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.ByteByReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Slf4j
@Service
public class UhfReaderService {
    
    private volatile boolean running = true;
    private volatile int frmHandle = -1;
    private volatile boolean isConnected = false;
    
    private final AtomicBoolean singleScanRequested = new AtomicBoolean(false);
    private volatile String lastReturnedTag = null;
    private Consumer<String> scanCallback;
    
    private static final int RECONNECT_INTERVAL_MS = 12000;
    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private volatile long lastReconnectAttempt = 0;
    private volatile int currentReconnectAttempts = 0;
    private volatile boolean lastConnectResultLogged = false;
    
    /**
     * Initialize the reader service - REQUIRED METHOD
     */
    public void initialize() {
        new Thread(this::readerLoop, "uhf-reader-thread").start();
        log.info("🔷 UHF Reader Service initialized with auto-reconnection");
    }
    
    /**
     * Request a single scan - REQUIRED METHOD
     */
    public void requestSingleScan(Consumer<String> callback) {
        this.scanCallback = callback;
        singleScanRequested.set(true);
        lastReturnedTag = null;
        log.debug("Single scan requested");
    }
    
    /**
     * Check if reader is connected - REQUIRED METHOD
     */
    public boolean isConnected() {
        return isConnected && frmHandle != -1 && running;
    }
    
    /**
     * Shutdown the reader service - REQUIRED METHOD
     */
    public void shutdown() {
        running = false;
        forceCloseCOMPort();
        isConnected = false;
        log.info("🛑 RFID Reader shutdown called");
    }
    
    /**
     * Trigger manual reconnection - OPTIONAL METHOD
     */
    public void triggerReconnect() {
        log.info("🔄 Manual reconnection triggered");
        isConnected = false;
        forceCloseCOMPort();
    }
    
    private void forceCloseCOMPort() {
        try {
            if (frmHandle != -1) {
                log.debug("Force closing COM port handle: {}", frmHandle);
                UhfDll.INSTANCE.CloseComPort();
                Thread.sleep(500);
                frmHandle = -1;
            }
        } catch (Exception e) {
            log.debug("Error force closing COM port: {}", e.getMessage());
        }
    }
    
    private boolean attemptConnection() {
        try {
            forceCloseCOMPort();
            Thread.sleep(1000);
            
            IntByReference portRef = new IntByReference();
            IntByReference handleRef = new IntByReference();
            ByteByReference comAdr = new ByteByReference((byte) 0x00);

            if (currentReconnectAttempts == 1 || currentReconnectAttempts % 5 == 0) {
                log.info("🔄 Attempting connection (attempt {})", currentReconnectAttempts);
            }
            
            int rc = UhfDll.INSTANCE.AutoOpenComPort(portRef, comAdr, (byte) 5, handleRef);
            
            if (rc != 0) {
                if (currentReconnectAttempts % 5 == 0) {
                    log.warn("⚠️ AutoOpenComPort failed (attempt {}), rc={}", currentReconnectAttempts, rc);
                }
                
                if (rc == 48) {
                    log.debug("Port in use - forcing cleanup...");
                    forceCloseCOMPort();
                    Thread.sleep(2000);
                }
                
                isConnected = false;
                lastConnectResultLogged = false;
                return false;
            }

            frmHandle = handleRef.getValue();
            isConnected = true;
            
            if (!lastConnectResultLogged) {
                log.info("✅ RFID Reader Connected! Handle: {}", frmHandle);
                lastConnectResultLogged = true;
            }
            
            return true;
            
        } catch (Exception e) {
            log.debug("Exception during connection: {}", e.getMessage());
            isConnected = false;
            forceCloseCOMPort();
            return false;
        }
    }
    
    private void readerLoop() {
        byte[] rawBuf = new byte[40960];
        IntByReference totallen = new IntByReference();
        IntByReference cardNum = new IntByReference();
        ByteByReference comAdr = new ByteByReference((byte) 0x00);
        
        int reconnectAttempts = 0;
        long lastStatusLog = System.currentTimeMillis();
        
        log.info("▶️ Reader loop started");
        
        while (running) {
            try {
                if (!isConnected || frmHandle == -1) {
                    long now = System.currentTimeMillis();
                    
                    if (now - lastReconnectAttempt < RECONNECT_INTERVAL_MS) {
                        Thread.sleep(500);
                        continue;
                    }
                    
                    lastReconnectAttempt = now;
                    reconnectAttempts++;
                    currentReconnectAttempts = reconnectAttempts;
                    
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastStatusLog > 10000) {
                        log.info("🔄 Reconnecting... (attempt {})", reconnectAttempts);
                        lastStatusLog = currentTime;
                    }
                    
                    if (attemptConnection()) {
                        reconnectAttempts = 0;
                        currentReconnectAttempts = 0;
                        log.info("🔌 Reader successfully connected!");
                    } else {
                        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
                            log.warn("⏸️ Max reconnection attempts ({}) reached. Waiting...", MAX_RECONNECT_ATTEMPTS);
                            Thread.sleep(10000);
                            reconnectAttempts = 0;
                            currentReconnectAttempts = 0;
                            lastStatusLog = System.currentTimeMillis();
                        }
                        continue;
                    }
                }
                
                // Normal scanning logic
                try {
                    int r = UhfDll.INSTANCE.Inventory_G2(comAdr, (byte)0, (byte)0, (byte)0, 
                                                          rawBuf, totallen, cardNum, frmHandle);

                    if ((r == 1 || r == 2 || r == 3 || r == 4 || r == 0xFB) && singleScanRequested.get()) {
                        int total = totallen.getValue();
                        int count = cardNum.getValue();

                        if (total > 0 && count > 0) {
                            try {
                                int idx = 0;
                                for (int i = 0; i < count; i++) {
                                    if (idx >= total) break;
                                    int epcLen = rawBuf[idx] & 0xFF;
                                    if (epcLen <= 0 || idx + 1 + epcLen > total) break;
                                    
                                    byte[] epcBytes = new byte[epcLen];
                                    System.arraycopy(rawBuf, idx + 1, epcBytes, 0, epcLen);
                                    String epcHex = bytesToHex(epcBytes);
                                    idx += 1 + epcLen;

                                    if (!epcHex.equalsIgnoreCase(lastReturnedTag)) {
                                        log.info("📌 Tag scanned: {}", epcHex);
                                        lastReturnedTag = epcHex;
                                        singleScanRequested.set(false);
                                        
                                        if (scanCallback != null) {
                                            scanCallback.accept(epcHex);
                                        }
                                        break;
                                    }
                                }
                            } catch (Exception ex) {
                                log.warn("Error parsing tags: {}", ex.getMessage());
                            }
                        }
                    }

                    Thread.sleep(200);
                    
                } catch (Exception e) {
                    log.debug("Reader communication error: {} (will reconnect)", e.getMessage());
                    isConnected = false;
                    forceCloseCOMPort();
                    Thread.sleep(1000);
                }
                
            } catch (Exception e) {
                log.error("Critical reader loop error: {}", e.getMessage());
                isConnected = false;
                forceCloseCOMPort();
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    break;
                }
            }
        }
        
        forceCloseCOMPort();
        isConnected = false;
        log.info("⏹️ Reader service stopped");
    }

    private String bytesToHex(byte[] arr) {
        StringBuilder sb = new StringBuilder();
        for (byte b : arr) sb.append(String.format("%02X", b));
        return sb.toString();
    }
}
