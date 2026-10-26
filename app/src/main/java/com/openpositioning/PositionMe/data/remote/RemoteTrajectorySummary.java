package com.openpositioning.PositionMe.data.remote;

/** Validated metadata returned by the remote trajectory catalog endpoint. */
public final class RemoteTrajectorySummary {
    private final String id;
    private final String ownerId;
    private final String dateSubmitted;

    public RemoteTrajectorySummary(String id, String ownerId, String dateSubmitted) {
        if (id == null || id.trim().isEmpty() || ownerId == null || ownerId.trim().isEmpty()
                || dateSubmitted == null || dateSubmitted.trim().isEmpty()) {
            throw new IllegalArgumentException("Remote trajectory metadata must be complete");
        }
        this.id = id;
        this.ownerId = ownerId;
        this.dateSubmitted = dateSubmitted;
    }

    public String getId() { return id; }
    public String getOwnerId() { return ownerId; }
    public String getDateSubmitted() { return dateSubmitted; }
}
