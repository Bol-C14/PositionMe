package com.openpositioning.PositionMe.sensors;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorManager;

/**
 * Factory class for creating {@link MovementSensor} instances.
 *
 * Encapsulates the logic of obtaining the Android {@link Sensor} and preparing
 * {@link SensorInfo} so other classes do not need to directly interact with
 * {@link SensorManager}.
 */
public class MovementSensorFactory {

    /**
     * Creates a {@link MovementSensor} for the given Android sensor type.
     *
     * @param context application context used for hardware access
     * @param sensorType one of the {@link Sensor} TYPE constants
     * @return fully constructed {@link MovementSensor}
     */
    public MovementSensor create(Context context, int sensorType) {
        SensorManager manager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        Sensor sensor = manager.getDefaultSensor(sensorType);
        SensorInfo info;
        if (sensor != null) {
            info = new SensorInfo(
                    sensor.getName(),
                    sensor.getVendor(),
                    sensor.getResolution(),
                    sensor.getPower(),
                    sensor.getVersion(),
                    sensor.getType()
            );
        } else {
            info = SensorInfo.unavailable();
        }
        return new MovementSensor(context, manager, sensor, info);
    }
}
