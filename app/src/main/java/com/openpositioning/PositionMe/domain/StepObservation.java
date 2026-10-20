package com.openpositioning.PositionMe.domain;

/** One PDR step. Heading is radians clockwise from north; time is elapsedRealtime milliseconds. */
public final class StepObservation {
    private final long elapsedRealtimeMillis;
    private final double stepLengthMeters;
    private final double headingRadians;
    private final long stepCounter;

    public StepObservation(long elapsedRealtimeMillis, double stepLengthMeters,
                           double headingRadians, long stepCounter) {
        if (elapsedRealtimeMillis < 0 || stepCounter < 0) throw new IllegalArgumentException("Times and counters must be non-negative");
        if (!Double.isFinite(stepLengthMeters) || !Double.isFinite(headingRadians)) throw new IllegalArgumentException("Step values must be finite");
        this.elapsedRealtimeMillis = elapsedRealtimeMillis;
        this.stepLengthMeters = stepLengthMeters;
        this.headingRadians = headingRadians;
        this.stepCounter = stepCounter;
    }

    public long getElapsedRealtimeMillis() { return elapsedRealtimeMillis; }
    public double getStepLengthMeters() { return stepLengthMeters; }
    public double getHeadingRadians() { return headingRadians; }
    public long getStepCounter() { return stepCounter; }
}
