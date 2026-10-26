package com.openpositioning.PositionMe.application.recording;

import android.content.Context;
import android.net.Uri;

import com.openpositioning.PositionMe.data.local.session.SessionRepository;
import com.openpositioning.PositionMe.data.local.session.SessionBundleExporter;
import com.openpositioning.PositionMe.domain.recording.SessionSummary;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Asynchronous catalog, explicit recovery, and export boundary for recording sessions. */
public final class RecordingSessionFacade {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "recording-session-catalog");
        thread.setDaemon(true);
        return thread;
    });
    private final Context context;
    private volatile SessionRepository repository;
    private volatile CompletableFuture<?> recoveryScan;

    public RecordingSessionFacade(Context context) {
        if (context == null) throw new IllegalArgumentException("context must not be null");
        this.context = context.getApplicationContext();
    }

    /** Lists sessions after one cold-start scan marks open process lifetimes interrupted. */
    public CompletableFuture<List<SessionSummary>> listSessionsAsync() {
        return ensureRecoveryScan().thenApplyAsync(ignored -> {
            try { return repository().listSessions(); }
            catch (IOException failure) { throw new java.util.concurrent.CompletionException(failure); }
        }, IO);
    }

    /** Explicitly finalizes one validated interrupted session; this never resumes sensor capture. */
    public CompletableFuture<SessionSummary> recoverSessionAsync(UUID id) {
        if (id == null) throw new IllegalArgumentException("id must not be null");
        return ensureRecoveryScan().thenApplyAsync(ignored -> {
            try { return repository().finalizeRecovered(id); }
            catch (IOException failure) { throw new java.util.concurrent.CompletionException(failure); }
        }, IO);
    }

    /** Reads the latest manifest-committed protobuf checkpoint without changing session state. */
    public CompletableFuture<byte[]> readCheckpointAsync(UUID id) {
        if (id == null) throw new IllegalArgumentException("id must not be null");
        return ensureRecoveryScan().thenApplyAsync(ignored -> {
            try { return repository().readCheckpoint(id); }
            catch (IOException failure) { throw new java.util.concurrent.CompletionException(failure); }
        }, IO);
    }

    /** Publishes a validated session bundle to Downloads and records the independent export result. */
    public CompletableFuture<Uri> exportAsync(UUID id) {
        if (id == null) throw new IllegalArgumentException("id must not be null");
        return ensureRecoveryScan().thenApplyAsync(ignored -> {
            try { return SessionBundleExporter.export(context, repository(), id); }
            catch (IOException failure) { throw new java.util.concurrent.CompletionException(failure); }
        }, IO);
    }

    private synchronized CompletableFuture<Void> ensureRecoveryScan() {
        if (recoveryScan == null) {
            recoveryScan = CompletableFuture.runAsync(() -> {
                try { repository().recoverOpenSessions(); }
                catch (IOException failure) { throw new java.util.concurrent.CompletionException(failure); }
            }, IO);
        }
        @SuppressWarnings("unchecked") CompletableFuture<Void> scan = (CompletableFuture<Void>) recoveryScan;
        return scan;
    }

    private SessionRepository repository() throws IOException {
        SessionRepository current = repository;
        if (current != null) return current;
        synchronized (this) {
            if (repository == null) repository = RecordingSessionFactory.openRepository(context);
            return repository;
        }
    }

}
