package com.openpositioning.PositionMe.sensors;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorManager;

/**
 * Movement sensor class representing all Sensor Manager based devices.
 *
 * The class is initialised with the application context to be used for permissions and hardware
 * access. Using the context, it adds a Sensor Manager, as well as a Sensor and {@link SensorInfo}
 * instance, with the type of the sensor determined upon initialisation of the class.
 *
 * @see SensorFusion where instances of this class are intended to be used.
 *
 * @author Mate Stodulka
 */
public class MovementSensor {
    // Application context for permissions and hardware access
    private final Context context;
    // Sensor Manager from the android hardware manager
    private final SensorManager sensorManager;
    // The Sensor instance determined by the type upon initialisation
    private final Sensor sensor;
    // Information about the sensor stored in a SensorInfo object
    private final SensorInfo sensorInfo;


    /**
     * Private constructor used by {@link MovementSensorFactory}.
     */
    MovementSensor(Context context, SensorManager sensorManager, Sensor sensor, SensorInfo sensorInfo) {
        this.context = context;
        this.sensorManager = sensorManager;
        this.sensor = sensor;
        this.sensorInfo = sensorInfo;
    }

    public Context getContext() {
        return context;
    }

    public SensorManager getSensorManager() {
        return sensorManager;
    }

    public Sensor getSensor() {
        return sensor;
    }

    public SensorInfo getSensorInfo() {
        return sensorInfo;
    }

}
