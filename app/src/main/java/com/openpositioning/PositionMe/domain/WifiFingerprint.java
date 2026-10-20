package com.openpositioning.PositionMe.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable Wi-Fi fingerprint captured at elapsedRealtime milliseconds, RSSI in dBm. */
public final class WifiFingerprint {
    public static final class AccessPoint {
        private final String identifier;
        private final int rssiDbm;

        public AccessPoint(String identifier, int rssiDbm) {
            if (identifier == null || identifier.isEmpty()) {
                throw new IllegalArgumentException("Access point identifier is required");
            }
            this.identifier = identifier;
            this.rssiDbm = rssiDbm;
        }

        public String getIdentifier() { return identifier; }
        public int getRssiDbm() { return rssiDbm; }
    }

    private final long elapsedRealtimeMillis;
    private final List<AccessPoint> accessPoints;

    public WifiFingerprint(long elapsedRealtimeMillis, List<AccessPoint> accessPoints) {
        if (elapsedRealtimeMillis < 0) throw new IllegalArgumentException("Negative timestamp");
        this.elapsedRealtimeMillis = elapsedRealtimeMillis;
        ArrayList<AccessPoint> copy = new ArrayList<>(Objects.requireNonNull(accessPoints));
        for (AccessPoint point : copy) Objects.requireNonNull(point);
        this.accessPoints = Collections.unmodifiableList(copy);
    }

    public long getElapsedRealtimeMillis() { return elapsedRealtimeMillis; }
    public List<AccessPoint> getAccessPoints() { return accessPoints; }
}
