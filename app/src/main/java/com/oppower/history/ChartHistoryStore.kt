package com.oppower.history

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.oppower.model.BatterySnapshot
import com.oppower.model.ChartPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

object ChartHistoryBus {
    val points = MutableStateFlow<List<ChartPoint>>(emptyList())

    fun mergeLoaded(loaded: List<ChartPoint>) {
        points.update { current ->
            if (current.isEmpty()) {
                CurveProcessing.historyWithinWindow(loaded)
            } else {
                CurveProcessing.historyWithinWindow(
                    (loaded + current)
                        .associateBy { it.epochMillis }
                        .values
                        .sortedBy { it.epochMillis },
                )
            }
        }
    }

    fun append(point: ChartPoint) {
        points.update { current ->
            when {
                current.isEmpty() -> listOf(point)
                current.last().epochMillis == point.epochMillis -> current
                current.last().epochMillis > point.epochMillis -> CurveProcessing.historyWithinWindow(
                    (current + point)
                        .associateBy { it.epochMillis }
                        .values
                        .sortedBy { it.epochMillis },
                )
                else -> CurveProcessing.historyWithinWindow(current + point)
            }
        }
    }

    fun reset() {
        points.value = emptyList()
    }
}

/**
 * Persists raw battery samples for session records. Page metrics are sampled at
 * 1 s by the UI, while chart history follows LLPower's persisted cadence:
 * charging/screen-on 3 s, charging/screen-off 5 s, idle 180 s.
 */
class ChartHistoryStore(context: Context) {
    private val appContext = context.applicationContext
    private val database = HistoryDatabase(appContext)

    fun load(nowMillis: Long = System.currentTimeMillis()): List<ChartPoint> = synchronized(LOCK) {
        val cutoff = (nowMillis - RETENTION_MILLIS).coerceAtLeast(0L)
        val loaded = readSince(cutoff)
        lastRecordedMillis = loaded.lastOrNull()?.epochMillis ?: latestEpoch() ?: 0L
        loaded
    }

    /**
     * Returns only a newly persisted point. Updating a 24 h list on every due
     * sample made the service repeatedly read the whole table; callers now
     * publish this point through [ChartHistoryBus] incrementally.
     */
    fun record(
        battery: BatterySnapshot,
        nowMillis: Long = System.currentTimeMillis(),
        screenOn: Boolean = true,
    ): ChartPoint? = synchronized(LOCK) {
        val interval = sampleIntervalMillis(battery.isCharging, screenOn)
        val previous = lastRecordedMillis ?: latestEpoch() ?: 0L
        lastRecordedMillis = previous
        if (previous > 0L && (nowMillis <= previous || nowMillis - previous < interval)) {
            return@synchronized null
        }
        if (!battery.sampleValid) return@synchronized null

        val point = ChartPoint(
            epochMillis = nowMillis,
            powerW = battery.powerW,
            temperatureC = battery.temperatureC,
            levelPercent = battery.levelPercent,
            acOnline = battery.acOnline,
            currentMa = battery.currentMa,
            voltageMv = battery.voltageMv,
        )
        val inserted = database.writableDatabase.insertWithOnConflict(
            HistoryDatabase.TABLE,
            null,
            point.toContentValues(),
            SQLiteDatabase.CONFLICT_IGNORE,
        )
        if (inserted == -1L) return@synchronized null

        lastRecordedMillis = nowMillis
        if (nowMillis - lastPurgeMillis >= PURGE_INTERVAL_MILLIS) {
            database.writableDatabase.delete(
                HistoryDatabase.TABLE,
                "${HistoryDatabase.COLUMN_EPOCH} < ?",
                arrayOf((nowMillis - RETENTION_MILLIS).coerceAtLeast(0L).toString()),
            )
            lastPurgeMillis = nowMillis
        }
        point
    }

    fun clear() = synchronized(LOCK) {
        database.writableDatabase.delete(HistoryDatabase.TABLE, null, null)
        File(appContext.filesDir, LEGACY_FILE_NAME).delete()
        lastRecordedMillis = 0L
        lastPurgeMillis = 0L
    }

    private fun latestEpoch(): Long? {
        return database.readableDatabase.rawQuery(
            "SELECT MAX(${HistoryDatabase.COLUMN_EPOCH}) FROM ${HistoryDatabase.TABLE}",
            null,
        ).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
    }

    private fun readSince(cutoff: Long): List<ChartPoint> {
        val points = ArrayList<ChartPoint>()
        database.readableDatabase.rawQuery(
            "SELECT ${HistoryDatabase.COLUMN_EPOCH}, ${HistoryDatabase.COLUMN_POWER}, " +
                "${HistoryDatabase.COLUMN_TEMPERATURE}, ${HistoryDatabase.COLUMN_SOC}, " +
                "${HistoryDatabase.COLUMN_AC}, ${HistoryDatabase.COLUMN_CURRENT}, ${HistoryDatabase.COLUMN_VOLTAGE} " +
                "FROM ${HistoryDatabase.TABLE} WHERE ${HistoryDatabase.COLUMN_EPOCH} >= ? " +
                "ORDER BY ${HistoryDatabase.COLUMN_EPOCH} ASC LIMIT ?",
            arrayOf(cutoff.toString(), MAX_POINTS.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                points += ChartPoint(
                    epochMillis = cursor.getLong(0),
                    powerW = cursor.getFloat(1),
                    temperatureC = cursor.getFloat(2),
                    levelPercent = cursor.getInt(3),
                    acOnline = cursor.getInt(4) != 0,
                    currentMa = cursor.getInt(5),
                    voltageMv = cursor.getInt(6),
                )
            }
        }
        return points
    }

