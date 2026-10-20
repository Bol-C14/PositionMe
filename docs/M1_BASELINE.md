# M1 — positioning and recording ownership

Date: 2026-10-20. Based on the M0 milestone branch. See ARCHITECTURE.md for contracts and threading rules.

## Delivered

- Pure PositioningEngine plus immutable geographic, step, GNSS, WiFi and estimate values; seed-injectable particle filter and pure geodesy. Existing fusion thresholds and measurement models are retained.
- Typed sensor subscriptions with lifecycle cleanup, coherent defensive snapshots, bounded UI notification rate and app-owned PDR history.
- RecordingSessionCollector with injected source/sink/scheduler/clock; serial sampling, annotation ordering, asynchronous stop and explicit failures. Full-sensor JSON polling and WiFi scheduling removed from Fragments.
- Private UTF-8 JSONL authoritative files with finalized Downloads export. API 28 uses app-specific external Downloads without broad storage permission. API 29+ uses pending MediaStore exports. Failed exports preserve the private file.
- Serialized protobuf mutation/build access and rejection of post-stop callbacks.
- Optional TFLite predictor moved out of domain and accepts typed fingerprints with explicit model bounds, resource lifetime and tensor validation. No optional model assets were added.
- System-bar/cutout padding and measured bottom navigation constraints prevent controls from overlapping system UI.
- Hosted Android CI disabled by user instruction. The YAML is retained as an inactive document; local checks remain the validation method.

## Local verification

- Clean credential-free Debug build, unit tests and Lint passed during integration.
- Final Debug and unsigned Release assemblies, JUnit suite and Lint passed locally with JDK 21, SDK 35, Gradle 8.11.1 and AGP 8.8.2.
- 26 JUnit tests passed, zero failures/errors. Added estimator fixed-input replay/seed determinism/stale ordering/geodesy, deferred session queue drain/failure/finalization and immutable snapshot/event contracts; M0 schema/replay regressions remain green.
- Lint: zero errors, 326 warnings to triage. No new lint baseline or suppression was introduced.
- `python3 scripts/check-architecture.py` and `git diff --check` pass.
- Final layout inspection: stop control moved from y=29–176 to y=92–239 below system bars; bottom navigation ends at y=2337 above the gesture region.
- API 36.1 emulator: cold launch; background recording growth; return without new session file; stop through visible control; service shutdown; private JSONL and finalized Downloads file both 277851 bytes; no AndroidRuntime crash. Correction quality dialog was cancelled; synthetic test data was not uploaded. Emulator System UI sometimes reported ANR during startup; app-specific checks completed after dismissing it.

## Limitations

This is an architecture milestone, not release qualification. Physical sensor accuracy/battery/long-duration behavior, process-death recovery, API 28 export on hardware, authenticated services and optional TFLite inference remain unverified. SensorFusion is still an Android compatibility facade, and PdrProcessing retains Android preference dependencies. Network WiFi fixes use arrival time; full asynchronous freshness handling remains open. Fixed-input domain tests are synthetic observation traces, not a claim of full raw-hardware-event replay.

JSONL export now uses an accurate .jsonl suffix; existing .json recordings are untouched. The seven existing training entrypoints now discover both .json and .jsonl in deterministic order. Their changed sources pass Python syntax parsing; a pre-existing invalid LSTM target-list expression was corrected. No model training was run. M2 should provide a complete file-management/export/recovery flow and explicit user-controlled recording/upload UX.

## Next

M2: make session state durable; checkpoints and interruption recovery; versioned session metadata and raw-event recording; explicit recording start/stop/retry/export flows; freshness and data-quality reporting. Preserve the isolated estimator contract and separate algorithm tuning from structural changes.
