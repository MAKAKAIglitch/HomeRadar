package org.homeradar.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

data class MotionSensorSnapshot(
    val availableSensors: List<String> = emptyList(),
    val receivedSampleCount: Long = 0L,
    val lastSensorName: String? = null,
    val lastTimestampNs: Long? = null,
    val lastValues: List<Float> = emptyList()
)

class MotionSensorDataSource(
    context: Context,
    private val onSnapshot: (MotionSensorSnapshot) -> Unit
) : SensorEventListener {
    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val sensors = listOfNotNull(
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE),
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR),
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION),
        sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
    ).distinctBy { it.type }

    private var registered = false
    private var sampleCount = 0L
    private var lastPublishedTimestampNs = 0L
    private var lastSensorName: String? = null
    private var lastTimestampNs: Long? = null
    private var lastValues: List<Float> = emptyList()

    fun register() {
        if (registered) return
        sensors.forEach { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        }
        registered = true
        publish()
    }

    fun unregister() {
        if (!registered) return
        sensorManager.unregisterListener(this)
        registered = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        sampleCount += 1
        lastSensorName = event.sensor.name
        lastTimestampNs = event.timestamp
        lastValues = event.values.toList()

        // Bound UI updates; raw event persistence will be added with sessions.
        if (event.timestamp - lastPublishedTimestampNs >= 250_000_000L) {
            lastPublishedTimestampNs = event.timestamp
            publish()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun publish() {
        onSnapshot(
            MotionSensorSnapshot(
                availableSensors = sensors.map { it.name },
                receivedSampleCount = sampleCount,
                lastSensorName = lastSensorName,
                lastTimestampNs = lastTimestampNs,
                lastValues = lastValues
            )
        )
    }
}
