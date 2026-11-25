# HomeRadar

**HomeRadar** is an Android-first, local-first indoor radio-mapping project. Walk through a building with a phone and HomeRadar records the estimated path, samples nearby wireless networks, and builds signal maps along that path.

The project is designed around a reusable **Rust core** and a polished **Kotlin / Jetpack Compose** Android application.

> **No floor plan is required.** HomeRadar maps the walk itself: a trajectory in a local coordinate space, with radio measurements attached to positions. A user may optionally add labels or reference points, but drawing rooms, walls, or doors is not part of the required workflow.

## Goals

- Draw and save a 2D walking trajectory without requiring a floor plan.
- Attach Wi-Fi observations to positions with timestamps and uncertainty.
- Visualize signal strength as heatmaps along and around the recorded path.
- Distinguish SSIDs, BSSIDs, radios, physical access points, and inferred network groups.
- Handle mesh systems, roaming, multi-band radios, repeated BSSIDs, and changing network layouts without unsafe assumptions.
- Save immutable scan sessions and compare repeated walks over time.
- Fuse camera-based visual-inertial tracking, phone motion sensors, and radio observations.
- Keep user data on-device by default; provide explicit export/import.
- Make the Rust core reusable by other applications independently of Android.

## Current project status

This repository contains the first portable Rust core and an initial Android application with a Material 3 Wi-Fi scanner. The walking-position provider (ARCore/IMU), live trajectory screen, persistence and Rust/Kotlin bridge are not implemented yet. See [docs/ROADMAP.md](docs/ROADMAP.md) for verified progress.

## Architecture

- **Android app (Kotlin):** permissions, Wi-Fi scan access, camera/AR session, IMU acquisition, lifecycle, UI, project management.
- **Rust core:** domain models, time/coordinate handling, sensor fusion, trajectory processing, network identity/grouping, heatmap generation, session validation and export.
- **Bridge:** UniFFI is the preferred Rust ↔ Kotlin boundary, subject to a small integration prototype.
- **UI:** Jetpack Compose + Material 3 / Material You.
- **Persistence:** SQLite for indexed observations and metadata; files for larger session assets; portable `.homeradar` project archive.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), [docs/CORE-API.md](docs/CORE-API.md), [docs/PROJECT-FORMAT.md](docs/PROJECT-FORMAT.md), and the dated [research notes](docs/research/25-11-2026.md).

## Core principles

1. **Trajectory first:** no mandatory floor-plan creation or room reconstruction.
2. **Measurements are evidence:** retain raw observations and their timestamps; never silently present stale scans as live data.
3. **Uncertainty is visible:** positions, associations, and heatmap values carry confidence/quality metadata.
4. **Inference is not fact:** shared SSIDs and similar signal patterns do not prove that radios belong to the same mesh.
5. **Repeatable sessions:** preserve raw sessions so algorithms can be improved and maps regenerated later.
6. **Local-first:** no account, cloud backend, telemetry, or automatic upload is required.
7. **Reusable core:** Android-specific APIs stay outside the portable Rust domain layer.

## Planned capabilities

- Live walk recording and trajectory visualization.
- Wi-Fi observations: SSID, BSSID, frequency, channel where available, RSSI, capabilities, timestamp, and source.
- Separate 2.4 GHz, 5 GHz, and 6 GHz layers where the device and OS expose the data.
- Network grouping with confidence and evidence.
- Mesh/roaming analysis using observed behavior and optional router integrations.
- Signal heatmaps with interpolation limits and sample-density overlays.
- Session replay, route alignment, and before/after comparison.
- Optional AR view for showing the current track and nearby observations.
- JSON/CSV exports and complete project archive import/export.

## Platform reality

Android controls Wi-Fi scan frequency and the availability of some radio metadata. Camera/IMU tracking can run at a higher rate than Wi-Fi scanning, so each Wi-Fi sample must be associated with a nearby pose while preserving its actual timestamp and uncertainty. See [docs/ANDROID-PLATFORM.md](docs/ANDROID-PLATFORM.md).

A phone generally cannot discover a complete mesh topology from passive Wi-Fi scans alone. HomeRadar will distinguish observed facts from inferred relationships and may add opt-in integrations for routers that expose topology information.

## Repository layout

```text
android/                 Kotlin Android application
core/                    Rust workspace
  crates/
    homeradar-types/     Shared domain types
    homeradar-localize/  Pose and sensor-fusion interfaces/algorithms
    homeradar-wifi/      Wi-Fi observations and network identity
    homeradar-trajectory/Track processing and alignment
    homeradar-heatmap/   Spatial signal aggregation/render data
    homeradar-session/   Session persistence and validation
    homeradar-ffi/       UniFFI-facing API
docs/                    Specifications and engineering decisions
examples/                Core usage examples
```

The Rust crate contains initial domain types, trajectory processing/interpolation, conservative SSID grouping, frequency-band classification, spatial RSSI aggregation, and unit tests. Android currently acquires and displays Wi-Fi scan results with freshness metadata. Camera/IMU localization, mapping UI, persistence and FFI remain planned.

## Development status

See [the roadmap](docs/ROADMAP.md) for phased implementation and acceptance criteria. No scheduled GitHub Actions are required; local builds and tests are supported.

## License

The project is intended to use the MIT License. See [LICENSE](LICENSE).
