# M2 — Durable local recording sessions

Date: 2026-10-26. Base: M1 commit `18b3f8bb5f275b8c802104a6c97f15266293e8a5`.

## Behavior delivered

- A user starts recording explicitly and chooses Stop & save. The UI waits for durable finalization before correction. Upload is a separate user action.
- Each capture has a UUID, versioned manifest, epoch/monotonic millisecond time provenance, app/device/algorithm metadata and append-only sampled JSONL with session IDs.
- The collector checkpoints immutable protobuf snapshots on its serial worker, targets five-second checkpoints, and freezes acquisition before the final checkpoint. Writer locks protect live sessions from recovery scans.
- Startup classifies abandoned OPEN sessions as INTERRUPTED. Recovery validates the manifest-committed JSON prefix and checkpoint checksum, reports trimmed trailing bytes and preserves corrupt committed data. It does not resume acquisition or claim continuous coverage.
- Explicit recovery works for validated interrupted or failed sessions, including JSON-only data without a protobuf checkpoint. Recovery remains in the catalog for export: the live correction screen depends on GNSS/PDR process context that is not fully reconstructed. JSON-only recovery clears stale trajectory state.
- Saved-session catalog actions run asynchronously. Portable ZIP bundles include the manifest, validated committed JSONL prefix and optional referenced checkpoint; they exclude writer locks, temporary files and orphan snapshots.
- Public Downloads publication uses MediaStore pending entries on Android 10+, with app-specific Downloads on Android 9. Failed export retains private saved data; retry preserves/reuses previously completed exports.
- Foreground service callbacks carry a generation token. Old callbacks cannot finalize a newer recording. Worker/storage failure stops live capture and reports failure rather than silently continuing.

## Local verification

Environment: Linux, Temurin JDK 17.0.21, Gradle 8.11.1, Android SDK 35/build-tools 35.0.0. Placeholder secrets only (`secrets.properties.example`). No hosted CI was added or enabled.

Final command: `./gradlew --no-daemon --max-workers=2 -Ppositionme.secretsFile=secrets.properties.example clean assembleDebug assembleRelease testDebugUnitTest lintDebug` (the local Gradle 8.11.1 binary was used with the equivalent arguments).

- Full clean build: PASS, Debug APK and unsigned Release APK generated; release vital lint passed.
- JVM tests: 40 tests, 0 failures/errors. Includes a real child JVM abrupt halt/reopen, live-writer lock exclusion, committed-prefix/torn-tail recovery, orphan checkpoint handling, checksum/corruption preservation, failed-session recovery, malformed-manifest isolation, checkpoint pruning, JSON-only export and collector final-checkpoint failure paths.
- Debug lint: PASS, 0 errors, 321 warnings; existing and new nonfatal warnings remain (see the local lint report). They are not suppressed by this milestone.
- Architecture dependency check, resource XML parsing and `git diff --check`: PASS.

A resource edit during incremental builds left duplicate generated R dex artifacts in this environment; a clean build removed the stale outputs. Final verification above used a clean build with all source changes in place.

## Reliability contract and limits

Five seconds is a scheduling target, not a hard bound on loss during stalls or storage failure. JSON fsync and protobuf snapshot publication are distinct commits; the latest valid protobuf can precede the JSON prefix. Recovery reports known discarded bytes and unknown continuity, not an invented missing-sample count. Superseded protobuf generations are pruned only after a new manifest is published, avoiding cumulative snapshot growth.

Atomic manifest replacement is used where supported; directory fsync is best effort. Arbitrary device/storage/power-failure durability is not established. Malformed manifests are preserved on disk but omitted from the catalog; corrupt committed records are not silently repaired. The app still records sampled sensor snapshots rather than a full hardware-event stream, and the recovered path does not reconstruct all algorithm inputs.

The Linux environment has no attached Android device or hardware-accelerated emulator. This milestone has no new Android runtime smoke result; M1 device/emulator observations do not validate M2 behavior. Before release, test permissions, foreground start/stop, screen-off/background capture, process kill/relaunch, recovery, MediaStore export/retry and explicit upload on supported physical devices.

Remote resumable synchronization, bounded buffering, long-session memory/battery/disk measurements, retention/deletion UX, encryption decisions, signing, credential/server-auth migration and licensing/privacy qualification remain open. This milestone establishes durable local collection; it is not a commercial release qualification.
