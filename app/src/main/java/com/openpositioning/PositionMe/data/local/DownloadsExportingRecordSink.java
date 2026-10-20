package com.openpositioning.PositionMe.data.local;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.annotation.RequiresApi;

import com.openpositioning.PositionMe.application.recording.RecordSink;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Publishes a finalized private JSONL recording to the user's Downloads location. */
public final class DownloadsExportingRecordSink implements RecordSink {
    private final Context context;
    private final DataFileManager privateSink;

    public DownloadsExportingRecordSink(Context context, DataFileManager privateSink) {
        if (context == null || privateSink == null) {
            throw new IllegalArgumentException("context and private sink must be provided");
        }
        this.context = context.getApplicationContext();
        this.privateSink = privateSink;
    }

    @Override
    public void write(JSONObject record) throws Exception {
        privateSink.write(record);
    }

    @Override
    public void flush() throws Exception {
        privateSink.flush();
    }

    @Override
    public void close() throws Exception {
        privateSink.close();
        File source = privateSink.getOutputFile();
        if (!source.isFile()) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                exportThroughMediaStore(source);
            } else {
                exportToAppDownloads(source);
            }
        } catch (Exception failure) {
            throw new IOException("Downloads export failed; private recording remains at "
                    + source.getAbsolutePath() + ": " + failure.getMessage(), failure);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    private void exportThroughMediaStore(File source) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, source.getName());
        values.put(MediaStore.Downloads.MIME_TYPE, "application/x-ndjson");
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri destination = resolver.insert(collection, values);
        if (destination == null) throw new IOException("MediaStore did not create a Downloads entry");

        boolean published = false;
        try {
            OutputStream output = resolver.openOutputStream(destination, "w");
            if (output == null) throw new IOException("Could not open the Downloads entry");
            try (InputStream input = new FileInputStream(source); OutputStream closeable = output) {
                copy(input, closeable);
            }
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.Downloads.IS_PENDING, 0);
            if (resolver.update(destination, ready, null, null) != 1) {
                throw new IOException("Could not publish the Downloads entry");
            }
            published = true;
        } finally {
            if (!published) resolver.delete(destination, null, null);
        }
    }

    private void exportToAppDownloads(File source) throws IOException {
        File downloads = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (downloads == null) throw new IOException("App-specific Downloads directory is unavailable");
        if (!downloads.exists() && !downloads.mkdirs()) {
            throw new IOException("Could not create app-specific Downloads directory");
        }
        File destination = new File(downloads, source.getName());
        if (!destination.createNewFile()) {
            throw new IOException("Export destination already exists: " + destination.getName());
        }
        boolean copied = false;
        try (InputStream input = new FileInputStream(source);
             OutputStream output = new FileOutputStream(destination, false)) {
            copy(input, output);
            copied = true;
        } finally {
            if (!copied && destination.exists() && !destination.delete()) {
                throw new IOException("Could not remove incomplete Downloads export: " + destination);
            }
        }
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[16 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        output.flush();
    }
}
