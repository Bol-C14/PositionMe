package com.openpositioning.PositionMe.application.recording;

import com.openpositioning.PositionMe.domain.recording.SessionState;
import com.openpositioning.PositionMe.domain.recording.SessionStatus;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RecordingSessionCollectorTest {
    @Test
    public void samplesEvery500msRequestsWifiEvery8SecondsAndClosesAfterQueuedAnnotation() throws Exception {
        FakeSource source = new FakeSource();
        FakeSink sink = new FakeSink();
        FakeScheduler scheduler = new FakeScheduler();
        FakeClock clock = new FakeClock();
        RecordingSessionCollector collector = new RecordingSessionCollector(source, sink, scheduler, clock);

        assertEquals(SessionState.RUNNING, collector.start().getState());
        assertEquals(500L, scheduler.periodMs);
        scheduler.tick();
        assertEquals(1, source.wifiRequests);
        assertEquals(1, sink.records.size());

        clock.nowMs = 7_999L;
        scheduler.tick();
        assertEquals(1, source.wifiRequests);
        clock.nowMs = 8_000L;
        scheduler.tick();
        assertEquals(2, source.wifiRequests);

        collector.append(new JSONObject().put("isCalibration", true));
        scheduler.drain();
        assertEquals(4, sink.records.size());
        assertTrue(sink.records.get(3).contains("isCalibration"));

        CompletableFuture<SessionStatus> stopped = collector.stop();
        scheduler.drain();
        assertEquals(SessionState.STOPPED, stopped.join().getState());
        assertEquals(List.of("flush", "close"), sink.finalization);
        assertTrue(scheduler.closed);
        assertFalse(scheduler.periodicActive);
        assertEquals(4, sink.records.size());
    }

    @Test
    public void sourceFailureIsVisibleAndSinkStillFinalizes() {
        FakeSource source = new FakeSource();
        source.failure = new IllegalStateException("sensor snapshot unavailable");
        FakeSink sink = new FakeSink();
        FakeScheduler scheduler = new FakeScheduler();
        RecordingSessionCollector collector = new RecordingSessionCollector(source, sink, scheduler, new FakeClock());

        collector.start();
        scheduler.tick();
        scheduler.drain();

        assertEquals(SessionState.FAILED, collector.getStatus().getState());
        assertEquals(SessionState.FAILED, collector.completion().join().getState());
        assertTrue(collector.getStatus().getFailureMessage().contains("sensor snapshot unavailable"));
        assertEquals(SessionState.FAILED, collector.stop().join().getState());
        assertEquals(List.of("flush", "close"), sink.finalization);
        assertTrue(scheduler.closed);
    }

    @Test
    public void stopDrainsAnnotationAlreadyQueuedOnWorker() throws Exception {
        FakeSource source = new FakeSource();
        FakeSink sink = new FakeSink();
        DeferredScheduler scheduler = new DeferredScheduler();
        RecordingSessionCollector collector = new RecordingSessionCollector(source, sink, scheduler, new FakeClock());

        collector.start();
        JSONObject annotation = new JSONObject().put("isCalibration", true);
        collector.append(annotation);
        CompletableFuture<SessionStatus> stopped = collector.stop();

        assertFalse(stopped.isDone());
        assertEquals(0, sink.records.size());
        scheduler.drain();

        assertEquals(SessionState.STOPPED, stopped.join().getState());
        assertEquals(1, sink.records.size());
        assertTrue(sink.records.get(0).contains("isCalibration"));
        assertEquals(List.of("flush", "close"), sink.finalization);
    }

    @Test
    public void checkpointsPeriodicallyAndAgainBeforeSuccessfulFinalization() {
        CheckpointSource source = new CheckpointSource();
        DurableFakeSink sink = new DurableFakeSink();
        FakeScheduler scheduler = new FakeScheduler();
        FakeClock clock = new FakeClock();
        RecordingSessionCollector collector = new RecordingSessionCollector(source, sink, scheduler, clock);

        collector.start();
        scheduler.tick();
        assertEquals(1, sink.checkpoints.size());
        clock.nowMs = RecordingSessionCollector.CHECKPOINT_INTERVAL_MS - 1;
        scheduler.tick();
        assertEquals(1, sink.checkpoints.size());
        clock.nowMs++;
        scheduler.tick();
        assertEquals(2, sink.checkpoints.size());

        CompletableFuture<SessionStatus> stopped = collector.stop();
        scheduler.drain();
        assertEquals(SessionState.STOPPED, stopped.join().getState());
        assertEquals(3, sink.checkpoints.size());
        assertEquals(List.of("checkpoint", "checkpoint", "checkpoint", "flush", "complete", "close"),
                sink.lifecycle);
        assertEquals(RecordingSessionCollector.CHECKPOINT_INTERVAL_MS,
                sink.checkpointElapsedTimes.get(1).longValue());
    }

    @Test
    public void checkpointFailureMarksSessionFailedAndNeverCompletesSink() {
        CheckpointSource source = new CheckpointSource();
        DurableFakeSink sink = new DurableFakeSink();
        sink.checkpointFailure = new IllegalStateException("checkpoint write failed");
        FakeScheduler scheduler = new FakeScheduler();
        RecordingSessionCollector collector = new RecordingSessionCollector(source, sink, scheduler, new FakeClock());

        collector.start();
        scheduler.tick();
        scheduler.drain();

        assertEquals(SessionState.FAILED, collector.getStatus().getState());
        assertTrue(collector.getStatus().getFailureMessage().contains("checkpoint write failed"));
        assertEquals(List.of("checkpoint", "flush", "fail", "close"), sink.lifecycle);
        assertFalse(sink.completed);
    }

    private static final class FakeClock implements SessionClock {
        long nowMs;
        @Override public long elapsedRealtimeMillis() { return nowMs; }
    }

    private static class FakeSource implements SnapshotSource {
        int wifiRequests;
        Exception failure;
        @Override public JSONObject captureSnapshot() throws Exception {
            if (failure != null) throw failure;
            return new JSONObject().put("sample", true);
        }
        @Override public void requestWifiUpdate() { wifiRequests++; }
        @Override public byte[] captureCheckpoint() { return null; }
    }

    private static final class CheckpointSource extends FakeSource {
        int generation;
        @Override public byte[] captureCheckpoint() { return new byte[]{(byte) ++generation}; }
    }

    private static final class FakeSink implements RecordSink {
        final List<String> records = new ArrayList<>();
        final List<String> finalization = new ArrayList<>();
        @Override public void write(JSONObject record) { records.add(record.toString()); }
        @Override public void flush() { finalization.add("flush"); }
        @Override public void close() { finalization.add("close"); }
    }

    private static final class DurableFakeSink implements CheckpointingRecordSink {
        final List<String> lifecycle = new ArrayList<>();
        final List<byte[]> checkpoints = new ArrayList<>();
        final List<Long> checkpointElapsedTimes = new ArrayList<>();
        Exception checkpointFailure;
        boolean completed;

        @Override public void write(JSONObject record) { }
        @Override public void checkpoint(byte[] protobuf, long wallMillis, long elapsedMillis) throws Exception {
            lifecycle.add("checkpoint");
            if (checkpointFailure != null) throw checkpointFailure;
            checkpoints.add(protobuf.clone());
            checkpointElapsedTimes.add(elapsedMillis);
        }
        @Override public String getSessionId() { return "session-id"; }
        @Override public void complete() { completed = true; lifecycle.add("complete"); }
        @Override public void fail(Throwable failure) { lifecycle.add("fail"); }
        @Override public void flush() { lifecycle.add("flush"); }
        @Override public void close() { lifecycle.add("close"); }
    }

    private static final class FakeScheduler implements SessionScheduler {
        Runnable periodicTask;
        long periodMs;
        boolean periodicActive;
        boolean closed;
        final List<Runnable> queued = new ArrayList<>();
        @Override public Cancellable scheduleAtFixedRate(Runnable task, long initialDelayMs, long periodMs) {
            this.periodicTask = task;
            this.periodMs = periodMs;
            periodicActive = true;
            return () -> periodicActive = false;
        }
        @Override public void execute(Runnable task) { queued.add(task); }
        void tick() { if (periodicActive) periodicTask.run(); }
        void drain() {
            while (!queued.isEmpty()) queued.remove(0).run();
        }
        @Override public void close() { closed = true; }
    }

    private static final class DeferredScheduler implements SessionScheduler {
        final List<Runnable> queued = new ArrayList<>();
        boolean closed;
        @Override public Cancellable scheduleAtFixedRate(Runnable task, long initialDelayMs, long periodMs) {
            return () -> { };
        }
        @Override public void execute(Runnable task) { queued.add(task); }
        void drain() {
            while (!queued.isEmpty()) queued.remove(0).run();
        }
        @Override public void close() { closed = true; }
    }
}
