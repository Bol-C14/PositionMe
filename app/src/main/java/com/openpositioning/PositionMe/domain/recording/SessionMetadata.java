package com.openpositioning.PositionMe.domain.recording;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable session identity and clock provenance. Wall time is UTC epoch millis;
 * elapsed time is Android elapsedRealtime millis and is meaningful only on this boot. */
public final class SessionMetadata {
    public static final int CURRENT_SCHEMA_VERSION = 1;
    /** Intended fsync cadence while records continue arriving; a quiet or stalled source can exceed it. */
    public static final long JOURNAL_SYNC_TARGET_INTERVAL_MILLIS = 5000L;

    private final int schemaVersion;
    private final long wallStartEpochMillis;
    private final long elapsedStartMillis;
    private final Map<String, String> attributes;

    public SessionMetadata(long wallStartEpochMillis, long elapsedStartMillis,
                           Map<String, String> attributes) {
        this(CURRENT_SCHEMA_VERSION, wallStartEpochMillis, elapsedStartMillis, attributes);
    }

    public SessionMetadata(int schemaVersion, long wallStartEpochMillis, long elapsedStartMillis,
                           Map<String, String> attributes) {
        if (schemaVersion <= 0) throw new IllegalArgumentException("schemaVersion must be positive");
        if (wallStartEpochMillis < 0 || elapsedStartMillis < 0) {
            throw new IllegalArgumentException("session start times must be non-negative");
        }
        this.schemaVersion = schemaVersion;
        this.wallStartEpochMillis = wallStartEpochMillis;
        this.elapsedStartMillis = elapsedStartMillis;
        Map<String, String> copy = new LinkedHashMap<>();
        if (attributes != null) {
            for (Map.Entry<String, String> entry : attributes.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    throw new IllegalArgumentException("metadata keys and values must not be null");
                }
                copy.put(entry.getKey(), entry.getValue());
            }
        }
        this.attributes = Collections.unmodifiableMap(copy);
    }

    public int getSchemaVersion() { return schemaVersion; }
    public long getWallStartEpochMillis() { return wallStartEpochMillis; }
    public long getElapsedStartMillis() { return elapsedStartMillis; }
    public Map<String, String> getAttributes() { return attributes; }
    public long getJournalSyncTargetIntervalMillis() { return JOURNAL_SYNC_TARGET_INTERVAL_MILLIS; }
}
