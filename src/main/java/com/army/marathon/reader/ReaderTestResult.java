package com.army.marathon.reader;

import java.util.Map;

/**
 * Shared result shape for a reader's connect + per-antenna connectivity test.
 * Used by both ClouReaderClient and ZebraFxReaderClient's static
 * testConnectionAndAntennas() so ConfigurationController doesn't need to know
 * which reader vendor produced the result.
 */
public class ReaderTestResult {
    public final boolean readerConnected;
    public final Map<Integer, Boolean> antennaStatus;

    public ReaderTestResult(boolean readerConnected, Map<Integer, Boolean> antennaStatus) {
        this.readerConnected = readerConnected;
        this.antennaStatus = antennaStatus;
    }
}
