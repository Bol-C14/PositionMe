package com.openpositioning.PositionMe.presentation.recording;

import com.openpositioning.PositionMe.domain.recording.SessionStatus;
import com.openpositioning.PositionMe.application.recording.RecordingOperationSnapshot;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Pure, synchronized command state machine. UI lifecycle and Android types stay outside. */
public final class RecordingController {
    public interface StateListener { void onStateChanged(RecordingUiState state); }

    private final RecordingCommands commands;
    private final StateListener listener;
    private RecordingUiState state = RecordingUiState.initial();
    private long nextEffectId;
    private long commandGeneration;
    private boolean stopCommandPending;
    private CompletableFuture<SessionStatus> observedStart;
    private CompletableFuture<SessionStatus> observedSave;

    public RecordingController(RecordingCommands commands, StateListener listener) {
        if (commands == null || listener == null) throw new IllegalArgumentException("dependencies required");
        this.commands = commands;
        this.listener = listener;
    }

    public synchronized RecordingUiState getState() { return state; }

    public synchronized void requestStart() {
        if (state.isBusy() || state.getPhase() == RecordingPhase.RECORDING) return;
        long generation = ++commandGeneration;
        stopCommandPending = false;
        publish(new RecordingUiState(RecordingPhase.STARTING, null, false, 0, RecordingUiState.Effect.NONE));
        try { invoke(commands.start(), generation, false); }
        catch (RuntimeException failure) { complete(generation, false, null, failure); }
    }

    public synchronized void requestStop() {
        if (state.isBusy() || state.getPhase() != RecordingPhase.RECORDING) return;
        long generation = ++commandGeneration;
        stopCommandPending = true;
        publish(new RecordingUiState(RecordingPhase.SAVING, null, false, 0, RecordingUiState.Effect.NONE));
        try { invoke(commands.stopAndSave(), generation, true); }
        catch (RuntimeException failure) { complete(generation, true, null, failure); }
    }

    /** A permission denial is actionable and leaves a direct retry path. */
    public synchronized void permissionDenied() {
        if (!state.isBusy() && state.getPhase() != RecordingPhase.RECORDING) {
            ++commandGeneration;
            publish(new RecordingUiState(RecordingPhase.WARNING, null, true, 0, RecordingUiState.Effect.NONE));
        }
    }

    public synchronized void permissionGranted() {
        if (state.getPhase() == RecordingPhase.WARNING && state.isPermissionRequired()) {
            publish(new RecordingUiState(RecordingPhase.IDLE, null, false, true, 0,
                    RecordingUiState.Effect.NONE));
        }
    }

    public synchronized void onRecordingStarted() {
        // The start command future is authoritative; a lifecycle event can race setup/finalization.
    }

    public synchronized void onRecordingStopped() {
        // A stop event can arrive before the stop/save future finishes. Keep SAVING visible.
        if (state.getPhase() == RecordingPhase.RECORDING) {
            publish(new RecordingUiState(RecordingPhase.SAVING, null, false, 0, RecordingUiState.Effect.NONE));
        }
    }

    public synchronized void onSessionCompleted(SessionStatus status) {
        if (status == null || stopCommandPending) return;
        RecordingPhase phase = state.getPhase();
        if (phase != RecordingPhase.IDLE && phase != RecordingPhase.RECORDING
                && phase != RecordingPhase.SAVING) return;
        String detail = status.isFailure() ? status.getFailureMessage()
                : "Recording ended before it was saved from the recording controls.";
        ++commandGeneration;
        publish(new RecordingUiState(RecordingPhase.ERROR, detail, false, 0,
                RecordingUiState.Effect.NONE));
    }

    /** Reattach a newly created application-scoped view model to owner work already in flight. */
    public synchronized void onOperationSnapshot(RecordingOperationSnapshot snapshot) {
        if (snapshot == null) return;
        CompletableFuture<SessionStatus> saving = snapshot.getSavingOperation();
        if (saving != null && snapshot.isSaving()) {
            if (observedSave == saving) return;
            observedSave = saving;
            observedStart = null;
            long generation = ++commandGeneration;
            stopCommandPending = true;
            publish(new RecordingUiState(RecordingPhase.SAVING, null, false, 0,
                    RecordingUiState.Effect.NONE));
            observeFuture(saving, generation, true);
            return;
        }
        CompletableFuture<SessionStatus> starting = snapshot.getStartingOperation();
        if (starting != null && snapshot.isStarting()) {
            if (observedStart == starting) return;
            observedStart = starting;
            observedSave = null;
            long generation = ++commandGeneration;
            stopCommandPending = false;
            publish(new RecordingUiState(RecordingPhase.STARTING, null, false, 0,
                    RecordingUiState.Effect.NONE));
            observeFuture(starting, generation, false);
            return;
        }
        if (snapshot.isRecording()) {
            observedStart = null;
            observedSave = null;
            stopCommandPending = false;
            if (state.getPhase() != RecordingPhase.STARTING && state.getPhase() != RecordingPhase.SAVING) {
                publish(new RecordingUiState(RecordingPhase.RECORDING, null, false, 0,
                        RecordingUiState.Effect.NONE));
            }
        } else if (snapshot.getCollectorStatus() != null && snapshot.getCollectorStatus().isFailure()) {
            onSessionCompleted(snapshot.getCollectorStatus());
        }
    }

    public synchronized void consumeEffect(long id) {
        if (id != 0 && id == state.getEffectId()) {
            publish(new RecordingUiState(state.getPhase(), state.getDetail(), state.isPermissionRequired(),
                    state.isPermissionReady(), 0, RecordingUiState.Effect.NONE));
        }
    }

    private void invoke(CompletableFuture<SessionStatus> future, long generation, boolean stopping) {
        if (future == null) {
            complete(generation, stopping, null, new IllegalStateException("Recording command returned no result"));
            return;
        }
        observeFuture(future, generation, stopping);
    }

    private void observeFuture(CompletableFuture<SessionStatus> future, long generation, boolean stopping) {
        future.whenComplete((status, failure) -> complete(generation, stopping, status, failure));
    }

    private void complete(long generation, boolean stopping, SessionStatus status, Throwable failure) {
        synchronized (this) {
            if (generation != commandGeneration) return;
            if (stopping) stopCommandPending = false;
            if (failure != null || status == null || status.isFailure()) {
                Throwable cause = unwrap(failure);
                String detail = status == null ? message(cause) : status.getFailureMessage();
                boolean permission = cause instanceof SecurityException || (detail != null
                        && detail.toLowerCase(java.util.Locale.ROOT).contains("permission"));
                publish(new RecordingUiState(permission ? RecordingPhase.WARNING : RecordingPhase.ERROR,
                        detail, permission, 0, RecordingUiState.Effect.NONE));
            } else if (!stopping) {
                // The future is authoritative for command success even when lifecycle publication races it.
                publish(new RecordingUiState(RecordingPhase.RECORDING, null, false, 0,
                        RecordingUiState.Effect.NONE));
            } else {
                RecordingPhase terminal = status.hasWarning() ? RecordingPhase.WARNING : RecordingPhase.SAVED;
                publish(new RecordingUiState(terminal, status.hasWarning() ? status.getFailureMessage() : null,
                        false, ++nextEffectId, RecordingUiState.Effect.OPEN_CORRECTION));
            }
        }
    }

    private void publish(RecordingUiState value) {
        state = value;
        listener.onStateChanged(value);
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) current = current.getCause();
        return current;
    }

    private static String message(Throwable failure) {
        String text = failure == null ? null : failure.getMessage();
        return text == null || text.trim().isEmpty() ? "Please try again." : text;
    }
}
