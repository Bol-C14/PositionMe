package com.openpositioning.PositionMe.sensors;

/** Deep-copied, immutable view of the latest sensor values. Vector getters return new arrays. */
public final class SensorSnapshot {
    private final long elapsedRealtimeMillis;
    private final float[] acceleration, filteredAcceleration, gravity, magneticField;
    private final float[] angularVelocity, orientation, rotation;
    private final float pressure, light, proximity, elevation, latitude, longitude;
    private final double altitude;
    private final float[] startLocation;
    private final boolean elevator;
    private final int stepCounter;

    SensorSnapshot(long elapsedRealtimeMillis, SensorState state) {
        synchronized (state) {
            this.elapsedRealtimeMillis = elapsedRealtimeMillis;
            acceleration = state.acceleration.clone();
            filteredAcceleration = state.filteredAcc.clone();
            gravity = state.gravity.clone();
            magneticField = state.magneticField.clone();
            angularVelocity = state.angularVelocity.clone();
            orientation = state.orientation.clone();
            rotation = state.rotation.clone();
            pressure = state.pressure;
            light = state.light;
            proximity = state.proximity;
            elevation = state.elevation;
            elevator = state.elevator;
            latitude = state.latitude;
            longitude = state.longitude;
            altitude = state.altitude;
            startLocation = state.startLocation.clone();
            stepCounter = state.stepCounter;
        }
    }

    public long getElapsedRealtimeMillis() { return elapsedRealtimeMillis; }
    public float[] getAcceleration() { return acceleration.clone(); }
    public float[] getFilteredAcceleration() { return filteredAcceleration.clone(); }
    public float[] getGravity() { return gravity.clone(); }
    public float[] getMagneticField() { return magneticField.clone(); }
    public float[] getAngularVelocity() { return angularVelocity.clone(); }
    public float[] getOrientation() { return orientation.clone(); }
    public float[] getRotation() { return rotation.clone(); }
    public float[] getStartLocation() { return startLocation.clone(); }
    public float getPressure() { return pressure; }
    public float getLight() { return light; }
    public float getProximity() { return proximity; }
    public float getElevation() { return elevation; }
    public boolean isElevator() { return elevator; }
    public float getLatitude() { return latitude; }
    public float getLongitude() { return longitude; }
    public double getAltitude() { return altitude; }
    public int getStepCounter() { return stepCounter; }
}
