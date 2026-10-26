package com.openpositioning.PositionMe.presentation.recording;

import static org.junit.Assert.*;

import com.openpositioning.PositionMe.domain.recording.SessionState;
import com.openpositioning.PositionMe.domain.recording.SessionStatus;
import com.openpositioning.PositionMe.application.recording.RecordingOperationSnapshot;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;

public class RecordingControllerTest {
    @Test public void concurrentStartsIssueOnlyOneCommandAndStopWaitsForRecordingState() {
        FakeCommands commands = new FakeCommands();
        RecordingController controller = new RecordingController(commands, state -> { });
        controller.requestStart();
        controller.requestStart();
        assertEquals(1, commands.starts);
        assertEquals(RecordingPhase.STARTING, controller.getState().getPhase());
        controller.requestStop();
        assertEquals(0, commands.stops);
        commands.start.complete(SessionStatus.of(SessionState.RUNNING));
        assertEquals(RecordingPhase.RECORDING, controller.getState().getPhase());
        controller.requestStop();
        controller.requestStop();
        assertEquals(1, commands.stops);
    }

    @Test public void failedSaveLeavesErrorAndDoesNotNavigate() {
        FakeCommands commands = new FakeCommands();
        RecordingController controller = recordingController(commands);
        controller.requestStop();
        commands.stop.complete(SessionStatus.failed(new IllegalStateException("disk full")));
        assertEquals(RecordingPhase.ERROR, controller.getState().getPhase());
        assertEquals(RecordingUiState.Effect.NONE, controller.getState().getEffect());
        assertEquals("disk full", controller.getState().getDetail());
    }

    @Test public void savedExportWarningEmitsCorrectionEffectOnceUntilConsumed() {
        FakeCommands commands = new FakeCommands();
        RecordingController controller = recordingController(commands);
        controller.requestStop();
        commands.stop.complete(SessionStatus.savedWithWarning(new IllegalStateException("export failed")));
        RecordingUiState saved = controller.getState();
        assertEquals(RecordingPhase.WARNING, saved.getPhase());
        assertEquals(RecordingUiState.Effect.OPEN_CORRECTION, saved.getEffect());
        assertTrue(saved.getEffectId() > 0);
        controller.consumeEffect(saved.getEffectId());
        assertEquals(RecordingUiState.Effect.NONE, controller.getState().getEffect());
        controller.consumeEffect(saved.getEffectId());
        assertEquals(RecordingPhase.WARNING, controller.getState().getPhase());
    }

    @Test public void stateAndPendingEffectSurviveDetachedObserverAndCanBeConsumedAfterReattach() {
        FakeCommands commands = new FakeCommands();
        RecordingController controller = recordingController(commands);
        controller.requestStop();
        commands.stop.complete(SessionStatus.of(SessionState.STOPPED));
        long effect = controller.getState().getEffectId();
        assertEquals(RecordingPhase.SAVED, controller.getState().getPhase());
        // A recreated view reads this same retained state and consumes its pending navigation.
        assertEquals(effect, controller.getState().getEffectId());
        controller.consumeEffect(effect);
        assertEquals(0, controller.getState().getEffectId());
    }

    @Test public void deniedPermissionCanBeRetriedExplicitly() {
        FakeCommands commands = new FakeCommands();
        RecordingController controller = new RecordingController(commands, state -> { });
        controller.requestStart();
        commands.start.completeExceptionally(new SecurityException("Location permission was revoked"));
        assertEquals(RecordingPhase.WARNING, controller.getState().getPhase());
        assertTrue(controller.getState().isPermissionRequired());
        controller.requestStart();
        assertEquals(2, commands.starts);
    }

    @Test public void permissionGrantAfterDeniedRequestShowsReadyStateWithoutStarting() {
        FakeCommands commands = new FakeCommands();
        RecordingController controller = new RecordingController(commands, state -> { });
        controller.permissionDenied();
        controller.permissionGranted();
        assertEquals(RecordingPhase.IDLE, controller.getState().getPhase());
        assertTrue(controller.getState().isPermissionReady());
        assertEquals(0, commands.starts);
    }

