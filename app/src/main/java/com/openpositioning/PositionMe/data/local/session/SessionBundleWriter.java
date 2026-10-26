package com.openpositioning.PositionMe.data.local.session;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds a portable archive from a session's manifest-committed data only. */
final class SessionBundleWriter {
    private SessionBundleWriter() { }

    static void write(File output, File manifestFile, File recordsFile, long expectedCommittedBytes,
                      UUID expectedSessionId, byte[] checkpoint) throws IOException {
        byte[] manifestBytes = readFile(manifestFile);
        JSONObject manifest;
        try {
            manifest = new JSONObject(new String(manifestBytes, StandardCharsets.UTF_8));
        } catch (JSONException invalid) {
            throw new IOException("Could not read session manifest for export", invalid);
        }

        if (manifest.optInt("manifestVersion", -1) != 1
                || !expectedSessionId.toString().equals(manifest.optString("sessionId"))) {
            throw new IOException("Session manifest does not match the requested session");
        }
        long committedBytes = manifest.optLong("committedBytes", -1L);
        long recordCount = manifest.optLong("recordCount", -1L);
        if (committedBytes < 0 || committedBytes != expectedCommittedBytes || recordCount < 0) {
            throw new IOException("Session manifest has an invalid committed JSONL prefix");
        }

        String checkpointFilename = referencedCheckpoint(manifest, checkpoint);
        try (FileOutputStream file = new FileOutputStream(output);
             ZipOutputStream zip = new ZipOutputStream(file)) {
            zip.putNextEntry(new ZipEntry(SessionRepository.MANIFEST_FILE));
            zip.write(manifestBytes);
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry(SessionRepository.RECORDS_FILE));
            copyValidatedJsonPrefix(recordsFile, zip, committedBytes, recordCount);
            zip.closeEntry();

            if (checkpointFilename != null) {
                zip.putNextEntry(new ZipEntry(checkpointFilename));
                zip.write(checkpoint);
                zip.closeEntry();
            }
            zip.finish();
            file.getFD().sync();
        }
    }

    private static String referencedCheckpoint(JSONObject manifest, byte[] checkpoint) throws IOException {
        long generation = manifest.optLong("checkpointGeneration", 0L);
        String filename = manifest.optString("checkpointFile", "");
        if (generation < 0) throw new IOException("Manifest has an invalid checkpoint generation");
        if (generation == 0) {
            if (checkpoint != null || !filename.isEmpty()) {
                throw new IOException("Manifest has an unexpected checkpoint reference");
            }
            return null;
        }
        if (filename.isEmpty() || filename.contains("/") || filename.contains("\\")
                || !filename.endsWith(".pb") || checkpoint == null) {
            throw new IOException("Manifest has an invalid or unavailable checkpoint reference");
        }
        if (!SessionRepository.sha256(checkpoint).equals(manifest.optString("checkpointSha256", ""))) {
            throw new IOException("Referenced protobuf checkpoint checksum does not match");
        }
        return filename;
    }

    private static void copyValidatedJsonPrefix(File source, OutputStream target, long length,
                                                long expectedCount) throws IOException {
        if (length < 0 || !source.isFile() || source.length() < length) {
            throw new IOException("Committed JSONL prefix is unavailable");
        }
        long consumed = 0;
        long count = 0;
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        try (InputStream input = new FileInputStream(source)) {
            while (consumed < length) {
                int requested = (int) Math.min(buffer.length, length - consumed);
                int read = input.read(buffer, 0, requested);
                if (read < 0) throw new IOException("Unexpected end of committed JSONL prefix");
                target.write(buffer, 0, read);
                consumed += read;
                for (int index = 0; index < read; index++) {
                    int value = buffer[index] & 0xff;
                    if (value == '\n') {
                        validateLine(line, count);
                        count++;
                        line.reset();
                    } else {
                        line.write(value);
                    }
                }
            }
        }
        if (line.size() != 0) throw new IOException("Committed JSONL prefix ends with an incomplete record");
        if (count != expectedCount) throw new IOException("Manifest record count does not match committed JSONL prefix");
    }

    private static void validateLine(ByteArrayOutputStream line, long lineIndex) throws IOException {
        if (line.size() == 0) throw new IOException("Committed prefix contains an empty JSONL record");
        try {
            new JSONObject(new String(line.toByteArray(), StandardCharsets.UTF_8));
        } catch (JSONException invalid) {
            throw new IOException("Committed JSONL prefix contains malformed JSON at record " + lineIndex, invalid);
        }
    }

    private static byte[] readFile(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }
}
