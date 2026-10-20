package com.openpositioning.PositionMe.sensors;

import com.openpositioning.PositionMe.domain.PositionEstimate;

/** Immutable event emitted by {@link SensorFusion}. Irrelevant payload getters return null. */
public final class SensorFusionEvent {
    public enum Kind {
        SENSOR_STATE, PDR_STEP, POSITION_ESTIMATE, WIFI_FLOOR, RECORDING_STARTED, RECORDING_STOPPED
    }

    private final Kind kind;
    private final long elapsedRealtimeMillis;
    private final SensorSnapshot snapshot;
    private final PositionEstimate positionEstimate;
    private final int floor;
    private final float[] pdrPosition;
    private final double stepLengthMeters;
    private final double headingRadians;

    private SensorFusionEvent(Kind kind, long elapsedRealtimeMillis, SensorSnapshot snapshot,
                              PositionEstimate positionEstimate, int floor) {
        this(kind, elapsedRealtimeMillis, snapshot, positionEstimate, floor, null, 0.0, 0.0);
    }

    private SensorFusionEvent(Kind kind, long elapsedRealtimeMillis, SensorSnapshot snapshot,
                              PositionEstimate positionEstimate, int floor, float[] pdrPosition,
                              double stepLengthMeters, double headingRadians) {
        this.kind = kind;
        this.elapsedRealtimeMillis = elapsedRealtimeMillis;
        this.snapshot = snapshot;
        this.positionEstimate = positionEstimate;
        this.floor = floor;
        this.pdrPosition = pdrPosition == null ? null : pdrPosition.clone();
        this.stepLengthMeters = stepLengthMeters;
        this.headingRadians = headingRadians;
    }

    public static SensorFusionEvent sensorState(long time, SensorSnapshot snapshot) {
        return new SensorFusionEvent(Kind.SENSOR_STATE, time, snapshot, null, 0);
    }

    public static SensorFusionEvent positionEstimate(long time, PositionEstimate estimate) {
        return new SensorFusionEvent(Kind.POSITION_ESTIMATE, time, null, estimate, 0);
    }

    public static SensorFusionEvent pdrStep(long time, float[] position, double stepLengthMeters,
                                            double headingRadians) {
        return new SensorFusionEvent(Kind.PDR_STEP, time, null, null, 0, position,
                stepLengthMeters, headingRadians);
    }

    public static SensorFusionEvent wifiFloor(long time, int floor) {
        return new SensorFusionEvent(Kind.WIFI_FLOOR, time, null, null, floor);
    }

    public static SensorFusionEvent recordingLifecycle(long time, boolean started) {
        return new SensorFusionEvent(started ? Kind.RECORDING_STARTED : Kind.RECORDING_STOPPED,
                time, null, null, 0);
    }

    public Kind getKind() { return kind; }
    public long getElapsedRealtimeMillis() { return elapsedRealtimeMillis; }
    public SensorSnapshot getSnapshot() { return snapshot; }
    public PositionEstimate getPositionEstimate() { return positionEstimate; }
    public int getFloor() { return floor; }
    public float[] getPdrPosition() { return pdrPosition == null ? null : pdrPosition.clone(); }
    public double getStepLengthMeters() { return stepLengthMeters; }
    public double getHeadingRadians() { return headingRadians; }
}
