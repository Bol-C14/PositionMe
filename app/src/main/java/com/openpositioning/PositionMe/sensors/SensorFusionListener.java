package com.openpositioning.PositionMe.sensors;

/** Receives immutable sensor-fusion updates. Callbacks run on the producing sensor/network thread. */
public interface SensorFusionListener {
    void onSensorFusionEvent(SensorFusionEvent event);
}
