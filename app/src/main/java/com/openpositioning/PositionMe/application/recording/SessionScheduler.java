package com.openpositioning.PositionMe.application.recording;

/** Serial worker and periodic scheduler owned by one recording session. execute() enqueues
 * work asynchronously and returns without running the task on the caller. */
public interface SessionScheduler extends AutoCloseable {
    interface Cancellable {
        void cancel();
    }

    Cancellable scheduleAtFixedRate(Runnable task, long initialDelayMs, long periodMs);
    void execute(Runnable task);
    @Override
    void close();
}
