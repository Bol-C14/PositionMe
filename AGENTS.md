# PositionMe — Agent Engineering Instructions

## Product mandate

Design and maintain PositionMe as a complete, releasable Android application serving commercial use, scientific research, and reliable data collection. All three are first-class use cases.

Treat usability, correctness, session reliability, maintainability, privacy, reproducibility, and release readiness as product requirements. Research functionality must integrate with the product architecture. Experimental algorithms must be explicitly selectable and identifiable; they must not silently change production behavior.

Use this mandate when choosing architecture, implementation scope, acceptance criteria, and tradeoffs. Establish a phased delivery plan; do not assume every change must implement every future capability.

## Object-oriented design

Apply OOP and SOLID principles throughout new code and refactoring:

- Encapsulate state and enforce invariants inside the object that owns it. Avoid exposing mutable collections, internal buffers, or unrestricted setters.
- Give each class one coherent responsibility and an explicit lifecycle. Separate sensor acquisition, estimation, recording, persistence, networking, and rendering.
- Depend on narrow contracts at meaningful boundaries. Use constructor injection and explicit ownership instead of service locators, global mutable singletons, or hidden dependencies.
- Prefer composition over inheritance. Use inheritance only for a genuine substitutable relationship; keep contracts valid for every implementation.
- Make algorithms and providers replaceable through stable interfaces where substitution is needed, including live/replay inputs and positioning implementations.
- Keep domain models and numerical algorithms independent of Android UI, Google Maps, network clients, and storage representations.
- Use typed inputs, outputs, and value objects for coordinates, observations, timestamps, configuration, and results. Document units, coordinate frames, clock domains, validity, and uncertainty.
- Avoid god objects, cyclic dependencies, presentation references in domain/data code, and JSON as an internal component communication contract.
- Keep abstractions proportional to real responsibilities and variation. Do not create an interface for every class or deep inheritance hierarchies merely to demonstrate OOP.

Package names alone do not establish architectural boundaries. Review actual dependencies and state ownership. Kotlin, Java, Compose, and library upgrades support these principles; changing technology does not itself satisfy them.

## Architecture and application behavior

- UI renders explicit state and submits user intentions. Views and Fragments must not own long-running recording, sensor-processing, persistence, or upload loops.
- Define a recording-session owner and state machine with explicit start, stop, failure, and recovery behavior. Specify whether recording continues when the app is backgrounded or the screen turns off, and implement the corresponding Android lifecycle behavior.
- Give listeners, jobs, timers, executors, network calls, files, and inference runtimes clear ownership and deterministic cleanup.
- Establish ordering and concurrency rules for sensor events, asynchronous observations, and estimator updates. Handle stale data and late responses explicitly.
- Preserve useful offline operation. Represent unavailable sensors, missing models, denied permissions, and network failures as explicit states with actionable UI.
- Externalize building/floor definitions, calibration, model metadata, and environment-specific configuration rather than embedding them in UI or algorithm classes.
- Keep production capabilities and experimental controls understandable to their respective users. Product flows should not require knowledge of implementation internals.

## Data collection and scientific integrity

- Preserve raw observations separately from derived estimates and manual ground-truth annotations.
- Define versioned data schemas, session identifiers, timestamp semantics, coordinate systems, units, sensor metadata, calibration, algorithm configuration, and model versions.
- Make recording resilient to interruption. Define checkpointing, finalization, recovery, export, and upload behavior; report incomplete sessions and dropped samples honestly.
- Keep disk I/O and expensive processing off the UI thread. Define buffering and backpressure behavior without silently discarding data.
- Distinguish map playback from algorithm replay. Enable recorded raw events to drive the same estimation contracts as live events.
- Support reproducible comparisons using fixed recorded inputs, explicit configurations, controlled randomness, and documented numerical tolerances.
- Prevent ground-truth annotations or future observations from entering online estimation or evaluation inputs unless explicitly part of the experiment protocol.
- Validate training/inference preprocessing, feature dictionaries, normalization, tensor contracts, and model metadata together.

## Commercial and release quality

- Design complete user journeys: onboarding, permissions, recording, status, save, recovery, replay, export, and applicable synchronization.
- Define supported devices and Android versions; handle hardware capability differences deliberately.
- Treat accessibility, clear error messages, responsiveness, and long-session battery/memory/storage behavior as acceptance criteria.
- Collect only necessary data and permissions. Make recording and upload behavior transparent, with appropriate retention, deletion, and export controls.
- Keep credentials out of source control. Privileged server credentials must not be embedded in a distributed client. Review existing tracked configuration without reproducing secrets in logs or reports.
- Maintain reproducible builds, compatible pinned dependencies, CI checks, release configuration, versioning, migration plans, and accurate setup documentation.
- Review third-party code, model, dataset, and map licensing before distribution. Do not claim release readiness without the relevant checks.

## Refactoring and verification

- Before structural changes, identify existing user-visible behavior, data contracts, and a runnable baseline. Record unresolved build/runtime limitations.
- Prefer incremental, reviewable changes that preserve working flows and existing recordings. Plan explicit migrations for intentional compatibility changes.
- Separate behavior-preserving refactoring from algorithm changes so their effects can be assessed independently.
- Prioritize meaningful tests: estimator behavior on fixed traces, coordinate conversions, session state transitions and cleanup, persistence/recovery, schema compatibility, and critical user journeys.
- Verify device-dependent behavior on physical hardware when relevant. Unit tests alone do not establish sensor or background-recording reliability.
- Measure positioning quality, latency, sample loss, battery, memory, and UI performance before making optimization claims.
- Report what changed, why, what was verified, and remaining limitations. Never describe untested code as production-ready.

## Working convention

Apply these instructions to the whole repository. More specific directory instructions may add details while preserving the product mandate and architectural principles above. Before implementation, resolve material product assumptions from existing context or state them explicitly.
