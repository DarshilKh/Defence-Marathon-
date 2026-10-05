package com.army.marathon.reader;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Common shape implemented by every reader vendor client (ClouReaderClient,
 * ZebraFxReaderClient). Lets MultiReaderService hold and operate on whichever
 * reader type is configured (see RaceConfiguration.ReaderType) through a single
 * instantiation-time branch, with no other call sites needing to know or care
 * which vendor's hardware is actually connected.
 */
public interface RfidReaderClient {

    boolean connect(String ip, int port, String name);

    boolean isConnected();

    void startInventory(BiConsumer<String, LocalDateTime> callback);

    void stopInventory();

    void disconnect();

    String getReaderName();

    boolean isReading();

    /**
     * Per-antenna status captured the last time this client connected.
     *
     * Exists so the Configuration page's "Test" button can report the status of
     * a reader that is ALREADY armed and scanning, instead of opening a second
     * connection to it. Zebra FX readers permit only one host session, so a
     * competing test connection is refused with "Connection Already exists" -
     * which made a perfectly healthy, actively-scanning reader show up red.
     *
     * Default is an empty map for vendors that don't capture this.
     */
    default Map<Integer, Boolean> getLastAntennaStatus() {
        return Collections.emptyMap();
    }
}
