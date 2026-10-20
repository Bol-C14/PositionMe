package com.openpositioning.PositionMe.domain;

/** Immutable WGS-84 position; latitude/longitude are degrees and altitude is meters. */
public final class GeoPosition {
    private final double latitudeDegrees;
    private final double longitudeDegrees;
    private final double altitudeMeters;

    public GeoPosition(double latitudeDegrees, double longitudeDegrees, double altitudeMeters) {
        if (!Double.isFinite(latitudeDegrees) || latitudeDegrees < -90.0 || latitudeDegrees > 90.0) {
            throw new IllegalArgumentException("Latitude must be finite and in [-90, 90]");
        }
        if (!Double.isFinite(longitudeDegrees) || longitudeDegrees < -180.0 || longitudeDegrees > 180.0) {
            throw new IllegalArgumentException("Longitude must be finite and in [-180, 180]");
        }
        if (!Double.isFinite(altitudeMeters)) {
            throw new IllegalArgumentException("Altitude must be finite");
        }
        this.latitudeDegrees = latitudeDegrees;
        this.longitudeDegrees = longitudeDegrees;
        this.altitudeMeters = altitudeMeters;
    }

    public double getLatitudeDegrees() { return latitudeDegrees; }
    public double getLongitudeDegrees() { return longitudeDegrees; }
    public double getAltitudeMeters() { return altitudeMeters; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof GeoPosition)) return false;
        GeoPosition that = (GeoPosition) other;
        return Double.compare(latitudeDegrees, that.latitudeDegrees) == 0
                && Double.compare(longitudeDegrees, that.longitudeDegrees) == 0
                && Double.compare(altitudeMeters, that.altitudeMeters) == 0;
    }

    @Override public int hashCode() {
        return java.util.Objects.hash(latitudeDegrees, longitudeDegrees, altitudeMeters);
    }
}
