use std::fmt;

/// Monotonic timestamp in nanoseconds from one documented clock domain.
///
/// Do not mix timestamps from different clock domains. Android SensorEvent
/// timestamps use the elapsed-realtime monotonic time base.
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq, Ord, PartialOrd, Hash)]
pub struct TimestampNs(pub u64);

#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct Vec2 {
    pub x: f64,
    pub y: f64,
}

impl Vec2 {
    pub const ZERO: Self = Self { x: 0.0, y: 0.0 };

    pub const fn new(x: f64, y: f64) -> Self {
        Self { x, y }
    }

    pub fn is_finite(self) -> bool {
        self.x.is_finite() && self.y.is_finite()
    }

    pub fn distance(self, other: Self) -> f64 {
        let dx = self.x - other.x;
        let dy = self.y - other.y;
        (dx * dx + dy * dy).sqrt()
    }

    pub fn lerp(self, other: Self, t: f64) -> Self {
        Self::new(
            self.x + (other.x - self.x) * t,
            self.y + (other.y - self.y) * t,
        )
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub enum PositionQuality {
    /// Pose is supplied by a currently tracking visual-inertial provider.
    Tracked,
    /// Pose is estimated from inertial/PDR data and may drift.
    Estimated,
    /// Tracking is currently unavailable; position is only a last-known value.
    Lost,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Pose2 {
    pub timestamp: TimestampNs,
    pub position_m: Vec2,
    pub heading_rad: f64,
    pub quality: PositionQuality,
}

impl Pose2 {
    pub fn is_valid(&self) -> bool {
        self.position_m.is_finite() && self.heading_rad.is_finite()
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, Ord, PartialOrd, Hash)]
pub enum WifiBand {
    Ghz2_4,
    Ghz5,
    Ghz6,
    Unknown,
}

impl WifiBand {
    /// Classifies common Wi-Fi bands from the observed center frequency in MHz.
    /// This is a broad display classification, not a regulatory-channel validator.
    pub fn from_frequency_mhz(frequency_mhz: u32) -> Self {
        match frequency_mhz {
            2400..=2500 => Self::Ghz2_4,
            4900..=5924 => Self::Ghz5,
            5925..=7125 => Self::Ghz6,
            _ => Self::Unknown,
        }
    }
}

#[derive(Clone, Debug, PartialEq)]
pub struct WifiObservation {
    /// Time reported by the platform for the radio measurement.
    pub observed_at: TimestampNs,
    /// Time at which the app received this result.
    pub received_at: TimestampNs,
    pub ssid: Option<String>,
    pub bssid: Option<String>,
    pub rssi_dbm: i16,
    pub frequency_mhz: u32,
    pub capabilities: Option<String>,
}

impl WifiObservation {
    pub fn validate(&self) -> Result<(), WifiObservationError> {
        if self.rssi_dbm < -127 || self.rssi_dbm > 0 {
            return Err(WifiObservationError::InvalidRssi(self.rssi_dbm));
        }
        if self.frequency_mhz == 0 {
            return Err(WifiObservationError::MissingFrequency);
        }
        if self.received_at < self.observed_at {
            return Err(WifiObservationError::ReceiveBeforeObservation);
        }
        Ok(())
    }

    pub fn age_at_receive_ns(&self) -> u64 {
        self.received_at.0.saturating_sub(self.observed_at.0)
    }

    pub fn band(&self) -> WifiBand {
        WifiBand::from_frequency_mhz(self.frequency_mhz)
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum WifiObservationError {
    InvalidRssi(i16),
    MissingFrequency,
    ReceiveBeforeObservation,
}

impl fmt::Display for WifiObservationError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::InvalidRssi(value) => write!(f, "RSSI must be between -127 and 0 dBm, got {value}"),
            Self::MissingFrequency => write!(f, "Wi-Fi frequency must be greater than zero"),
            Self::ReceiveBeforeObservation => write!(f, "receive timestamp precedes observation timestamp"),
        }
    }
}

impl std::error::Error for WifiObservationError {}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn vector_distance_is_euclidean() {
        assert!((Vec2::new(0.0, 0.0).distance(Vec2::new(3.0, 4.0)) - 5.0).abs() < 1e-9);
    }

    #[test]
    fn wifi_age_uses_measurement_timestamp() {
        let observation = WifiObservation {
            observed_at: TimestampNs(10),
            received_at: TimestampNs(35),
            ssid: Some("synthetic".into()),
            bssid: None,
            rssi_dbm: -60,
            frequency_mhz: 2437,
            capabilities: None,
        };
        assert_eq!(observation.age_at_receive_ns(), 25);
        assert_eq!(observation.band(), WifiBand::Ghz2_4);
        assert!(observation.validate().is_ok());
    }

    #[test]
    fn classifies_common_wifi_bands() {
        assert_eq!(WifiBand::from_frequency_mhz(5180), WifiBand::Ghz5);
        assert_eq!(WifiBand::from_frequency_mhz(5975), WifiBand::Ghz6);
        assert_eq!(WifiBand::from_frequency_mhz(1234), WifiBand::Unknown);
    }

    #[test]
    fn invalid_wifi_timestamps_are_rejected() {
        let observation = WifiObservation {
            observed_at: TimestampNs(20),
            received_at: TimestampNs(10),
            ssid: None,
            bssid: None,
            rssi_dbm: -60,
            frequency_mhz: 5180,
            capabilities: None,
        };
        assert_eq!(
            observation.validate(),
            Err(WifiObservationError::ReceiveBeforeObservation)
        );
    }
}
