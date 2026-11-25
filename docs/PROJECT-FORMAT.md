# HomeRadar project format

## File extension

The portable project container is `.homeradar`. The initial implementation should use a documented ZIP container with a versioned manifest. Do not make the extension imply that every file inside is JSON or that derived maps are permanent source data.

## Proposed layout

```text
Example.homeradar
  manifest.json
  database.sqlite
  assets/
    optional/
  exports/
```

The SQLite database stores indexed sessions, raw observations, poses, network identities, relations, and derived metadata. Large optional assets may live under `assets/`.

## Principles

- Raw observations are immutable once recorded, except through explicit redaction/deletion.
- Derived trajectories and heatmaps can be regenerated from raw data.
- Every table and serialized record is schema-versioned or covered by a database migration version.
- Store units explicitly in schema documentation.
- Preserve unknown fields when safe to do so, to improve forward compatibility.
- Validate archive paths and sizes during import; do not extract arbitrary paths.
- Import must not execute code or scripts.
- Export should allow users to omit optional camera-related assets.

## Initial entities

- project metadata;
- sessions and session state;
- pose samples;
- sensor samples or summarized sensor batches;
- Wi-Fi observations;
- network identities and radio descriptors;
- network-group relations with confidence/evidence;
- trajectory versions;
- heatmap configurations/results;
- algorithm and schema versions.

## SQLite guidance

Use integer monotonic timestamps and explicit clock-domain identifiers. Add indexes for session/time, BSSID/time, frequency/time, and spatial-cell queries after measuring actual workloads. Avoid premature denormalization.

## Privacy

Project archives may contain sensitive information about a home and nearby wireless networks. Keep them local by default. Warn before sharing, provide a sanitization/export mode, and never include real user scans in public test fixtures.


## Current Android database prototype

The initial local SQLite schema is version 1 and currently stores:

- `sessions`: session start/end wall-clock metadata, lifecycle status, step count and estimated distance;
- `route_points`: ordered local XY points and monotonic sensor timestamps;
- `wifi_samples`: SSID/BSSID, RSSI, frequency, monotonic measurement timestamp and associated local XY position.

Foreign keys cascade child rows when a session is removed. The current app keeps this database local and does not upload its contents. The schema does not yet store raw camera frames or the full IMU stream. Future schema changes must add explicit migrations; the current helper intentionally does not drop or silently recreate an older database.
