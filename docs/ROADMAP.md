# PositionMe modernization roadmap

PositionMe serves commercial use, scientific research, and reliable data collection as first-class use cases. See ../AGENTS.md for OOP and engineering requirements.

| Milestone | Acceptance |
| --- | --- |
| M0 — Integration and build baseline | Upstream history integrated without discarding fork positioning/calibration flows; reproducible debug build, meaningful unit tests, lint checks and explicit runtime limitations. |
| M1 — Architecture | Separate acquisition, estimation, recording, persistence and presentation; typed contracts, explicit ownership and lifecycle; regression fixtures protect behavior. |
| M2 — Reliable collection | Session state machine, background ownership, durable local checkpoints, validated recovery, versioned exports and provenance metadata. Remote resumable synchronization remains release work. |
| M3 — Application modernization | Lifecycle-owned screen state, contextual permissions, offline/remote flows, accessible core controls and recoverable errors. Kotlin/Compose migration remains incremental. |
| M4 — Release and research qualification | Fixed-trace estimator comparison, performance/battery evidence, supported-device validation, signing/distribution, privacy and licensing review. |

## Delivery

Continue commits on the working branch. Open one PR when a coherent milestone is verified. Keep algorithm changes distinguishable from structural refactoring. Do not merge a milestone merely because its build passes.

## Next after M0

1. Define typed timestamped observation and position contracts independent of Maps and Android UI.
2. Establish raw-event replay fixtures before modifying fusion math.
3. Replace the global SensorFusion facade gradually; explicitly own session/service lifetime and UI subscriptions.
4. Move full-fidelity collection and persistence out of Fragment polling.
5. Remove privileged client credentials through a server authentication design.

Early development: hosted CI is disabled by user instruction (2026-10-20). Local build/test/lint verification remains required. The disabled workflow is retained in docs only as a future reference.

## Current checkpoint

M3 core application journeys are implemented; see M3_BASELINE.md, M3_PLAN.md and ARCHITECTURE.md. Recording/calibration share lifecycle-owned state, permissions follow explicit actions, and local sessions are independent of remote catalog loading. Verification remains local. M4 release/research qualification and the documented runtime, authentication, synchronization and raw-replay gaps remain open.
