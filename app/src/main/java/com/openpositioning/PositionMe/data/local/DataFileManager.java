package com.openpositioning.PositionMe.data.local;

import android.content.Context;
import android.os.Build;

import com.openpositioning.PositionMe.application.recording.RecordSink;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

/** Writes newline-delimited JSON records to an app-specific, API 28-safe file. */
public final class DataFileManager implements RecordSink {
    private final File outputFile;
    private FileOutputStream outputStream;
    private boolean closed;

    public DataFileManager(Context context) throws IOException {
        if (context == null) throw new IllegalArgumentException("context must not be null");
        Context appContext = context.getApplicationContext();
        File recordingDirectory = new File(appContext.getFilesDir(), "recordings");
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
        String model = Build.MODEL == null ? "device" : Build.MODEL.replaceAll("[^A-Za-z0-9._-]", "_");
        outputFile = new File(recordingDirectory,
                "collection_data_" + model + "_" + timestamp + "_" + UUID.randomUUID() + ".jsonl");
    }

    @Override
    public synchronized void write(JSONObject record) throws IOException {
        ensureOpen();
        if (record == null) throw new IllegalArgumentException("record must not be null");
        openStreamIfNeeded();
        outputStream.write((record.toString() + "\n").getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public synchronized void flush() throws IOException {
        if (!closed && outputStream != null) outputStream.flush();
    }

    /** Closing is safe to repeat and always attempts to flush the last record. */
    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        if (outputStream == null) {
            closed = true;
            return;
        }
        IOException failure = null;
        try {
            outputStream.flush();
        } catch (IOException e) {
            failure = e;
        }
        try {
            outputStream.close();
        } catch (IOException e) {
            if (failure == null) failure = e;
        } finally {
            closed = true;
        }
        if (failure != null) throw failure;
    }

    public File getOutputFile() {
        return outputFile;
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("Recording file is closed: " + outputFile.getName());
    }

    private void openStreamIfNeeded() throws IOException {
        if (outputStream != null) return;
        File directory = outputFile.getParentFile();
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Unable to create recording directory: " + directory);
        }
        outputStream = new FileOutputStream(outputFile);
    }
}
