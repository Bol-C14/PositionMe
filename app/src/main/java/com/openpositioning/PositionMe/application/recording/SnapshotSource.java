package com.openpositioning.PositionMe.application.recording;

import org.json.JSONObject;

/** Supplies a point-in-time sensor snapshot and starts a WiFi positioning request. */
public interface SnapshotSource {
    JSONObject captureSnapshot() throws Exception;
    void requestWifiUpdate() throws Exception;

    /** Immutable protobuf checkpoint; null means this source has no protobuf payload. */
    default byte[] captureCheckpoint() throws Exception { return null; }
}
