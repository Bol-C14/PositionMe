package com.openpositioning.PositionMe.presentation.recording;

import com.openpositioning.PositionMe.domain.recording.SessionStatus;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Suppresses completion callbacks from replaced collectors and a cleared view model. */
final class RecordingCompletionSubscription implements AutoCloseable {
    private final AtomicReference<Consumer<SessionStatus>> callback;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicLong generation = new AtomicLong();
    private final AtomicReference<Object> source = new AtomicReference<>();

    RecordingCompletionSubscription(Consumer<SessionStatus> callback) {
        this.callback = new AtomicReference<>(callback);
    }

    void attach(Object identity, CompletableFuture<SessionStatus> completion) {
        if (identity == null || completion == null || closed.get()) return;
        Object previous = source.getAndSet(identity);
        if (previous == identity) return;
        long token = generation.incrementAndGet();
        completion.whenComplete((status, failure) -> {
            if (closed.get() || generation.get() != token || source.get() != identity) return;
            Consumer<SessionStatus> target = callback.get();
            if (target != null && !closed.get()) {
                target.accept(failure == null ? status : SessionStatus.failed(unwrap(failure)));
            }
        });
    }

    boolean isClosed() { return closed.get(); }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) {
            generation.incrementAndGet();
            source.set(null);
            callback.set(null);
        }
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current != null && current.getCause() != null) current = current.getCause();
        return current;
    }
}
