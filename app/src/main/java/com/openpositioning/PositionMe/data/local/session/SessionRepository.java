package com.openpositioning.PositionMe.data.local.session;

import com.openpositioning.PositionMe.domain.recording.DurableSessionState;
import com.openpositioning.PositionMe.domain.recording.ExportStatus;
import com.openpositioning.PositionMe.domain.recording.RecoveryReport;
import com.openpositioning.PositionMe.domain.recording.SessionMetadata;
import com.openpositioning.PositionMe.domain.recording.SessionSummary;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** File-backed durable session catalog. JSONL is authoritative; protobuf files are derived checkpoints. */
public final class SessionRepository {
    public static final String MANIFEST_FILE = "manifest.json";
    public static final String RECORDS_FILE = "records.jsonl";
    public static final String CHECKPOINT_PREFIX = "checkpoint-";

    private final File root;
    private final SessionFailureInjector failureInjector;

    public SessionRepository(File root) throws IOException {
        this(root, boundary -> { });
    }

    SessionRepository(File root, SessionFailureInjector failureInjector) throws IOException {
        if (root == null) throw new IllegalArgumentException("root must not be null");
        if (failureInjector == null) throw new IllegalArgumentException("failureInjector must not be null");
        this.failureInjector = failureInjector;
        this.root = root.getCanonicalFile();
        if (!this.root.exists() && !this.root.mkdirs()) throw new IOException("Cannot create session root: " + this.root);
        if (!this.root.isDirectory()) throw new IOException("Session root is not a directory: " + this.root);
    }

    public synchronized SessionJournal create(SessionMetadata metadata) throws IOException {
        if (metadata == null) throw new IllegalArgumentException("metadata must not be null");
        return create(UUID.randomUUID(), metadata);
    }

