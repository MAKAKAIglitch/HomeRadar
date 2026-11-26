# Roadmap

This roadmap defines a sequence of deliverable milestones. It is a plan, not a claim that the features already exist.

## Phase 0 — Specification and contracts

- [x] Define trajectory-first product scope; no required floor plan.
- [x] Separate Android acquisition from portable Rust processing.
- [x] Define Wi-Fi identity entities and uncertainty requirements.
- [x] Document Android scan limitations and local-first storage.
- [x] Agree on initial Rust/Kotlin toolchain and minimum Android API level.
- [x] Add initial architecture decision records.

**Exit criteria:** domain contracts and platform constraints are reviewed before implementation.

## Phase 1 — Rust domain core

- [x] Create initial Rust workspace and core crate.
- [x] Define initial monotonic timestamps, local 2D pose and quality types.
- [x] Define initial Wi-Fi observation type, frequency-band classification and conservative SSID display grouping.
- [ ] Expand to explicit BSSID, radio, access-point, and network-group entities.
- [x] Add input validation and unit tests for timestamps, trajectory gaps, tracking loss, pose interpolation, Wi-Fi bands, RSSI aggregation and SSID grouping.
- [ ] Add serialization tests when the storage format is implemented.
- [x] Add synthetic unit-test observations without real home/network information.

**Status:** initial standard-library-only core is implemented. Run `cd core && cargo test --workspace` locally; CI/host compilation has not yet been run in this environment. Serialization and the Android bridge remain open.

## Phase 2 — Android acquisition prototype

- [x] Create initial Kotlin Android app using Jetpack Compose and Material 3.
- [x] Implement initial fine-location permission request and Activity receiver lifecycle.
- [x] Collect Wi-Fi scan results and scan-result update events; distinguish updated from stale/cached results.
- [x] Receive accelerometer, gyroscope, rotation-vector, linear-acceleration, pressure and step-detector events when available.
- [x] Request ACTIVITY_RECOGNITION at point of use on Android 10+ for the hardware step detector.
- [x] Preserve ScanResult measurement timestamp separately from receive time in the Android acquisition model.
- [x] Show observed SSID/BSSID, RSSI, frequency band and freshness status in the initial Material 3 screen.
- [x] Detect the currently connected Wi-Fi SSID where Android permissions allow it; allow choosing a target SSID or all networks.
- [x] Filter route markers by the selected SSID while preserving BSSID-level observations.

**Status:** the foreground prototype records a step-and-heading PDR estimate and renders it on a blank 2D canvas. Route points and linked Wi-Fi samples are persisted in SQLite. The fixed 0.68 m stride is only an initial approximation. Full raw sensor recording, ARCore pose tracking and sensor fusion remain open. Android build has not been executed in this environment.

## Phase 3 — Session format and persistence

- [x] Implement initial versioned SQLite schema for sessions, route points and mapped Wi-Fi samples (migration paths are added with future schema versions).
- [ ] Store the full raw IMU stream separately from derived route points.
- [x] Implement session start, finish and recovery of interrupted sessions; pause/resume remains open.
- [x] Implement versioned `.homeradar` single-session archive import/export (ZIP: manifest + session JSON; raw IMU/assets are not included).
- [ ] Add corruption and compatibility tests.

**Status:** route points and Wi-Fi samples are persisted locally; saved passes can be reopened and exported/imported as validated version-1 single-session `.homeradar` archives. Raw IMU streams, pause/resume, full-project archives and corruption/compatibility test coverage remain open.

**Exit criteria:** a recorded session survives app restart and can be exported/imported.

## Phase 4 — Trajectory tracking

- [x] Add ARCore session adapter and full-screen camera background on supported devices.
- [x] Read ARCore camera pose only while TrackingState.TRACKING and convert horizontal X/Z to the local route plane.
- [ ] Add explicit tracking-quality timeline, relocalization events and recovery markers.
- [ ] Add IMU/PDR fallback selection with explicit drift warnings for devices without ARCore.
- [x] Implement bounded pose interpolation for asynchronous Wi-Fi observations; reject interpolation across gaps or tracking loss.
- [x] Draw the live estimated path on a blank 2D canvas and persist AR route points through the session store.
- [ ] Add manual origin/reset and optional calibration anchors.
- [ ] Validate ARCore frame lifecycle, pose stability and Wi-Fi timestamp association on physical devices.

**Status:** ARCore camera rendering and pose-to-route acquisition are implemented in Kotlin. The same ARCore session supplies both the camera image and camera pose; CameraX has been removed to avoid opening the camera twice. Pose callbacks use Android elapsed-realtime at frame-processing time so Wi-Fi scan timestamps can be associated on the same clock. This timing is approximate and still requires device validation. Walls/floor-plan reconstruction, relocalization quality, calibration, and automatic non-AR fallback remain open.

**Exit criteria:** a walk is represented as a timestamped trajectory with quality metadata.

## Phase 5 — Wi-Fi spatial mapping

- [x] Associate fresh Wi-Fi measurements with a route position only when their timestamps are bracketed by sensor poses; reject unbracketed measurements.
- [x] Render Wi-Fi sample points along the route using RSSI strength colours (per-band layers remain open).
- [ ] Implement signal aggregation and configurable heatmap cells.
- [ ] Mark interpolation separately from measured samples.
- [ ] Expose sample density and confidence.
- [ ] Test with synthetic paths and controlled real-world recordings.

**Status:** the Android prototype renders and persists fresh scan samples as points on the estimated route. Rust/Core heatmap processing exists, but per-network/per-band selection, configurable cell aggregation, density/confidence overlays and full regeneration from raw session observations are not yet wired to Android.

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
