package com.openpositioning.PositionMe.domain;

/** Wi-Fi location fix, with mean scan RSSI in dBm and monotonic elapsedRealtime milliseconds. */
public final class WifiObservation {
    private final long elapsedRealtimeMillis;
    private final GeoPosition position;
    private final double averageRssiDbm;
    private final int floor;

    public WifiObservation(long elapsedRealtimeMillis, GeoPosition position, double averageRssiDbm, int floor) {
        if (elapsedRealtimeMillis < 0) throw new IllegalArgumentException("Timestamp must be non-negative");
        if (position == null) throw new IllegalArgumentException("Position is required");
        if (!Double.isFinite(averageRssiDbm)) throw new IllegalArgumentException("RSSI must be finite");
        this.elapsedRealtimeMillis = elapsedRealtimeMillis;
        this.position = position;
        this.averageRssiDbm = averageRssiDbm;
        this.floor = floor;
    }

    public long getElapsedRealtimeMillis() { return elapsedRealtimeMillis; }
    public GeoPosition getPosition() { return position; }
    public double getAverageRssiDbm() { return averageRssiDbm; }
    public int getFloor() { return floor; }
}
