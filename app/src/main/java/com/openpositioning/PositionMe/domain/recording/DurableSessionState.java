package com.openpositioning.PositionMe.domain.recording;

/** Persisted lifecycle, distinct from the in-memory collector lifecycle. */
public enum DurableSessionState {
    OPEN,
    INTERRUPTED,
    SAVED,
    RECOVERED,
    CORRUPT,
    FAILED
}
