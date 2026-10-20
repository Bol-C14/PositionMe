package com.openpositioning.PositionMe.domain.recording;

/** Lifecycle state for an auxiliary sensor-data recording session. */
public enum SessionState {
    NEW,
    RUNNING,
    STOPPING,
    STOPPED,
    FAILED
}
