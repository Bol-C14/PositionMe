package com.openpositioning.PositionMe.presentation.files;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.openpositioning.PositionMe.data.remote.RemoteTrajectorySummary;
import com.openpositioning.PositionMe.domain.recording.SessionSummary;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns asynchronous Files screen state across view recreation. */
public final class FilesViewModel extends ViewModel {
    public enum OperationKind { RECOVERY, EXPORT }
    public enum OperationStatus { PENDING, SUCCEEDED, FAILED }

    public static final class OperationOutcome {
        public final OperationKind kind;
        public final OperationStatus status;
        public final String message;

        private OperationOutcome(OperationKind kind, OperationStatus status, String message) {
            this.kind = kind;
            this.status = status;
            this.message = message;
        }
    }

    public static final class UiState {
        public final List<SessionSummary> sessions;
        public final boolean localLoading;
        public final boolean localFailed;
        public final Set<UUID> recovering;
        public final Set<UUID> exporting;
        public final Map<UUID, OperationOutcome> outcomes;
        public final FilesScreenState remote;
        public final List<RemoteTrajectorySummary> remoteEntries;

        private UiState(List<SessionSummary> sessions, boolean localLoading, boolean localFailed,
                        Set<UUID> recovering, Set<UUID> exporting, FilesScreenState remote,
                        Map<UUID, OperationOutcome> outcomes,
                        List<RemoteTrajectorySummary> remoteEntries) {
            this.sessions = Collections.unmodifiableList(new ArrayList<>(sessions));
            this.localLoading = localLoading;
            this.localFailed = localFailed;
            this.recovering = Collections.unmodifiableSet(new HashSet<>(recovering));
            this.exporting = Collections.unmodifiableSet(new HashSet<>(exporting));
            this.outcomes = Collections.unmodifiableMap(new HashMap<>(outcomes));
            this.remote = remote;
            this.remoteEntries = Collections.unmodifiableList(new ArrayList<>(remoteEntries));
        }
    }

    private final LocalSessionCommands commands;
    private final MutableLiveData<UiState> state = new MutableLiveData<>();
    private UiState snapshot;
    private final Set<UUID> recovering = new HashSet<>();
    private final Set<UUID> exporting = new HashSet<>();
    private final Map<UUID, OperationOutcome> outcomes = new HashMap<>();
    private List<SessionSummary> sessions = Collections.emptyList();
    private List<RemoteTrajectorySummary> remoteEntries = Collections.emptyList();
    private boolean localLoading = true;
    private boolean localFailed;
    private long localLoadGeneration;
    private FilesScreenState remote = FilesScreenState.initial();

    public FilesViewModel(LocalSessionCommands commands) {
        this.commands = commands;
        publish();
    }

    public LiveData<UiState> getState() { return state; }

    /** Synchronous immutable snapshot for deterministic transition tests and command consumers. */
    public synchronized UiState getCurrentState() { return snapshot; }

    public synchronized long beginRemoteLoad() {
        remote = remote.beginRemoteLoad();
        remoteEntries = Collections.emptyList();
        publish();
        return remote.getRequestGeneration();
    }

    public synchronized void remoteLoadSucceeded(long generation,
                                                  List<RemoteTrajectorySummary> entries) {
        FilesScreenState next = remote.finishRemoteLoad(generation, entries != null && !entries.isEmpty());
        if (next == remote) return;
        remote = next;
        remoteEntries = entries == null ? Collections.emptyList() : new ArrayList<>(entries);
        publish();
    }

    public synchronized void remoteLoadFailed(long generation) {
        FilesScreenState next = remote.failRemoteLoad(generation);
        if (next == remote) return;
        remote = next;
        remoteEntries = Collections.emptyList();
        publish();
    }

    public synchronized void cancelRemoteLoad(long generation) {
        remoteLoadFailed(generation);
    }

    public void refreshLocalSessions() {
        final long generation;
        synchronized (this) {
            generation = ++localLoadGeneration;
            localLoading = true;
            localFailed = false;
            publish();
        }
        final java.util.concurrent.CompletableFuture<List<SessionSummary>> future;
        try {
            future = commands.listSessions();
        } catch (RuntimeException failure) {
            completeLocalLoad(generation, null, failure);
            return;
        }
        if (future == null) {
            completeLocalLoad(generation, null, new IllegalStateException("No catalog result"));
            return;
        }
        AtomicBoolean completed = new AtomicBoolean();
        try {
            future.whenComplete((result, failure) -> {
                if (completed.compareAndSet(false, true)) {
                    completeLocalLoad(generation, result, failure);
                }
            });
        } catch (RuntimeException failure) {
            if (completed.compareAndSet(false, true)) completeLocalLoad(generation, null, failure);
        }
    }

    private synchronized void completeLocalLoad(long generation, List<SessionSummary> result,
                                                Throwable failure) {
        if (generation != localLoadGeneration) return;
        localLoading = false;
        localFailed = failure != null || result == null;
        if (!localFailed) sessions = new ArrayList<>(result);
        publish();
    }

    public synchronized boolean isRecovering(UUID id) { return recovering.contains(id); }
    public synchronized boolean isExporting(UUID id) { return exporting.contains(id); }

    public void recover(UUID id) {
        startOperation(id, OperationKind.RECOVERY);
    }

    public void export(UUID id) {
        startOperation(id, OperationKind.EXPORT);
    }

    private void startOperation(UUID id, OperationKind kind) {
        synchronized (this) {
            Set<UUID> pending = kind == OperationKind.RECOVERY ? recovering : exporting;
            if (recovering.contains(id) || exporting.contains(id)) return;
            if (!pending.add(id)) return;
            outcomes.put(id, new OperationOutcome(kind, OperationStatus.PENDING, null));
            publish();
        }
        final java.util.concurrent.CompletableFuture<?> future;
        try {
            future = kind == OperationKind.RECOVERY ? commands.recover(id) : commands.export(id);
        } catch (RuntimeException failure) {
            completeOperation(id, kind, failure);
            return;
        }
        if (future == null) {
            completeOperation(id, kind, new IllegalStateException("No operation result"));
            return;
        }
        AtomicBoolean completed = new AtomicBoolean();
        try {
            future.whenComplete((ignored, failure) -> {
                if (completed.compareAndSet(false, true)) completeOperation(id, kind, failure);
            });
        } catch (RuntimeException failure) {
            if (completed.compareAndSet(false, true)) completeOperation(id, kind, failure);
        }
    }

    private void completeOperation(UUID id, OperationKind kind, Throwable failure) {
        synchronized (this) {
            (kind == OperationKind.RECOVERY ? recovering : exporting).remove(id);
            outcomes.put(id, new OperationOutcome(kind,
                    failure == null ? OperationStatus.SUCCEEDED : OperationStatus.FAILED,
                    failure == null ? null : errorMessage(failure)));
            publish();
        }
        refreshLocalSessions();
    }

    private static String errorMessage(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        String message = cause.getMessage();
        return message == null || message.trim().isEmpty()
                ? "The operation failed. Retry it or check the session files." : message;
    }

    private synchronized void publish() {
        snapshot = new UiState(sessions, localLoading, localFailed, recovering, exporting,
                remote, outcomes, remoteEntries);
        state.postValue(snapshot);
    }
}
