package com.openpositioning.PositionMe.presentation.recording;

import com.openpositioning.PositionMe.application.recording.RecordingSessionCollector;
import com.openpositioning.PositionMe.application.recording.RecordingOperationSnapshot;
import com.openpositioning.PositionMe.domain.recording.SessionStatus;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.sensors.SensorFusionEvent;
import com.openpositioning.PositionMe.sensors.SensorFusionListener;
import java.util.concurrent.CompletableFuture;

/** Adapter keeping SensorFusion's broad facade out of the presentation state machine. */
public final class SensorFusionRecordingCommands implements RecordingCommands {
    private final SensorFusion sensorFusion;

    public SensorFusionRecordingCommands(SensorFusion sensorFusion) {
        if (sensorFusion == null) throw new IllegalArgumentException("sensorFusion is required");
        this.sensorFusion = sensorFusion;
    }

    @Override public CompletableFuture<SessionStatus> start() {
        sensorFusion.setStartGNSSLatitude(sensorFusion.getGNSSLatitude(false));
        return sensorFusion.startRecordingAsync();
    }

    @Override public CompletableFuture<SessionStatus> stopAndSave() {
        return sensorFusion.stopAndSave();
    }

    @Override public AutoCloseable observe(Listener listener) {
        RecordingCompletionSubscription completionSubscription =
                new RecordingCompletionSubscription(listener::onSessionCompleted);
        java.util.function.Consumer<RecordingSessionCollector> attachCompletion = collector -> {
            if (collector != null) completionSubscription.attach(collector, collector.completion());
        };
        SensorFusionListener adapter = event -> {
            if (completionSubscription.isClosed()) return;
            if (event.getKind() == SensorFusionEvent.Kind.RECORDING_STARTED) {
                listener.onRecordingStarted();
                attachCompletion.accept(sensorFusion.getRecordingSessionCollector());
            } else if (event.getKind() == SensorFusionEvent.Kind.RECORDING_STOPPED) {
                listener.onRecordingStopped();
            }
        };
        AutoCloseable subscription = sensorFusion.subscribe(adapter);
        RecordingOperationSnapshot initial = sensorFusion.getRecordingOperationSnapshot();
        listener.onOperationSnapshot(initial);
        if (initial.shouldObserveCollectorCompletion()) {
            attachCompletion.accept(initial.getCollector());
            if (initial.getCollectorStatus() != null && initial.getCollectorStatus().isFailure()) {
                listener.onSessionCompleted(initial.getCollectorStatus());
            }
        }
        return () -> {
            completionSubscription.close();
            subscription.close();
        };
    }
}
