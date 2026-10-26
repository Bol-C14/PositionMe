package com.openpositioning.PositionMe.presentation.recording;

import static org.junit.Assert.assertEquals;

import com.openpositioning.PositionMe.domain.recording.SessionState;
import com.openpositioning.PositionMe.domain.recording.SessionStatus;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;

public class RecordingCompletionSubscriptionTest {
    @Test public void replacedCollectorCompletionIsIgnored() {
        ArrayList<SessionStatus> received = new ArrayList<>();
        RecordingCompletionSubscription subscription = new RecordingCompletionSubscription(received::add);
        Object oldCollector = new Object();
        Object activeCollector = new Object();
        CompletableFuture<SessionStatus> oldCompletion = new CompletableFuture<>();
        CompletableFuture<SessionStatus> activeCompletion = new CompletableFuture<>();
        subscription.attach(oldCollector, oldCompletion);
        subscription.attach(activeCollector, activeCompletion);
        oldCompletion.complete(SessionStatus.failed(new IllegalStateException("stale")));
        activeCompletion.complete(SessionStatus.of(SessionState.STOPPED));
        assertEquals(1, received.size());
        assertEquals(SessionState.STOPPED, received.get(0).getState());
    }

    @Test public void closedSubscriptionDoesNotRetainCompletionCallback() {
        ArrayList<SessionStatus> received = new ArrayList<>();
        RecordingCompletionSubscription subscription = new RecordingCompletionSubscription(received::add);
        CompletableFuture<SessionStatus> completion = new CompletableFuture<>();
        subscription.attach(new Object(), completion);
        subscription.close();
        completion.complete(SessionStatus.failed(new IllegalStateException("after close")));
        assertEquals(0, received.size());
    }
}
