package com.oppower.history

import android.content.Context
import com.oppower.model.BatterySnapshot
import com.oppower.model.ChartPoint
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object ChartHistoryBus {
    val points = MutableStateFlow<List<ChartPoint>>(emptyList())
}

class ChartHistoryStore(context: Context) {
    private val file = File(context.filesDir, FILE_NAME)

    fun load(nowMillis: Long = System.currentTimeMillis()): List<ChartPoint> = synchronized(LOCK) {
        if (!file.exists()) return emptyList()
        runCatching {
            ChartHistoryCodec.decode(file.readText())
                .filter { it.epochMillis >= nowMillis - RETENTION_MILLIS }
                .sortedBy { it.epochMillis }
        }.getOrDefault(emptyList())
    }

    fun record(battery: BatterySnapshot, nowMillis: Long = System.currentTimeMillis()): List<ChartPoint> =
        synchronized(LOCK) {
            val existing = load(nowMillis)
            val last = existing.lastOrNull()
            if (last != null && nowMillis - last.epochMillis < SAMPLE_INTERVAL_MILLIS) {
                return existing
            }
            val updated = (existing + ChartPoint(
                epochMillis = nowMillis,
                powerW = battery.powerW,
                temperatureC = battery.temperatureC,
                levelPercent = battery.levelPercent,
                acOnline = battery.acOnline,
                currentMa = battery.currentMa,
                voltageMv = battery.voltageMv,
            ))
                .filter { it.epochMillis >= nowMillis - RETENTION_MILLIS }
                .takeLast(MAX_POINTS)
            writeAtomically(ChartHistoryCodec.encode(updated))
            updated
        }

    fun clear() = synchronized(LOCK) {
        file.delete()
    }

    private fun writeAtomically(content: String) {
        val temporary = File(file.parentFile, "$FILE_NAME.tmp")
        temporary.writeText(content)
        runCatching {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.getOrElse {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        private const val FILE_NAME = "chart-history-24h.csv"
        private const val RETENTION_MILLIS = 24L * 60L * 60L * 1_000L
        private const val SAMPLE_INTERVAL_MILLIS = 30_000L
        private const val MAX_POINTS = 3_000
        private val LOCK = Any()
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
