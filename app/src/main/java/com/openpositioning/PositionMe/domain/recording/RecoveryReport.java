package com.openpositioning.PositionMe.domain.recording;

/** Honest account of bytes that could not be included in a recovered durable prefix. */
public final class RecoveryReport {
    private final long discardedBytes;
    private final String reason;
    private final long committedBytes;
    private final long committedRecords;

    public RecoveryReport(long discardedBytes, String reason, long committedBytes, long committedRecords) {
        this.discardedBytes = Math.max(0, discardedBytes);
        this.reason = reason == null ? "" : reason;
        this.committedBytes = Math.max(0, committedBytes);
        this.committedRecords = Math.max(0, committedRecords);
    }

    public long getDiscardedBytes() { return discardedBytes; }
    public String getReason() { return reason; }
    public long getCommittedBytes() { return committedBytes; }
    public long getCommittedRecords() { return committedRecords; }
    public boolean hasLoss() { return discardedBytes > 0; }
}
