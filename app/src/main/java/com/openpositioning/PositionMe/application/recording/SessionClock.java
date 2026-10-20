package com.openpositioning.PositionMe.application.recording;

/** Monotonic time source used for recording cadence decisions. */
public interface SessionClock {
    long elapsedRealtimeMillis();
}