    public synchronized SessionJournal create(UUID id, SessionMetadata metadata) throws IOException {
        if (id == null || metadata == null) throw new IllegalArgumentException("id and metadata must not be null");
        File dir = new File(root, id.toString());
        if (!dir.mkdir()) throw new IOException("Cannot create session directory: " + dir);
        LockHandle writerLock = null;
        File records = new File(dir, RECORDS_FILE);
        JSONObject manifest = new JSONObject();
        try {
            writerLock = tryLock(dir);
            if (writerLock == null) throw new IOException("Session directory is already locked: " + id);
            try (FileOutputStream stream = new FileOutputStream(records)) { stream.getFD().sync(); }
            manifest.put("manifestVersion", 1);
            manifest.put("sessionId", id.toString());
            manifest.put("schemaVersion", metadata.getSchemaVersion());
            manifest.put("wallStartEpochMillis", metadata.getWallStartEpochMillis());
            manifest.put("elapsedStartMillis", metadata.getElapsedStartMillis());
            manifest.put("journalSyncTargetIntervalMillis", metadata.getJournalSyncTargetIntervalMillis());
            JSONObject attributes = new JSONObject();
            for (Map.Entry<String, String> entry : metadata.getAttributes().entrySet()) attributes.put(entry.getKey(), entry.getValue());
            manifest.put("attributes", attributes);
            manifest.put("state", DurableSessionState.OPEN.name());
            manifest.put("committedBytes", 0L);
            manifest.put("recordCount", 0L);
            manifest.put("checkpointGeneration", 0L);
            manifest.put("checkpointFile", "");
            manifest.put("checkpointSha256", "");
            manifest.put("checkpointWallMillis", metadata.getWallStartEpochMillis());
            manifest.put("checkpointElapsedMillis", metadata.getElapsedStartMillis());
            manifest.put("recoveryDiscardedBytes", 0L);
            manifest.put("recoveryReason", "");
            manifest.put("exportStatus", ExportStatus.NOT_REQUESTED.name());
            manifest.put("exportFailure", "");
            manifest.put("failure", "");
            writeManifest(dir, manifest);
            syncDirectory(dir);
            SessionJournal journal = new SessionJournal(this, id, dir, manifest, writerLock);
            writerLock = null;
            return journal;
        } catch (JSONException | IOException failure) {
            if (writerLock != null) {
                try { writerLock.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            }
            deleteTree(dir);
            if (failure instanceof IOException) throw (IOException) failure;
            throw new IOException("Cannot create session manifest", failure);
        }
    }

    /** Converts OPEN sessions from prior process lifetimes to INTERRUPTED and truncates only uncommitted suffixes. */
    public synchronized List<SessionSummary> recoverOpenSessions() throws IOException {
        List<SessionSummary> recovered = new ArrayList<>();
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs == null) return recovered;
        for (File dir : dirs) {
            LockHandle lock;
            try { lock = tryLock(dir); }
            catch (OverlappingFileLockException activeInThisProcess) { continue; }
            if (lock == null) continue;
            try (LockHandle ignored = lock) {
            File manifestFile = new File(dir, MANIFEST_FILE);
            if (!manifestFile.isFile()) continue;
            JSONObject manifest;
            try { manifest = readManifest(dir); }
            catch (IOException | JSONException badManifest) { continue; }
            SessionSummary preflight;
            try { preflight = toSummary(manifest); }
            catch (IOException | JSONException | RuntimeException malformedHeader) { continue; }
            if (!dir.getName().equals(preflight.getId().toString())) continue;
            if (preflight.getState() != DurableSessionState.OPEN) continue;
            File records = new File(dir, RECORDS_FILE);
            long committed = manifest.optLong("committedBytes", 0L);
            long expectedCount = manifest.optLong("recordCount", 0L);
            String invalid = manifest.optInt("manifestVersion", -1) != 1
                    ? "Unsupported session manifest version; original session files were preserved."
                    : validateCommittedPrefix(records, committed, expectedCount);
            if (invalid == null) invalid = validateCheckpoint(dir, manifest);
            if (invalid != null) {
                putManifest(manifest, "state", DurableSessionState.CORRUPT.name());
                putManifest(manifest, "recoveryReason", invalid);
                putManifest(manifest, "recoveryDiscardedBytes", 0L);
            } else {
                long fileLength = records.isFile() ? records.length() : 0L;
                long discarded = Math.max(0L, fileLength - committed);
                if (!records.isFile()) {
                    try (FileOutputStream createdRecords = new FileOutputStream(records)) { createdRecords.getFD().sync(); }
                } else if (discarded > 0) {
                    try (FileOutputStream output = new FileOutputStream(records, true)) {
                        output.getChannel().truncate(committed);
                        output.getFD().sync();
                    }
                }
                putManifest(manifest, "state", DurableSessionState.INTERRUPTED.name());
                putManifest(manifest, "recoveryDiscardedBytes", discarded);
                putManifest(manifest, "recoveryReason", discarded > 0
                        ? "Discarded " + discarded + " uncommitted or torn trailing bytes after the last committed fsync checkpoint; continuity after that point is unknown."
                        : "Process ended while session was OPEN; no uninterrupted resume is implied.");
            }
            writeManifest(dir, manifest);
            recovered.add(toSummaryOrIOException(manifest));
            }
        }
        return Collections.unmodifiableList(recovered);
    }

