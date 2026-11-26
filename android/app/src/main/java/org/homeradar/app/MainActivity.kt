package org.homeradar.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private var isCreatingSession = false
    private var pendingExportSessionId: Long? = null
    private var pendingRouteStartAfterLocation = false
    private val scanHandler = Handler(Looper.getMainLooper())
    private val scanTicker = object : Runnable {
        override fun run() {
            val current = screenState.value
            if (current.isArMode && current.isRecording) {
                wifiScanner.requestScan()
                scanHandler.postDelayed(this, WIFI_SCAN_INTERVAL_MS)
            }
        }
    }
    private val preferences by lazy {
        getSharedPreferences("homeradar_preferences", MODE_PRIVATE)
    }

    private val exportDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val sessionId = pendingExportSessionId
        pendingExportSessionId = null
        if (uri != null && sessionId != null) exportSessionToUri(sessionId, uri)
    }

    private val importDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) importSessionFromUri(uri)
    }

    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        screenState.value = screenState.value.copy(
            hasPermission = granted,
            status = if (granted) "Разрешение получено. Запрашиваем сканирование…" else
                "Для чтения результатов Wi-Fi Android требует разрешение на геолокацию."
        )
        if (granted) {
            if (pendingRouteStartAfterLocation) {
                pendingRouteStartAfterLocation = false
                requestCameraAndContinue()
            } else {
                wifiScanner.requestScan()
            }
        } else {
            pendingRouteStartAfterLocation = false
        }
    }

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            continueRouteStart()
        } else {
            screenState.value = screenState.value.copy(
                routeStatus = "Для режима картирования требуется доступ к камере."
            )
        }
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
            hasPermission = hasLocationPermission(),
            selectedNetworkSsid = preferences.getString(PREF_TARGET_SSID, null)
        )

        setContent {
            HomeRadarTheme {
                HomeRadarScreen(
                    activity = this,
                    state = screenState.value,
                    onScanClick = ::requestScan,
                    onRouteClick = ::toggleRoute,
                    onTargetNetworkChange = ::selectTargetNetwork,
                    onArExit = ::stopArScan,
                    onArPose = ::onArPose,
                    onArStatus = ::onArStatus,
                    onOpenSession = ::openSavedSession,
                    onExportSession = ::exportSession,
                    onImportSession = ::launchImportSession
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        screenState.value = screenState.value.copy(hasPermission = hasLocationPermission())
        wifiScanner.register()
        motionSensors.register()
        if (hasLocationPermission()) wifiScanner.requestScan()
    }

    override fun onStop() {
        scanHandler.removeCallbacks(scanTicker)
        if (motionSensors.recording) motionSensors.stopRoute()
        screenState.value = screenState.value.copy(isArMode = false)
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
        val selectedSsid = current.selectedNetworkSsid ?: snapshot.connectedSsid
        val effectiveState = current.copy(
            connectedSsid = snapshot.connectedSsid,
            selectedNetworkSsid = current.selectedNetworkSsid ?: snapshot.connectedSsid
        )
        val targetNetworks = when (selectedSsid) {
            ALL_NETWORKS -> snapshot.networks
            null -> emptyList()
            else -> snapshot.networks.filter { it.ssid == selectedSsid }
        }

        // A stopped or saved route must never receive later scan results.
        if (current.isRecording && snapshot.resultsFresh && current.routePoints.isNotEmpty()) {
            targetNetworks.forEach { network ->
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

        screenState.value = effectiveState.copy(
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

    private fun selectTargetNetwork(ssid: String) {
        if (screenState.value.isRecording) return
        preferences.edit().putString(PREF_TARGET_SSID, ssid).apply()
        screenState.value = screenState.value.copy(selectedNetworkSsid = ssid)
    }

    private fun onArPose(timestampNs: Long, worldX: Double, worldZ: Double) {
        if (screenState.value.isArMode && screenState.value.isRecording) {
            motionSensors.recordArPose(timestampNs, worldX, worldZ)
        }
    }

    private fun onArStatus(message: String) {
        if (screenState.value.arStatus != message) {
            screenState.value = screenState.value.copy(arStatus = message)
        }
    }

    private fun stopArScan() {
        scanHandler.removeCallbacks(scanTicker)
        if (motionSensors.recording) motionSensors.stopRoute()
        screenState.value = screenState.value.copy(
            isArMode = false,
            arStatus = "Камера остановлена."
        )
    }

    private fun requestScan() {
        if (!hasLocationPermission()) {
            locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }
        wifiScanner.requestScan()
    }

    private fun toggleRoute() {
        if (screenState.value.isRecording || screenState.value.isArMode) {
            stopArScan()
            return
        }

        if (!hasLocationPermission()) {
            pendingRouteStartAfterLocation = true
            locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }
        requestCameraAndContinue()
    }

    private fun requestCameraAndContinue() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermission.launch(Manifest.permission.CAMERA)
            return
        }
        continueRouteStart()
    }

    private fun continueRouteStart() {
        startRoute()
    }

    private fun startRoute() {
        if (isCreatingSession) {
            screenState.value = screenState.value.copy(
                routeStatus = "Предыдущая сессия ещё создаётся в локальной базе."
            )
            return
        }
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
            routeStatus = "Подготавливаем ARCore…",
            isArMode = true,
            arStatus = "Запускаем камеру и AR-трекинг…"
        )

        if (!motionSensors.startArRoute()) {
            screenState.value = screenState.value.copy(isArMode = false)
            return
        }
        isCreatingSession = true
        // Start with an immediate request, then ask Android no more often than every 30 seconds.
        wifiScanner.requestScan()
        scanHandler.removeCallbacks(scanTicker)
        scanHandler.postDelayed(scanTicker, WIFI_SCAN_INTERVAL_MS)

        databaseExecutor.execute {
            runCatching { sessionDatabase.createSession() }
                .onSuccess { sessionId ->
                    runOnUiThread {
                        if (isDestroyed || databaseExecutor.isShutdown) return@runOnUiThread
                        isCreatingSession = false
                        activeSessionId = sessionId
                        persistedRoutePointCount = 0
                        val current = screenState.value
                        val currentSnapshot = MotionSensorSnapshot(
                            isRecording = current.isRecording,
                            routePoints = current.routePoints,
                            routeSteps = current.routeSteps,
                            routeDistanceMeters = current.routeDistanceMeters
                        )
                        if (current.isRecording) {
                            persistRouteSnapshot(currentSnapshot)
                            val existingMarkers = current.wifiMarkers.toList()
                            if (existingMarkers.isNotEmpty()) {
                                databaseExecutor.execute {
                                    runCatching {
                                        sessionDatabase.appendWifiMarkers(sessionId, existingMarkers)
                                    }.onFailure { error ->
                                        reportStorageFailure(
                                            "Ошибка сохранения Wi-Fi: ${error.javaClass.simpleName}"
                                        )
                                    }
                                }
                            }
                            refreshSavedSessions()
                        } else {
                            finishActiveSession(currentSnapshot)
                        }
                    }
                }
                .onFailure { error ->
                    runOnUiThread {
                        isCreatingSession = false
                        if (isDestroyed || databaseExecutor.isShutdown) return@runOnUiThread
                        motionSensors.stopRoute()
                        scanHandler.removeCallbacks(scanTicker)
                        screenState.value = screenState.value.copy(
                            isArMode = false,
                            arStatus = "AR-сканирование не запущено.",
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
        // Reinsert the complete bounded snapshots with conflict-ignore before
        // closing the session, repairing any gaps from failed incremental writes.
        val routeSnapshot = snapshot.routePoints.toList()
        val markerSnapshot = screenState.value.wifiMarkers.toList()
        activeSessionId = null
        databaseExecutor.execute {
            runCatching {
                sessionDatabase.appendRoutePoints(sessionId, 0, routeSnapshot)
                sessionDatabase.appendWifiMarkers(sessionId, markerSnapshot)
                sessionDatabase.finishSession(
                    sessionId = sessionId,
                    steps = snapshot.routeSteps,
                    distanceMeters = snapshot.routeDistanceMeters
                )
                sessionDatabase.listSessions()
            }.onSuccess { sessions ->
                runOnUiThread {
                    screenState.value = screenState.value.copy(
                        savedSessions = sessions,
                        storageStatus = null
                    )
                }
            }.onFailure { error ->
                runOnUiThread {
                    screenState.value = screenState.value.copy(
                        storageStatus = "Не удалось завершить сохранение: ${error.javaClass.simpleName}"
                    )
                }
            }
        }
    }

    private fun reportStorageFailure(message: String) {
        runOnUiThread {
            screenState.value = screenState.value.copy(storageStatus = message)
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

    private fun exportSession(sessionId: Long) {
        if (screenState.value.isRecording) return
        pendingExportSessionId = sessionId
        exportDocument.launch("homeradar-session-$sessionId.homeradar")
    }

    private fun exportSessionToUri(sessionId: Long, uri: android.net.Uri) {
        databaseExecutor.execute {
            runCatching {
                val stored = sessionDatabase.loadSession(sessionId)
                    ?: throw IllegalArgumentException("Проход не найден.")
                val output = contentResolver.openOutputStream(uri)
                    ?: throw IllegalStateException("Не удалось открыть файл для записи.")
                output.use { HomeRadarArchive.write(it, stored) }
            }.onSuccess {
                runOnUiThread {
                    screenState.value = screenState.value.copy(
                        storageStatus = "Проход экспортирован в файл .homeradar."
                    )
                }
            }.onFailure { error ->
                runOnUiThread {
                    screenState.value = screenState.value.copy(
                        storageStatus = "Ошибка экспорта: ${error.message ?: error.javaClass.simpleName}"
                    )
                }
            }
        }
    }

    private fun launchImportSession() {
        if (screenState.value.isRecording) return
        importDocument.launch(arrayOf("application/zip", "application/octet-stream"))
    }

    private fun importSessionFromUri(uri: android.net.Uri) {
        if (screenState.value.isRecording) return
        databaseExecutor.execute {
            runCatching {
                val input = contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Не удалось открыть выбранный файл.")
                val payload = input.use(HomeRadarArchive::read)
                val importedId = sessionDatabase.importSession(payload)
                importedId to sessionDatabase.listSessions()
            }.onSuccess { (importedId, sessions) ->
                runOnUiThread {
                    screenState.value = screenState.value.copy(
                        savedSessions = sessions,
                        storageStatus = "Импортирован проход #$importedId. Исходный файл не изменён."
                    )
                }
            }.onFailure { error ->
                runOnUiThread {
                    screenState.value = screenState.value.copy(
                        storageStatus = "Ошибка импорта: ${error.message ?: error.javaClass.simpleName}"
                    )
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
        const val ALL_NETWORKS = "*"
        const val WIFI_SCAN_INTERVAL_MS = 30_000L
        const val PREF_TARGET_SSID = "target_wifi_ssid"
    }
}
