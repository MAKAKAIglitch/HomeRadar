# Rust Core API design

This document defines intended boundaries, not a stabilized API.

## Design goals

- Portable Rust library; no Android types in public domain models.
- Deterministic processing for the same input and algorithm version.
- Explicit units, timestamps, coordinate frames, and uncertainty.
- Batch-oriented FFI to reduce Kotlin/Rust boundary overhead.
- Versioned public API suitable for use by other applications.
- No network access, telemetry, or cloud requirement in the core.

## Proposed crates

- `homeradar-types`: IDs, units, timestamps, poses, observations, errors, quality metadata.
- `homeradar-localize`: pose streams, sensor-fusion interfaces, interpolation, trajectory quality.
- `homeradar-wifi`: Wi-Fi observations, identity model, grouping evidence.
- `homeradar-trajectory`: track processing, segmentation, replay and alignment.
- `homeradar-heatmap`: spatial aggregation and renderer-neutral heatmap data.
- `homeradar-session`: session model, validation, import/export and persistence interfaces.
- `homeradar-ffi`: UniFFI-facing facade; keep it thin.

Crate boundaries may change after prototypes and dependency/license review.

## Boundary types

Initial API concepts:
- `SessionId`, `ObservationId`, `NetworkId`, `RadioId`, `TrackId`;
- `TimestampNs` based on a monotonic clock domain;
- `PoseSample` with local position, orientation, frame ID and quality;
- `WifiObservation` with raw and normalized radio fields;
- `NetworkRelation` with relation kind, confidence and evidence;
- `HeatmapRequest` and `HeatmapCell`;
- `SessionMetadata` and schema version.

All FFI-exposed structures must be simple, explicitly versioned, and safe to serialize.

## Suggested facade operations

- create/open/close session;
- append pose batch;
- append Wi-Fi observation batch;
- query session summary;
- build trajectory snapshot;
- build heatmap for a selected band/network/time window;
- compare two sessions;
- export/import a project archive;
- validate archive and report compatibility.

Long-running processing should expose progress/cancellation at the app layer where practical.

## Error model

Use typed errors internally. At the FFI boundary, convert them into stable error categories and human-readable messages. Never silently discard malformed observations.

## Compatibility

- Version the archive format independently from the crate version.
- Version the FFI API.
- Document breaking changes.
- Keep raw observation formats backward-readable where feasible.
- Include algorithm/version metadata with derived outputs so they can be regenerated.
