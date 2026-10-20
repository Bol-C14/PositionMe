package com.openpositioning.PositionMe.application.recording;

import android.content.Context;
import android.os.SystemClock;

import com.openpositioning.PositionMe.data.local.DataFileManager;
import com.openpositioning.PositionMe.data.local.DownloadsExportingRecordSink;

import java.io.IOException;

/** Android composition root for an app-private JSON recording session. */
public final class RecordingSessionFactory {
    private RecordingSessionFactory() { }

    public static RecordingSessionCollector create(Context context, SnapshotSource source)
            throws IOException {
        DataFileManager privateSink = new DataFileManager(context);
        return new RecordingSessionCollector(source,
                new DownloadsExportingRecordSink(context, privateSink),
                new ExecutorSessionScheduler(), SystemClock::elapsedRealtime);
    }
}
