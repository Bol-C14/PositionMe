package com.openpositioning.PositionMe.presentation.files;

import com.openpositioning.PositionMe.domain.recording.SessionSummary;
import com.openpositioning.PositionMe.sensors.SensorFusion;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Production Files command adapter for the existing durable session owner. */
public final class SensorFusionLocalSessionCommands implements LocalSessionCommands {
    private final SensorFusion sensorFusion;

    public SensorFusionLocalSessionCommands(SensorFusion sensorFusion) {
        this.sensorFusion = sensorFusion;
    }

    @Override public CompletableFuture<List<SessionSummary>> listSessions() {
        return sensorFusion.listRecordingSessionsAsync();
    }

    @Override public CompletableFuture<?> recover(UUID sessionId) {
        return sensorFusion.recoverRecordingSessionAsync(sessionId);
    }

    @Override public CompletableFuture<?> export(UUID sessionId) {
        return sensorFusion.exportRecordingSessionAsync(sessionId);
    }
}
