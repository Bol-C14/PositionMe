package com.openpositioning.PositionMe.domain.recording;

import java.util.UUID;

/** Immutable read model for session listings and recovery/export UI. */
public final class SessionSummary {
    private final UUID id;
    private final SessionMetadata metadata;
    private final DurableSessionState state;
    private final long recordCount;
    private final long committedBytes;
    private final long checkpointWallMillis;
    private final long checkpointElapsedMillis;
    private final RecoveryReport recoveryReport;
    private final ExportStatus exportStatus;
    private final String exportFailure;
    private final String failure;

    public SessionSummary(UUID id, SessionMetadata metadata, DurableSessionState state,
                          long recordCount, long committedBytes, long checkpointWallMillis,
                          long checkpointElapsedMillis, RecoveryReport recoveryReport,
                          ExportStatus exportStatus, String exportFailure, String failure) {
        this.id = id;
        this.metadata = metadata;
        this.state = state;
        this.recordCount = recordCount;
        this.committedBytes = committedBytes;
        this.checkpointWallMillis = checkpointWallMillis;
        this.checkpointElapsedMillis = checkpointElapsedMillis;
        this.recoveryReport = recoveryReport;
        this.exportStatus = exportStatus;
        this.exportFailure = exportFailure == null ? "" : exportFailure;
        this.failure = failure == null ? "" : failure;
    }

    public UUID getId() { return id; }
    public SessionMetadata getMetadata() { return metadata; }
    public DurableSessionState getState() { return state; }
    public long getRecordCount() { return recordCount; }
    public long getCommittedBytes() { return committedBytes; }
    public long getCheckpointWallMillis() { return checkpointWallMillis; }
    public long getCheckpointElapsedMillis() { return checkpointElapsedMillis; }
    public RecoveryReport getRecoveryReport() { return recoveryReport; }
    public ExportStatus getExportStatus() { return exportStatus; }
    public String getExportFailure() { return exportFailure; }
    public String getFailure() { return failure; }
}
