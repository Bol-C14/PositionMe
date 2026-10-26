package com.openpositioning.PositionMe.presentation.files;

import java.util.Objects;

/** Immutable state used by the Files screen. Pure Java so transitions can be tested locally. */
public final class FilesScreenState {
    public enum RemoteStatus { NOT_LOADED, LOADING, LOADED, EMPTY, FAILED }

    private final RemoteStatus remoteStatus;
    private final long requestGeneration;

    public FilesScreenState(RemoteStatus remoteStatus, long requestGeneration) {
        this.remoteStatus = Objects.requireNonNull(remoteStatus);
        this.requestGeneration = requestGeneration;
    }

    public static FilesScreenState initial() {
        return new FilesScreenState(RemoteStatus.NOT_LOADED, 0);
    }

    public RemoteStatus getRemoteStatus() { return remoteStatus; }
    public long getRequestGeneration() { return requestGeneration; }

    public FilesScreenState beginRemoteLoad() {
        return new FilesScreenState(RemoteStatus.LOADING, requestGeneration + 1);
    }

    public FilesScreenState finishRemoteLoad(long generation, boolean hasRows) {
        if (generation != requestGeneration || remoteStatus != RemoteStatus.LOADING) return this;
        return new FilesScreenState(hasRows ? RemoteStatus.LOADED : RemoteStatus.EMPTY, requestGeneration);
    }

    public FilesScreenState failRemoteLoad(long generation) {
        if (generation != requestGeneration || remoteStatus != RemoteStatus.LOADING) return this;
        return new FilesScreenState(RemoteStatus.FAILED, requestGeneration);
    }
}
