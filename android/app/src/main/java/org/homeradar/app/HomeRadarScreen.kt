package org.homeradar.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeRadarScreen(
    state: WifiScanUiState,
    onScanClick: () -> Unit,
    onRouteClick: () -> Unit,
    onTargetNetworkChange: (String) -> Unit,
    onOpenSession: (Long) -> Unit,
    onExportSession: (Long) -> Unit,
    onImportSession: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("HomeRadar")
                        Text(
                            "Карта Wi-Fi по маршруту",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                WifiScannerCard(state, onScanClick, onTargetNetworkChange)
            }
            item {
                RouteControlCard(state, onRouteClick)
            }
            item {
                RouteMapCard(state)
            }
            item {
                SavedSessionsCard(state, onOpenSession, onExportSession, onImportSession)
            }
            item {
                MotionDiagnosticsCard(state)
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Обнаруженные точки", style = MaterialTheme.typography.titleMedium)
                    Text(
                        state.networks.size.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (state.networks.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("Пока нет результатов", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Запроси Wi-Fi-сканирование. Android может ограничивать частоту обновлений и возвращать кэшированные результаты.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(
                    items = state.networks,
                    key = { "${it.bssid}:${it.frequencyMhz}" }
                ) { network ->
                    WifiNetworkCard(network, state.lastReceivedAtNs)
                }
            }
        }
    }
}

@Composable
private fun RouteControlCard(
    state: WifiScanUiState,
    onRouteClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Запись прохода", style = MaterialTheme.typography.titleLarge)
                Text(
                    if (state.isRecording) "Идёт запись" else "Остановлено",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state.isRecording) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                state.routeStatus,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            state.storageStatus?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Button(onClick = onRouteClick) {
                    Text(if (state.isRecording) "Завершить проход" else "Начать проход")
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "${state.routeSteps} шагов",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        "≈ ${"%.1f".format(state.routeDistanceMeters)} м",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (state.isRecording) {
                Text("Камера активна", style = MaterialTheme.typography.titleSmall)
                CameraPreview(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .clip(RoundedCornerShape(16.dp))
                )
            }
            Text(
                "Камера показывает пространство во время прохода. Позиция пока рассчитывается по шагам и компасу (PDR, шаг ≈ 0,68 м); полноценный ARCore visual-inertial tracking ещё не подключён.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RouteMapCard(state: WifiScanUiState) {
    val colors = MaterialTheme.colorScheme
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (state.selectedSessionId == null) "Траектория" else "Сохранённый проход #${state.selectedSessionId}",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "${state.wifiMarkers.size} Wi-Fi",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.surfaceContainerLow)
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val gridColor = colors.outlineVariant.copy(alpha = 0.45f)
                    for (index in 1..4) {
                        val x = size.width * index / 5f
                        val y = size.height * index / 5f
                        drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                        drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                    }

                    val routeX = state.routePoints.map { it.xMeters }
                    val routeY = state.routePoints.map { it.yMeters }
                    val markerX = state.wifiMarkers.map { it.xMeters }
                    val markerY = state.wifiMarkers.map { it.yMeters }
                    val allX = routeX + markerX
                    val allY = routeY + markerY

                    if (allX.isNotEmpty() && allY.isNotEmpty()) {
                        val minX = allX.minOrNull() ?: 0.0
                        val maxX = allX.maxOrNull() ?: 1.0
                        val minY = allY.minOrNull() ?: 0.0
                        val maxY = allY.maxOrNull() ?: 1.0
                        val rangeX = (maxX - minX).coerceAtLeast(0.8)
                        val rangeY = (maxY - minY).coerceAtLeast(0.8)
                        val margin = 28.dp.toPx()
                        val usableWidth = (size.width - margin * 2f).coerceAtLeast(1f)
                        val usableHeight = (size.height - margin * 2f).coerceAtLeast(1f)
                        val scale = min(
                            usableWidth / rangeX.toFloat(),
                            usableHeight / rangeY.toFloat()
                        )
                        val drawnWidth = rangeX.toFloat() * scale
                        val drawnHeight = rangeY.toFloat() * scale
                        val left = (size.width - drawnWidth) / 2f
                        val top = (size.height - drawnHeight) / 2f

                        fun project(x: Double, y: Double): Offset = Offset(
                            x = left + (x - minX).toFloat() * scale,
                            y = top + (maxY - y).toFloat() * scale
                        )

                        val routePath = Path()
                        state.routePoints.forEachIndexed { index, point ->
                            val position = project(point.xMeters, point.yMeters)
                            if (index == 0) routePath.moveTo(position.x, position.y)
                            else routePath.lineTo(position.x, position.y)
                        }
                        if (state.routePoints.size > 1) {
                            drawPath(
                                path = routePath,
                                color = colors.primary,
                                style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                            )
                        }

                        state.wifiMarkers.forEach { marker ->
                            val signalColor = when {
                                marker.rssiDbm >= -55 -> colors.tertiary
                                marker.rssiDbm >= -67 -> colors.primary
                                marker.rssiDbm >= -80 -> colors.secondary
                                else -> colors.error
                            }
                            drawCircle(
                                color = signalColor.copy(alpha = 0.88f),
                                radius = 7.dp.toPx(),
                                center = project(marker.xMeters, marker.yMeters)
                            )
                        }

                        state.routePoints.firstOrNull()?.let { start ->
                            drawCircle(
                                color = colors.tertiary,
                                radius = 7.dp.toPx(),
                                center = project(start.xMeters, start.yMeters)
                            )
                        }
                        state.routePoints.lastOrNull()?.let { end ->
                            drawCircle(
                                color = colors.primary,
                                radius = 5.dp.toPx(),
                                center = project(end.xMeters, end.yMeters)
                            )
                        }
                    }
                }

                if (state.routePoints.isEmpty()) {
                    Text(
                        "Здесь появится линия твоего прохода",
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant
                    )
                }
            }

            Text(
                "Точки окрашены по уровню сигнала: сильный → слабый. Пустые участки не интерполируются.",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant
            )
            if (state.unmappedWifiSamples > 0) {
                Text(
                    "Не привязано к маршруту: ${state.unmappedWifiSamples} измерений (не хватило временных данных).",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant
                )
            }
        }
    }
}


