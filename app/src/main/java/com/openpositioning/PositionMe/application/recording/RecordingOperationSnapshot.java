package com.openpositioning.PositionMe.application.recording;

import com.openpositioning.PositionMe.domain.recording.SessionStatus;
import java.util.concurrent.CompletableFuture;

/** Point-in-time view of SensorFusion's session owner for presentation reattachment. */
public final class RecordingOperationSnapshot {
    private final boolean recording;
    private final CompletableFuture<SessionStatus> starting;
    private final CompletableFuture<SessionStatus> saving;
    private final RecordingSessionCollector collector;
    private final SessionStatus collectorStatus;
    private final boolean startingAtSnapshot;
    private final boolean savingAtSnapshot;

    public RecordingOperationSnapshot(boolean recording,
                                      CompletableFuture<SessionStatus> starting,
                                      CompletableFuture<SessionStatus> saving,
                                      RecordingSessionCollector collector,
                                      SessionStatus collectorStatus) {
        this.recording = recording;
        this.starting = starting;
        this.saving = saving;
        this.collector = collector;
        this.collectorStatus = collectorStatus;
        this.startingAtSnapshot = starting != null && !starting.isDone();
        this.savingAtSnapshot = saving != null && !saving.isDone();
    }

    public boolean isRecording() { return recording; }
    public CompletableFuture<SessionStatus> getStartingOperation() { return starting; }
    public CompletableFuture<SessionStatus> getSavingOperation() { return saving; }
    public RecordingSessionCollector getCollector() { return collector; }
    public SessionStatus getCollectorStatus() { return collectorStatus; }
    /** Captured at snapshot creation so a completion racing delivery is still observed. */
    public boolean isStarting() { return startingAtSnapshot; }
    public boolean isSaving() { return savingAtSnapshot; }
    public boolean shouldObserveCollectorCompletion() {
        return recording || startingAtSnapshot || savingAtSnapshot
                || collectorStatus != null && collectorStatus.isFailure();
    }
}
