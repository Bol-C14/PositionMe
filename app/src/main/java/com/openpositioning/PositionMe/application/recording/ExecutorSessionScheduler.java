package com.openpositioning.PositionMe.application.recording;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Production scheduler backed by one named worker thread. */
final class ExecutorSessionScheduler implements SessionScheduler {
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "recording-session-worker");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public Cancellable scheduleAtFixedRate(Runnable task, long initialDelayMs, long periodMs) {
        ScheduledFuture<?> future = executor.scheduleAtFixedRate(task, initialDelayMs,
                periodMs, TimeUnit.MILLISECONDS);
        return () -> future.cancel(false);
    }

    @Override
    public void execute(Runnable task) {
        executor.execute(task);
    }

    @Override
    public void close() {
        executor.shutdown();
    }
}
