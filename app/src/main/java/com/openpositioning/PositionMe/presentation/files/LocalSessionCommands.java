package com.openpositioning.PositionMe.presentation.files;

import com.openpositioning.PositionMe.domain.recording.SessionSummary;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Narrow asynchronous boundary for Files local catalog and session operations. */
public interface LocalSessionCommands {
    CompletableFuture<List<SessionSummary>> listSessions();
    CompletableFuture<?> recover(UUID sessionId);
    CompletableFuture<?> export(UUID sessionId);
}
