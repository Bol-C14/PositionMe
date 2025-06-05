package com.openpositioning.PositionMe.data.storage;

import org.json.JSONObject;

/**
 * Interface defining how trajectory data should be written.
 */
public interface TrajectoryDataWriter {
    /**
     * Add a single JSON record.
     * @param record JSON data to append
     */
    void addRecord(JSONObject record);

    /** Flush any buffered data to storage. */
    void flush();

    /** Finalize the writer and release resources. */
    void close();
}
