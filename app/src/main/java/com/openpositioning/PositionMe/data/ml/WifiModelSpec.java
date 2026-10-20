package com.openpositioning.PositionMe.data.ml;

/** Normalization contract supplied with a trained Wi-Fi model, in geographic degrees. */
public final class WifiModelSpec {
    private final int topN;
    private final double minLatitude, maxLatitude, minLongitude, maxLongitude;

    public WifiModelSpec(int topN, double minLatitude, double maxLatitude,
                         double minLongitude, double maxLongitude) {
        if (topN <= 0 || !Double.isFinite(minLatitude) || !Double.isFinite(maxLatitude)
                || !Double.isFinite(minLongitude) || !Double.isFinite(maxLongitude)
                || minLatitude < -90 || maxLatitude > 90 || minLongitude < -180
                || maxLongitude > 180 || minLatitude >= maxLatitude || minLongitude >= maxLongitude) {
            throw new IllegalArgumentException("Invalid model feature count or normalization bounds");
        }
        this.topN = topN;
        this.minLatitude = minLatitude;
        this.maxLatitude = maxLatitude;
        this.minLongitude = minLongitude;
        this.maxLongitude = maxLongitude;
    }
    public int getTopN() { return topN; }
    public double latitude(double normalized) { return minLatitude + normalized * (maxLatitude - minLatitude); }
    public double longitude(double normalized) { return minLongitude + normalized * (maxLongitude - minLongitude); }
}