    @Test public void attachSnapshotRestoresActiveAndPendingStartOrSaveStates() {
        FakeCommands commands = new FakeCommands();
        RecordingController active = new RecordingController(commands, state -> { });
        active.onOperationSnapshot(new RecordingOperationSnapshot(true, null, null, null,
                SessionStatus.of(SessionState.RUNNING)));
        assertEquals(RecordingPhase.RECORDING, active.getState().getPhase());

        CompletableFuture<SessionStatus> pendingStart = new CompletableFuture<>();
        RecordingController starting = new RecordingController(commands, state -> { });
        starting.onOperationSnapshot(new RecordingOperationSnapshot(false, pendingStart, null, null, null));
        assertEquals(RecordingPhase.STARTING, starting.getState().getPhase());
        pendingStart.complete(SessionStatus.of(SessionState.RUNNING));
        assertEquals(RecordingPhase.RECORDING, starting.getState().getPhase());

        CompletableFuture<SessionStatus> pendingSave = new CompletableFuture<>();
        RecordingController saving = new RecordingController(commands, state -> { });
        RecordingOperationSnapshot savingSnapshot =
                new RecordingOperationSnapshot(false, null, pendingSave, null, null);
        assertTrue(savingSnapshot.isSaving());
        pendingSave.complete(SessionStatus.of(SessionState.STOPPED));
        // The future may finish after the owner snapshot but before the view model handles it.
        saving.onOperationSnapshot(savingSnapshot);
        assertEquals(RecordingPhase.SAVED, saving.getState().getPhase());
        assertEquals(RecordingUiState.Effect.OPEN_CORRECTION, saving.getState().getEffect());
    }

    @Test public void historicSuccessfulCollectorSnapshotIsNotTreatedAsLiveWork() {
        RecordingOperationSnapshot snapshot = new RecordingOperationSnapshot(false, null, null, null,
                SessionStatus.of(SessionState.STOPPED));
        assertFalse(snapshot.shouldObserveCollectorCompletion());
        RecordingController controller = new RecordingController(new FakeCommands(), state -> { });
        controller.onOperationSnapshot(snapshot);
        assertEquals(RecordingPhase.IDLE, controller.getState().getPhase());
    }

    @Test public void externalStopThenCollectorFailureBecomesError() {
        FakeCommands commands = new FakeCommands();
        RecordingController controller = recordingController(commands);
        controller.onRecordingStopped();
        assertEquals(RecordingPhase.SAVING, controller.getState().getPhase());
        controller.onSessionCompleted(SessionStatus.failed(new IllegalStateException("writer failed")));
        assertEquals(RecordingPhase.ERROR, controller.getState().getPhase());
        assertEquals("writer failed", controller.getState().getDetail());
    }

    @Test public void collectorFailureDoesNotReplaceAnActiveStopCommandResult() {
        FakeCommands commands = new FakeCommands();
        RecordingController controller = recordingController(commands);
        controller.requestStop();
        controller.onSessionCompleted(SessionStatus.failed(new IllegalStateException("writer failed")));
        assertEquals(RecordingPhase.SAVING, controller.getState().getPhase());
        commands.stop.complete(SessionStatus.failed(new IllegalStateException("writer failed")));
        assertEquals(RecordingPhase.ERROR, controller.getState().getPhase());
    }

    @Test public void synchronousCommandExceptionsBecomeTerminalErrors() {
        FakeCommands commands = new FakeCommands();
        commands.failStartSynchronously = true;
        RecordingController controller = new RecordingController(commands, state -> { });
        controller.requestStart();
        assertEquals(RecordingPhase.ERROR, controller.getState().getPhase());
        commands.failStartSynchronously = false;
        controller.onOperationSnapshot(new RecordingOperationSnapshot(true, null, null, null,
                SessionStatus.of(SessionState.RUNNING)));
        commands.failStopSynchronously = true;
        controller.requestStop();
        assertEquals(RecordingPhase.ERROR, controller.getState().getPhase());
    }

    private static RecordingController recordingController(FakeCommands commands) {
        RecordingController controller = new RecordingController(commands, state -> { });
        controller.requestStart();
        commands.start.complete(SessionStatus.of(SessionState.RUNNING));
        return controller;
    }

    private static final class FakeCommands implements RecordingCommands {
        int starts;
        int stops;
        boolean failStartSynchronously;
        boolean failStopSynchronously;
        CompletableFuture<SessionStatus> start;
        CompletableFuture<SessionStatus> stop;
        @Override public CompletableFuture<SessionStatus> start() {
            starts++;
            if (failStartSynchronously) throw new IllegalStateException("start failed synchronously");
            start = new CompletableFuture<>();
            return start;
        }
        @Override public CompletableFuture<SessionStatus> stopAndSave() {
            stops++;
            if (failStopSynchronously) throw new IllegalStateException("stop failed synchronously");
            stop = new CompletableFuture<>();
            return stop;
        }
        @Override public AutoCloseable observe(Listener listener) { return () -> { }; }
    }
}
