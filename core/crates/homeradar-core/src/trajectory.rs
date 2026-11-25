use std::fmt;

use crate::model::{Pose2, TimestampNs, Vec2};

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct TrajectoryConfig {
    /// A larger interval starts a new segment instead of drawing a misleading line.
    pub max_gap_ns: u64,
}

impl Default for TrajectoryConfig {
    fn default() -> Self {
        Self {
            max_gap_ns: 2_000_000_000,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct TrajectoryPoint {
    pub timestamp: TimestampNs,
    pub position_m: Vec2,
    pub heading_rad: f64,
    pub segment_index: u32,
}

#[derive(Clone, Debug, Default, PartialEq)]
pub struct Trajectory {
    points: Vec<TrajectoryPoint>,
    config: TrajectoryConfig,
}

impl Trajectory {
    pub fn new(config: TrajectoryConfig) -> Self {
        Self {
            points: Vec::new(),
            config,
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

    pub fn push(&mut self, pose: Pose2) -> Result<(), TrajectoryError> {
        if !pose.is_valid() {
            return Err(TrajectoryError::InvalidPose);
        }

        let (segment_index, previous) = match self.points.last() {
            None => (0, None),
            Some(last) => {
                if pose.timestamp <= last.timestamp {
                    return Err(TrajectoryError::NonMonotonicTimestamp {
                        previous: last.timestamp,
                        next: pose.timestamp,
                    });
                }
                let gap = pose.timestamp.0 - last.timestamp.0;
                let next_segment = if gap > self.config.max_gap_ns {
                    last.segment_index.saturating_add(1)
                } else {
                    last.segment_index
                };
                (next_segment, Some(*last))
            }
        };

        let _ = previous;
        self.points.push(TrajectoryPoint {
            timestamp: pose.timestamp,
            position_m: pose.position_m,
            heading_rad: pose.heading_rad,
            segment_index,
        });
        Ok(())
    }
}

impl Default for TrajectoryConfig {
    fn default() -> Self {
        Self {
            max_gap_ns: 2_000_000_000,
        }
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum TrajectoryError {
    InvalidPose,
    NonMonotonicTimestamp {
        previous: TimestampNs,
        next: TimestampNs,
    },
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
    use crate::model::PositionQuality;

    fn pose(t: u64, x: f64) -> Pose2 {
        Pose2 {
            timestamp: TimestampNs(t),
            position_m: Vec2::new(x, 0.0),
            heading_rad: 0.0,
            quality: PositionQuality::Tracked,
        }
    }

    #[test]
    fn appends_points_and_calculates_distance() {
        let mut track = Trajectory::default();
        track.push(pose(1, 0.0)).unwrap();
        track.push(pose(2, 3.0)).unwrap();
        track.push(pose(3, 7.0)).unwrap();
        assert_eq!(track.points().len(), 3);
        assert!((track.total_distance_m() - 7.0).abs() < 1e-9);
    }

    #[test]
    fn large_time_gap_starts_new_segment() {
        let mut track = Trajectory::default();
        track.push(pose(1, 0.0)).unwrap();
        track.push(pose(3_000_000_002, 10.0)).unwrap();
        assert_eq!(track.points()[0].segment_index, 0);
        assert_eq!(track.points()[1].segment_index, 1);
        assert_eq!(track.total_distance_m(), 0.0);
    }

    #[test]
    fn rejects_out_of_order_timestamps() {
        let mut track = Trajectory::default();
        track.push(pose(10, 0.0)).unwrap();
        assert!(matches!(
            track.push(pose(9, 1.0)),
            Err(TrajectoryError::NonMonotonicTimestamp { .. })
        ));
    }
}
