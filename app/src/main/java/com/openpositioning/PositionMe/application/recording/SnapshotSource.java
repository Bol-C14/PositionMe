package com.openpositioning.PositionMe.application.recording;

import org.json.JSONObject;

/** Supplies a point-in-time sensor snapshot and starts a WiFi positioning request. */
public interface SnapshotSource {
    JSONObject captureSnapshot() throws Exception;
    void requestWifiUpdate() throws Exception;
}
