package com.openpositioning.PositionMe.data.local.session;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

public class SessionBundleWriterTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test public void jsonOnlySessionExportsCommittedPrefixAndIgnoresOrphanCheckpoint() throws Exception {
        UUID id = UUID.randomUUID();
        byte[] committed = "{\"kind\":\"sample\"}\n".getBytes(StandardCharsets.UTF_8);
        File directory = temporaryFolder.newFolder("json-only");
        File manifest = writeManifest(directory, id, committed.length, 1, 0, "", "");
        File records = new File(directory, SessionRepository.RECORDS_FILE);
        write(records, concat(committed, "{\"uncommitted\":true}\n".getBytes(StandardCharsets.UTF_8)));
        write(new File(directory, "checkpoint-orphan.pb"), new byte[]{99, 98, 97});
        File archive = new File(directory, "bundle.zip");

        SessionBundleWriter.write(archive, manifest, records, committed.length, id, null);

        assertArrayEquals(committed, zipEntry(archive, SessionRepository.RECORDS_FILE));
        assertArrayEquals(read(manifest), zipEntry(archive, SessionRepository.MANIFEST_FILE));
        assertFalse(hasEntry(archive, "checkpoint-orphan.pb"));
        assertEquals(2, entryCount(archive));
    }

    @Test public void bundleIncludesOnlyManifestReferencedAndChecksummedCheckpoint() throws Exception {
        UUID id = UUID.randomUUID();
        byte[] committed = "{\"kind\":\"sample\"}\n".getBytes(StandardCharsets.UTF_8);
        byte[] checkpoint = new byte[]{1, 2, 3, 4};
        String checkpointName = "checkpoint-00000000000000000001.pb";
        File directory = temporaryFolder.newFolder("checkpointed");
        File manifest = writeManifest(directory, id, committed.length, 1, 1,
                checkpointName, SessionRepository.sha256(checkpoint));
        File records = new File(directory, SessionRepository.RECORDS_FILE);
        write(records, committed);
        File archive = new File(directory, "bundle.zip");

        SessionBundleWriter.write(archive, manifest, records, committed.length, id, checkpoint);

        assertArrayEquals(checkpoint, zipEntry(archive, checkpointName));
        assertEquals(3, entryCount(archive));
    }

    @Test public void rejectsMalformedCommittedRecordEvenWhenSuffixIsValid() throws Exception {
        UUID id = UUID.randomUUID();
        byte[] recordsBytes = "{not-json}\n{\"valid\":true}\n".getBytes(StandardCharsets.UTF_8);
        File directory = temporaryFolder.newFolder("invalid-prefix");
        File manifest = writeManifest(directory, id, "{not-json}\n".length(), 1, 0, "", "");
        File records = new File(directory, SessionRepository.RECORDS_FILE);
        write(records, recordsBytes);
        File archive = new File(directory, "bundle.zip");

        try {
            SessionBundleWriter.write(archive, manifest, records, "{not-json}\n".length(), id, null);
            fail("Expected malformed committed JSONL to be rejected");
        } catch (IOException expected) {
            // The committed byte offset, rather than a valid trailing record, controls exportability.
        }
    }

    private static File writeManifest(File directory, UUID id, long committedBytes, long count,
                                      long generation, String checkpoint, String digest) throws Exception {
        JSONObject json = new JSONObject();
        json.put("manifestVersion", 1);
        json.put("sessionId", id.toString());
        json.put("committedBytes", committedBytes);
        json.put("recordCount", count);
        json.put("checkpointGeneration", generation);
        json.put("checkpointFile", checkpoint);
        json.put("checkpointSha256", digest);
        File file = new File(directory, SessionRepository.MANIFEST_FILE);
        write(file, json.toString().getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static boolean hasEntry(File archive, String name) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new java.io.FileInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) if (name.equals(entry.getName())) return true;
        }
        return false;
    }

    private static int entryCount(File archive) throws IOException {
        int count = 0;
        try (ZipInputStream zip = new ZipInputStream(new java.io.FileInputStream(archive))) {
            while (zip.getNextEntry() != null) count++;
        }
        return count;
    }

    private static byte[] zipEntry(File archive, String name) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new java.io.FileInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (name.equals(entry.getName())) {
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    byte[] buffer = new byte[1024];
                    int count;
                    while ((count = zip.read(buffer)) != -1) output.write(buffer, 0, count);
                    return output.toByteArray();
                }
            }
        }
        throw new IOException("ZIP entry not found: " + name);
    }

    private static void write(File file, byte[] bytes) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) { output.write(bytes); }
    }

    private static byte[] read(File file) throws IOException {
        try (java.io.FileInputStream input = new java.io.FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
