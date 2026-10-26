package com.openpositioning.PositionMe.data.local.session;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import com.openpositioning.PositionMe.domain.recording.DurableSessionState;
import com.openpositioning.PositionMe.domain.recording.ExportStatus;
import com.openpositioning.PositionMe.domain.recording.SessionSummary;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/** Publishes only the committed, manifest-referenced contents of a finalized session. */
public final class SessionBundleExporter {
    private static final String RELATIVE_PATH = Environment.DIRECTORY_DOWNLOADS + "/PositionMe";

    private SessionBundleExporter() { }

    public static Uri export(Context context, SessionRepository repository, UUID id) throws IOException {
        SessionSummary summary = findSummary(repository, id);
        DurableSessionState state = summary.getState();
        if (state != DurableSessionState.SAVED && state != DurableSessionState.RECOVERED) {
            throw new IOException("Only saved or explicitly recovered sessions can be exported; state=" + state);
        }
        Uri existing = findExisting(context, id);
        if (existing != null) {
            if (summary.getExportStatus() != ExportStatus.EXPORTED) {
                repository.markExportResult(id, ExportStatus.EXPORTED, "");
            }
            return existing;
        }

        File sessionDir = new File(repository.getRootDirectory(), id.toString());
        File manifestFile = new File(sessionDir, SessionRepository.MANIFEST_FILE);
        File recordsFile = repository.getExportSource(id);
        byte[] checkpoint = repository.readCheckpoint(id);

        File staging = File.createTempFile("positionme-session-", ".zip", context.getCacheDir());
        try {
            SessionBundleWriter.write(staging, manifestFile, recordsFile, summary.getCommittedBytes(), id, checkpoint);
            repository.markExportResult(id, ExportStatus.PENDING, "");
            Uri published = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? publishMediaStore(context, staging, id)
                    : publishAppDownloads(context, staging, id);
            repository.markExportResult(id, ExportStatus.EXPORTED, "");
            return published;
        } catch (Exception failure) {
            repository.markExportResult(id, ExportStatus.FAILED,
                    failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
            if (failure instanceof IOException) throw (IOException) failure;
            throw new IOException("Session export failed", failure);
        } finally {
            if (staging.exists() && !staging.delete()) staging.deleteOnExit();
        }
    }

    private static SessionSummary findSummary(SessionRepository repository, UUID id) throws IOException {
        for (SessionSummary summary : repository.listSessions()) if (summary.getId().equals(id)) return summary;
        throw new IOException("Unknown session: " + id);
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private static Uri publishMediaStore(Context context, File staging, UUID id) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        String displayName = nextDisplayName(context, id);
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, displayName);
        values.put(MediaStore.Downloads.MIME_TYPE, "application/zip");
        values.put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_PATH);
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri destination = resolver.insert(MediaStore.Downloads.getContentUri(
                MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
        if (destination == null) throw new IOException("MediaStore did not create a Downloads entry");
        boolean published = false;
        try {
            OutputStream output = resolver.openOutputStream(destination, "w");
            if (output == null) throw new IOException("Could not open Downloads export");
            try (InputStream input = new FileInputStream(staging); OutputStream closeable = output) {
                copy(input, closeable);
            }
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.Downloads.IS_PENDING, 0);
            if (resolver.update(destination, ready, null, null) != 1) {
                throw new IOException("Could not publish session export");
            }
            published = true;
            return destination;
        } finally {
            if (!published) resolver.delete(destination, null, null);
        }
    }

    private static Uri publishAppDownloads(Context context, File staging, UUID id) throws IOException {
        File downloads = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (downloads == null) throw new IOException("App Downloads directory is unavailable");
        File directory = new File(downloads, "PositionMe");
        if (!directory.exists() && !directory.mkdirs()) throw new IOException("Could not create Downloads directory");
        String displayName = nextFileName(directory, id);
        File destination = new File(directory, displayName);
        File pending = new File(directory, displayName + ".pending");
        try (InputStream input = new FileInputStream(staging); OutputStream output = new FileOutputStream(pending)) {
            copy(input, output);
            output.flush();
        } catch (IOException failure) {
            pending.delete();
            throw failure;
        }
        if (!pending.renameTo(destination)) {
            pending.delete();
            throw new IOException("Could not publish session export");
        }
        return Uri.fromFile(destination);
    }

    private static Uri findExisting(Context context, UUID id) {
        String base = "PositionMe_Session_" + id;
        String name = base + ".zip";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            String[] projection = {MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME};
            String selection = MediaStore.Downloads.RELATIVE_PATH + "=? AND "
                    + MediaStore.Downloads.IS_PENDING + "=0";
            try (Cursor cursor = context.getContentResolver().query(collection, projection,
                    selection, new String[]{RELATIVE_PATH + "/"}, MediaStore.Downloads.DISPLAY_NAME + " ASC")) {
                if (cursor != null && cursor.moveToFirst()) {
                    String retryId = null;
                    do {
                        String candidate = cursor.getString(1);
                        if (name.equals(candidate)) {
                            return Uri.withAppendedPath(collection, cursor.getString(0));
                        }
                        if (retryId == null && candidate != null && candidate.startsWith(base + "_retry_")
                                && candidate.endsWith(".zip")) retryId = cursor.getString(0);
                    } while (cursor.moveToNext());
                    if (retryId != null) return Uri.withAppendedPath(collection, retryId);
                }
            } catch (RuntimeException ignored) { }
        } else {
            File downloads = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            File directory = downloads == null ? null : new File(downloads, "PositionMe");
            File file = directory == null ? null : new File(directory, name);
            if (file != null && file.isFile()) return Uri.fromFile(file);
            if (directory != null) {
                File[] retries = directory.listFiles((dir, filename) -> filename.startsWith(base + "_retry_")
                        && filename.endsWith(".zip"));
                if (retries != null && retries.length > 0) {
                    java.util.Arrays.sort(retries, java.util.Comparator.comparing(File::getName));
                    return Uri.fromFile(retries[retries.length - 1]);
                }
            }
        }
        return null;
    }

    private static String nextDisplayName(Context context, UUID id) {
        String base = "PositionMe_Session_" + id;
        if (findExisting(context, id) == null) return base + ".zip";
        return base + "_retry_" + UUID.randomUUID() + ".zip";
    }

    private static String nextFileName(File directory, UUID id) {
        String base = "PositionMe_Session_" + id;
        File first = new File(directory, base + ".zip");
        return first.exists() ? base + "_retry_" + UUID.randomUUID() + ".zip" : base + ".zip";
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[16 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        output.flush();
    }
}
