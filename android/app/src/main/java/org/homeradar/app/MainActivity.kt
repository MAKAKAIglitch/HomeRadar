package org.homeradar.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private val screenState = mutableStateOf(WifiScanUiState())
    private lateinit var wifiScanner: WifiScanDataSource

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        wifiScanner = WifiScanDataSource(this) { snapshot ->
            screenState.value = screenState.value.copy(
                status = snapshot.status,
                networks = snapshot.networks,
                resultsFresh = snapshot.resultsFresh,
                lastReceivedAtNs = snapshot.receivedAtNs
            )
        }

        screenState.value = screenState.value.copy(
            hasPermission = hasLocationPermission()
        )

        setContent {
            HomeRadarTheme {
                HomeRadarScreen(
                    state = screenState.value,
                    onScanClick = ::requestScan
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        wifiScanner.register()
    }

    override fun onStop() {
        wifiScanner.unregister()
        super.onStop()
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    private fun requestScan() {
        if (!hasLocationPermission()) {
            locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }
        wifiScanner.requestScan()
    }
}
