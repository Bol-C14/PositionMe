# PositionMe architecture after M1

## Ownership and dependency direction

| Component | Owns | Depends on |
| --- | --- | --- |
| domain/PositioningEngine | Estimator state, local coordinate frame, ordering of typed observations | Immutable domain values and pure EKF/PF/geodesy |
| sensors/SensorFusion | Android acquisition adapters, session composition, event subscriptions, recorded PDR history | Domain interfaces and application recording contracts |
| application/recording/RecordingSessionCollector | Sampling cadence, ordered writes, stop/failure/finalization state | Injected source, sink, scheduler and monotonic clock |
| sensors/TrajectoryRecorder | Protobuf trajectory builder and its recording timer | Immutable sensor snapshots and recorder lock |
| data/local | App-private JSONL persistence and finalized Downloads export | Android file/MediaStore APIs and recording sink contract |
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

The foreground service keeps a live session active in the background. It does not claim process-death recovery. Application-owned PDR history survives view unsubscription; correction views render their own copied path.

## Remaining boundaries

SharedPreferences/PdrProcessing and the legacy Android facade still need incremental cleanup. JSON is retained as the existing persistence compatibility format, not introduced into the domain estimator. Optional WiFi model assets remain absent; no model inference quality claim is made. Session state is not durable across process death, and protobuf collection still uses a timer rather than a raw-event log. These belong to later collection/replay work.
