package org.homeradar.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class HomeRadarArchivePayload(
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long?,
    val status: String,
    val steps: Int,
    val distanceMeters: Double,
    val routePoints: List<RoutePoint>,
    val wifiMarkers: List<WifiRouteMarker>
)

/** Versioned data-only archive. Import never extracts arbitrary ZIP paths. */
object HomeRadarArchive {
    private const val FORMAT_NAME = "homeradar-session"
    private const val FORMAT_VERSION = 1
    private const val MANIFEST_ENTRY = "manifest.json"
    private const val SESSION_ENTRY = "session.json"
    private const val MAX_ARCHIVE_BYTES = 32 * 1024 * 1024
    private const val MAX_ROUTE_POINTS = 100_000
    private const val MAX_WIFI_SAMPLES = 50_000
    private const val MAX_TEXT_LENGTH = 512

    fun write(output: OutputStream, session: StoredHomeRadarSession) {
        require(session.summary.status == "finished" || session.summary.status == "interrupted") {
            "Незавершённый проход нельзя экспортировать."
        }

        val manifest = JSONObject()
            .put("format", FORMAT_NAME)
            .put("version", FORMAT_VERSION)
            .put("payload", SESSION_ENTRY)

        val data = JSONObject()
            .put("startedAtEpochMs", session.summary.startedAtEpochMs)
            .put("finishedAtEpochMs", session.summary.finishedAtEpochMs ?: JSONObject.NULL)
            .put("status", session.summary.status)
            .put("steps", session.summary.steps)
            .put("distanceMeters", session.summary.distanceMeters)
            .put("routePoints", JSONArray().apply {
                session.routePoints.forEach { point ->
                    put(
                        JSONObject()
                            .put("timestampNs", point.timestampNs)
                            .put("xMeters", point.xMeters)
                            .put("yMeters", point.yMeters)
                    )
                }
            })
            .put("wifiSamples", JSONArray().apply {
                session.wifiMarkers.forEach { marker ->
                    put(
                        JSONObject()
                            .put("id", marker.id)
                            .put("ssid", marker.ssid)
                            .put("bssid", marker.bssid)
                            .put("rssiDbm", marker.rssiDbm)
                            .put("frequencyMhz", marker.frequencyMhz)
                            .put("measuredAtNs", marker.measuredAtNs)
                            .put("xMeters", marker.xMeters)
                            .put("yMeters", marker.yMeters)
                    )
                }
            })

        ZipOutputStream(output.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
            zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(SESSION_ENTRY))
            zip.write(data.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    fun read(input: InputStream): HomeRadarArchivePayload {
        val entries = mutableMapOf<String, ByteArray>()
        var totalBytes = 0

        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory) { "Каталоги внутри архива не поддерживаются." }
                require(entry.name == MANIFEST_ENTRY || entry.name == SESSION_ENTRY) {
                    "В архиве найден неизвестный файл."
                }
                require(entries[entry.name] == null) { "В архиве повторяется файл ${entry.name}." }
                val bytes = readEntryLimited(zip, MAX_ARCHIVE_BYTES - totalBytes)
                totalBytes += bytes.size
                require(totalBytes <= MAX_ARCHIVE_BYTES) { "Архив слишком большой." }
                entries[entry.name] = bytes
                zip.closeEntry()
            }
        }

        val manifestBytes = entries[MANIFEST_ENTRY]
            ?: throw IllegalArgumentException("В архиве нет manifest.json.")
        val sessionBytes = entries[SESSION_ENTRY]
            ?: throw IllegalArgumentException("В архиве нет session.json.")
        val manifest = JSONObject(manifestBytes.toString(Charsets.UTF_8))
        require(manifest.getString("format") == FORMAT_NAME) { "Это не архив HomeRadar." }
        require(manifest.getInt("version") == FORMAT_VERSION) {
            "Версия архива не поддерживается: ${manifest.optInt("version", -1)}."
        }
        require(manifest.getString("payload") == SESSION_ENTRY) { "Некорректный manifest." }

