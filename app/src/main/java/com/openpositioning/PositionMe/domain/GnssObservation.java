package com.openpositioning.PositionMe.domain;

/** GNSS fix. Accuracy is horizontal meters; timestamp uses monotonic elapsedRealtime milliseconds. */
public final class GnssObservation {
    private final long elapsedRealtimeMillis;
    private final GeoPosition position;
    private final double accuracyMeters;

    public GnssObservation(long elapsedRealtimeMillis, GeoPosition position, double accuracyMeters) {
        if (elapsedRealtimeMillis < 0) throw new IllegalArgumentException("Timestamp must be non-negative");
        if (position == null) throw new IllegalArgumentException("Position is required");
        if (!Double.isFinite(accuracyMeters) || accuracyMeters < 0) throw new IllegalArgumentException("Accuracy must be finite and non-negative");
        this.elapsedRealtimeMillis = elapsedRealtimeMillis;
        this.position = position;
        this.accuracyMeters = accuracyMeters;
    }

    public long getElapsedRealtimeMillis() { return elapsedRealtimeMillis; }
    public GeoPosition getPosition() { return position; }
    public double getAccuracyMeters() { return accuracyMeters; }
}
