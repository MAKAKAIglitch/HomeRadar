package org.homeradar.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class SavedSessionSummary(
    val id: Long,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long?,
    val status: String,
    val steps: Int,
    val distanceMeters: Double,
    val routePointCount: Int,
    val wifiSampleCount: Int
)

data class StoredHomeRadarSession(
    val summary: SavedSessionSummary,
    val routePoints: List<RoutePoint>,
    val wifiMarkers: List<WifiRouteMarker>
)

/**
 * Local-only session storage. The database contains no cloud sync or upload code.
 * Timestamps from sensors remain monotonic nanoseconds within each session.
 */
class HomeRadarDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                started_at_epoch_ms INTEGER NOT NULL,
                finished_at_epoch_ms INTEGER,
                status TEXT NOT NULL CHECK(status IN ('recording', 'finished', 'interrupted')),
                steps INTEGER NOT NULL DEFAULT 0,
                distance_m REAL NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE route_points (
                session_id INTEGER NOT NULL,
                sequence INTEGER NOT NULL,
                elapsed_ns INTEGER NOT NULL,
                x_m REAL NOT NULL,
                y_m REAL NOT NULL,
                PRIMARY KEY(session_id, sequence),
                FOREIGN KEY(session_id) REFERENCES sessions(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE wifi_samples (
                session_id INTEGER NOT NULL,
                sample_key TEXT NOT NULL,
                ssid TEXT NOT NULL,
                bssid TEXT NOT NULL,
                rssi_dbm INTEGER NOT NULL,
                frequency_mhz INTEGER NOT NULL,
                measured_at_ns INTEGER NOT NULL,
                x_m REAL NOT NULL,
                y_m REAL NOT NULL,
                PRIMARY KEY(session_id, sample_key),
                FOREIGN KEY(session_id) REFERENCES sessions(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_route_points_time ON route_points(session_id, elapsed_ns)")
        db.execSQL("CREATE INDEX idx_wifi_samples_bssid_time ON wifi_samples(bssid, measured_at_ns)")
        db.execSQL("CREATE INDEX idx_wifi_samples_frequency_time ON wifi_samples(frequency_mhz, measured_at_ns)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Never drop user recordings automatically. Add explicit migrations here as the schema evolves.
        throw IllegalStateException("No migration path from database version $oldVersion to $newVersion")
    }

    fun recoverInterruptedSessions(nowEpochMs: Long = System.currentTimeMillis()) {
        val values = ContentValues().apply {
            put("status", STATUS_INTERRUPTED)
            put("finished_at_epoch_ms", nowEpochMs)
        }
        writableDatabase.update(
            TABLE_SESSIONS,
            values,
            "status = ?",
            arrayOf(STATUS_RECORDING)
        )
    }

    fun createSession(startedAtEpochMs: Long = System.currentTimeMillis()): Long {
        val values = ContentValues().apply {
            put("started_at_epoch_ms", startedAtEpochMs)
            put("status", STATUS_RECORDING)
        }
        return writableDatabase.insertOrThrow(TABLE_SESSIONS, null, values)
    }

    fun appendRoutePoints(sessionId: Long, startSequence: Int, points: List<RoutePoint>) {
        if (points.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            points.forEachIndexed { index, point ->
                val values = ContentValues().apply {
                    put("session_id", sessionId)
                    put("sequence", startSequence + index)
                    put("elapsed_ns", point.timestampNs)
                    put("x_m", point.xMeters)
                    put("y_m", point.yMeters)
                }
                db.insertWithOnConflict(
                    TABLE_ROUTE_POINTS,
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_IGNORE
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun appendWifiMarkers(sessionId: Long, markers: List<WifiRouteMarker>) {
        if (markers.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            markers.forEach { marker ->
                val values = ContentValues().apply {
                    put("session_id", sessionId)
                    put("sample_key", marker.id)
                    put("ssid", marker.ssid)
                    put("bssid", marker.bssid)
                    put("rssi_dbm", marker.rssiDbm)
                    put("frequency_mhz", marker.frequencyMhz)
                    put("measured_at_ns", marker.measuredAtNs)
                    put("x_m", marker.xMeters)
                    put("y_m", marker.yMeters)
                }
                db.insertWithOnConflict(
                    TABLE_WIFI_SAMPLES,
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_IGNORE
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun updateProgress(sessionId: Long, steps: Int, distanceMeters: Double) {
        val values = ContentValues().apply {
            put("steps", steps)
            put("distance_m", distanceMeters)
        }
        writableDatabase.update(TABLE_SESSIONS, values, "id = ?", arrayOf(sessionId.toString()))
    }

    fun finishSession(
        sessionId: Long,
        steps: Int,
        distanceMeters: Double,
        finishedAtEpochMs: Long = System.currentTimeMillis()
    ) {
        val values = ContentValues().apply {
            put("status", STATUS_FINISHED)
            put("finished_at_epoch_ms", finishedAtEpochMs)
            put("steps", steps)
            put("distance_m", distanceMeters)
        }
        writableDatabase.update(TABLE_SESSIONS, values, "id = ?", arrayOf(sessionId.toString()))
    }

    fun listSessions(limit: Int = DEFAULT_SESSION_LIMIT): List<SavedSessionSummary> {
        val safeLimit = limit.coerceIn(1, MAX_SESSION_LIMIT)
        val sql = """
            SELECT
                s.id,
                s.started_at_epoch_ms,
                s.finished_at_epoch_ms,
                s.status,
                s.steps,
                s.distance_m,
                (SELECT COUNT(*) FROM route_points p WHERE p.session_id = s.id) AS route_point_count,
                (SELECT COUNT(*) FROM wifi_samples w WHERE w.session_id = s.id) AS wifi_sample_count
            FROM sessions s
            ORDER BY s.started_at_epoch_ms DESC, s.id DESC
            LIMIT ?
        """.trimIndent()

        return readableDatabase.rawQuery(sql, arrayOf(safeLimit.toString())).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toSummary())
            }
        }
    }

    fun loadSession(sessionId: Long): StoredHomeRadarSession? {
        val db = readableDatabase
        val summary = db.rawQuery(
            """
            SELECT
                s.id,
                s.started_at_epoch_ms,
                s.finished_at_epoch_ms,
                s.status,
                s.steps,
                s.distance_m,
                (SELECT COUNT(*) FROM route_points p WHERE p.session_id = s.id) AS route_point_count,
                (SELECT COUNT(*) FROM wifi_samples w WHERE w.session_id = s.id) AS wifi_sample_count
            FROM sessions s
            WHERE s.id = ?
            """.trimIndent(),
            arrayOf(sessionId.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toSummary() else null
        } ?: return null

        val routePoints = db.rawQuery(
            """
            SELECT elapsed_ns, x_m, y_m
            FROM route_points
            WHERE session_id = ?
            ORDER BY sequence ASC
            """.trimIndent(),
            arrayOf(sessionId.toString())
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        RoutePoint(
                            xMeters = cursor.getDouble(2),
                            yMeters = cursor.getDouble(1),
                            timestampNs = cursor.getLong(0)
                        )
                    )
                }
            }
        }

        val wifiMarkers = db.rawQuery(
            """
            SELECT sample_key, ssid, bssid, rssi_dbm, frequency_mhz, measured_at_ns, x_m, y_m
            FROM wifi_samples
            WHERE session_id = ?
            ORDER BY measured_at_ns ASC
            """.trimIndent(),
            arrayOf(sessionId.toString())
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        WifiRouteMarker(
                            id = cursor.getString(0),
                            ssid = cursor.getString(1),
                            bssid = cursor.getString(2),
                            rssiDbm = cursor.getInt(3),
                            frequencyMhz = cursor.getInt(4),
                            measuredAtNs = cursor.getLong(5),
                            xMeters = cursor.getDouble(6),
                            yMeters = cursor.getDouble(7)
                        )
                    )
                }
            }
        }

        return StoredHomeRadarSession(summary, routePoints, wifiMarkers)
    }

    fun deleteSession(sessionId: Long) {
        writableDatabase.delete(TABLE_SESSIONS, "id = ?", arrayOf(sessionId.toString()))
    }

    private fun android.database.Cursor.toSummary() = SavedSessionSummary(
        id = getLong(0),
        startedAtEpochMs = getLong(1),
        finishedAtEpochMs = if (isNull(2)) null else getLong(2),
        status = getString(3),
        steps = getInt(4),
        distanceMeters = getDouble(5),
        routePointCount = getInt(6),
        wifiSampleCount = getInt(7)
    )

    private companion object {
        const val DATABASE_NAME = "homeradar-local.db"
        const val DATABASE_VERSION = 1
        const val DEFAULT_SESSION_LIMIT = 50
        const val MAX_SESSION_LIMIT = 200

        const val TABLE_SESSIONS = "sessions"
        const val TABLE_ROUTE_POINTS = "route_points"
        const val TABLE_WIFI_SAMPLES = "wifi_samples"

        const val STATUS_RECORDING = "recording"
        const val STATUS_FINISHED = "finished"
        const val STATUS_INTERRUPTED = "interrupted"
    }
}
