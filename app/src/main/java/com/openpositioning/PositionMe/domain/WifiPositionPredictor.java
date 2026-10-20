package com.openpositioning.PositionMe.domain;

/** Model boundary. Implementations own their runtime; missing assets fail at construction. */
public interface WifiPositionPredictor extends AutoCloseable {
    GeoPosition predict(WifiFingerprint fingerprint);
    @Override void close();
}
