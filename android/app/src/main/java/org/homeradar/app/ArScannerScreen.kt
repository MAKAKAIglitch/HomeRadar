package org.homeradar.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

@Composable
fun ArScannerScreen(
    activity: ComponentActivity,
    state: WifiScanUiState,
    onExit: () -> Unit,
    onPose: (Long, Double, Double) -> Unit,
    onStatus: (String) -> Unit
) {
    val cameraViewState = remember { mutableStateOf<ArCoreCameraView?>(null) }
    val target = state.selectedNetworkSsid ?: "Сеть не выбрана"
    val targetRows = state.networks.filter { it.ssid == state.selectedNetworkSsid }
    val bestSignal = targetRows.maxByOrNull { it.rssiDbm }?.rssiDbm
    val lifecycle = activity.lifecycle

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                ArCoreCameraView(
                    context = context,
                    activity = activity,
                    onPose = onPose,
                    onStatus = onStatus
                ).also { cameraViewState.value = it }
            }
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
                )
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(target, style = MaterialTheme.typography.titleMedium)
                    Text(
                        bestSignal?.let { "Сигнал: $it dBm · точек на маршруте: ${state.wifiMarkers.size}" }
                            ?: "Ожидаем актуальный замер выбранной сети…",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        state.arStatus,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }

        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
            )
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("AR-картирование", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${state.routeDistanceMeters.formatOneDecimal()} м",
                        style = MaterialTheme.typography.titleSmall
                    )
                }
                Text(
                    "Двигайся медленно. Замеры Wi-Fi будут добавляться по мере обновления Android scan results.",
                    style = MaterialTheme.typography.bodySmall
                )
                Button(onClick = onExit, modifier = Modifier.fillMaxWidth()) {
                    Text("Завершить сканирование")
                }
            }
        }
    }

    DisposableEffect(cameraViewState.value, lifecycle) {
        val view = cameraViewState.value
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view?.resumeCamera()
                Lifecycle.Event.ON_PAUSE -> view?.pauseCamera()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            view?.resumeCamera()
        }
        onDispose {
            lifecycle.removeObserver(observer)
            view?.closeCamera()
            cameraViewState.value = null
        }
    }
}

private fun Double.formatOneDecimal(): String = String.format(java.util.Locale.ROOT, "%.1f", this)
