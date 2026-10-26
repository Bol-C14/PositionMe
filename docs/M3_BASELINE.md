# M3 — Application state and core journeys

Date: 2026-10-26. Base: M2 `5ac25b0406bb293c544c6d57c152e9320c4d76be`. Upstream develop rechecked: `591e07c05682e9acccd974b5bb6e4ca049c20a9b`, already included.

## Delivered behavior

- Main recording and calibration controls observe one activity-scoped ViewModel with immutable IDLE/STARTING/RECORDING/SAVING/SAVED/WARNING/ERROR state. Shared command gating prevents duplicate start/save operations; failures and saved/export warnings remain distinct. Consumed navigation effects do not replay after view reattachment.
- A narrow injected command adapter reconnects to live recording or pending owner futures when a new Activity/ViewModel appears. Start futures remain authoritative even when lifecycle events arrive early. Closed subscriptions and superseded collector completions cannot update the current screen.
- Recording access is requested from explicit user actions, with required versus optional capabilities separated. Approximate-to-precise requests include coarse+fine on Android 12+. Persistent denial offers Settings; returning to the app refreshes access without a prompt loop. A Manage access action remains reachable. Local sessions need no recording grant.
- Sessions loads the private catalog independently of the network. Recovery/export pending state and outcomes survive view recreation; failed actions can be retried. The remote catalog loads only after Load/Retry, uses validated typed metadata, and distinguishes loading, empty and failed responses. Requests and file observers have cleanup paths.
- Bottom navigation uses NavigationUI state handling. The back action explains when current capture/save continues in the background. Primary labels identify Record and Sessions directly.
- Correction validates localized positive finite decimal input, rejects invalid measured baselines and keeps deterministic scale relative to the original measurement. Preview changes the drawing only; saved scientific data and uploads retain original observations. Focus/labels/touch targets were improved in the recording, calibration, Files and correction flows.

## Local verification

Linux, Temurin JDK 17.0.21, Gradle 8.11.1, Android SDK 35/build-tools 35.0.0; placeholder secrets only. Explicit lifecycle dependencies stay on the already-resolved 2.6.2 baseline; arch core-testing 2.2.0 is test-only. No hosted CI added or enabled.

Final local command: `./gradlew --no-daemon --max-workers=2 -Ppositionme.secretsFile=secrets.properties.example assembleDebug assembleRelease testDebugUnitTest lintDebug`, using the equivalent installed Gradle 8.11.1 binary. A clean build preceded the final incremental verification after fixes.

- Debug and unsigned Release builds: PASS; release vital lint: PASS.
- JVM tests: 72 tests, 0 failures/errors (M2 baseline: 40). New coverage includes shared command gating, save failures/export warnings, permission revocation/retry, pending-operation reattachment races, historic/closed collector suppression, permission API-level mapping and approximate-location upgrades, stale local/remote loads, retained failed operation outcomes and retry, strict remote catalog parsing and visual-step validation. Files ViewModel tests observe LiveData using InstantTaskExecutorRule.
- Debug lint: PASS, 0 errors, 347 warnings. Warnings remain visible; no lint baseline/suppression was introduced.
- Resource XML parsing, architecture boundary checks (including remote adapters versus presentation) and `git diff --check`: PASS.

No hosted CI was added or enabled.

## Design choices

Keep the Java-compatible domain and persistence APIs. ViewModel/LiveData establish lifecycle ownership without coupling an algorithm change or broad Kotlin/Compose rewrite to this milestone. The narrow controller/command boundaries allow later language or UI migrations. M1 numerical behavior and M2 file formats remain the baseline.

## Limitations

No attached Android device or hardware-accelerated emulator was available. JVM transition tests and builds do not prove actual rotation/navigation, screen reader behavior, large-font layout, permission Settings round trips or background recording on devices. These require M4 runtime qualification.

ViewModel state survives view/configuration recreation within a process. Process death still terminates acquisition and uses M2 interrupted-data recovery; it does not resume a continuous recording. Saved preview state describes a visual adjustment and is not persisted as research ground truth. The legacy upload transport still reports through its observer path; resumable remote synchronization and complete upload-state migration remain open.

Kotlin/Compose, full raw hardware-event replay, authentication/credential migration, retention/encryption UX, long-session performance evidence, supported-device qualification, signing and distribution remain follow-on work. This milestone is core application modernization, not release qualification.
