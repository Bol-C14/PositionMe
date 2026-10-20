package com.openpositioning.PositionMe.application.recording;

import org.json.JSONObject;

/** Ordered destination for passive snapshots and user annotations. */
public interface RecordSink extends AutoCloseable {
    void write(JSONObject record) throws Exception;
    void flush() throws Exception;
    @Override
    void close() throws Exception;
}
