# Localization and trajectory

## Scope

HomeRadar estimates the phone's movement and draws a path in a local coordinate space. It does not need to reconstruct rooms, walls, doors, or a floor plan.

## Sources

### Visual-inertial tracking

On supported Android devices, ARCore may provide camera/IMU-based pose tracking and tracking state. Keep ARCore in the Android adapter layer. Convert its poses and tracking-quality events into core-neutral structures.

### IMU and pedestrian dead reckoning

Use available accelerometer, gyroscope, rotation-vector, and step-counter sensors. Sensor availability varies by device. PDR is a fallback/constraint source, not a promise of drift-free absolute position.

### Wi-Fi fingerprinting

Wi-Fi fingerprints may help recognize previously visited places after enough training data has been collected. A single RSSI observation is not a reliable distance measurement. Walls, antenna orientation, people, multipath, transmit power, and device differences affect RSSI.

## Time model

Every sample should carry:
- monotonic timestamp for ordering and sensor fusion;
- optional wall-clock timestamp for display and cross-session metadata;
- source timestamp when provided by the platform;
- uncertainty or quality estimate.

Never align sensors solely by wall-clock time. Handle pauses, sleep, clock changes, and discontinuities.

## Pose model

A pose contains:
- local position (metres);
- orientation (quaternion or documented equivalent);
- coordinate-frame identifier;
- monotonic timestamp;
- tracking state;
- position/orientation uncertainty or quality metadata;
- source/provider identifier.

The UI may project the path onto X/Y. Keep vertical position when available, but do not invent floor/height values.

## Trajectory processing

Required operations:
- append timestamped poses;
- detect gaps and tracking loss;
- smooth noise without hiding uncertainty;
- segment a walk into continuous and discontinuous sections;
- replay a session;
- align repeated sessions using shared geometry or explicit user anchors;
- preserve original raw poses when producing corrected trajectories.

## Calibration and correction

Allow optional user-provided anchors, known-distance calibration, or heading reset. These are aids, not required floor-plan drawing.

## Quality presentation

Show tracking quality and data gaps. If the phone loses visual tracking, the route should visibly indicate the uncertain segment rather than drawing a falsely precise continuous line.