    public synchronized List<SessionSummary> listSessions() throws IOException {
        List<SessionSummary> sessions = new ArrayList<>();
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs == null) return sessions;
        for (File dir : dirs) {
            try {
                SessionSummary summary = toSummary(readManifest(dir));
                if (dir.getName().equals(summary.getId().toString())) sessions.add(summary);
            }
            catch (IOException | JSONException | RuntimeException ignored) { /* malformed manifests remain untouched for manual inspection */ }
        }
        sessions.sort(Comparator.comparingLong(s -> s.getMetadata().getWallStartEpochMillis()));
        return Collections.unmodifiableList(sessions);
    }

    public synchronized SessionSummary finalizeRecovered(UUID id) throws IOException {
        File dir = sessionDirectory(id);
        JSONObject manifest = readManifestOrIOException(dir);
        SessionSummary preflight = toSummaryOrIOException(manifest);
        if (!dir.getName().equals(preflight.getId().toString())) {
            throw new IOException("Session manifest ID does not match its directory.");
        }
        String state = manifest.optString("state");
        boolean interrupted = DurableSessionState.INTERRUPTED.name().equals(state);
        boolean failed = DurableSessionState.FAILED.name().equals(state);
        if (!interrupted && !failed) {
            throw new IOException("Only interrupted or failed sessions can be finalized as recovered; state=" + state);
        }
        File records = new File(dir, RECORDS_FILE);
        long committed = manifest.optLong("committedBytes");
        String invalid = validateCommittedPrefix(records, committed, manifest.optLong("recordCount"));
        if (invalid == null) invalid = validateCheckpoint(dir, manifest);
        if (invalid != null) throw new IOException("Cannot finalize invalid durable prefix: " + invalid);
        if (failed) {
            long fileLength = records.isFile() ? records.length() : 0L;
            long discarded = Math.max(0L, fileLength - committed);
            if (discarded > 0) {
                try (FileOutputStream output = new FileOutputStream(records, true)) {
                    output.getChannel().truncate(committed);
                    output.getFD().sync();
                }
            }
            if (discarded > 0) {
                putManifest(manifest, "recoveryDiscardedBytes", discarded);
                putManifest(manifest, "recoveryReason", "Finalized the valid durable prefix after recording failure; discarded "
                        + discarded + " uncommitted trailing bytes.");
            } else if (manifest.optString("failure", "").length() > 0) {
                putManifest(manifest, "recoveryReason", "Finalized the valid durable prefix after recording failure: "
                        + manifest.optString("failure"));
            }
        }
        putManifest(manifest, "state", DurableSessionState.RECOVERED.name());
        writeManifest(dir, manifest);
        return toSummaryOrIOException(manifest);
    }

    /** Export state is recorded separately so retrying an export never mutates saved source data. */
    public synchronized void markExportResult(UUID id, ExportStatus status, String failure) throws IOException {
        if (status == null) throw new IllegalArgumentException("status must not be null");
        File dir = sessionDirectory(id);
        JSONObject manifest = readManifestOrIOException(dir);
        String sessionState = manifest.optString("state");
        if (!DurableSessionState.SAVED.name().equals(sessionState)
                && !DurableSessionState.RECOVERED.name().equals(sessionState)) {
            throw new IOException("Only saved or recovered sessions can be exported; state=" + sessionState);
        }
        putManifest(manifest, "exportStatus", status.name());
        putManifest(manifest, "exportFailure", failure == null ? "" : failure);
        writeManifest(dir, manifest);
    }

    public synchronized File getExportSource(UUID id) throws IOException {
        File source = new File(sessionDirectory(id), RECORDS_FILE);
        if (!source.isFile()) throw new IOException("Session records are missing: " + source);
        return source;
    }

    /** Returns only the checkpoint named and checksummed by the published manifest. */
    public synchronized byte[] readCheckpoint(UUID id) throws IOException {
        File dir = sessionDirectory(id);
        JSONObject manifest;
        try { manifest = readManifest(dir); }
        catch (JSONException failure) { throw new IOException("Invalid session manifest", failure); }
        if (manifest.optLong("checkpointGeneration", 0L) == 0L) return null;
        String invalid = validateCheckpoint(dir, manifest);
        if (invalid != null) throw new IOException(invalid);
        try { return readAll(new File(dir, manifest.getString("checkpointFile"))); }
        catch (JSONException failure) { throw new IOException("Missing checkpoint reference in manifest", failure); }
    }

    public File getRootDirectory() { return root; }
    File sessionDirectory(UUID id) throws IOException {
        if (id == null) throw new IllegalArgumentException("id must not be null");
        File dir = new File(root, id.toString()).getCanonicalFile();
        if (!root.equals(dir.getParentFile()) || !dir.isDirectory()) throw new IOException("Unknown session: " + id);
        return dir;
    }

    synchronized JSONObject readManifest(File dir) throws IOException, JSONException {
        return new JSONObject(new String(readAll(new File(dir, MANIFEST_FILE)), StandardCharsets.UTF_8));
    }

    private JSONObject readManifestOrIOException(File dir) throws IOException {
        try { return readManifest(dir); }
        catch (JSONException invalid) { throw new IOException("Invalid session manifest", invalid); }
    }

    private SessionSummary toSummaryOrIOException(JSONObject manifest) throws IOException {
        try { return toSummary(manifest); }
        catch (JSONException | RuntimeException invalid) { throw new IOException("Invalid session manifest fields", invalid); }
    }

    synchronized void writeManifest(File dir, JSONObject manifest) throws IOException {
        hit("before_manifest_publish");
        writeAtomically(new File(dir, MANIFEST_FILE), manifest.toString().getBytes(StandardCharsets.UTF_8));
        hit("after_manifest_publish");
    }

    void hit(String boundary) throws IOException { failureInjector.hit(boundary); }

    static final class LockHandle implements AutoCloseable {
        private final FileOutputStream stream;
        private final FileLock lock;
        LockHandle(FileOutputStream stream, FileLock lock) { this.stream = stream; this.lock = lock; }
        @Override public void close() throws IOException {
            IOException failure = null;
            try { if (lock.isValid()) lock.release(); } catch (IOException e) { failure = e; }
            try { stream.close(); } catch (IOException e) { if (failure == null) failure = e; }
            if (failure != null) throw failure;
        }
    }

    static LockHandle tryLock(File directory) throws IOException {
        FileOutputStream stream = new FileOutputStream(new File(directory, "session.lock"), true);
        try {
            FileLock lock = stream.getChannel().tryLock();
            if (lock == null) { stream.close(); return null; }
            return new LockHandle(stream, lock);
        } catch (OverlappingFileLockException busy) {
            stream.close();
            throw busy;
        } catch (IOException | RuntimeException failure) {
            stream.close();
            throw failure;
        }
    }

    synchronized SessionSummary toSummary(JSONObject manifest) throws IOException, JSONException {
        String sessionId = manifest.getString("sessionId");
        UUID parsedId = UUID.fromString(sessionId);
        int schemaVersion = manifest.getInt("schemaVersion");
        long wallStart = manifest.getLong("wallStartEpochMillis");
        long elapsedStart = manifest.getLong("elapsedStartMillis");
        if (schemaVersion <= 0 || wallStart < 0 || elapsedStart < 0) {
            throw new JSONException("Manifest contains invalid session metadata.");
        }
        Object attributesValue = manifest.opt("attributes");
        if (attributesValue != null && attributesValue != JSONObject.NULL && !(attributesValue instanceof JSONObject)) {
            throw new JSONException("Manifest attributes must be an object.");
        }
        DurableSessionState state = DurableSessionState.valueOf(manifest.getString("state"));
        long recordCount = manifest.getLong("recordCount");
        long committedBytes = manifest.getLong("committedBytes");
        if (recordCount < 0 || committedBytes < 0) throw new JSONException("Manifest has negative durable counters.");
        JSONObject attributesObject = manifest.optJSONObject("attributes");
        Map<String, String> attributes = new LinkedHashMap<>();
        if (attributesObject != null) {
            java.util.Iterator<String> keys = attributesObject.keys();
            while (keys.hasNext()) { String key = keys.next(); attributes.put(key, attributesObject.getString(key)); }
        }
        SessionMetadata metadata = new SessionMetadata(schemaVersion, wallStart, elapsedStart, attributes);
        RecoveryReport recovery = new RecoveryReport(manifest.optLong("recoveryDiscardedBytes"),
                manifest.optString("recoveryReason"), committedBytes, recordCount);
        return new SessionSummary(parsedId, metadata, state, recordCount,
                committedBytes, manifest.optLong("checkpointWallMillis"),
                manifest.optLong("checkpointElapsedMillis"), recovery,
                ExportStatus.valueOf(manifest.optString("exportStatus", ExportStatus.NOT_REQUESTED.name())),
                manifest.optString("exportFailure"), manifest.optString("failure"));
    }

    static void writeAtomically(File target, byte[] bytes) throws IOException {
        File parent = target.getParentFile();
        File temp = new File(parent, target.getName() + ".tmp-" + UUID.randomUUID());
        try {
            try (FileOutputStream out = new FileOutputStream(temp)) { out.write(bytes); out.getFD().sync(); }
            try { Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException unsupported) { Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING); }
            syncDirectory(parent);
        } finally { if (temp.exists()) temp.delete(); }
    }

    /** Best-effort cleanup after a newer checkpoint is manifest-committed. */
    static void pruneSupersededCheckpoints(File directory, long committedGeneration, String currentFilename) {
        File[] files = directory.listFiles();
        if (files == null) return;
        boolean deleted = false;
        for (File file : files) {
            String name = file.getName();
            if (!name.matches("checkpoint-[0-9]{20}\\.pb") || name.equals(currentFilename)) continue;
            try {
                long generation = Long.parseLong(name.substring("checkpoint-".length(), name.length() - ".pb".length()));
                if (generation > 0L && generation < committedGeneration && file.isFile() && file.delete()) deleted = true;
            } catch (NumberFormatException ignored) { /* Keep an unrecognized file for inspection. */ }
        }
        if (deleted) syncDirectory(directory);
    }

    static void syncDirectory(File directory) {
        try (FileChannel channel = FileChannel.open(directory.toPath())) { channel.force(true); }
        catch (IOException | UnsupportedOperationException ignored) { /* directory fsync is not supported on every filesystem */ }
    }

    static byte[] readAll(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = in.read(buffer)) >= 0) out.write(buffer, 0, count);
            return out.toByteArray();
        }
    }

    static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static void putManifest(JSONObject manifest, String key, Object value) throws IOException {
        try { manifest.put(key, value); }
        catch (JSONException failure) { throw new IOException("Could not update manifest field " + key, failure); }
    }

    private static String validateCheckpoint(File directory, JSONObject manifest) throws IOException {
        long generation = manifest.optLong("checkpointGeneration", 0L);
        if (generation < 0L) return "Manifest contains a negative checkpoint generation.";
        if (generation == 0L) {
            if (!manifest.optString("checkpointFile", "").isEmpty()
                    || !manifest.optString("checkpointSha256", "").isEmpty()) {
                return "Manifest has checkpoint data without a committed checkpoint generation.";
            }
            return null;
        }
        String filename = manifest.optString("checkpointFile", "");
        if (filename.isEmpty() || filename.contains("/") || filename.contains("\\")) return "Manifest has an invalid checkpoint filename.";
        File checkpoint = new File(directory, filename);
        if (!checkpoint.isFile()) return "Referenced protobuf checkpoint is missing.";
        String digest = sha256(readAll(checkpoint));
        return digest.equals(manifest.optString("checkpointSha256", ""))
                ? null : "Referenced protobuf checkpoint checksum does not match.";
    }

    private static String validateCommittedPrefix(File records, long committed, long expectedCount) throws IOException {
        if (committed < 0 || expectedCount < 0) return "Manifest contains negative committed length or record count.";
        if (committed == 0 && expectedCount == 0) return null;
        if (!records.isFile()) return "Committed JSONL file is missing.";
        if (records.length() < committed) return "JSONL file is shorter than the committed durable byte offset.";
        int count = 0;
        long consumed = 0;
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        try {
            try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(records))) {
                while (consumed < committed) {
                    int value = input.read();
                    if (value < 0) return "JSONL file ended before the committed durable byte offset.";
                    consumed++;
                    if (value == '\n') {
                        if (line.size() == 0) return "Committed prefix contains an empty JSONL record.";
                        new JSONObject(new String(line.toByteArray(), StandardCharsets.UTF_8));
                        count++;
                        line.reset();
                    } else {
                        line.write(value);
                    }
                }
            }
            if (line.size() != 0) return "Committed durable prefix ends with an incomplete JSONL record.";
        } catch (JSONException invalid) { return "Committed JSONL prefix contains malformed JSON."; }
        return count == expectedCount ? null : "Manifest record count does not match committed JSONL prefix.";
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }
}
