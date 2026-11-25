package org.homeradar.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.util.TreeMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

data class MotionSensorSnapshot(
    val availableSensors: List<String> = emptyList(),
    val receivedSampleCount: Long = 0L,
    val lastSensorName: String? = null,
    val lastTimestampNs: Long? = null,
    val lastValues: List<Float> = emptyList(),
    val isRecording: Boolean = false,
    val routePoints: List<RoutePoint> = emptyList(),
    val routeSteps: Int = 0,
    val routeDistanceMeters: Double = 0.0,
    val routeStatus: String = "Маршрут не записывается."
)

private data class TimedRoutePosition(
    val timestampNs: Long,
    val xMeters: Double,
    val yMeters: Double
)

class MotionSensorDataSource(
    context: Context,
    private val onSnapshot: (MotionSensorSnapshot) -> Unit
) : SensorEventListener {
    private val appContext = context.applicationContext
    private val sensorManager =
        appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val diagnosticSensors = listOfNotNull(
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE),
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR),
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION),
        sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
    ).distinctBy { it.type }

    private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val stepDetector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    private val registeredSensorTypes = mutableSetOf<Int>()
    private val poseHistory = TreeMap<Long, TimedRoutePosition>()
    private val routePoints = mutableListOf<RoutePoint>()

    private var registered = false
    private var sampleCount = 0L
    private var lastPublishedTimestampNs = 0L
    private var lastSensorName: String? = null
    private var lastTimestampNs: Long? = null
    private var lastValues: List<Float> = emptyList()

    private var isRecording = false
    private var routeStatus = "Маршрут не записывается."
    private var routeStartTimestampNs = 0L
    private var latestAzimuthRad: Double? = null
    private var originAzimuthRad: Double? = null
    private var positionX = 0.0
    private var positionY = 0.0
    private var stepCount = 0
    private val estimatedStepLengthMeters = 0.68

    val recording: Boolean
        get() = isRecording

    fun register() {
        if (registered) return
        diagnosticSensors.forEach(::registerSensor)
        if (hasActivityRecognitionPermission()) {
            stepDetector?.let(::registerSensor)
        }
        registered = true
        publish(force = true)
    }

    fun unregister() {
        if (!registered) return
        sensorManager.unregisterListener(this)
        registeredSensorTypes.clear()
        registered = false
    }

    fun startRoute(): Boolean {
        if (!hasActivityRecognitionPermission()) {
            routeStatus = "Для шагомера нужно разрешение «Физическая активность»."
            publish(force = true)
            return false
        }
        if (stepDetector == null) {
            routeStatus = "На устройстве не найден аппаратный датчик шагов."
            publish(force = true)
            return false
        }
        if (rotationSensor == null) {
            routeStatus = "На устройстве не найден датчик ориентации."
            publish(force = true)
            return false
        }
        if (!registerSensor(stepDetector)) {
            routeStatus = "Android не разрешил включить датчик шагов."
            publish(force = true)
            return false
        }

        routePoints.clear()
        synchronized(poseHistory) { poseHistory.clear() }
        positionX = 0.0
        positionY = 0.0
        stepCount = 0
        routeStartTimestampNs = SystemClock.elapsedRealtimeNanos()
        originAzimuthRad = latestAzimuthRad
        isRecording = true
        routePoints += RoutePoint(0.0, 0.0, routeStartTimestampNs)
        routeStatus =
            "Оценка по шагам и магнитному направлению. Держи телефон горизонтально экраном вверх, верхним краем по направлению движения. Длина шага принята за 0,68 м."
        addHistory(routeStartTimestampNs)
        publish(force = true)
        return true
    }

    fun stopRoute() {
        if (!isRecording) return
        isRecording = false
        routeStatus = "Проход остановлен. Путь — приблизительная оценка по шагам и ориентации."
        publish(force = true)
    }

    /**
     * Returns a position only when the measurement timestamp is bracketed by
     * two recorded sensor poses. No extrapolation is performed.
     */
    fun positionAt(timestampNs: Long): RoutePosition? {
        synchronized(poseHistory) {
            val before = poseHistory.floorEntry(timestampNs) ?: return null
            val after = poseHistory.ceilingEntry(timestampNs) ?: return null

            if (before.key == after.key) {
                return RoutePosition(before.value.xMeters, before.value.yMeters)
            }

            val span = after.key - before.key
            if (span <= 0L || span > MAX_POSE_BRACKET_NS) return null

            val amount = (timestampNs - before.key).toDouble() / span.toDouble()
            return RoutePosition(
                xMeters = before.value.xMeters +
                    (after.value.xMeters - before.value.xMeters) * amount,
                yMeters = before.value.yMeters +
                    (after.value.yMeters - before.value.yMeters) * amount
            )
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        sampleCount += 1
        lastSensorName = event.sensor.name
        lastTimestampNs = event.timestamp
        lastValues = event.values.toList()

        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> onRotationChanged(event)
            Sensor.TYPE_STEP_DETECTOR -> onStepDetected(event)
        }

        publish()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun onRotationChanged(event: SensorEvent) {
        val rotationMatrix = FloatArray(9)
        val orientation = FloatArray(3)
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientation)
        latestAzimuthRad = orientation[0].toDouble()

        if (isRecording && event.timestamp >= routeStartTimestampNs) {
            if (originAzimuthRad == null) originAzimuthRad = latestAzimuthRad
            addHistory(event.timestamp)
        }
    }

    private fun onStepDetected(event: SensorEvent) {
        if (!isRecording || event.timestamp < routeStartTimestampNs) return

        val currentAzimuth = latestAzimuthRad ?: return
        val initialAzimuth = originAzimuthRad ?: currentAzimuth.also {
            originAzimuthRad = it
        }
        val relativeHeading = normalizeAngle(currentAzimuth - initialAzimuth)

        // Local +Y is the initial forward direction; +X is to its right.
        positionX += sin(relativeHeading) * estimatedStepLengthMeters
        positionY += cos(relativeHeading) * estimatedStepLengthMeters
        stepCount += 1

        if (routePoints.size < MAX_ROUTE_POINTS) {
            routePoints += RoutePoint(positionX, positionY, event.timestamp)
        } else if (routePoints.size == MAX_ROUTE_POINTS) {
            routeStatus = "Достигнут предел точек маршрута; запись шагов и оценка расстояния продолжаются."
        }
        addHistory(event.timestamp)
        publish(force = true)
    }

    private fun addHistory(timestampNs: Long) {
        if (!isRecording || timestampNs < routeStartTimestampNs) return
        synchronized(poseHistory) {
            poseHistory[timestampNs] = TimedRoutePosition(timestampNs, positionX, positionY)
            while (poseHistory.size > MAX_POSE_HISTORY) {
                poseHistory.pollFirstEntry()
            }
        }
    }

    private fun registerSensor(sensor: Sensor): Boolean {
        if (registeredSensorTypes.contains(sensor.type)) return true
        val success = try {
            sensorManager.registerListener(
                this,
                sensor,
                SensorManager.SENSOR_DELAY_GAME
            )
        } catch (_: SecurityException) {
            false
        }
        if (success) registeredSensorTypes += sensor.type
        return success
    }

    private fun hasActivityRecognitionPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACTIVITY_RECOGNITION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun availableSensorNames(): List<String> {
        val names = diagnosticSensors.map { it.name }.toMutableList()
        names += when {
            stepDetector == null -> "Датчик шагов недоступен"
            hasActivityRecognitionPermission() -> stepDetector.name
            else -> "Датчик шагов: требуется разрешение"
        }
        return names
    }

    private fun normalizeAngle(angle: Double): Double {
        var value = angle
        while (value > PI) value -= 2.0 * PI
        while (value < -PI) value += 2.0 * PI
        return value
    }

    private fun publish(force: Boolean = false) {
        val timestamp = lastTimestampNs ?: SystemClock.elapsedRealtimeNanos()
        if (!force && timestamp - lastPublishedTimestampNs < UI_UPDATE_INTERVAL_NS) return
        lastPublishedTimestampNs = timestamp

        onSnapshot(
            MotionSensorSnapshot(
                availableSensors = availableSensorNames(),
                receivedSampleCount = sampleCount,
                lastSensorName = lastSensorName,
                lastTimestampNs = lastTimestampNs,
                lastValues = lastValues,
                isRecording = isRecording,
                routePoints = routePoints.toList(),
                routeSteps = stepCount,
                routeDistanceMeters = stepCount * estimatedStepLengthMeters,
                routeStatus = routeStatus
            )
        )
    }

    private companion object {
        const val UI_UPDATE_INTERVAL_NS = 250_000_000L
        const val MAX_POSE_BRACKET_NS = 2_000_000_000L
        const val MAX_POSE_HISTORY = 30_000
        const val MAX_ROUTE_POINTS = 30_000
    }
}
