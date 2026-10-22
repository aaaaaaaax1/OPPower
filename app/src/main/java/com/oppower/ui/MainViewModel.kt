package com.oppower.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.oppower.battery.BatteryReader
import com.oppower.data.PreferencesStore
import com.oppower.history.ChartHistoryBus
import com.oppower.history.ChartHistoryStore
import com.oppower.model.BatterySnapshot
import com.oppower.model.ChargeRecord
import com.oppower.model.ChartPoint
import com.oppower.model.ControllerStatus
import com.oppower.model.GearReadResult
import com.oppower.model.RuntimeSettings
import com.oppower.model.conservatismRank
import com.oppower.model.toControlTier
import com.oppower.service.ChargeControlBus
import com.oppower.service.ChargeControlService
import com.oppower.shizuku.ShizukuGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

data class UiState(
    val battery: BatterySnapshot = BatterySnapshot(),
    val settings: RuntimeSettings = RuntimeSettings(),
    val controllerStatus: ControllerStatus = ControllerStatus(),
    val gearReadResult: GearReadResult? = null,
    val points: List<ChartPoint> = emptyList(),
    val chargeRecords: List<ChargeRecord> = emptyList(),
    val dischargeRecords: List<ChargeRecord> = emptyList(),
    val shizukuInstalled: Boolean = false,
    val shizukuAvailable: Boolean = false,
    val shizukuPermission: Boolean = false,
    val showIntro: Boolean = false,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val reader = BatteryReader(application)
    private val store = PreferencesStore(application)
    private val shizuku = ShizukuGateway(application)
    private val historyStore = ChartHistoryStore(application)
    private var historySignature = ""
    private val mutable = MutableStateFlow(
        UiState(
            settings = store.load(),
            showIntro = !store.introSeen(),
        ),
    )
    val state: StateFlow<UiState> = mutable.asStateFlow()

    init {
        viewModelScope.launch {
            // File decoding and record grouping stay off the UI thread.
            val initial = withContext(Dispatchers.IO) {
                val points = historyStore.load()
                val records = deriveRecords(points)
                Triple(
                    points,
                    records,
                    Triple(shizuku.isInstalled, shizuku.isAvailable, shizuku.hasPermission),
                )
            }
            mutable.value = mutable.value.copy(
                points = initial.first,
                chargeRecords = initial.second.first.takeLast(50),
                dischargeRecords = initial.second.second.takeLast(50),
                shizukuInstalled = initial.third.first,
                shizukuAvailable = initial.third.second,
                shizukuPermission = initial.third.third,
            )
            historySignature = initial.first.lastOrNull()?.let { "${initial.first.size}:${it.epochMillis}" }.orEmpty()
            // Publish the loaded history once. The service and view model share
            // this bus, so without this handoff the persisted points stay hidden
            // until the next new sample is recorded.
        }
        viewModelScope.launch {
            ChartHistoryBus.points.collectLatest { points ->
                val signature = points.lastOrNull()?.let { "${points.size}:${it.epochMillis}" }.orEmpty()
                if (signature == historySignature) return@collectLatest
                historySignature = signature
                val records = withContext(Dispatchers.Default) {
                    deriveRecords(points, latestOnly = true)
                }
                mutable.value = mutable.value.copy(
                    points = points,
                    chargeRecords = records.first.takeLast(50),
                    dischargeRecords = records.second.takeLast(50),
                )
            }
        }
        viewModelScope.launch {
            delay(50)
            while (true) {
                val battery = withContext(Dispatchers.IO) { reader.snapshot(mutable.value.settings) }
                mutable.value = mutable.value.copy(
                    battery = battery,
                    controllerStatus = ChargeControlBus.status.value,
                )
                delay(2_000)
            }
        }
    }

    fun updateSettings(transform: (RuntimeSettings) -> RuntimeSettings) {
        val oldSettings = mutable.value.settings
        val updated = transform(oldSettings).sanitized()
        store.save(updated)
        mutable.value = mutable.value.copy(settings = updated)
        if (!oldSettings.masterEnabled && updated.masterEnabled) {
            ChargeControlService.start(getApplication())
        }
        if (updated.historyEnabled != oldSettings.historyEnabled) {
            ChargeControlService.historySettingsChanged(getApplication())
        }
        ChargeControlBus.requestUpdate()
    }

    fun readCurrentGear() {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    require(shizuku.isAvailable) { "请先启动 Shizuku" }
                    require(shizuku.hasPermission) { "请先完成 Shizuku 授权" }
                    val raw = shizuku.readThermalPolicy()
                    Regex("gearVal:\\s*(-?\\d+)")
                        .find(raw)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                        ?: error("系统暂未返回档位")
                }
            }
            mutable.value = mutable.value.copy(
                gearReadResult = result.fold(
                    onSuccess = { GearReadResult(it, now) },
                    onFailure = { GearReadResult(null, now, it.message ?: "读取失败") },
                ),
            )
        }
    }

    fun requestShizukuPermission() {
        if (shizuku.hasPermission) {
            shizuku.bind()
            ChargeControlBus.requestUpdate()
            return
        }
        if (!shizuku.isAvailable) {
            if (!shizuku.openManager()) shizuku.openInstallPage()
        } else {
            shizuku.requestPermission()
        }
        refreshShizukuState()
    }

    fun onShizukuPermissionGranted() {
        shizuku.bind()
        refreshShizukuState()
        ChargeControlBus.requestUpdate()
    }

    fun refreshShizukuState() {
        viewModelScope.launch {
            val state = withContext(Dispatchers.IO) {
                Triple(shizuku.isInstalled, shizuku.isAvailable, shizuku.hasPermission)
            }
            mutable.value = mutable.value.copy(
                shizukuInstalled = state.first,
                shizukuAvailable = state.second,
                shizukuPermission = state.third,
            )
        }
    }

    fun restoreAll() {
        updateSettings { it.copy(masterEnabled = false) }
        ChargeControlService.restore(getApplication())
    }

    fun clearHistory() {
        historyStore.clear()
        ChartHistoryBus.points.value = emptyList()
        mutable.value = mutable.value.copy(
            points = emptyList(),
            chargeRecords = emptyList(),
            dischargeRecords = emptyList(),
        )
    }

    fun dismissIntro() {
        store.markIntroSeen()
        mutable.value = mutable.value.copy(showIntro = false)
    }

    private fun RuntimeSettings.sanitized(): RuntimeSettings {
        val sourceTemperatures = customRules.temperatureRules.take(2)
        var previous = 29.5f
        val normalized = sourceTemperatures.mapIndexed { index, rule ->
            val minimum = previous + 0.5f
            val maximum = 46f - (sourceTemperatures.lastIndex - index) * 0.5f
            val threshold = rule.threshold.coerceIn(minimum, maximum)
            previous = threshold
            rule.copy(threshold = threshold)
        }
        var maximum = baseTier.toControlTier()
        val orderedTargets = normalized.map { rule ->
            val target = if (rule.target.conservatismRank() <= maximum.conservatismRank()) {
                rule.target
            } else {
                maximum
            }
            maximum = target
            rule.copy(target = target)
        }
        val sourceSoc = customRules.socRules.take(2)
        var previousSoc = 0
        var maximumSoc = baseTier.toControlTier()
        val orderedSocTargets = sourceSoc.mapIndexed { index, rule ->
            val minimum = previousSoc + 1
            val maximum = 100 - (sourceSoc.lastIndex - index)
            val threshold = rule.thresholdPercent.coerceIn(minimum, maximum)
            previousSoc = threshold
            val target = if (rule.target.conservatismRank() <= maximumSoc.conservatismRank()) {
                rule.target
            } else {
                maximumSoc
            }
            maximumSoc = target
            rule.copy(thresholdPercent = threshold, target = target)
        }
        return copy(
            customRules = customRules.copy(
                temperatureRules = orderedTargets,
                socRules = orderedSocTargets,
                socStopPercent = customRules.socStopPercent.coerceIn(1, 100),
            ),
        )
    }

    private fun deriveRecords(
        points: List<ChartPoint>,
        latestOnly: Boolean = false,
    ): Pair<List<ChargeRecord>, List<ChargeRecord>> {
        val groups = mutableListOf<List<ChartPoint>>()
        var current = mutableListOf<ChartPoint>()
        points.sortedBy { it.epochMillis }.forEach { point ->
            if (current.isEmpty() || current.last().acOnline == point.acOnline) {
                current += point
            } else {
                groups += current
                current = mutableListOf(point)
            }
        }
        if (current.isNotEmpty()) groups += current
        val relevantGroups = if (latestOnly) groups.takeLast(2) else groups
        val records = relevantGroups.filter { it.size >= 2 }.map(::toRecord)
        return records.partition { record ->
            record.points.firstOrNull()?.acOnline == true
        }
    }

    private fun toRecord(group: List<ChartPoint>): ChargeRecord {
        val first = group.first()
        val last = group.last()
        val charging = first.acOnline
        val record = ChargeRecord(
            startedAtMillis = first.epochMillis,
            endedAtMillis = last.epochMillis,
            startPercent = first.levelPercent,
            endPercent = last.levelPercent,
            peakPowerW = group.maxOf { it.powerW },
            averagePowerW = group.map { it.powerW }.average().toFloat(),
            maxTemperatureC = group.maxOf { it.temperatureC },
            startVoltageV = first.voltageMv / 1000f,
            endVoltageV = last.voltageMv / 1000f,
            peakCurrentMa = group.maxOf { kotlin.math.abs(it.currentMa) },
            averageCurrentMa = group.map { kotlin.math.abs(it.currentMa) }.average().roundToInt(),
            chargeDeltaMah = if (charging) {
                group.zipWithNext().sumOf { (left, right) ->
                    val seconds = ((right.epochMillis - left.epochMillis).coerceAtLeast(0L)) / 1000.0
                    ((kotlin.math.abs(left.currentMa) + kotlin.math.abs(right.currentMa)) / 2.0) * seconds / 3600.0
                }.roundToInt().takeIf { it > 0 }
            } else null,
            endReason = when {
                charging && last.levelPercent >= 100 -> "充满"
                charging -> "拔电"
                else -> "放电会话"
            },
            points = group,
        )
        return record
    }
}
