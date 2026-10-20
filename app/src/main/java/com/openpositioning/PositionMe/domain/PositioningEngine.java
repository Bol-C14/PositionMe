package com.openpositioning.PositionMe.domain;

/** Stateful pure-domain contract for fusing monotonic-time positioning observations. */
public interface PositioningEngine {
    boolean isInitialized();
    /** Returns null until a valid Wi-Fi or GNSS observation initializes the local frame. */
    PositionEstimate getEstimate();
    PositionEstimate onStep(StepObservation observation);
    PositionEstimate onWifi(WifiObservation observation);
    PositionEstimate onGnss(GnssObservation observation);
}
