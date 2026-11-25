package org.homeradar.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private val screenState = mutableStateOf(WifiScanUiState())
    private lateinit var wifiScanner: WifiScanDataSource
    private lateinit var motionSensors: MotionSensorDataSource
    private val mappedMarkerIds = mutableSetOf<String>()

    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        screenState.value = screenState.value.copy(
            hasPermission = granted,
            status = if (granted) "Разрешение получено. Запрашиваем сканирование…" else
                "Для чтения результатов Wi-Fi Android требует разрешение на геолокацию."
        )
        if (granted) wifiScanner.requestScan()
    }

    private val activityRecognitionPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startRoute()
        } else {
            screenState.value = screenState.value.copy(
                routeStatus = "Без разрешения «Физическая активность» датчик шагов недоступен."
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        motionSensors = MotionSensorDataSource(this) { snapshot ->
            screenState.value = screenState.value.copy(
                availableMotionSensors = snapshot.availableSensors,
                motionSampleCount = snapshot.receivedSampleCount,
                lastMotionSensor = snapshot.lastSensorName,
                lastMotionValues = snapshot.lastValues,
                isRecording = snapshot.isRecording,
                routePoints = snapshot.routePoints,
                routeSteps = snapshot.routeSteps,
                routeDistanceMeters = snapshot.routeDistanceMeters,
                routeStatus = snapshot.routeStatus
            )
        }

        wifiScanner = WifiScanDataSource(this) { snapshot ->
            val current = screenState.value
            val newMarkers = mutableListOf<WifiRouteMarker>()
            var unmappedCount = 0

            if (snapshot.resultsFresh && current.routePoints.isNotEmpty()) {
                snapshot.networks.forEach { network ->
                    val measuredAt = network.measuredAtNs
                    val position = measuredAt?.let(motionSensors::positionAt)
                    if (measuredAt == null || position == null) {
                        unmappedCount += 1
                    } else {
                        val markerId = "${network.bssid}@$measuredAt"
                        if (mappedMarkerIds.add(markerId)) {
                            newMarkers += WifiRouteMarker(
                                id = markerId,
                                ssid = network.ssid,
                                bssid = network.bssid,
                                rssiDbm = network.rssiDbm,
                                frequencyMhz = network.frequencyMhz,
                                xMeters = position.xMeters,
                                yMeters = position.yMeters,
                                measuredAtNs = measuredAt
                            )
                        }
                    }
                }
            }

            screenState.value = current.copy(
                status = snapshot.status,
                networks = snapshot.networks,
                resultsFresh = snapshot.resultsFresh,
                lastReceivedAtNs = snapshot.receivedAtNs,
                wifiMarkers = (current.wifiMarkers + newMarkers).takeLast(MAX_WIFI_MARKERS),
                unmappedWifiSamples = current.unmappedWifiSamples + unmappedCount
            )
        }

        screenState.value = screenState.value.copy(
            hasPermission = hasLocationPermission()
        )

        setContent {
            HomeRadarTheme {
                HomeRadarScreen(
                    state = screenState.value,
                    onScanClick = ::requestScan,
                    onRouteClick = ::toggleRoute
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        screenState.value = screenState.value.copy(hasPermission = hasLocationPermission())
        wifiScanner.register()
        motionSensors.register()
    }

    override fun onStop() {
        if (motionSensors.recording) motionSensors.stopRoute()
        motionSensors.unregister()
        wifiScanner.unregister()
        super.onStop()
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    private fun hasActivityRecognitionPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED

    private fun requestScan() {
        if (!hasLocationPermission()) {
            locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }
        wifiScanner.requestScan()
    }

    private fun toggleRoute() {
        if (screenState.value.isRecording) {
            motionSensors.stopRoute()
            return
        }

        if (!hasActivityRecognitionPermission()) {
            activityRecognitionPermission.launch(Manifest.permission.ACTIVITY_RECOGNITION)
            return
        }
        startRoute()
    }

    private fun startRoute() {
        mappedMarkerIds.clear()
        screenState.value = screenState.value.copy(
            wifiMarkers = emptyList(),
            unmappedWifiSamples = 0
        )
        motionSensors.startRoute()
    }

    private companion object {
        const val MAX_WIFI_MARKERS = 3_000
    }
}
