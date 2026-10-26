package com.openpositioning.PositionMe.application.recording;

import com.openpositioning.PositionMe.domain.recording.SessionState;
import com.openpositioning.PositionMe.domain.recording.SessionStatus;

import org.json.JSONObject;

import java.util.concurrent.CompletableFuture;

/** Owns passive sensor sampling, ordered annotation writes, and deterministic sink finalization. */
public final class RecordingSessionCollector {
    public static final long SNAPSHOT_INTERVAL_MS = 500L;
    public static final long WIFI_REQUEST_INTERVAL_MS = 8_000L;
    public static final long CHECKPOINT_INTERVAL_MS = 5_000L;

    private final SnapshotSource source;
    private final RecordSink sink;
    private final SessionScheduler scheduler;
    private final SessionClock clock;
    private final CompletableFuture<SessionStatus> stopped = new CompletableFuture<>();
    private volatile SessionStatus status = SessionStatus.of(SessionState.NEW);
    private SessionScheduler.Cancellable sampling;
    private boolean wifiRequested;
    private long lastWifiRequestMs;
    private volatile boolean sinkClosed;
    private long lastCheckpointMs;
    private boolean checkpointWritten;

    public RecordingSessionCollector(SnapshotSource source, RecordSink sink,
                                     SessionScheduler scheduler, SessionClock clock) {
        if (source == null || sink == null || scheduler == null || clock == null) {
            throw new IllegalArgumentException("Recording session dependencies must be provided");
        }
        this.source = source;
        this.sink = sink;
        this.scheduler = scheduler;
        this.clock = clock;
    }

    /** Starts sampling immediately, then every 500 ms, on the owned worker. */
    public synchronized SessionStatus start() {
        if (status.getState() == SessionState.RUNNING) return status;
        if (status.getState() != SessionState.NEW) {
            throw new IllegalStateException("Cannot start recording session in state " + status.getState());
        }
        status = SessionStatus.of(SessionState.RUNNING);
        try {
            sampling = scheduler.scheduleAtFixedRate(this::collectSnapshot, 0L,
                    SNAPSHOT_INTERVAL_MS);
        } catch (RuntimeException failure) {
            failAndClose(failure);
        }
        return status;
    }

    /** Queues an annotation or other explicit record behind prior snapshots. */
    public synchronized void append(JSONObject record) {
        if (status.getState() != SessionState.RUNNING) {
            throw new IllegalStateException("Recording session is not running");
        }
        if (record == null) throw new IllegalArgumentException("record must not be null");
        final JSONObject queuedRecord;
        try {
            queuedRecord = tagRecord(new JSONObject(record.toString()));
        } catch (Exception failure) {
            failAndClose(failure);
            return;
        }
        try {
            scheduler.execute(() -> {
                if (status.getState() == SessionState.FAILED || sinkClosed) return;
                try {
                    sink.write(queuedRecord);
                } catch (Exception failure) {
                    failAndClose(failure);
                }
            });
        } catch (RuntimeException failure) {
            failAndClose(failure);
        }
    }

    /** Stops sampling, then flushes and closes after all previously queued writes. */
    public synchronized CompletableFuture<SessionStatus> stop() {
        SessionState state = status.getState();
        if (state == SessionState.STOPPED || state == SessionState.FAILED
                || state == SessionState.STOPPING) return stopped;
        if (state == SessionState.NEW) {
            status = SessionStatus.of(SessionState.STOPPING);
            dispatchFinish(false, null);
            return stopped;
        }
        status = SessionStatus.of(SessionState.STOPPING);
        if (sampling != null) sampling.cancel();
        dispatchFinish(false, null);
        return stopped;
    }

    /** Marks an interrupted owner/service lifecycle as a durable failure and finalizes best effort. */
    public synchronized CompletableFuture<SessionStatus> fail(Throwable failure) {
        if (failure == null) failure = new IllegalStateException("Recording owner stopped unexpectedly");
        failAndClose(failure);
        return stopped;
    }

    public SessionStatus getStatus() {
        return status;
    }

    /** Completes after the sink has been finalized and its terminal status is durable. */
    public CompletableFuture<SessionStatus> completion() {
        return stopped;
    }

    public String getSessionId() {
        return sink instanceof CheckpointingRecordSink
                ? ((CheckpointingRecordSink) sink).getSessionId() : null;
    }

