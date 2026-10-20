package com.openpositioning.PositionMe.domain;

/** Immutable fused estimate. ENU values are meters, heading is radians clockwise from north. */
public final class PositionEstimate {
    private final GeoPosition position;
    private final double eastMeters;
    private final double northMeters;
    private final double upMeters;
    private final double headingRadians;
    private final long elapsedRealtimeMillis;

    public PositionEstimate(GeoPosition position, double eastMeters, double northMeters, double upMeters,
                            double headingRadians, long elapsedRealtimeMillis) {
        if (position == null) throw new IllegalArgumentException("Position is required");
        if (!Double.isFinite(eastMeters) || !Double.isFinite(northMeters) || !Double.isFinite(upMeters)
                || !Double.isFinite(headingRadians) || elapsedRealtimeMillis < 0) {
            throw new IllegalArgumentException("Estimate values must be finite and timestamp non-negative");
        }
        this.position = position;
        this.eastMeters = eastMeters;
        this.northMeters = northMeters;
        this.upMeters = upMeters;
        this.headingRadians = headingRadians;
        this.elapsedRealtimeMillis = elapsedRealtimeMillis;
    }

    public GeoPosition getPosition() { return position; }
    public double getEastMeters() { return eastMeters; }
    public double getNorthMeters() { return northMeters; }
    public double getUpMeters() { return upMeters; }
    public double getHeadingRadians() { return headingRadians; }
    public long getElapsedRealtimeMillis() { return elapsedRealtimeMillis; }
}
