package com.openpositioning.PositionMe.sensors;

import com.openpositioning.PositionMe.domain.recording.SessionStatus;

/** Combined view of protobuf trajectory collection and auxiliary JSON session persistence. */
public final class SensorFusionRecordingStatus {
    private final boolean trajectoryRecording;
    private final SessionStatus sessionStatus;

    SensorFusionRecordingStatus(boolean trajectoryRecording, SessionStatus sessionStatus) {
        this.trajectoryRecording = trajectoryRecording;
        this.sessionStatus = sessionStatus;
    }

    public boolean isTrajectoryRecording() { return trajectoryRecording; }
    public SessionStatus getSessionStatus() { return sessionStatus; }
    public boolean hasSessionFailure() {
        return sessionStatus != null && sessionStatus.isFailure();
    }
}
