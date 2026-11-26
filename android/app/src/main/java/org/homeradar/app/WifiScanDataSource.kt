package org.homeradar.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat

internal const val ALL_NETWORKS = "*"

const val ALL_NETWORKS = "*"

data class WifiNetworkRow(
    val ssid: String,
    val bssid: String,
    val rssiDbm: Int,
    val frequencyMhz: Int,
    val capabilities: String,
    val measuredAtNs: Long?
) {
    val bandLabel: String
        get() = when (frequencyMhz) {
            in 2400..2500 -> "2.4 GHz"
            in 4900..5924 -> "5 GHz"
            in 5925..7125 -> "6 GHz"
            else -> "Unknown band"
        }
}

data class WifiScanSnapshot(
    val status: String,
    val networks: List<WifiNetworkRow>,
    val resultsFresh: Boolean,
    val receivedAtNs: Long,
    val connectedSsid: String? = null
)

data class WifiScanUiState(
    val hasPermission: Boolean = false,
    val isArMode: Boolean = false,
    val arStatus: String = "Камера не запущена.",
    val status: String = "Сканирование ещё не запускалось.",
    val networks: List<WifiNetworkRow> = emptyList(),
    val resultsFresh: Boolean = false,
    val lastReceivedAtNs: Long? = null,
    val availableMotionSensors: List<String> = emptyList(),
    val motionSampleCount: Long = 0L,
    val lastMotionSensor: String? = null,
    val lastMotionValues: List<Float> = emptyList(),
    val isRecording: Boolean = false,
    val routePoints: List<RoutePoint> = emptyList(),
    val routeSteps: Int = 0,
    val routeDistanceMeters: Double = 0.0,
    val routeStatus: String = "Маршрут не записывается.",
    val wifiMarkers: List<WifiRouteMarker> = emptyList(),
    val unmappedWifiSamples: Int = 0,
    val savedSessions: List<SavedSessionSummary> = emptyList(),
    val selectedSessionId: Long? = null,
    val storageStatus: String? = null,
    val connectedSsid: String? = null,
    val selectedNetworkSsid: String? = null
)

class WifiScanDataSource(
    context: Context,
    private val onSnapshot: (WifiScanSnapshot) -> Unit
) {
    private val appContext = context.applicationContext
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var registered = false
    private var lastRows: List<WifiNetworkRow> = emptyList()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) return

            val updated = intent.getBooleanExtra(
                WifiManager.EXTRA_RESULTS_UPDATED,
                false
            )
            publishResults(updated)
        }
    }

    fun register() {
        if (registered) return
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)

        if (Build.VERSION.SDK_INT >= 33) {
            // This is a system/privileged Wi-Fi broadcast; EXPORTED allows delivery from system components.
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(receiver, filter)
        }
        registered = true
    }

    fun unregister() {
        if (!registered) return
        runCatching { appContext.unregisterReceiver(receiver) }
        registered = false
    }

    @Suppress("DEPRECATION")
    fun requestScan() {
        if (ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            emitStatus("Нет разрешения ACCESS_FINE_LOCATION.")
            return
        }

        try {
            val accepted = wifiManager.startScan()
            emitStatus(
                if (accepted) {
                    "Запрос передан Android. Ожидаем результат системного сканирования…"
                } else {
                    "Android не принял запрос. Ожидаем доступные системные результаты."
                }
            )
        } catch (security: SecurityException) {
            emitStatus("Android не разрешил сканирование: проверь разрешения и системную геолокацию.")
        } catch (error: RuntimeException) {
            emitStatus("Ошибка Wi-Fi API: ${error.javaClass.simpleName}")
        }
    }

    private fun publishResults(updated: Boolean) {
        val receivedAt = SystemClock.elapsedRealtimeNanos()
        try {
            val rows = wifiManager.scanResults
                .map(::toRow)
                .sortedByDescending { it.rssiDbm }
            lastRows = rows

            onSnapshot(
                WifiScanSnapshot(
                    status = if (updated) {
                        "Получены обновлённые результаты Wi-Fi."
                    } else {
                        "Новые результаты не подтверждены: показаны последние доступные данные, они могут быть устаревшими."
                    },
                    networks = rows,
                    resultsFresh = updated,
                    receivedAtNs = receivedAt,
                    connectedSsid = currentConnectedSsid()
                )
            )
        } catch (security: SecurityException) {
            emitStatus("Android запретил чтение результатов. Проверь разрешение и настройки геолокации.")
        }
    }

    @Suppress("DEPRECATION")
    private fun currentConnectedSsid(): String? {
        return try {
            val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivity.activeNetwork
            val capabilities = network?.let(connectivity::getNetworkCapabilities)
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) return null
                val transportInfo = capabilities.transportInfo as? WifiInfo
                // Some Android versions redact SSID from NetworkCapabilities.transportInfo.
                // Fall back to the deprecated API while the required location permission is held.
                transportInfo?.takeIf {
                    it.ssid.isNotBlank() && it.ssid != WifiManager.UNKNOWN_SSID
                } ?: wifiManager.connectionInfo
            } else {
                wifiManager.connectionInfo
            }
            info?.ssid
                ?.takeIf { it.isNotBlank() && it != WifiManager.UNKNOWN_SSID }
                ?.removeSurrounding("\"")
        } catch (_: SecurityException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun toRow(result: ScanResult): WifiNetworkRow {
        val measuredAtNs = result.timestamp
            .takeIf { it > 0L }
            ?.let { it * 1_000L }

        return WifiNetworkRow(
            ssid = result.SSID.takeIf { it.isNotBlank() } ?: "(скрытая или недоступная сеть)",
            bssid = result.BSSID?.takeIf { it.isNotBlank() } ?: "(недоступен)",
            rssiDbm = result.level,
            frequencyMhz = result.frequency,
            capabilities = result.capabilities.orEmpty(),
            measuredAtNs = measuredAtNs
        )
    }

    private fun emitStatus(message: String) {
        onSnapshot(
            WifiScanSnapshot(
                status = message,
                networks = lastRows,
                resultsFresh = false,
                receivedAtNs = SystemClock.elapsedRealtimeNanos(),
                connectedSsid = currentConnectedSsid()
            )
        )
    }
}
