package org.homeradar.app

data class RoutePoint(
    val xMeters: Double,
    val yMeters: Double,
    val timestampNs: Long
)

data class RoutePosition(
    val xMeters: Double,
    val yMeters: Double
)

data class WifiRouteMarker(
    val id: String,
    val ssid: String,
    val bssid: String,
    val rssiDbm: Int,
    val frequencyMhz: Int,
    val xMeters: Double,
    val yMeters: Double,
    val measuredAtNs: Long
)
