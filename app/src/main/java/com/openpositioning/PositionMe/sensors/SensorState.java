package com.openpositioning.PositionMe.sensors;

/**
 * Package-private mutable state owned by the sensor subsystem. Every coherent multi-field read
 * or mutation must synchronize on this object; external consumers receive {@link SensorSnapshot}.
 */
final class SensorState {

    // IMU arrays
    final float[] acceleration = new float[3];
    final float[] filteredAcc = new float[3];
    final float[] gravity = new float[3];
    final float[] magneticField = new float[3];
    final float[] angularVelocity = new float[3];
    final float[] orientation = new float[3];
    final float[] R = new float[9];

    // Rotation vector: volatile because TYPE_ROTATION_VECTOR replaces via clone()
    volatile float[] rotation = new float[]{0, 0, 0, 1.0f};

    // Scalar sensors
    volatile float pressure;
    volatile float light;
    volatile float proximity;

    // Derived values from PDR
    volatile float elevation;
    volatile boolean elevator;

    // GNSS location
    volatile float latitude;
    volatile float longitude;
    volatile double altitude;
    final float[] startLocation = new float[2];

    // Step counting
    volatile int stepCounter;
}
