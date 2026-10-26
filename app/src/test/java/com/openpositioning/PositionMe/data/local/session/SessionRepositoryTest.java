package com.openpositioning.PositionMe.data.local.session;

import com.openpositioning.PositionMe.domain.recording.DurableSessionState;
import com.openpositioning.PositionMe.domain.recording.ExportStatus;
import com.openpositioning.PositionMe.domain.recording.SessionMetadata;
import com.openpositioning.PositionMe.domain.recording.SessionSummary;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.CodeSource;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SessionRepositoryTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test public void restartMarksOpenInterruptedAndDiscardsReportedTornTail() throws Exception {
        File root = temporaryFolder.newFolder("sessions");
        SessionRepository repository = new SessionRepository(root);
        SessionJournal journal = repository.create(new SessionMetadata(1000, 50, Collections.singletonMap("sensor", "imu")));
        journal.write(new JSONObject().put("kind", "sample").put("n", 1));
        journal.write(new JSONObject().put("kind", "sample").put("n", 2));
        assertTrue(repository.recoverOpenSessions().isEmpty()); // The live writer owns an exclusive lock.
        journal.flush();
        String id = journal.getSessionId();
        journal.close(); // A close is a durable prefix, not an orderly session completion.
        File source = repository.getExportSource(java.util.UUID.fromString(id));
        long committedLength = source.length();
        try (FileOutputStream suffix = new FileOutputStream(source, true)) {
            suffix.write("{\"kind\":\"partial".getBytes(StandardCharsets.UTF_8));
            suffix.getFD().sync();
        }

        SessionRepository afterRestart = new SessionRepository(root);
        List<SessionSummary> interrupted = afterRestart.recoverOpenSessions();
        assertEquals(1, interrupted.size());
        SessionSummary summary = interrupted.get(0);
        assertEquals(DurableSessionState.INTERRUPTED, summary.getState());
        assertEquals(2, summary.getRecordCount());
        assertEquals(committedLength, source.length());
        assertEquals("sensor", summary.getMetadata().getAttributes().keySet().iterator().next());
        assertEquals(SessionMetadata.JOURNAL_SYNC_TARGET_INTERVAL_MILLIS,
                summary.getMetadata().getJournalSyncTargetIntervalMillis());
        assertTrue(summary.getRecoveryReport().hasLoss());
        assertTrue(summary.getRecoveryReport().getReason().contains("uncommitted"));

        SessionSummary finalized = afterRestart.finalizeRecovered(summary.getId());
        assertEquals(DurableSessionState.RECOVERED, finalized.getState());
        assertEquals(ExportStatus.NOT_REQUESTED, finalized.getExportStatus());
        assertEquals(2, afterRestart.listSessions().get(0).getRecordCount());
    }

    @Test public void malformedOpenManifestIsPreservedWhileOtherSessionsRecover() throws Exception {
        File root = temporaryFolder.newFolder("malformed-manifests");
        SessionRepository repository = new SessionRepository(root);
        SessionJournal malformed = repository.create(new SessionMetadata(1100, 60, Collections.emptyMap()));
        malformed.write(new JSONObject().put("kind", "keep-original"));
        malformed.flush();
        File malformedDirectory = malformed.getDirectory();
        File malformedRecords = new File(malformedDirectory, SessionRepository.RECORDS_FILE);
        malformed.close();
        JSONObject badManifest = repository.readManifest(malformedDirectory);
        badManifest.put("sessionId", "not-a-uuid");
        SessionRepository.writeAtomically(new File(malformedDirectory, SessionRepository.MANIFEST_FILE),
                badManifest.toString().getBytes(StandardCharsets.UTF_8));
        try (FileOutputStream suffix = new FileOutputStream(malformedRecords, true)) {
            suffix.write("uncommitted".getBytes(StandardCharsets.UTF_8));
            suffix.getFD().sync();
        }
        long originalLength = malformedRecords.length();

        SessionJournal valid = repository.create(new SessionMetadata(1200, 70, Collections.emptyMap()));
        valid.write(new JSONObject().put("kind", "recover-me"));
        valid.flush();
        UUID validId = valid.getId();
        valid.close();

        List<SessionSummary> recovered = new SessionRepository(root).recoverOpenSessions();
        assertEquals(1, recovered.size());
        assertEquals(validId, recovered.get(0).getId());
        assertEquals(DurableSessionState.INTERRUPTED, recovered.get(0).getState());
        assertEquals(originalLength, malformedRecords.length());
        assertEquals("not-a-uuid", new JSONObject(new String(
                SessionRepository.readAll(new File(malformedDirectory, SessionRepository.MANIFEST_FILE)),
                StandardCharsets.UTF_8)).getString("sessionId"));
    }

    @Test public void orphanCheckpointFromCrashBeforeManifestPublicationIsIgnored() throws Exception {
        File root = temporaryFolder.newFolder("sessions");
        SessionRepository repository = new SessionRepository(root, boundary -> {
            if ("after_checkpoint_file_publish".equals(boundary)) throw new java.io.IOException("simulated process death");
        });
        SessionJournal journal = repository.create(new SessionMetadata(1000, 1, Collections.emptyMap()));
        journal.write(new JSONObject().put("kind", "sample"));
        String id = journal.getSessionId();
        try { journal.checkpoint(new byte[]{1, 2, 3}, 1010, 11); }
        catch (java.io.IOException expected) { }
        abandonWriterForCrash(journal);

        SessionRepository restarted = new SessionRepository(root);
        SessionSummary summary = restarted.recoverOpenSessions().get(0);
        assertEquals(DurableSessionState.INTERRUPTED, summary.getState());
        assertNull(restarted.readCheckpoint(summary.getId()));
        assertEquals(1, summary.getRecordCount()); // JSONL was committed before checkpoint generation publication.
        assertEquals(0, summary.getRecoveryReport().getDiscardedBytes());
    }

    @Test public void crashAfterLogFsyncButBeforeManifestLeavesNoFalseDurabilityClaim() throws Exception {
        File root = temporaryFolder.newFolder("sessions");
        SessionRepository repository = new SessionRepository(root, boundary -> {
            if ("after_records_fsync".equals(boundary)) throw new java.io.IOException("simulated process death");
        });
        SessionJournal journal = repository.create(new SessionMetadata(1000, 1, Collections.emptyMap()));
        journal.write(new JSONObject().put("kind", "sample"));
        String id = journal.getSessionId();
        try { journal.flush(); }
        catch (java.io.IOException expected) { }
        abandonWriterForCrash(journal);

        SessionRepository restarted = new SessionRepository(root);
        SessionSummary summary = restarted.recoverOpenSessions().get(0);
        assertEquals(java.util.UUID.fromString(id), summary.getId());
        assertEquals(DurableSessionState.INTERRUPTED, summary.getState());
        assertEquals(0, summary.getRecordCount());
        assertEquals(0, summary.getCommittedBytes());
        assertTrue(summary.getRecoveryReport().hasLoss());
        assertEquals(0, restarted.getExportSource(summary.getId()).length());
    }

    @Test public void manifestCommittedCheckpointHasImmutableBytesAndVerifiedChecksum() throws Exception {
        File root = temporaryFolder.newFolder("sessions");
        SessionRepository repository = new SessionRepository(root);
        SessionJournal journal = repository.create(new SessionMetadata(2000, 100, Collections.emptyMap()));
        journal.write(new JSONObject().put("kind", "sample"));
        byte[] checkpoint = new byte[]{9, 8, 7};
        journal.checkpoint(checkpoint, 2010, 110);
        checkpoint[0] = 0;
        byte[] restored = repository.readCheckpoint(journal.getId());
        assertNotNull(restored);
        assertArrayEquals(new byte[]{9, 8, 7}, restored);
        journal.complete();
        assertEquals(DurableSessionState.SAVED, repository.listSessions().get(0).getState());
    }

    @Test public void checkpointPublicationPrunesOnlySupersededGenerationFiles() throws Exception {
        File root = temporaryFolder.newFolder("checkpoint-pruning");
        SessionRepository repository = new SessionRepository(root);
        SessionJournal journal = repository.create(new SessionMetadata(2500, 150, Collections.emptyMap()));
        journal.checkpoint(new byte[]{1}, 2510, 160);
        journal.checkpoint(new byte[]{2}, 2520, 170);
        journal.checkpoint(new byte[]{3}, 2530, 180);

        File[] checkpointFiles = journal.getDirectory().listFiles((directory, name) ->
                name.matches("checkpoint-[0-9]{20}\\.pb"));
        assertNotNull(checkpointFiles);
        assertEquals(1, checkpointFiles.length);
        assertEquals("checkpoint-00000000000000000003.pb", checkpointFiles[0].getName());
        assertArrayEquals(new byte[]{3}, repository.readCheckpoint(journal.getId()));
    }

    @Test public void corruptedCommittedCheckpointIsReportedWithoutDeletingJsonlPrefix() throws Exception {
        File root = temporaryFolder.newFolder("sessions");
        SessionRepository repository = new SessionRepository(root);
        SessionJournal journal = repository.create(new SessionMetadata(3000, 200, Collections.emptyMap()));
        journal.write(new JSONObject().put("kind", "preserve"));
        journal.checkpoint(new byte[]{1, 2, 3}, 3010, 210);
        File records = new File(journal.getDirectory(), SessionRepository.RECORDS_FILE);
        long validBytes = records.length();
        File checkpoint = new File(journal.getDirectory(), "checkpoint-00000000000000000001.pb");
        try (FileOutputStream damaged = new FileOutputStream(checkpoint)) {
            damaged.write(new byte[]{1, 2, 4});
            damaged.getFD().sync();
        }
        journal.close();

        SessionSummary summary = new SessionRepository(root).recoverOpenSessions().get(0);
        assertEquals(DurableSessionState.CORRUPT, summary.getState());
        assertTrue(summary.getRecoveryReport().getReason().contains("checksum"));
        assertEquals(validBytes, records.length());
        assertArrayEquals(new byte[]{1, 2, 4}, SessionRepository.readAll(checkpoint));
    }

    @Test public void abruptSubprocessDeathReleasesWriterLockAndRecoversCommittedPrefix() throws Exception {
        File root = temporaryFolder.newFolder("subprocess-sessions");
        UUID id = UUID.randomUUID();
        File childLog = temporaryFolder.newFile("abrupt-writer.log");
        String java = new File(new File(System.getProperty("java.home"), "bin"), "java").getAbsolutePath();
        String classpath = codeSource(SessionRepositoryTest.class) + File.pathSeparator
                + codeSource(SessionRepository.class) + File.pathSeparator
                + codeSource(JSONObject.class);
        Process child = new ProcessBuilder(java, "-cp", classpath,
                AbruptExitProcess.class.getName(), root.getAbsolutePath(), id.toString())
                .redirectErrorStream(true).redirectOutput(childLog).start();
        assertTrue("child process did not exit", child.waitFor() == 0);

        SessionRepository afterDeath = new SessionRepository(root);
        List<SessionSummary> recovered = afterDeath.recoverOpenSessions();
        assertEquals(1, recovered.size());
        SessionSummary summary = recovered.get(0);
        assertEquals(id, summary.getId());
        assertEquals(DurableSessionState.INTERRUPTED, summary.getState());
        assertEquals(1, summary.getRecordCount());
        assertTrue("uncheckpointed record should be reported", summary.getRecoveryReport().hasLoss());
        assertTrue(afterDeath.recoverOpenSessions().isEmpty());
    }

    @Test public void failedSessionCanBeRecoveredOnlyAfterCheckpointValidation() throws Exception {
        File root = temporaryFolder.newFolder("failed-sessions");
        SessionRepository repository = new SessionRepository(root);
        SessionJournal journal = repository.create(new SessionMetadata(4000, 300, Collections.emptyMap()));
        journal.write(new JSONObject().put("kind", "retained"));
        journal.checkpoint(new byte[]{4, 5, 6}, 4010, 310);
        journal.fail(new IOException("sensor stopped"));
        SessionSummary failed = repository.listSessions().get(0);
        assertEquals(DurableSessionState.FAILED, failed.getState());
        assertEquals("sensor stopped", failed.getFailure());

        File checkpoint = new File(journal.getDirectory(), "checkpoint-00000000000000000001.pb");
        try (FileOutputStream damaged = new FileOutputStream(checkpoint)) {
            damaged.write(new byte[]{4, 5, 7});
            damaged.getFD().sync();
        }
        try {
            repository.finalizeRecovered(journal.getId());
            throw new AssertionError("invalid checkpoint must not be finalized");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("checksum"));
        }
        assertEquals(DurableSessionState.FAILED, repository.listSessions().get(0).getState());
        assertArrayEquals(new byte[]{4, 5, 7}, SessionRepository.readAll(checkpoint));

        try (FileOutputStream repaired = new FileOutputStream(checkpoint)) {
            repaired.write(new byte[]{4, 5, 6});
            repaired.getFD().sync();
        }
        SessionSummary finalized = repository.finalizeRecovered(journal.getId());
        assertEquals(DurableSessionState.RECOVERED, finalized.getState());
        assertEquals("sensor stopped", finalized.getFailure());
        assertTrue(finalized.getRecoveryReport().getReason().contains("sensor stopped"));
    }

    public static final class AbruptExitProcess {
        public static void main(String[] arguments) throws Exception {
            File root = new File(arguments[0]);
            UUID id = UUID.fromString(arguments[1]);
            SessionJournal journal = new SessionRepository(root).create(id,
                    new SessionMetadata(5000, 400, Collections.emptyMap()));
            journal.write(new JSONObject().put("kind", "committed"));
            journal.flush();
            journal.write(new JSONObject().put("kind", "uncommitted"));
            Runtime.getRuntime().halt(0);
        }
    }

    private static String codeSource(Class<?> type) throws Exception {
        CodeSource source = type.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) throw new AssertionError("No class code source for " + type);
        return new File(source.getLocation().toURI()).getAbsolutePath();
    }

    private static void abandonWriterForCrash(SessionJournal journal) throws Exception {
        java.lang.reflect.Field records = SessionJournal.class.getDeclaredField("recordsStream");
        records.setAccessible(true);
        FileOutputStream stream = (FileOutputStream) records.get(journal);
        if (stream != null) stream.close();
        java.lang.reflect.Field lock = SessionJournal.class.getDeclaredField("lockHandle");
        lock.setAccessible(true);
        ((SessionRepository.LockHandle) lock.get(journal)).close();
    }
}
