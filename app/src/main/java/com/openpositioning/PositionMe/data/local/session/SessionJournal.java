package com.openpositioning.PositionMe.data.local.session;

import com.openpositioning.PositionMe.application.recording.CheckpointingRecordSink;
import com.openpositioning.PositionMe.domain.recording.DurableSessionState;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Append-only authoritative JSONL journal plus immutable generation-based protobuf checkpoints. */
public final class SessionJournal implements CheckpointingRecordSink {
    // A target cadence while writes continue. A quiet/stalled producer cannot trigger a timed sync.
    private static final long SYNC_TARGET_INTERVAL_NANOS = 5_000_000_000L;

    private final SessionRepository repository;
    private final UUID id;
    private final File directory;
    private final File recordsFile;
    private JSONObject manifest;
    private FileOutputStream recordsStream;
    private final SessionRepository.LockHandle lockHandle;
    private boolean closed;
    private long recordCount;
    private long lastSyncNanos;

    SessionJournal(SessionRepository repository, UUID id, File directory, JSONObject manifest,
                   SessionRepository.LockHandle lockHandle) throws IOException {
        this.repository = repository;
        this.id = id;
        this.directory = directory;
        this.recordsFile = new File(directory, SessionRepository.RECORDS_FILE);
        this.manifest = manifest;
        this.recordCount = manifest.optLong("recordCount", 0L);
        this.lastSyncNanos = System.nanoTime();
        if (lockHandle == null) throw new IllegalArgumentException("lockHandle must not be null");
        this.lockHandle = lockHandle;
        try { this.recordsStream = new FileOutputStream(recordsFile, true); }
        catch (IOException failure) {
            try { lockHandle.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    @Override
    public synchronized void write(JSONObject record) throws IOException {
        ensureOpen();
        if (record == null) throw new IllegalArgumentException("record must not be null");
        byte[] line = (record.toString() + "\n").getBytes(StandardCharsets.UTF_8);
        recordsStream.write(line);
        recordCount++;
        if (System.nanoTime() - lastSyncNanos >= SYNC_TARGET_INTERVAL_NANOS) syncJournal();
    }

    /** Forces a storage checkpoint at any time. The session id and protobuf bytes stay linked by directory. */
    @Override
    public synchronized void checkpoint(byte[] protobuf, long wallMillis, long elapsedMillis) throws IOException {
        ensureOpen();
        if (protobuf == null) throw new IllegalArgumentException("protobuf must not be null");
        syncJournal();
        long generation = manifest.optLong("checkpointGeneration", 0L) + 1L;
        String filename = SessionRepository.CHECKPOINT_PREFIX + String.format(java.util.Locale.ROOT, "%020d", generation) + ".pb";
        File target = new File(directory, filename);
        if (target.exists()) throw new IOException("Checkpoint generation already exists: " + target.getName());
        SessionRepository.writeAtomically(target, protobuf.clone());
        repository.hit("after_checkpoint_file_publish");
        put("checkpointGeneration", generation);
        put("checkpointFile", filename);
        put("checkpointSha256", SessionRepository.sha256(protobuf));
        put("checkpointWallMillis", wallMillis);
        put("checkpointElapsedMillis", elapsedMillis);
        put("committedBytes", recordsFile.length());
        put("recordCount", recordCount);
        persistManifest();
        SessionRepository.pruneSupersededCheckpoints(directory, generation, filename);
    }

    /** RecordSink flush is a durable JSONL checkpoint; protobuf remains at its last explicit generation. */
    @Override
    public synchronized void flush() throws IOException {
        if (!closed) syncJournal();
    }

    @Override
    public synchronized void complete() throws IOException {
        finish(DurableSessionState.SAVED, "");
    }

    @Override
    public synchronized void fail(Throwable failure) throws IOException {
        String message = failure == null ? "Recording failed." : failure.getMessage();
        finish(DurableSessionState.FAILED, message == null ? failure.getClass().getSimpleName() : message);
    }

    /** Marks an orderly session finalization only after its final durable JSONL checkpoint. */
    public synchronized void finish(DurableSessionState state, String failure) throws IOException {
        ensureOpen();
        if (state != DurableSessionState.SAVED && state != DurableSessionState.FAILED) {
            throw new IllegalArgumentException("An active journal can finish only as SAVED or FAILED");
        }
        syncJournal();
        put("state", state.name());
        put("failure", failure == null ? "" : failure);
        persistManifest();
        closeStream();
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        IOException failure = null;
        try { syncJournal(); } catch (IOException e) { failure = e; }
        try { closeStream(); } catch (IOException e) { if (failure == null) failure = e; }
        if (failure != null) throw failure;
    }

    @Override public String getSessionId() { return id.toString(); }
    public UUID getId() { return id; }
    public File getDirectory() { return directory; }

    private void syncJournal() throws IOException {
        if (recordsStream == null) return;
        recordsStream.flush();
        recordsStream.getFD().sync();
        repository.hit("after_records_fsync");
        put("committedBytes", recordsFile.length());
        put("recordCount", recordCount);
        persistManifest();
        lastSyncNanos = System.nanoTime();
    }

    private void persistManifest() throws IOException {
        repository.writeManifest(directory, manifest);
    }

    private void put(String key, Object value) throws IOException {
        try { manifest.put(key, value); }
        catch (JSONException failure) { throw new IOException("Could not update manifest field " + key, failure); }
    }

    private void closeStream() throws IOException {
        if (closed) return;
        closed = true;
        IOException failure = null;
        if (recordsStream != null) {
            FileOutputStream stream = recordsStream;
            recordsStream = null;
            try { stream.close(); } catch (IOException e) { failure = e; }
        }
        try { lockHandle.close(); } catch (IOException e) { if (failure == null) failure = e; }
        if (failure != null) throw failure;
    }

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("Session journal is closed: " + id);
    }
}
