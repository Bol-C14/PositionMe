package com.openpositioning.PositionMe.application.recording;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;

import com.openpositioning.PositionMe.BuildConfig;
import com.openpositioning.PositionMe.data.local.session.SessionJournal;
import com.openpositioning.PositionMe.data.local.session.SessionRepository;
import com.openpositioning.PositionMe.domain.recording.SessionMetadata;

import java.io.IOException;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Android composition root for a durable JSONL journal and protobuf checkpoint session. */
public final class RecordingSessionFactory {
    private RecordingSessionFactory() { }

    public static SessionRepository openRepository(Context context) throws IOException {
        if (context == null) throw new IllegalArgumentException("context must not be null");
        return new SessionRepository(new File(context.getApplicationContext().getFilesDir(),
                "recording_sessions"));
    }

    public static RecordingSessionCollector create(Context context, SnapshotSource source,
                                                    UUID sessionId) throws IOException {
        return create(context, source, sessionId, System.currentTimeMillis(),
                SystemClock.elapsedRealtime());
    }

    public static RecordingSessionCollector create(Context context, SnapshotSource source,
                                                    UUID sessionId, long wallStart,
                                                    long elapsedStart) throws IOException {
        if (context == null || source == null || sessionId == null) {
            throw new IllegalArgumentException("context, source, and sessionId must be provided");
        }
        Map<String, String> provenance = new LinkedHashMap<>();
        provenance.put("appVersion", BuildConfig.VERSION_NAME);
        provenance.put("algorithm", "PositionMe-PDR");
        provenance.put("trajectorySchemaVersion", "2.0");
        provenance.put("androidVersion", Build.VERSION.RELEASE == null ? "unknown" : Build.VERSION.RELEASE);
        provenance.put("deviceModel", Build.MODEL == null ? "unknown" : Build.MODEL);
        SessionRepository repository = openRepository(context);
        SessionJournal journal = repository.create(sessionId,
                new SessionMetadata(wallStart, elapsedStart, provenance));
        return new RecordingSessionCollector(source, journal,
                new ExecutorSessionScheduler(), new SessionClock() {
                    @Override public long elapsedRealtimeMillis() { return SystemClock.elapsedRealtime(); }
                    @Override public long wallTimeMillis() { return System.currentTimeMillis(); }
                });
    }
}
