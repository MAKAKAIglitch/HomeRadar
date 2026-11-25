use homeradar_core::{
    build_heatmap, HeatmapConfig, Pose2, PositionQuality, PositionedSignalSample,
    TimestampNs, Trajectory, Vec2,
};

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut trajectory = Trajectory::default();
    let poses = [
        Pose2 {
            timestamp: TimestampNs(1_000_000_000),
            position_m: Vec2::new(0.0, 0.0),
            heading_rad: 0.0,
            quality: PositionQuality::Tracked,
        },
        Pose2 {
            timestamp: TimestampNs(1_500_000_000),
            position_m: Vec2::new(0.5, 0.0),
            heading_rad: 0.0,
            quality: PositionQuality::Tracked,
        },
        Pose2 {
            timestamp: TimestampNs(2_000_000_000),
            position_m: Vec2::new(1.0, 0.25),
            heading_rad: 0.2,
            quality: PositionQuality::Tracked,
        },
        Pose2 {
            timestamp: TimestampNs(2_500_000_000),
            position_m: Vec2::new(1.5, 0.5),
            heading_rad: 0.3,
            quality: PositionQuality::Tracked,
        },
    ];

    for pose in poses {
        trajectory.push(pose)?;
    }

    let signal_samples = [
        PositionedSignalSample {
            position_m: Vec2::new(0.0, 0.0),
            rssi_dbm: -48,
        },
        PositionedSignalSample {
            position_m: Vec2::new(0.5, 0.0),
            rssi_dbm: -55,
        },
        PositionedSignalSample {
            position_m: Vec2::new(1.0, 0.25),
            rssi_dbm: -67,
        },
        PositionedSignalSample {
            position_m: Vec2::new(1.5, 0.5),
            rssi_dbm: -73,
        },
    ];

    let heatmap = build_heatmap(
        &signal_samples,
        HeatmapConfig { cell_size_m: 0.5 },
    )?;

    println!("Synthetic trajectory points: {}", trajectory.points().len());
    println!("Trajectory distance: {:.2} m", trajectory.total_distance_m());
    println!("Measured heatmap cells: {}", heatmap.cells.len());

    for cell in heatmap.cells {
        println!(
            "cell ({}, {}): mean {:.1} dBm, {} samples",
            cell.cell_x, cell.cell_y, cell.mean_rssi_dbm, cell.sample_count
        );
    }

    Ok(())
}
