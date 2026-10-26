package com.openpositioning.PositionMe.presentation.files;

import com.openpositioning.PositionMe.domain.recording.SessionSummary;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.Observer;

import org.junit.Test;
import org.junit.Rule;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FilesViewModelTest {
    @Rule public final InstantTaskExecutorRule instantExecutor = new InstantTaskExecutorRule();

    @Test public void duplicateRecoveryIsIgnoredAndFailureRemainsVisibleAndRetryable() {
        FakeCommands commands = new FakeCommands();
        CompletableFuture<?> firstRecovery = new CompletableFuture<>();
        commands.recoveryResults.add(firstRecovery);
        FilesViewModel model = new FilesViewModel(commands);
        AtomicReference<FilesViewModel.UiState> observed = new AtomicReference<>();
        Observer<FilesViewModel.UiState> observer = observed::set;
        model.getState().observeForever(observer);
        UUID id = UUID.randomUUID();
        model.recover(id);
        model.recover(id);

        assertEquals(1, commands.recoveryCalls);
        model.export(id);
        assertEquals(0, commands.exportCalls);
        assertTrue(model.getCurrentState().recovering.contains(id));
        assertEquals(FilesViewModel.OperationStatus.PENDING,
                model.getCurrentState().outcomes.get(id).status);
        assertEquals(FilesViewModel.OperationStatus.PENDING,
                observed.get().outcomes.get(id).status);

        firstRecovery.completeExceptionally(new IllegalStateException("disk is read-only"));

        FilesViewModel.OperationOutcome failure = model.getCurrentState().outcomes.get(id);
        assertFalse(model.getCurrentState().recovering.contains(id));
        assertEquals(FilesViewModel.OperationStatus.FAILED, failure.status);
        assertTrue(failure.message.contains("disk is read-only"));
        assertEquals(FilesViewModel.OperationStatus.FAILED,
                observed.get().outcomes.get(id).status);

        commands.recoveryResults.add(new CompletableFuture<>());
        model.recover(id);
        assertEquals(2, commands.recoveryCalls);
        assertEquals(FilesViewModel.OperationStatus.PENDING,
                model.getCurrentState().outcomes.get(id).status);
        model.getState().removeObserver(observer);
    }

    @Test public void successfulExportOutcomeIsRetainedInUiState() {
        FakeCommands commands = new FakeCommands();
        FilesViewModel model = new FilesViewModel(commands);
        UUID id = UUID.randomUUID();
        model.export(id);

        assertEquals(FilesViewModel.OperationStatus.SUCCEEDED,
                model.getCurrentState().outcomes.get(id).status);
        assertFalse(model.getCurrentState().exporting.contains(id));
    }

    @Test public void synchronousCommandFailureAlsoReleasesPendingState() {
        FakeCommands commands = new FakeCommands();
        commands.throwOnExport = true;
        FilesViewModel model = new FilesViewModel(commands);
        UUID id = UUID.randomUUID();
        model.export(id);

        assertFalse(model.getCurrentState().exporting.contains(id));
        assertEquals(FilesViewModel.OperationStatus.FAILED,
                model.getCurrentState().outcomes.get(id).status);
    }

    @Test public void staleLocalCatalogCompletionCannotReplaceNewerResult() {
        FakeCommands commands = new FakeCommands();
        CompletableFuture<java.util.List<SessionSummary>> stale = new CompletableFuture<>();
        CompletableFuture<java.util.List<SessionSummary>> current = new CompletableFuture<>();
        commands.listResults.add(stale);
        commands.listResults.add(current);
        FilesViewModel model = new FilesViewModel(commands);

        model.refreshLocalSessions();
        model.refreshLocalSessions();
        current.complete(Collections.emptyList());
        stale.completeExceptionally(new IllegalStateException("old request failed"));

        assertFalse(model.getCurrentState().localLoading);
        assertFalse(model.getCurrentState().localFailed);
    }

    private static final class FakeCommands implements LocalSessionCommands {
        final Queue<CompletableFuture<java.util.List<SessionSummary>>> listResults = new ArrayDeque<>();
        final Queue<CompletableFuture<?>> recoveryResults = new ArrayDeque<>();
        int recoveryCalls;
        int exportCalls;
        boolean throwOnExport;

        @Override public CompletableFuture<java.util.List<SessionSummary>> listSessions() {
            return listResults.isEmpty()
                    ? CompletableFuture.completedFuture(Collections.emptyList()) : listResults.remove();
        }

        @Override public CompletableFuture<?> recover(UUID sessionId) {
            recoveryCalls++;
            return recoveryResults.isEmpty() ? new CompletableFuture<>() : recoveryResults.remove();
        }

        @Override public CompletableFuture<?> export(UUID sessionId) {
            exportCalls++;
            if (throwOnExport) throw new IllegalStateException("export service unavailable");
            return CompletableFuture.completedFuture(null);
        }
    }
}
