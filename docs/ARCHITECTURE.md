# Architecture

## 1. Product model

HomeRadar records a person's movement through an indoor space and samples radio conditions along that movement. The primary spatial artifact is a **trajectory**, not a floor plan.

A session consists of:
- a local coordinate frame;
- timestamped pose estimates;
- timestamped sensor observations;
- timestamped Wi-Fi observations;
- derived trajectory segments and heatmap data;
- quality and uncertainty metadata.

The application must remain useful without room detection, wall detection, floor-plan drawing, or a backend.

## 2. High-level layers

### Android application — Kotlin

Responsibilities:
- runtime permissions and Android lifecycle;
- Wi-Fi scan requests and system scan-result callbacks;
- collection of sensor timestamps and readings;
- camera access and ARCore session lifecycle, when available;
- converting platform data into versioned core input structures;
- project/session UI, map rendering, settings, import/export;
- presenting platform limitations and data freshness.

Android-specific classes must not leak into portable Rust domain types.

### Rust core

Responsibilities:
- validated domain models and units;
- coordinate-frame transformations;
- time alignment and interpolation between pose samples;
- sensor-fusion algorithms and quality estimates;
- trajectory smoothing, segmentation, alignment, and replay;
- Wi-Fi observation normalization;
- BSSID/radio/access-point/network-group modeling;
- probabilistic association with explicit confidence;
- spatial aggregation and heatmap generation;
- session validation, deterministic processing, and portable serialization.

The core should not own camera, Wi-Fi, permission, or Android lifecycle APIs.

### Interoperability

Preferred first prototype: UniFFI-generated Kotlin bindings. Keep the public boundary coarse-grained and versioned. Batch observations across the bridge rather than making a cross-language call for every sensor event.

If UniFFI does not fit a particular data path after benchmarking, isolate that path behind an adapter; do not spread JNI details across the codebase.

## 3. Data flow

1. Android acquires sensor, camera-tracking, and Wi-Fi observations.
2. Every observation receives a monotonic timestamp and, where useful, a wall-clock timestamp.
3. The app sends batches to the core.
4. The core validates units, timestamps, coordinate frames, and source quality.
5. Pose estimation produces a trajectory with uncertainty.
6. Wi-Fi observations are associated with a pose at the observation's actual time, using bounded interpolation only when safe.
7. The heatmap engine aggregates observations by radio/band and spatial cell.
8. The session store preserves raw observations and derived outputs separately.
9. The UI renders the track, sample points, signal layers, uncertainty, and session comparisons.

## 4. Coordinate model

Use a local metric coordinate frame, initially 2D for display:
- X/Y: horizontal position in metres;
- heading: orientation around the vertical axis;
- optional Z: relative height when a trustworthy source exists;
- origin: session-defined local origin, not a geographic coordinate.

The internal pose representation may retain 3D orientation/position even when the UI displays a 2D projection. Never imply globally accurate coordinates.

## 5. Position sources

Use a provider interface so sources can be combined or replaced:
- visual-inertial tracking provider (ARCore on supported Android devices);
- IMU/PDR fallback;
- optional manual anchors or known-distance calibration;
- optional radio fingerprint correction after sufficient reference data exists.

ARCore is an Android-side tracking provider, not a dependency of the portable core. Tracking quality and relocalization events must be passed into the core.

## 6. Sensor fusion

A staged approach is preferred:
- establish timestamp and coordinate contracts first;
- implement deterministic interpolation and quality propagation;
- add a tested baseline filter;
- compare complementary/EKF-style approaches with recorded data;
- evaluate factor-graph or SLAM integration only when real recordings demonstrate a need.

Do not claim that raw accelerometer integration provides stable long-term position. Drift must be corrected by visual tracking, anchors, or other independent observations.

## 7. Rendering

The primary map is a free 2D canvas:
- trajectory polyline;
- start/end/current-position markers;
- Wi-Fi observation points;
- per-band or per-network heatmap;
- signal sample density;
- optional uncertainty visualization.

No room polygons, walls, doors, or floor-plan assets are required. The renderer must distinguish measured values from interpolated estimates.

## 8. Privacy and security

- Local-only operation by default.
- No analytics, telemetry, accounts, or background uploads by default.
- Do not store raw camera frames unless a user explicitly enables a documented feature that requires them.
- Provide deletion and export controls.
- Avoid including personal names, home addresses, exact locations, or private network identifiers in public examples, issue templates, logs, or screenshots.
- Example datasets must be synthetic or sanitized.

## 9. Failure handling

Every source can become unavailable independently. The app should continue recording what remains available and annotate gaps:
- camera tracking lost;
- sensor unavailable or delayed;
- Wi-Fi scan throttled or stale;
- permission revoked;
- device sleep or app lifecycle interruption;
- clock discontinuity or invalid timestamps.

A partial session is still useful if missing data is represented honestly.
