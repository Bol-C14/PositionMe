package com.openpositioning.PositionMe.domain.recording;

/** Export tracking is independent from whether the private session was saved. */
public enum ExportStatus {
    NOT_REQUESTED,
    PENDING,
    EXPORTED,
    FAILED
}