@Composable
private fun SavedSessionsCard(
    state: WifiScanUiState,
    onOpenSession: (Long) -> Unit,
    onExportSession: (Long) -> Unit,
    onImportSession: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Сохранённые проходы", style = MaterialTheme.typography.titleMedium)
                Text(
                    state.savedSessions.size.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(onClick = onImportSession, enabled = !state.isRecording) {
                Text("Импортировать .homeradar")
            }
            Text(
                "Архив включает SSID/BSSID и координаты локального маршрута. Проверяй файл перед отправкой.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (state.savedSessions.isEmpty()) {
                Text(
                    "После завершения записи проход появится здесь и останется доступен после перезапуска приложения.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                state.savedSessions.forEach { session ->
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Проход #${session.id}",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                when (session.status) {
                                    "interrupted" -> "Прерван"
                                    "recording" -> "Не завершён"
                                    else -> "Сохранён"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            DateFormat.getDateTimeInstance(
                                DateFormat.SHORT,
                                DateFormat.SHORT
                            ).format(Date(session.startedAtEpochMs)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "${session.steps} шагов · ≈ ${"%.1f".format(session.distanceMeters)} м · ${session.wifiSampleCount} Wi-Fi-точек",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { onOpenSession(session.id) },
                                enabled = !state.isRecording && state.selectedSessionId != session.id
                            ) {
                                Text(
                                    if (state.selectedSessionId == session.id) "Открыт"
                                    else "Открыть"
                                )
                            }
                            OutlinedButton(
                                onClick = { onExportSession(session.id) },
                                enabled = !state.isRecording && session.status != "recording"
                            ) {
                                Text("Экспорт")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WifiScannerCard(
    state: WifiScanUiState,
    onScanClick: () -> Unit,
    onTargetNetworkChange: (String) -> Unit
) {
    val menuExpanded = remember { mutableStateOf(false) }
    val options = (listOfNotNull(state.connectedSsid) +
        state.networks.map { it.ssid }.filter { it.isNotBlank() } +
        listOf("*")).distinct()
    val selected = state.selectedNetworkSsid ?: state.connectedSsid ?: "*"
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Целевая Wi-Fi-сеть", style = MaterialTheme.typography.titleMedium)
            Text(
                "По умолчанию выбрана сеть, к которой подключён телефон. Для неё будут записываться точки сигнала; можно выбрать другую найденную сеть.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = { menuExpanded.value = true },
                enabled = !state.isRecording
            ) {
                Text("Сеть: " + if (selected == "*") "Все сети" else selected)
            }
            DropdownMenu(
                expanded = menuExpanded.value,
                onDismissRequest = { menuExpanded.value = false }
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(if (option == "*") "Все сети" else option) },
                        onClick = {
                            menuExpanded.value = false
                            onTargetNetworkChange(option)
                        }
                    )
                }
            }
            Text("Wi-Fi Scanner", style = MaterialTheme.typography.titleMedium)
            Text(
                state.status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(onClick = onScanClick) {
                    Text(if (state.hasPermission) "Сканировать Wi-Fi" else "Разрешить Wi-Fi")
                }
                Text(
                    if (state.resultsFresh) "Обновлено" else "Не подтверждено",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state.resultsFresh) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MotionDiagnosticsCard(state: WifiScanUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("Датчики движения", style = MaterialTheme.typography.titleSmall)
            Text(
                if (state.availableMotionSensors.isEmpty()) "Поддерживаемые датчики не обнаружены"
                else state.availableMotionSensors.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Получено событий: ${state.motionSampleCount}" +
                    (state.lastMotionSensor?.let { " · последнее: $it" } ?: ""),
                style = MaterialTheme.typography.labelSmall
            )
            if (state.lastMotionValues.isNotEmpty()) {
                Text(
                    "Последние значения: " + state.lastMotionValues.joinToString { "%.2f".format(it) },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun WifiNetworkCard(network: WifiNetworkRow, receivedAtNs: Long?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(network.ssid, style = MaterialTheme.typography.titleSmall)
            Text(
                network.bssid,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val ageSeconds = receivedAtNs?.let { received ->
                network.measuredAtNs?.let { measured ->
                    (received - measured).takeIf { it >= 0L }?.div(1_000_000_000L)
                }
            }
            Text(
                ageSeconds?.let { "Измерено $it с назад" } ?: "Время измерения недоступно",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(network.bandLabel, style = MaterialTheme.typography.labelMedium)
                Text(
                    "${network.rssiDbm} dBm · ${network.frequencyMhz} MHz",
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}