    private fun ChartPoint.toContentValues() = ContentValues().apply {
        put(HistoryDatabase.COLUMN_EPOCH, epochMillis)
        put(HistoryDatabase.COLUMN_POWER, powerW)
        put(HistoryDatabase.COLUMN_TEMPERATURE, temperatureC)
        put(HistoryDatabase.COLUMN_SOC, levelPercent)
        put(HistoryDatabase.COLUMN_AC, if (acOnline) 1 else 0)
        put(HistoryDatabase.COLUMN_CURRENT, currentMa)
        put(HistoryDatabase.COLUMN_VOLTAGE, voltageMv)
    }

    private class HistoryDatabase(private val context: Context) :
        android.database.sqlite.SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
        init {
            setWriteAheadLoggingEnabled(true)
        }

        override fun onConfigure(db: SQLiteDatabase) {
            super.onConfigure(db)
            db.setForeignKeyConstraintsEnabled(true)
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE (" +
                    "$COLUMN_EPOCH INTEGER PRIMARY KEY, " +
                    "$COLUMN_POWER REAL NOT NULL, " +
                    "$COLUMN_TEMPERATURE REAL NOT NULL, " +
                    "$COLUMN_SOC INTEGER NOT NULL, " +
                    "$COLUMN_AC INTEGER NOT NULL, " +
                    "$COLUMN_CURRENT INTEGER NOT NULL, " +
                    "$COLUMN_VOLTAGE INTEGER NOT NULL)",
            )
            migrateLegacyCsv(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

        private fun migrateLegacyCsv(db: SQLiteDatabase) {
            val legacy = File(context.filesDir, LEGACY_FILE_NAME)
            if (!legacy.exists()) return
            val points = runCatching { ChartHistoryCodec.decode(legacy.readText()) }.getOrDefault(emptyList())
            if (points.isNotEmpty()) {
                db.beginTransaction()
                try {
                    points.forEach { point ->
                        db.insertWithOnConflict(
                            TABLE,
                            null,
                            ContentValues().apply {
                                put(COLUMN_EPOCH, point.epochMillis)
                                put(COLUMN_POWER, point.powerW)
                                put(COLUMN_TEMPERATURE, point.temperatureC)
                                put(COLUMN_SOC, point.levelPercent)
                                put(COLUMN_AC, if (point.acOnline) 1 else 0)
                                put(COLUMN_CURRENT, point.currentMa)
                                put(COLUMN_VOLTAGE, point.voltageMv)
                            },
                            SQLiteDatabase.CONFLICT_IGNORE,
                        )
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
            legacy.delete()
        }

        companion object {
            const val DATABASE_NAME = "chart-history-24h.db"
            const val DATABASE_VERSION = 1
            const val TABLE = "samples"
            const val COLUMN_EPOCH = "epoch_ms"
            const val COLUMN_POWER = "power_w"
            const val COLUMN_TEMPERATURE = "temperature_c"
            const val COLUMN_SOC = "soc"
            const val COLUMN_AC = "ac_online"
            const val COLUMN_CURRENT = "current_ma"
            const val COLUMN_VOLTAGE = "voltage_mv"
        }
    }

    companion object {
        private const val LEGACY_FILE_NAME = "chart-history-24h.csv"
        private const val RETENTION_MILLIS = 24L * 60L * 60L * 1_000L
        private const val MAX_POINTS = 24 * 60 * 60
        private const val PURGE_INTERVAL_MILLIS = 60L * 60L * 1_000L
        private val LOCK = Any()
        private var lastRecordedMillis: Long? = null
        private var lastPurgeMillis = 0L

        internal fun sampleIntervalMillis(isCharging: Boolean, screenOn: Boolean): Long = when {
            isCharging && screenOn -> 3_000L
            isCharging -> 5_000L
            else -> 180_000L
        }
    }
}

internal object ChartHistoryCodec {
    fun encode(points: List<ChartPoint>): String = buildString {
        append("epoch,power,temp,soc,ac,current,voltage\n")
        points.forEach { point ->
            append(point.epochMillis).append(',')
            append(point.powerW).append(',')
            append(point.temperatureC).append(',')
            append(point.levelPercent).append(',')
            append(if (point.acOnline) 1 else 0).append(',')
            append(point.currentMa).append(',')
            append(point.voltageMv).append('\n')
        }
    }

    fun decode(content: String): List<ChartPoint> = content.lineSequence()
        .drop(1)
        .mapNotNull { line ->
            val values = line.split(',')
            if (values.size !in 5..7) return@mapNotNull null
            runCatching {
                ChartPoint(
                    epochMillis = values[0].toLong(),
                    powerW = values[1].toFloat(),
                    temperatureC = values[2].toFloat(),
                    levelPercent = values[3].toInt(),
                    acOnline = values[4] == "1",
                    currentMa = values.getOrNull(5)?.toIntOrNull() ?: 0,
                    voltageMv = values.getOrNull(6)?.toIntOrNull() ?: 0,
                )
            }.getOrNull()
        }
        .toList()
}