    private void collectSnapshot() {
        if (status.getState() != SessionState.RUNNING) return;
        try {
            long now = clock.elapsedRealtimeMillis();
            if (!wifiRequested || now - lastWifiRequestMs >= WIFI_REQUEST_INTERVAL_MS) {
                source.requestWifiUpdate();
                wifiRequested = true;
                lastWifiRequestMs = now;
            }
            JSONObject snapshot = source.captureSnapshot();
            if (snapshot == null) throw new IllegalStateException("Snapshot source returned no data");
            sink.write(tagRecord(snapshot));
            if (sink instanceof CheckpointingRecordSink) {
                CheckpointingRecordSink checkpointing = (CheckpointingRecordSink) sink;
                if (!checkpointWritten || now - lastCheckpointMs >= CHECKPOINT_INTERVAL_MS) {
                    byte[] checkpoint = source.captureCheckpoint();
                    if (checkpoint == null) throw new IllegalStateException("Checkpoint source returned no protobuf data");
                    checkpointing.checkpoint(checkpoint, clock.wallTimeMillis(), now);
                    checkpointWritten = true;
                    lastCheckpointMs = now;
                }
            }
        } catch (Exception failure) {
            failAndClose(failure);
        }
    }

    private JSONObject tagRecord(JSONObject record) throws Exception {
        if (sink instanceof CheckpointingRecordSink) {
            String sessionId = ((CheckpointingRecordSink) sink).getSessionId();
            if (sessionId != null) record.put("sessionId", sessionId);
        }
        return record;
    }

    private synchronized void failAndClose(Throwable failure) {
        if (status.getState() == SessionState.STOPPED || status.getState() == SessionState.FAILED) return;
        status = SessionStatus.failed(failure);
        if (sampling != null) sampling.cancel();
        dispatchFinish(true, failure);
    }

    private void dispatchFinish(boolean failed, Throwable failure) {
        try {
            scheduler.execute(() -> finish(failed, failure));
        } catch (RuntimeException rejected) {
            Throwable cause = failure == null ? rejected : failure;
            status = SessionStatus.failed(cause);
            Thread finalizer = new Thread(() -> finish(true, cause), "recording-session-finalizer");
            finalizer.setDaemon(true);
            finalizer.start();
        }
    }

    private void finish(boolean failed, Throwable priorFailure) {
        Throwable failure = priorFailure;
        synchronized (this) {
            if (sinkClosed) return;
            if (status.getState() == SessionState.FAILED && failure == null) {
                failure = new IllegalStateException(status.getFailureMessage());
            }
            failed = failed || status.getState() == SessionState.FAILED;
            sinkClosed = true;
        }
        if (!failed && failure == null && sink instanceof CheckpointingRecordSink) {
            try {
                byte[] checkpoint = source.captureCheckpoint();
                if (checkpoint == null) throw new IllegalStateException("Checkpoint source returned no protobuf data");
                ((CheckpointingRecordSink) sink).checkpoint(checkpoint,
                        clock.wallTimeMillis(), clock.elapsedRealtimeMillis());
            } catch (Exception checkpointFailure) {
                failure = checkpointFailure;
            }
        }
        try {
            sink.flush();
        } catch (Exception flushFailure) {
            if (failure == null) failure = flushFailure;
        }
        try {
            scheduler.close();
        } catch (RuntimeException schedulerFailure) {
            if (failure == null) failure = schedulerFailure;
        }
        if (sink instanceof CheckpointingRecordSink) {
            CheckpointingRecordSink checkpointing = (CheckpointingRecordSink) sink;
            try {
                if (failure == null && !failed) checkpointing.complete();
                else checkpointing.fail(failure);
            } catch (Exception lifecycleFailure) {
                if (failure == null) failure = lifecycleFailure;
                try { checkpointing.fail(failure); } catch (Exception ignored) { }
            }
        }
        try {
            sink.close();
        } catch (Exception closeFailure) {
            if (failure == null) failure = closeFailure;
        }
        SessionStatus terminalStatus;
        synchronized (this) {
            if (failure != null || failed) {
                terminalStatus = SessionStatus.failed(failure);
            } else {
                terminalStatus = SessionStatus.of(SessionState.STOPPED);
            }
            status = terminalStatus;
        }
        stopped.complete(terminalStatus);
    }
}
