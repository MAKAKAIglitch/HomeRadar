# Roadmap

This roadmap defines a sequence of deliverable milestones. It is a plan, not a claim that the features already exist.

## Phase 0 — Specification and contracts

- [x] Define trajectory-first product scope; no required floor plan.
- [x] Separate Android acquisition from portable Rust processing.
- [x] Define Wi-Fi identity entities and uncertainty requirements.
- [x] Document Android scan limitations and local-first storage.
- [ ] Agree on initial Rust/Kotlin toolchain and minimum Android API level.
- [ ] Add architecture decision records as implementation choices are validated.

**Exit criteria:** domain contracts and platform constraints are reviewed before implementation.

## Phase 1 — Rust domain core

- [x] Create initial Rust workspace and core crate.
- [x] Define initial monotonic timestamps, local 2D pose and quality types.
- [x] Define initial Wi-Fi observation type and conservative SSID display grouping.\n- [ ] Expand to explicit BSSID, radio, access-point, and network-group entities.
- [x] Add input validation and unit tests for timestamps, trajectory gaps, tracking loss, RSSI aggregation and SSID grouping.\n- [ ] Add serialization tests when the storage format is implemented.
- [x] Add synthetic unit-test observations without real home/network information.

**Status:** initial standard-library-only core is implemented. Run `cd core && cargo test --workspace` locally; CI/host compilation has not yet been run in this environment. Serialization and the Android bridge remain open.

## Phase 2 — Android acquisition prototype

- [ ] Create Kotlin Android app using Jetpack Compose and Material 3.
- [ ] Implement permission and lifecycle handling.
- [ ] Collect Wi-Fi scan results and scan-result update events.
- [ ] Collect accelerometer, gyroscope, rotation-vector and step-counter data when available.
- [ ] Record actual timestamps, scan age, source and permission state.
- [ ] Show a diagnostic list of observed networks and data freshness.

**Exit criteria:** a session can record Wi-Fi and motion observations without pretending scans are continuous.

## Phase 3 — Session format and persistence

- [ ] Implement SQLite schema and migrations.
- [ ] Store raw observations separately from derived data.
- [ ] Implement session start, pause, resume, finish and recovery.
- [ ] Implement versioned `.homeradar` archive import/export.
- [ ] Add corruption and compatibility tests.

**Exit criteria:** a recorded session survives app restart and can be exported/imported.

## Phase 4 — Trajectory tracking

- [ ] Add ARCore tracking adapter where supported.
- [ ] Add tracking quality, pose timestamps and relocalization events.
- [ ] Add IMU/PDR fallback with explicit drift warnings.
- [ ] Implement pose interpolation for asynchronous Wi-Fi observations.
- [ ] Draw and save the path on a blank 2D canvas.
- [ ] Add manual origin/reset and optional calibration anchors.

**Exit criteria:** a walk is represented as a timestamped trajectory with quality metadata.

## Phase 5 — Wi-Fi spatial mapping

- [ ] Associate Wi-Fi observations with poses.
- [ ] Render sample points and per-band layers.
- [ ] Implement signal aggregation and configurable heatmap cells.
- [ ] Mark interpolation separately from measured samples.
- [ ] Expose sample density and confidence.
- [ ] Test with synthetic paths and controlled real-world recordings.

**Exit criteria:** signal maps can be regenerated from raw session data.

## Phase 6 — Network identity and mesh inference

- [ ] Group observations by SSID and BSSID without conflating them.
- [ ] Model radios and multi-band access points.
- [ ] Add vendor/OUI hints as weak evidence only.
- [ ] Add temporal and spatial evidence for candidate AP grouping.
- [ ] Model mesh/roaming relationships as inferred, confidence-scored edges.
- [ ] Add optional router integrations only when their APIs and security implications are documented.

**Exit criteria:** the UI clearly distinguishes confirmed observations from inferred topology.

## Phase 7 — Repeated walks and analytics

- [ ] Align sessions using shared trajectory features and optional anchors.
- [ ] Compare signal coverage over time.
- [ ] Detect new, missing, and changed BSSIDs.
- [ ] Add channel/frequency views where platform data permits.
- [ ] Add session replay and data-quality diagnostics.

**Exit criteria:** two sessions can be compared without destroying original measurements.

## Phase 8 — Public reusable core

- [ ] Stabilize the Rust API and versioning policy.
- [ ] Publish API docs and examples.
- [ ] Document native build targets and Kotlin integration.
- [ ] Add deterministic test fixtures and benchmarks.
- [ ] Review third-party licenses before adding dependencies.

**Exit criteria:** another application can use the core without depending on the HomeRadar Android app.

## Quality gates for every phase

- Tests for time ordering, unit conversions, coordinate transforms, and missing data.
- No fabricated scan timestamps or positions.
- No secrets, personal data, real SSIDs/BSSIDs, or home locations in fixtures.
- No scheduled GitHub Actions; local builds/tests remain first-class.
