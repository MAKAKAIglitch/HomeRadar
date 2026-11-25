use std::collections::BTreeMap;
use std::fmt;

use crate::model::Vec2;

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct HeatmapConfig {
    /// Cell edge length in metres.
    pub cell_size_m: f64,
}

impl Default for HeatmapConfig {
    fn default() -> Self {
        Self { cell_size_m: 0.5 }
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct HeatmapCell {
    pub cell_x: i32,
    pub cell_y: i32,
    pub sample_count: u32,
    pub mean_rssi_dbm: f64,
    pub min_rssi_dbm: i16,
    pub max_rssi_dbm: i16,
    /// Center of the cell in the local trajectory coordinate frame.
    pub center_m: Vec2,
}

#[derive(Clone, Debug, PartialEq)]
pub struct Heatmap {
    pub cell_size_m: f64,
    pub cells: Vec<HeatmapCell>,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct PositionedSignalSample {
    pub position_m: Vec2,
    pub rssi_dbm: i16,
}

pub fn build_heatmap(
    samples: &[PositionedSignalSample],
    config: HeatmapConfig,
) -> Result<Heatmap, HeatmapError> {
    if !config.cell_size_m.is_finite() || config.cell_size_m <= 0.0 {
        return Err(HeatmapError::InvalidCellSize);
    }

    let mut buckets: BTreeMap<(i32, i32), (u32, i64, i16, i16)> = BTreeMap::new();
    for sample in samples {
        if !sample.position_m.is_finite() {
            return Err(HeatmapError::InvalidPosition);
        }
        if !(-127..=0).contains(&sample.rssi_dbm) {
            return Err(HeatmapError::InvalidRssi(sample.rssi_dbm));
        }

        let x = (sample.position_m.x / config.cell_size_m).floor();
        let y = (sample.position_m.y / config.cell_size_m).floor();
        if x < i32::MIN as f64 || x > i32::MAX as f64 || y < i32::MIN as f64 || y > i32::MAX as f64 {
            return Err(HeatmapError::CoordinateOutOfRange);
        }
        let key = (x as i32, y as i32);
        let entry = buckets.entry(key).or_insert((0, 0, sample.rssi_dbm, sample.rssi_dbm));
        entry.0 = entry.0.saturating_add(1);
        entry.1 += i64::from(sample.rssi_dbm);
        entry.2 = entry.2.min(sample.rssi_dbm);
        entry.3 = entry.3.max(sample.rssi_dbm);
    }

    let cells = buckets
        .into_iter()
        .map(|((cell_x, cell_y), (count, sum, min, max))| HeatmapCell {
            cell_x,
            cell_y,
            sample_count: count,
            mean_rssi_dbm: sum as f64 / f64::from(count),
            min_rssi_dbm: min,
            max_rssi_dbm: max,
            center_m: Vec2::new(
                (f64::from(cell_x) + 0.5) * config.cell_size_m,
                (f64::from(cell_y) + 0.5) * config.cell_size_m,
            ),
        })
        .collect();

    Ok(Heatmap {
        cell_size_m: config.cell_size_m,
        cells,
    })
}

#[derive(Clone, Debug, PartialEq)]
pub enum HeatmapError {
    InvalidCellSize,
    InvalidPosition,
    InvalidRssi(i16),
    CoordinateOutOfRange,
}

impl fmt::Display for HeatmapError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::InvalidCellSize => write!(f, "heatmap cell size must be finite and positive"),
            Self::InvalidPosition => write!(f, "heatmap sample position must be finite"),
            Self::InvalidRssi(value) => write!(f, "RSSI must be between -127 and 0 dBm, got {value}"),
            Self::CoordinateOutOfRange => write!(f, "sample position exceeds supported grid coordinates"),
        }
    }
}

impl std::error::Error for HeatmapError {}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn aggregates_only_cells_with_real_samples() {
        let samples = [
            PositionedSignalSample { position_m: Vec2::new(0.1, 0.1), rssi_dbm: -50 },
            PositionedSignalSample { position_m: Vec2::new(0.2, 0.2), rssi_dbm: -70 },
            PositionedSignalSample { position_m: Vec2::new(1.1, 0.1), rssi_dbm: -80 },
        ];
        let map = build_heatmap(&samples, HeatmapConfig { cell_size_m: 1.0 }).unwrap();
        assert_eq!(map.cells.len(), 2);
        assert_eq!(map.cells[0].sample_count, 2);
        assert_eq!(map.cells[0].mean_rssi_dbm, -60.0);
        assert_eq!(map.cells[0].min_rssi_dbm, -70);
        assert_eq!(map.cells[0].max_rssi_dbm, -50);
    }

    #[test]
    fn rejects_invalid_cell_size() {
        assert_eq!(
            build_heatmap(&[], HeatmapConfig { cell_size_m: 0.0 }),
            Err(HeatmapError::InvalidCellSize)
        );
    }
}
