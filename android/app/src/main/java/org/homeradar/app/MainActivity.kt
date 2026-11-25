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
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val screenState = mutableStateOf(WifiScanUiState())
    private lateinit var wifiScanner: WifiScanDataSource
    private lateinit var motionSensors: MotionSensorDataSource
    private lateinit var sessionDatabase: HomeRadarDatabase
    private val databaseExecutor = Executors.newSingleThreadExecutor()
    private val mappedMarkerIds = mutableSetOf<String>()

    private var activeSessionId: Long? = null
    private var persistedRoutePointCount = 0

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

        sessionDatabase = HomeRadarDatabase(this)
        databaseExecutor.execute {
            runCatching {
                sessionDatabase.recoverInterruptedSessions()
                sessionDatabase.listSessions()
            }.onSuccess { sessions ->
                runOnUiThread {
                    screenState.value = screenState.value.copy(savedSessions = sessions)
                }
            }.onFailure { error ->
                runOnUiThread {
                    screenState.value = screenState.value.copy(
                        routeStatus = "Не удалось открыть локальную базу: ${error.javaClass.simpleName}"
                    )
                }
            }
        }

        motionSensors = MotionSensorDataSource(this, ::onMotionSnapshot)
        wifiScanner = WifiScanDataSource(this, ::onWifiSnapshot)

        screenState.value = screenState.value.copy(
            hasPermission = hasLocationPermission()
        )

        setContent {
            HomeRadarTheme {
                HomeRadarScreen(
                    state = screenState.value,
                    onScanClick = ::requestScan,
                    onRouteClick = ::toggleRoute,
                    onOpenSession = ::openSavedSession
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

    override fun onDestroy() {
        if (::sessionDatabase.isInitialized && !databaseExecutor.isShutdown) {
            databaseExecutor.execute { sessionDatabase.close() }
            databaseExecutor.shutdown()
        }
        super.onDestroy()
    }

    private fun onMotionSnapshot(snapshot: MotionSensorSnapshot) {
        val previous = screenState.value
        val viewingSavedSession = previous.selectedSessionId != null

        var updated = previous.copy(
            availableMotionSensors = snapshot.availableSensors,
            motionSampleCount = snapshot.receivedSampleCount,
            lastMotionSensor = snapshot.lastSensorName,
            lastMotionValues = snapshot.lastValues
        )

        if (!viewingSavedSession) {
            updated = updated.copy(
                isRecording = snapshot.isRecording,
                routePoints = snapshot.routePoints,
                routeSteps = snapshot.routeSteps,
                routeDistanceMeters = snapshot.routeDistanceMeters,
                routeStatus = snapshot.routeStatus
            )
        }
        screenState.value = updated

        if (!viewingSavedSession && activeSessionId != null) {
            persistRouteSnapshot(snapshot)
            if (previous.isRecording && !snapshot.isRecording) {
                finishActiveSession(snapshot)
            }
        }
    }

    private fun onWifiSnapshot(snapshot: WifiScanSnapshot) {
        val current = screenState.value
        val newMarkers = mutableListOf<WifiRouteMarker>()
        var unmappedCount = 0

        // A stopped or saved route must never receive later scan results.
        if (current.isRecording && snapshot.resultsFresh && current.routePoints.isNotEmpty()) {
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

        val sessionId = activeSessionId
        if (sessionId != null && newMarkers.isNotEmpty()) {
            databaseExecutor.execute {
                runCatching { sessionDatabase.appendWifiMarkers(sessionId, newMarkers) }
                    .onFailure { error ->
                        runOnUiThread {
                            screenState.value = screenState.value.copy(
                                storageStatus = "Ошибка сохранения Wi-Fi: ${error.javaClass.simpleName}"
                            )
                        }
                    }
            }
        }
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
        activeSessionId = null
        persistedRoutePointCount = 0
        mappedMarkerIds.clear()
        screenState.value = screenState.value.copy(
            selectedSessionId = null,
            routePoints = emptyList(),
            wifiMarkers = emptyList(),
            routeSteps = 0,
            routeDistanceMeters = 0.0,
            unmappedWifiSamples = 0,
            routeStatus = "Подготавливаем запись…"
        )

        if (!motionSensors.startRoute()) return

        databaseExecutor.execute {
            runCatching { sessionDatabase.createSession() }
                .onSuccess { sessionId ->
                    runOnUiThread {
                        activeSessionId = sessionId
                        persistedRoutePointCount = 0
                        val current = screenState.value
                        persistRouteSnapshot(
                            MotionSensorSnapshot(
                                isRecording = current.isRecording,
                                routePoints = current.routePoints,
                                routeSteps = current.routeSteps,
                                routeDistanceMeters = current.routeDistanceMeters
                            )
                        )
                        if (current.wifiMarkers.isNotEmpty()) {
                            val existingMarkers = current.wifiMarkers.toList()
                            databaseExecutor.execute {
                                runCatching {
                                    sessionDatabase.appendWifiMarkers(sessionId, existingMarkers)
                                }.onFailure { error ->
                                    runOnUiThread {
                                        screenState.value = screenState.value.copy(
                                            storageStatus = "Ошибка сохранения Wi-Fi: ${error.javaClass.simpleName}"
                                        )
                                    }
                                }
                            }
                        }
                        if (!current.isRecording) {
                            finishActiveSession(
                                MotionSensorSnapshot(
                                    isRecording = false,
                                    routePoints = current.routePoints,
                                    routeSteps = current.routeSteps,
                                    routeDistanceMeters = current.routeDistanceMeters
                                )
                            )
                        }
                        refreshSavedSessions()
                    }
                }
                .onFailure { error ->
                    runOnUiThread {
                        motionSensors.stopRoute()
                        screenState.value = screenState.value.copy(
                            routeStatus = "Не удалось создать сессию: ${error.javaClass.simpleName}"
                        )
                    }
                }
        }
    }

    private fun persistRouteSnapshot(snapshot: MotionSensorSnapshot) {
        val sessionId = activeSessionId ?: return
        val startSequence = persistedRoutePointCount
        val newPoints = snapshot.routePoints.drop(startSequence)
        if (newPoints.isNotEmpty()) {
            persistedRoutePointCount += newPoints.size
        }
        val steps = snapshot.routeSteps
        val distance = snapshot.routeDistanceMeters

        if (newPoints.isNotEmpty() || !snapshot.isRecording) {
            databaseExecutor.execute {
                runCatching {
                    if (newPoints.isNotEmpty()) {
                        sessionDatabase.appendRoutePoints(sessionId, startSequence, newPoints)
                    }
                    sessionDatabase.updateProgress(sessionId, steps, distance)
                }.onSuccess {
                    runOnUiThread {
                        screenState.value = screenState.value.copy(storageStatus = null)
                    }
                }.onFailure { error ->
                    runOnUiThread {
                        if (activeSessionId == sessionId) {
                            persistedRoutePointCount = minOf(persistedRoutePointCount, startSequence)
                        }
                        screenState.value = screenState.value.copy(
                            storageStatus = "Ошибка сохранения маршрута: ${error.javaClass.simpleName}"
                        )
                    }
                }
            }
        }
    }

    private fun finishActiveSession(snapshot: MotionSensorSnapshot) {
        val sessionId = activeSessionId ?: return
        persistRouteSnapshot(snapshot)
        activeSessionId = null
        databaseExecutor.execute {
            runCatching {
                sessionDatabase.finishSession(
                    sessionId = sessionId,
                    steps = snapshot.routeSteps,
                    distanceMeters = snapshot.routeDistanceMeters
                )
                sessionDatabase.listSessions()
            }.onSuccess { sessions ->
                runOnUiThread {
                    screenState.value = screenState.value.copy(savedSessions = sessions)
                }
            }.onFailure { error ->
                runOnUiThread {
                    screenState.value = screenState.value.copy(
                        routeStatus = "Не удалось завершить сохранение: ${error.javaClass.simpleName}"
                    )
                }
            }
        }
    }

    private fun refreshSavedSessions() {
        databaseExecutor.execute {
            runCatching { sessionDatabase.listSessions() }
                .onSuccess { sessions ->
                    runOnUiThread {
                        screenState.value = screenState.value.copy(savedSessions = sessions)
                    }
                }
        }
    }

    private fun openSavedSession(sessionId: Long) {
        if (screenState.value.isRecording) return
        databaseExecutor.execute {
            runCatching { sessionDatabase.loadSession(sessionId) to sessionDatabase.listSessions() }
                .onSuccess { (stored, sessions) ->
                    runOnUiThread {
                        if (stored == null) {
                            screenState.value = screenState.value.copy(
                                routeStatus = "Сохранённый проход больше не найден.",
                                savedSessions = sessions
                            )
                        } else {
                            val summary = stored.summary
                            val statusLabel = when (summary.status) {
                                "interrupted" -> "Восстановлен после неожиданного завершения"
                                "finished" -> "Сохранённый проход"
                                else -> "Проход в процессе"
                            }
                            screenState.value = screenState.value.copy(
                                isRecording = false,
                                selectedSessionId = sessionId,
                                routePoints = stored.routePoints,
                                wifiMarkers = stored.wifiMarkers,
                                routeSteps = summary.steps,
                                routeDistanceMeters = summary.distanceMeters,
                                routeStatus = statusLabel,
                                unmappedWifiSamples = 0,
                                savedSessions = sessions
                            )
                        }
                    }
                }
                .onFailure { error ->
                    runOnUiThread {
                        screenState.value = screenState.value.copy(
                            routeStatus = "Не удалось загрузить проход: ${error.javaClass.simpleName}"
                        )
                    }
                }
        }
    }

    private companion object {
        const val MAX_WIFI_MARKERS = 3_000
    }
}