        return parseSession(JSONObject(sessionBytes.toString(Charsets.UTF_8)))
    }

    private fun parseSession(json: JSONObject): HomeRadarArchivePayload {
        val startedAt = json.getLong("startedAtEpochMs")
        val finishedAt = if (json.isNull("finishedAtEpochMs")) null
            else json.getLong("finishedAtEpochMs")
        val status = json.getString("status")
        val steps = json.getInt("steps")
        val distance = json.getDouble("distanceMeters")

        require(startedAt > 0L) { "Некорректное время начала прохода." }
        require(finishedAt == null || finishedAt >= startedAt) { "Некорректное время завершения." }
        require(status == "finished" || status == "interrupted") {
            "Архив содержит незавершённую запись."
        }
        require(steps >= 0) { "Некорректное число шагов." }
        require(distance.isFinite() && distance in 0.0..1_000_000.0) {
            "Некорректная длина маршрута."
        }

        val routeJson = json.getJSONArray("routePoints")
        require(routeJson.length() in 1..MAX_ROUTE_POINTS) { "Некорректное число точек маршрута." }
        var previousTimestamp = -1L
        val route = buildList {
            for (index in 0 until routeJson.length()) {
                val item = routeJson.getJSONObject(index)
                val timestamp = item.getLong("timestampNs")
                val x = item.getDouble("xMeters")
                val y = item.getDouble("yMeters")
                require(timestamp >= 0L && timestamp >= previousTimestamp) {
                    "Временные метки маршрута идут в неверном порядке."
                }
                require(x.isFinite() && y.isFinite() && kotlin.math.abs(x) <= 100_000.0 &&
                    kotlin.math.abs(y) <= 100_000.0) {
                    "Координаты маршрута выходят за допустимый диапазон."
                }
                previousTimestamp = timestamp
                add(RoutePoint(x, y, timestamp))
            }
        }

        val wifiJson = json.getJSONArray("wifiSamples")
        require(wifiJson.length() <= MAX_WIFI_SAMPLES) { "Слишком много Wi-Fi-измерений." }
        val seenIds = mutableSetOf<String>()
        val wifi = buildList {
            for (index in 0 until wifiJson.length()) {
                val item = wifiJson.getJSONObject(index)
                val id = item.getString("id")
                val ssid = item.getString("ssid")
                val bssid = item.getString("bssid")
                val rssi = item.getInt("rssiDbm")
                val frequency = item.getInt("frequencyMhz")
                val measuredAt = item.getLong("measuredAtNs")
                val x = item.getDouble("xMeters")
                val y = item.getDouble("yMeters")

                require(id.isNotBlank() && id.length <= MAX_TEXT_LENGTH && seenIds.add(id)) {
                    "Некорректный или повторяющийся идентификатор Wi-Fi-измерения."
                }
                require(ssid.length <= MAX_TEXT_LENGTH && bssid.length <= MAX_TEXT_LENGTH) {
                    "Слишком длинное имя сети или BSSID."
                }
                require(rssi in -127..0 && frequency in 2000..8000 && measuredAt >= 0L) {
                    "Некорректные параметры Wi-Fi-измерения."
                }
                require(x.isFinite() && y.isFinite() && kotlin.math.abs(x) <= 100_000.0 &&
                    kotlin.math.abs(y) <= 100_000.0) {
                    "Координаты Wi-Fi-измерения выходят за допустимый диапазон."
                }
                add(
                    WifiRouteMarker(
                        id = id,
                        ssid = ssid,
                        bssid = bssid,
                        rssiDbm = rssi,
                        frequencyMhz = frequency,
                        xMeters = x,
                        yMeters = y,
                        measuredAtNs = measuredAt
                    )
                )
            }
        }

        return HomeRadarArchivePayload(
            startedAtEpochMs = startedAt,
            finishedAtEpochMs = finishedAt,
            status = status,
            steps = steps,
            distanceMeters = distance,
            routePoints = route,
            wifiMarkers = wifi
        )
    }

    private fun readEntryLimited(input: InputStream, remainingLimit: Int): ByteArray {
        require(remainingLimit > 0) { "Архив слишком большой." }
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= remainingLimit) { "Архив слишком большой." }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
