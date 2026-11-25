# Android platform constraints

## Wi-Fi scanning

Android restricts active Wi-Fi scan frequency and may return cached results. The app must treat Wi-Fi as a sparse observation source, not a continuous sensor.

Implementation requirements:
- request scans only when allowed and useful;
- listen for scan-result updates;
- record platform measurement timestamp and app receive timestamp separately;
- expose result age and scan throttling;
- never relabel a cached result as a new measurement;
- associate scans with a pose at measurement time where possible;
- preserve missing/permission-denied states in session metadata.

Android version, target SDK, location/Wi-Fi permissions, location services, device firmware and OEM behavior can affect results. Verify requirements against the current Android documentation during implementation.

## Camera and ARCore

ARCore availability depends on device support, Google Play Services for AR, camera permission, runtime state, and tracking quality. Keep it optional and provide a clear unsupported/unavailable state.

The first version should avoid retaining raw camera video. Use ARCore tracking outputs and retain only the minimum data required for the session unless the user explicitly opts into additional assets.

## Motion sensors

Sensor availability and quality vary. Query available sensors at runtime. Do not assume a hardware step counter, rotation vector, barometer, or gyroscope exists.

## Background and lifecycle

Long walks may be interrupted by screen lock, battery management, process death, permission changes, or activity lifecycle events. Define explicit recording state and recovery behavior. Do not promise uninterrupted background collection until tested across supported devices.

## Permission UX

Explain why each permission is needed at the point of use. Request only permissions required for the active feature. Handle denial and revocation without crashing or corrupting a session.

## Device capability report

Expose a diagnostics page listing:
- Android/API level;
- Wi-Fi scan availability and latest result age;
- available motion sensors;
- ARCore support/availability and tracking state;
- current recording limitations.

Do not upload this report automatically.


## Current scanner implementation notes

The initial Android scanner registers for `WifiManager.SCAN_RESULTS_AVAILABLE_ACTION` while the activity is started. On Android 13+, it uses `RECEIVER_EXPORTED` so privileged system components can deliver the Wi-Fi broadcast. The receiver then queries `WifiManager.scanResults`; it does not trust scan details embedded in the broadcast.

The app requests `ACCESS_FINE_LOCATION` at runtime before requesting or reading scan results. Scan requests remain best-effort because Android can throttle or reject them.


## Motion sensors and route prototype

The Android acquisition source listens for accelerometer, gyroscope, rotation vector, linear acceleration, pressure and the step detector when available. The step detector requires ACTIVITY_RECOGNITION on Android 10+; request it only when the user starts a route. Rotation-vector orientation is used for a relative heading; the user must hold the phone horizontally with the screen facing up and the top edge pointing forward. The initial PDR prototype applies an estimated 0.68 m stride. This is deliberately marked approximate because device orientation, magnetic interference and stride length cause drift.

A short in-memory pose history supports Wi-Fi timestamp association. Only measurements bracketed by sensor poses are placed on the route; no extrapolation is performed. The route recording currently stops when the Activity leaves the foreground. Durable sensor streams, ARCore camera pose, sensor fusion and persistence remain open.
