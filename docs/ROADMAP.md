# PositionMe modernization roadmap

PositionMe serves commercial use, scientific research, and reliable data collection as first-class use cases. See ../AGENTS.md for OOP and engineering requirements.

| Milestone | Acceptance |
| --- | --- |
| M0 — Integration and build baseline | Upstream history integrated without discarding fork positioning/calibration flows; reproducible debug build, meaningful unit tests, lint checks and explicit runtime limitations. |
| M1 — Architecture | Separate acquisition, estimation, recording, persistence and presentation; typed contracts, explicit ownership and lifecycle; regression fixtures protect behavior. |
| M2 — Reliable collection | Session state machine, background behavior, durable checkpoints, recovery, versioned exports, quality metadata and synchronization. |
| M3 — Application modernization | Incremental Kotlin/state-management/UI migration; usable permission, offline, accessibility and error flows. |
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
