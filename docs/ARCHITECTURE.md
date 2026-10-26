# PositionMe architecture after M3

## Ownership and dependency direction

| Component | Owns | Depends on |
| --- | --- | --- |
| domain/PositioningEngine | Estimator state, local coordinate frame, ordering of typed observations | Immutable domain values and pure EKF/PF/geodesy |
| sensors/SensorFusion | Android acquisition adapters, session composition, event subscriptions, recorded PDR history | Domain interfaces and application recording contracts |
| application/recording/RecordingSessionCollector | Sampling cadence, ordered writes, stop/failure/finalization state | Injected source, sink, scheduler and monotonic clock |
| sensors/TrajectoryRecorder | Protobuf trajectory builder and its recording timer | Immutable sensor snapshots and recorder lock |
| data/local/session | Durable UUID session journals, manifest catalog, recovery validation and portable bundles | Recording sink contract, filesystem and Android export adapter |
| data/ml/TfliteWifiPositionPredictor | Optional TFLite runtime, dictionary and tensor contract | Typed WifiFingerprint, model bounds and domain predictor interface |
| presentation | Views, map objects, main-thread rendering and view subscription lifetime | Read-only snapshots, events and explicit commands |

`SensorFusion` remains a compatibility facade and Android composition root while legacy screen callers are migrated. Domain estimators can be constructed and replayed without that singleton or any Android runtime. The facade holds no Fragment, Activity or PathView references.

Run `python3 scripts/check-architecture.py` to enforce domain/platform and sensor/presentation import boundaries. New adapters should keep framework and storage dependencies outside domain code.

## Observations and time

Geographic coordinates use WGS-84 degrees/altitude meters; local positions use ENU meters; headings use radians. Domain observation timestamps use monotonic elapsed-realtime milliseconds. Stale observations are ignored by the engine. Android adapters currently stamp receipt/processing time; this is distinct from original hardware acquisition time and does not establish network-response freshness.

Compatibility JSONL retains epoch `timestamp` and adds `elapsedRealtimeMillis` for capture time. Manual annotation and protocol wire compatibility remain intact. New JSONL session collection is independent of the view lifetime.

## Threading and lifetimes

SensorState is package-private. Mutations and coherent immutable snapshots use the state lock. External vector getters return copies. Sensor events are delivered through closeable subscriptions; delivery is on the producer thread, so UI consumers marshal to main and invalidate queued work when their view stops. General sensor-state display events are capped at 5 Hz; raw processing and recording cadence are independent.

The JSONL collector has one serial worker for periodic snapshots, WiFi request scheduling, annotation writes and finalization. Stop cancels future sampling, drains accepted annotations, flushes/closes the sink and completes a future. Sink I/O is outside the collector monitor. Failure is observable in SessionStatus. Private files are retained if public export fails.

Protobuf builder mutations and immutable builds share a private recorder lock. Timer readings take an immutable sensor snapshot before that lock; sensor callbacks release the sensor-state lock before entering the recorder. Stopped recorders reject asynchronous appends. No recorder lock is held during network upload.

The foreground service supports live background collection and uses a session generation token so stale lifecycle callbacks cannot finalize a newer recording. It is non-sticky: process death ends acquisition. Application-owned PDR history survives view unsubscription; correction views render their own copied path. Hardware behavior still requires device qualification.

## Durable sessions and recovery

`RecordingSessionFactory` creates an app-private `recording_sessions/<UUID>` directory. `SessionJournal` owns its exclusive writer lock, append-only `records.jsonl` and a versioned `manifest.json`. JSON records include their session ID. The manifest records epoch and monotonic start times in milliseconds, provenance, committed byte length/count, state and independent export status. The collector periodically captures an immutable protobuf checkpoint; journal bytes are flushed and fsynced before a manifest publication claims them. Each protobuf generation has a distinct filename and SHA-256 reference. Manifest replacement is atomic where the filesystem supports it; directory fsync is best effort. This does not establish guarantees against arbitrary storage or power failure.

Cold-start recovery skips locked live writers. It validates the committed JSON prefix and referenced checkpoint, marks abandoned OPEN sessions INTERRUPTED, and trims only uncommitted suffix bytes with a recorded loss report. Invalid committed data is marked CORRUPT and preserved. User recovery finalizes retained data as RECOVERED; it never resumes sensors or fabricates continuity. Valid JSON-only sessions remain recoverable/exportable even without a protobuf checkpoint. Failed sessions can be recovered only after the same validation. Five seconds is a checkpoint scheduling target, not a guaranteed loss bound under stalls, storage failure or process termination.

Stop freezes protobuf acquisition before the collector drains accepted writes, captures its final checkpoint and finalizes the private session. A Downloads export failure leaves private SAVED data intact with a separate retry status. Export bundles contain the manifest, exactly the validated committed JSONL prefix and only its referenced protobuf checkpoint. Temporary files, locks and orphan generations are excluded. Android 10+ publishes through pending MediaStore Downloads entries; Android 9 uses app-specific Downloads. Existing completed exports survive failed retries. Upload remains an explicit user action.

## Lifecycle-owned presentation state

`RecordingController` is a pure synchronized state machine behind a narrow `RecordingCommands` boundary. `RecordingViewModel` bridges immutable state through LiveData; the activity-scoped instance is shared by the main recording screen and its calibration child. Fragments observe with their view lifecycle and submit commands. STARTING and SAVING disable duplicate commands across both controls. Navigation is an identified consumable effect so a recreated view does not repeat an acknowledged transition. Recording lifecycle no longer depends on the map refresh timer.

The SensorFusion adapter subscribes before reading a `RecordingOperationSnapshot`, reconnects a new screen owner to pending start/save futures, and ignores historic successful completion or callbacks from replaced/closed collectors. The command future remains authoritative through startup and durable finalization. Observer subscriptions close when the ViewModel is cleared. This reattachment covers work in the current process; process death still follows M2 interruption/recovery semantics.

`PermissionPolicy` maps required and optional permissions without Android dependencies. MainActivity requests access only from explicit recording/manage actions, requests coarse+fine together for Android 12 precise-location upgrades, offers Settings after persistent denial, and rechecks access on resume without prompting. Optional radio/notification permissions remain separate from recording eligibility. NavigationUI manages bottom-tab state; lifecycle back handling tells the user when work continues in the background.

`FilesViewModel` owns local catalog generations, in-flight recovery/export and immutable outcomes behind injected `LocalSessionCommands`. Late load responses are ignored and failed actions retain an actionable error. Remote catalog loading is explicit and typed, with loading/empty/failure states, cancellation and generation checks. Local sessions remain usable without remote catalog access or recording permissions. Remote download adapters dispose their file watchers when the view is destroyed. Server upload observers support removal and no longer inspect presentation classes.

Correction accepts only finite positive decimal step lengths against a valid original baseline. Every preview references the original measurement; preview state survives view recreation through saved instance state. It changes the drawing only, preserving recorded data and uploaded observations. The decorative path sits below the controls and does not take keyboard/accessibility focus.

## Remaining boundaries

SharedPreferences/PdrProcessing and the legacy Android facade still need incremental cleanup. JSON is retained as the existing persistence compatibility format, not introduced into the domain estimator. Optional WiFi model assets remain absent; no model inference quality claim is made. Protobuf collection still uses a timer rather than a full raw hardware-event log; JSONL contains sampled snapshots and annotations. A recovered protobuf may precede the latest committed JSON data. Recovery does not reconstruct a complete algorithm replay or restore the map correction path from raw events. Remote resumable synchronization, encryption/retention controls, bounded buffering, long-session storage limits and device process/background qualification remain open release work.
