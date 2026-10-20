package com.openpositioning.PositionMe.domain;

/** Pure WGS-84 geodetic and local East-North-Up conversions. Distances are meters. */
public final class Geodesy {
    private static final double A = 6378137.0;
    private static final double B = 6356752.31424518;
    private static final double F = (A - B) / A;
    private static final double E2 = F * (2.0 - F);

    private Geodesy() { }

    public static double[] geodeticToEnu(GeoPosition position, GeoPosition reference) {
        double[] point = geodeticToEcef(position);
        double[] origin = geodeticToEcef(reference);
        double dx = point[0] - origin[0];
        double dy = point[1] - origin[1];
        double dz = point[2] - origin[2];
        double lat = Math.toRadians(reference.getLatitudeDegrees());
        double lon = Math.toRadians(reference.getLongitudeDegrees());
        double east = -Math.sin(lon) * dx + Math.cos(lon) * dy;
        double north = -Math.sin(lat) * Math.cos(lon) * dx
                - Math.sin(lat) * Math.sin(lon) * dy + Math.cos(lat) * dz;
        double up = Math.cos(lat) * Math.cos(lon) * dx
                + Math.cos(lat) * Math.sin(lon) * dy + Math.sin(lat) * dz;
        return new double[]{east, north, up};
    }

    public static GeoPosition enuToGeodetic(double eastMeters, double northMeters, double upMeters,
                                             GeoPosition reference) {
        if (!Double.isFinite(eastMeters) || !Double.isFinite(northMeters) || !Double.isFinite(upMeters)) {
            throw new IllegalArgumentException("ENU coordinates must be finite");
        }
        double[] origin = geodeticToEcef(reference);
        double lat = Math.toRadians(reference.getLatitudeDegrees());
        double lon = Math.toRadians(reference.getLongitudeDegrees());
        double x = origin[0] - Math.sin(lon) * eastMeters
                - Math.sin(lat) * Math.cos(lon) * northMeters
                + Math.cos(lat) * Math.cos(lon) * upMeters;
        double y = origin[1] + Math.cos(lon) * eastMeters
                - Math.sin(lat) * Math.sin(lon) * northMeters
                + Math.cos(lat) * Math.sin(lon) * upMeters;
        double z = origin[2] + Math.cos(lat) * northMeters + Math.sin(lat) * upMeters;
        return ecefToGeodetic(x, y, z);
    }

    private static double[] geodeticToEcef(GeoPosition position) {
        double lat = Math.toRadians(position.getLatitudeDegrees());
        double lon = Math.toRadians(position.getLongitudeDegrees());
        double sinLat = Math.sin(lat);
        double n = A / Math.sqrt(1.0 - E2 * sinLat * sinLat);
        double alt = position.getAltitudeMeters();
        return new double[]{(n + alt) * Math.cos(lat) * Math.cos(lon),
                (n + alt) * Math.cos(lat) * Math.sin(lon),
                (n * (1.0 - E2) + alt) * sinLat};
    }

    private static GeoPosition ecefToGeodetic(double x, double y, double z) {
        double p = Math.hypot(x, y);
        double ep2 = (A * A - B * B) / (B * B);
        double theta = Math.atan2(A * z, B * p);
        double sinTheta = Math.sin(theta);
        double cosTheta = Math.cos(theta);
        double lat = Math.atan2(z + ep2 * B * sinTheta * sinTheta * sinTheta,
                p - E2 * A * cosTheta * cosTheta * cosTheta);
        double lon = Math.atan2(y, x);
        double sinLat = Math.sin(lat);
        double n = A / Math.sqrt(1.0 - E2 * sinLat * sinLat);
        double altitude = Math.abs(Math.cos(lat)) > 1e-12 ? p / Math.cos(lat) - n
                : Math.abs(z) - B;
        return new GeoPosition(Math.toDegrees(lat), Math.toDegrees(lon), altitude);
    }
}
