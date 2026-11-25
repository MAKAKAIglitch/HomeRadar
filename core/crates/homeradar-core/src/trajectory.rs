use std::fmt;

use crate::model::{Pose2, PositionQuality, TimestampNs, Vec2};

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct TrajectoryConfig {
    /// A larger interval starts a new segment instead of drawing a misleading line.
    pub max_gap_ns: u64,
}

impl Default for TrajectoryConfig {
    fn default() -> Self {
        Self { max_gap_ns: 2_000_000_000 }
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct TrajectoryPoint {
    pub timestamp: TimestampNs,
    pub position_m: Vec2,
    pub heading_rad: f64,
    pub quality: PositionQuality,
    pub segment_index: u32,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct InterpolatedPose {
    pub timestamp: TimestampNs,
    pub position_m: Vec2,
    pub heading_rad: f64,
    pub quality: PositionQuality,
    /// False for an exact recorded pose; true for interpolation between two poses.
    pub interpolated: bool,
}

#[derive(Clone, Debug, Default, PartialEq)]
pub struct Trajectory {
    points: Vec<TrajectoryPoint>,
    config: TrajectoryConfig,
    last_timestamp: Option<TimestampNs>,
    break_pending: bool,
}

impl Trajectory {
    pub fn new(config: TrajectoryConfig) -> Self {
        Self {
            points: Vec::new(),
            config,
            last_timestamp: None,
            break_pending: false,
        }
    }

    pub fn points(&self) -> &[TrajectoryPoint] {
        &self.points
    }

    pub fn is_empty(&self) -> bool {
        self.points.is_empty()
    }

    pub fn total_distance_m(&self) -> f64 {
        self.points
            .windows(2)
            .filter(|pair| pair[0].segment_index == pair[1].segment_index)
            .map(|pair| pair[0].position_m.distance(pair[1].position_m))
            .sum()
    }

    /// Returns a pose at an observation timestamp only when it is exactly
    /// recorded or safely bracketed by two points in the same trajectory segment.
    /// This deliberately performs no extrapolation across gaps or tracking loss.
    pub fn pose_at(
        &self,
        timestamp: TimestampNs,
        max_bracket_gap_ns: u64,
    ) -> Option<InterpolatedPose> {
        if let Some(point) = self.points.iter().find(|point| point.timestamp == timestamp) {
            return Some(InterpolatedPose {
                timestamp,
                position_m: point.position_m,
                heading_rad: point.heading_rad,
                quality: point.quality,
                interpolated: false,
            });
        }

        for pair in self.points.windows(2) {
            let left = pair[0];
            let right = pair[1];
            if timestamp <= left.timestamp || timestamp >= right.timestamp {
                continue;
            }
            if left.segment_index != right.segment_index {
                return None;
            }

            let span = right.timestamp.0.saturating_sub(left.timestamp.0);
            if span == 0 || span > max_bracket_gap_ns {
                return None;
            }

            let offset = timestamp.0.saturating_sub(left.timestamp.0);
            let t = offset as f64 / span as f64;
            let angle_delta = (right.heading_rad - left.heading_rad + std::f64::consts::PI)
                .rem_euclid(std::f64::consts::TAU)
                - std::f64::consts::PI;
            let heading = left.heading_rad + angle_delta * t;
            let quality = if left.quality == PositionQuality::Tracked
                && right.quality == PositionQuality::Tracked
            {
                PositionQuality::Tracked
            } else {
                PositionQuality::Estimated
            };

            return Some(InterpolatedPose {
                timestamp,
                position_m: left.position_m.lerp(right.position_m, t),
                heading_rad: heading,
                quality,
                interpolated: true,
            });
        }
        None
    }

    /// Adds a pose. Lost tracking creates a discontinuity and is never drawn
    /// as a fresh position using a stale last-known coordinate.
    pub fn push(&mut self, pose: Pose2) -> Result<(), TrajectoryError> {
        if !pose.is_valid() {
            return Err(TrajectoryError::InvalidPose);
        }

        if let Some(previous) = self.last_timestamp {
            if pose.timestamp <= previous {
                return Err(TrajectoryError::NonMonotonicTimestamp {
                    previous,
                    next: pose.timestamp,
                });
            }
        }

        let previous_timestamp = self.last_timestamp;
        self.last_timestamp = Some(pose.timestamp);

        if pose.quality == PositionQuality::Lost {
            self.break_pending = true;
            return Ok(());
        }

        let segment_index = match self.points.last() {
            None => 0,
            Some(last) => {
                let gap = previous_timestamp
                    .map(|previous| pose.timestamp.0.saturating_sub(previous.0))
                    .unwrap_or_else(|| pose.timestamp.0.saturating_sub(last.timestamp.0));
                if self.break_pending || gap > self.config.max_gap_ns {
                    last.segment_index.saturating_add(1)
                } else {
                    last.segment_index
                }
            }
        };

        self.break_pending = false;
        self.points.push(TrajectoryPoint {
            timestamp: pose.timestamp,
            position_m: pose.position_m,
            heading_rad: pose.heading_rad,
            quality: pose.quality,
            segment_index,
        });
        Ok(())
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum TrajectoryError {
    InvalidPose,
    NonMonotonicTimestamp { previous: TimestampNs, next: TimestampNs },
}

impl fmt::Display for TrajectoryError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::InvalidPose => write!(f, "pose contains a non-finite coordinate or heading"),
            Self::NonMonotonicTimestamp { previous, next } => write!(
                f,
                "trajectory timestamps must increase: previous {}, next {}",
                previous.0, next.0
            ),
        }
    }
}

impl std::error::Error for TrajectoryError {}

#[cfg(test)]
mod tests {
    use super::*;

    fn pose(t: u64, x: f64, quality: PositionQuality) -> Pose2 {
        Pose2 {
            timestamp: TimestampNs(t),
            position_m: Vec2::new(x, 0.0),
            heading_rad: 0.0,
            quality,
        }
    }

    #[test]
    fn appends_points_and_calculates_distance() {
        let mut track = Trajectory::default();
        track.push(pose(1, 0.0, PositionQuality::Tracked)).unwrap();
        track.push(pose(2, 3.0, PositionQuality::Tracked)).unwrap();
        track.push(pose(3, 7.0, PositionQuality::Tracked)).unwrap();
        assert_eq!(track.points().len(), 3);
        assert!((track.total_distance_m() - 7.0).abs() < 1e-9);
    }

    #[test]
    fn large_time_gap_starts_new_segment() {
        let mut track = Trajectory::default();
        track.push(pose(1, 0.0, PositionQuality::Tracked)).unwrap();
        track.push(pose(3_000_000_002, 10.0, PositionQuality::Tracked)).unwrap();
        assert_eq!(track.points()[0].segment_index, 0);
        assert_eq!(track.points()[1].segment_index, 1);
        assert_eq!(track.total_distance_m(), 0.0);
    }

    #[test]
    fn tracking_loss_starts_new_segment_without_fabricating_a_point() {
        let mut track = Trajectory::default();
        track.push(pose(1, 0.0, PositionQuality::Tracked)).unwrap();
        track.push(pose(2, 0.0, PositionQuality::Lost)).unwrap();
        track.push(pose(3, 5.0, PositionQuality::Tracked)).unwrap();
        assert_eq!(track.points().len(), 2);
        assert_eq!(track.points()[1].segment_index, 1);
        assert_eq!(track.total_distance_m(), 0.0);
    }

    #[test]
    fn rejects_out_of_order_timestamps_including_lost_samples() {
        let mut track = Trajectory::default();
        track.push(pose(10, 0.0, PositionQuality::Tracked)).unwrap();
        track.push(pose(11, 0.0, PositionQuality::Lost)).unwrap();
        assert!(matches!(
            track.push(pose(10, 1.0, PositionQuality::Tracked)),
            Err(TrajectoryError::NonMonotonicTimestamp { .. })
        ));
    }

    #[test]
    fn interpolates_wifi_time_between_two_valid_poses() {
        let mut track = Trajectory::default();
        track.push(pose(1_000, 0.0, PositionQuality::Tracked)).unwrap();
        track.push(pose(2_000, 10.0, PositionQuality::Tracked)).unwrap();
        let at = track.pose_at(TimestampNs(1_500), 1_500).unwrap();
        assert!(at.interpolated);
        assert_eq!(at.position_m, Vec2::new(5.0, 0.0));
        assert_eq!(at.quality, PositionQuality::Tracked);
    }

    #[test]
    fn refuses_interpolation_across_tracking_gap() {
        let mut track = Trajectory::default();
        track.push(pose(1_000, 0.0, PositionQuality::Tracked)).unwrap();
        track.push(pose(1_500, 0.0, PositionQuality::Lost)).unwrap();
        track.push(pose(2_000, 10.0, PositionQuality::Tracked)).unwrap();
        assert!(track.pose_at(TimestampNs(1_750), 2_000).is_none());
    }
}
