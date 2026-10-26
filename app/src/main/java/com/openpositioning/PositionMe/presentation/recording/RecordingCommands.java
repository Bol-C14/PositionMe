package com.openpositioning.PositionMe.presentation.recording;

import com.openpositioning.PositionMe.domain.recording.SessionStatus;
import com.openpositioning.PositionMe.application.recording.RecordingOperationSnapshot;
import java.util.concurrent.CompletableFuture;

/** Narrow command boundary around the recording owner. */
public interface RecordingCommands {
    interface Listener {
        void onRecordingStarted();
        void onRecordingStopped();
        void onSessionCompleted(SessionStatus status);
        default void onOperationSnapshot(RecordingOperationSnapshot snapshot) { }
    }

    CompletableFuture<SessionStatus> start();
    CompletableFuture<SessionStatus> stopAndSave();
    AutoCloseable observe(Listener listener);
}
