//! Portable, platform-independent primitives for trajectory-first radio mapping.
//!
//! Android acquisition, camera APIs, Wi-Fi APIs and UI code belong in the
//! platform layer. This crate only processes timestamped observations.

pub mod heatmap;
pub mod model;
pub mod trajectory;
pub mod wifi;

pub use heatmap::{Heatmap, HeatmapCell, HeatmapConfig, HeatmapError};
pub use model::{
    Pose2, PositionQuality, TimestampNs, Vec2, WifiObservation, WifiObservationError,
};
pub use trajectory::{Trajectory, TrajectoryConfig, TrajectoryError, TrajectoryPoint};
pub use wifi::{group_indices_by_ssid, NetworkRelationKind};
