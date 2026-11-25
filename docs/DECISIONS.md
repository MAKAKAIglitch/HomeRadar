# Architecture decision log

This file records decisions and their status. A decision may be revised when a prototype or device test provides evidence.

## ADR-001 — Trajectory-first mapping

**Status:** Accepted

HomeRadar draws the user's path and places wireless observations along it. A floor plan, room graph, wall reconstruction, or manual room drawing is not required.

**Reason:** The core user goal is to understand signal conditions along a walk, not to create a building model.

## ADR-002 — Rust core, Kotlin Android application

**Status:** Accepted as project direction

Use Rust for portable data processing and algorithms; use Kotlin for Android APIs, lifecycle, camera/sensor/Wi-Fi acquisition and UI.

**Reason:** This keeps the reusable engine independent of Android while allowing native access to platform APIs.

## ADR-003 — Jetpack Compose and Material 3

**Status:** Accepted as initial UI direction

Use Jetpack Compose with Material 3 / Material You. Revisit only if implementation evidence justifies another UI stack.

## ADR-004 — UniFFI as first Rust/Kotlin bridge prototype

**Status:** Proposed; validate with prototype

Start with UniFFI and batch-oriented APIs. Benchmark serialization and call overhead before committing to a high-frequency data path.

## ADR-005 — ARCore as optional Android pose provider

**Status:** Proposed; validate device support and behavior

Use ARCore visual-inertial tracking where supported. Keep the core provider-neutral and retain an IMU/PDR fallback with honest drift/quality indicators.

## ADR-006 — Local-first persistence

**Status:** Accepted

SQLite plus a versioned `.homeradar` archive. No cloud account, upload, or telemetry is required.

## ADR-007 — Inferred topology must remain explicitly inferred

**Status:** Accepted

Do not infer mesh membership from SSID equality alone. Store evidence, confidence, provenance and user overrides separately from directly observed facts.
