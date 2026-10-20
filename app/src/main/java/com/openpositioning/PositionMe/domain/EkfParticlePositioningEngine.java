package com.openpositioning.PositionMe.domain;

/** Behavior-preserving EKF/PF fusion core, independent of Android and map libraries. */
public final class EkfParticlePositioningEngine implements PositioningEngine {
    private static final int PARTICLE_COUNT = 100;

    private final GeoPosition preferredReferencePosition;
    private final double initialHeadingRadians;
    private final long randomSeed;
    private GeoPosition referencePosition;
    private EKF ekf;
    private ParticleFilter particleFilter;
    private double previousHeadingRadians;
    private long lastFusionMeasurementMillis;
    private long lastEventMillis;
    private long lastStepCounter;
    private PositionEstimate estimate;

    public EkfParticlePositioningEngine(GeoPosition preferredReferencePosition,
                                       double initialHeadingRadians, long randomSeed) {
        if (!Double.isFinite(initialHeadingRadians)) throw new IllegalArgumentException("Heading must be finite");
        this.preferredReferencePosition = preferredReferencePosition;
        this.initialHeadingRadians = initialHeadingRadians;
        this.randomSeed = randomSeed;
        this.previousHeadingRadians = initialHeadingRadians;
    }

    @Override public synchronized boolean isInitialized() { return ekf != null; }
    @Override public synchronized PositionEstimate getEstimate() { return estimate; }

    @Override public synchronized PositionEstimate onStep(StepObservation observation) {
        requireObservation(observation);
        if (!acceptTime(observation.getElapsedRealtimeMillis()) || ekf == null) return estimate;
        double heading = observation.getHeadingRadians();
        double deltaHeading = wrapToPi(heading - previousHeadingRadians);
        previousHeadingRadians = heading;
        double stepLength = Math.max(0.0, observation.getStepLengthMeters());
        ekf.predict(stepLength, deltaHeading);
        particleFilter.predict(stepLength, deltaHeading);
        if (observation.getStepCounter() % 5 == 0) particleFilter.resample();
        double[] pf = particleFilter.getEstimate2D();
        double[] ekfPosition = ekf.getEstimatedPositionENU();
        double disagreement = Math.hypot(ekfPosition[0] - pf[0], ekfPosition[1] - pf[1]);
        if (disagreement > 10.0 && particleFilter.computeEntropy() < 5.0) {
            ekf.resetPosition(pf[0], pf[1]);
        }
        lastStepCounter = observation.getStepCounter();
        return snapshot(observation.getElapsedRealtimeMillis());
    }

    @Override public synchronized PositionEstimate onWifi(WifiObservation observation) {
        requireObservation(observation);
        if (!acceptTime(observation.getElapsedRealtimeMillis())) return estimate;
        if (!initializeIfNeeded(observation.getPosition())) return estimate;
        double[] enu = Geodesy.geodeticToEnu(observation.getPosition(), referencePosition);
        long elapsed = lastFusionMeasurementMillis == 0L ? 0L
                : observation.getElapsedRealtimeMillis() - lastFusionMeasurementMillis;
        double rssi = observation.getAverageRssiDbm();
        double base = rssi > -50.0 ? 3.0 : (rssi > -75.0 ? 4.0 : 5.0);
        double penalty = base * (1.0 + Math.min(elapsed / 10000.0, 3.0));
        ekf.update(enu[0], enu[1], penalty);
        particleFilter.updateWiFi(enu[0], enu[1]);
        particleFilter.resample();
        lastFusionMeasurementMillis = observation.getElapsedRealtimeMillis();
        return snapshot(observation.getElapsedRealtimeMillis());
    }

    @Override public synchronized PositionEstimate onGnss(GnssObservation observation) {
        requireObservation(observation);
        if (!acceptTime(observation.getElapsedRealtimeMillis())) return estimate;
        if (!initializeIfNeeded(observation.getPosition())) return estimate;
        if (observation.getAccuracyMeters() >= 20.0) return snapshot(observation.getElapsedRealtimeMillis());
        double[] enu = Geodesy.geodeticToEnu(observation.getPosition(), referencePosition);
        ekf.updateGNSS(enu[0], enu[1], enu[2], 1.0);
        // Preserve the existing particle-filter measurement model, which treats GNSS altitude as zero.
        GeoPosition pfPosition = new GeoPosition(observation.getPosition().getLatitudeDegrees(),
                observation.getPosition().getLongitudeDegrees(), 0.0);
        particleFilter.updateGNSS(pfPosition);
        particleFilter.resample();
        return snapshot(observation.getElapsedRealtimeMillis());
    }

    private boolean initializeIfNeeded(GeoPosition firstMeasurement) {
        if (ekf != null) return true;
        if (firstMeasurement.getLatitudeDegrees() == 0.0
                && firstMeasurement.getLongitudeDegrees() == 0.0) return false;
        double referenceLat = preferredReferencePosition == null
                ? firstMeasurement.getLatitudeDegrees() : preferredReferencePosition.getLatitudeDegrees();
        double referenceLon = preferredReferencePosition == null
                ? firstMeasurement.getLongitudeDegrees() : preferredReferencePosition.getLongitudeDegrees();
        referencePosition = new GeoPosition(referenceLat, referenceLon, firstMeasurement.getAltitudeMeters());
        ekf = new EKF(0.0, 0.0, 0.0, initialHeadingRadians);
        particleFilter = new ParticleFilter(PARTICLE_COUNT, referencePosition, randomSeed);
        particleFilter.initializeParticles(0.0, 0.0, initialHeadingRadians, 1.0, 1.0, 0.1);
        previousHeadingRadians = initialHeadingRadians;
        return true;
    }

    private PositionEstimate snapshot(long timestampMillis) {
        double[] state = ekf.getStateVector();
        GeoPosition position = Geodesy.enuToGeodetic(state[0], state[1], state[2], referencePosition);
        estimate = new PositionEstimate(position, state[0], state[1], state[2], state[3], timestampMillis);
        return estimate;
    }

    private boolean acceptTime(long timestampMillis) {
        if (timestampMillis < lastEventMillis) return false;
        lastEventMillis = timestampMillis;
        return true;
    }

    private static void requireObservation(Object observation) {
        if (observation == null) throw new IllegalArgumentException("Observation is required");
    }

    private static double wrapToPi(double angle) {
        while (angle > Math.PI) angle -= 2.0 * Math.PI;
        while (angle < -Math.PI) angle += 2.0 * Math.PI;
        return angle;
    }
}
