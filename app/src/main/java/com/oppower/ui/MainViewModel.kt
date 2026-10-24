package com.oppower.ui

import android.app.Application
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.oppower.battery.BatteryReader
import com.oppower.controller.ChargingController
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
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
    private val pageVisible = MutableStateFlow(false)
    private var historySignature = ""
    private val historyWriteInFlight = AtomicBoolean(false)
    @Volatile private var historyLoaded = false
    private var currentSessionOnline: Boolean? = null
    private var currentSessionRecord: ChargeRecord? = null
    private val mutable = MutableStateFlow(
        UiState(
            settings = store.load(),
            showIntro = !store.introSeen(),
        ),
    )
    val state: StateFlow<UiState> = mutable.asStateFlow()

    init {
        viewModelScope.launch {
            val initial = withContext(Dispatchers.IO) {
                val points = historyStore.load()
                points to Triple(shizuku.isInstalled, shizuku.isAvailable, shizuku.hasPermission)
            }
            val points = initial.first
            currentSessionOnline = points.lastOrNull()?.acOnline
            mutable.value = mutable.value.copy(
                points = points,
                shizukuInstalled = initial.second.first,
                shizukuAvailable = initial.second.second,
                shizukuPermission = initial.second.third,
            )
            historySignature = signature(points)
            historyLoaded = true
            // Publish the persisted history once. The control service may already
            // hold newer samples, so mergeLoaded preserves both sides.
            ChartHistoryBus.mergeLoaded(points)

            // Session statistics are useful below the fold. Publish the persisted
            // samples first so the main page and controls can become responsive
            // without waiting for a full 24 hour record derivation.
            val records = withContext(Dispatchers.Default) { deriveRecords(points) }
            if (mutable.value.points === points) {
                currentSessionRecord = seedSessionRecord(
                    points.lastOrNull(),
                    if (points.lastOrNull()?.acOnline == true) records.first else records.second,
                )
                mutable.value = mutable.value.copy(
                    chargeRecords = records.first.takeLast(50),
                    dischargeRecords = records.second.takeLast(50),
                )
            }
        }

        viewModelScope.launch {
            ChartHistoryBus.points.collectLatest { points ->
                if (!historyLoaded) return@collectLatest
                val nextSignature = signature(points)
                if (nextSignature == historySignature) return@collectLatest
                if (points.isEmpty()) {
                    historySignature = ""
                    currentSessionOnline = null
                    currentSessionRecord = null
                    mutable.value = mutable.value.copy(
                        points = emptyList(),
                        chargeRecords = emptyList(),
                        dischargeRecords = emptyList(),
                    )
                    return@collectLatest
                }
                val lastKnown = mutable.value.points.lastOrNull()?.epochMillis ?: Long.MIN_VALUE
                val additions = points.filter { it.epochMillis > lastKnown }
                if (additions.isEmpty()) {
                    // mergeLoaded can replace an initial snapshot without adding
                    // samples. Refresh records once in that uncommon case.
                    val records = withContext(Dispatchers.Default) { deriveRecords(points) }
                    currentSessionOnline = points.last().acOnline
                    currentSessionRecord = seedSessionRecord(
                        points.last(),
                        if (points.last().acOnline) records.first else records.second,
                    )
                    mutable.value = mutable.value.copy(
                        points = points,
                        chargeRecords = records.first.takeLast(50),
                        dischargeRecords = records.second.takeLast(50),
                    )
                } else {
                    additions.forEach(::appendHistoryPoint)
                }
                historySignature = nextSignature
            }
        }

        viewModelScope.launch {
            pageVisible.collectLatest { visible ->
                if (!visible) return@collectLatest
                delay(50)
                while (isActive) {
                    try {
                        val settings = mutable.value.settings
                        val battery = withContext(Dispatchers.IO) {
                            runCatching { reader.snapshot(settings) }
                                .onFailure { android.util.Log.w("OPPower", "Battery sample failed", it) }
                                .getOrNull()
                        }
                        if (battery != null) {
                            mutable.value = mutable.value.copy(
                                battery = battery,
                                controllerStatus = ChargeControlBus.status.value,
                            )
                            if (settings.historyEnabled &&
                                historyWriteInFlight.compareAndSet(false, true)
                            ) {
                                viewModelScope.launch(Dispatchers.IO) {
                                    try {
                                        val screenOn = getApplication<Application>()
                                            .getSystemService(PowerManager::class.java)
                                            .isInteractive
                                        historyStore.record(battery, screenOn = screenOn)
                                            ?.let(ChartHistoryBus::append)
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (error: Throwable) {
                                        android.util.Log.w("OPPower", "History sample failed", error)
                                    } finally {
                                        historyWriteInFlight.set(false)
                                    }
                                }
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        // A transient battery or database failure must not stop the
                        // one-second page refresh permanently.
                        android.util.Log.w("OPPower", "Battery refresh cycle failed", error)
                    }
                    delay(1_000L)
                }
            }
        }
    }

    fun setPageVisible(visible: Boolean) {
        pageVisible.value = visible
    }

    fun updateSettings(transform: (RuntimeSettings) -> RuntimeSettings) {
        val oldSettings = mutable.value.settings
        val updated = transform(oldSettings).sanitized()
        store.save(updated)
        mutable.value = mutable.value.copy(settings = updated)
        val controlChanged =
            oldSettings.masterEnabled != updated.masterEnabled ||
                oldSettings.baseTier != updated.baseTier ||
                oldSettings.customRulesEnabled != updated.customRulesEnabled ||
                oldSettings.customRules != updated.customRules ||
                oldSettings.temperatureDownshiftSeconds != updated.temperatureDownshiftSeconds ||
                oldSettings.temperatureUpshiftSeconds != updated.temperatureUpshiftSeconds ||
                oldSettings.socDownshiftSeconds != updated.socDownshiftSeconds ||
                oldSettings.socUpshiftSeconds != updated.socUpshiftSeconds
        val serviceAvailabilityChanged =
            oldSettings.liveNotificationEnabled != updated.liveNotificationEnabled
        if (controlChanged || serviceAvailabilityChanged) {
            ChargeControlService.controlSettingsChanged(getApplication())
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
                    ChargingController(shizuku).readResolvedGear()
                }
            }
            mutable.value = mutable.value.copy(
                gearReadResult = result.fold(
                    onSuccess = {
                        GearReadResult(
                            gear = it.gear,
                            readAtMillis = now,
                            baseGear = it.baseGear,
                            calculatedGear = it.calculatedGear,
                            cosa = it.cosa,
                            chargeVal = it.chargeVal,
                            source = it.source,
                        )
                    },
                    onFailure = { GearReadResult(null, now, error = it.message ?: "读取失败") },
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
        currentSessionOnline = null
        currentSessionRecord = null
        historySignature = ""
        ChartHistoryBus.reset()
        mutable.value = mutable.value.copy(
            points = emptyList(),
            chargeRecords = emptyList(),
            dischargeRecords = emptyList(),
        )
        viewModelScope.launch(Dispatchers.IO) { historyStore.clear() }
    }

    fun dismissIntro() {
        store.markIntroSeen()
        mutable.value = mutable.value.copy(showIntro = false)
    }

    private fun appendHistoryPoint(point: ChartPoint) {
        val current = mutable.value
        val sameSession = currentSessionOnline == point.acOnline
        val base = if (sameSession) currentSessionRecord else null
        val updated = toRecord((base?.points ?: emptyList()) + point)
        currentSessionOnline = point.acOnline
        currentSessionRecord = updated

        val charge = current.chargeRecords.toMutableList()
        val discharge = current.dischargeRecords.toMutableList()
        val target = if (point.acOnline) charge else discharge
        val alreadyListed = sameSession && (base?.points?.size ?: 0) >= 2 &&
            target.lastOrNull()?.startedAtMillis == updated.startedAtMillis
        if (alreadyListed) {
            target[target.lastIndex] = updated
        } else if (updated.points.size >= 2) {
            target += updated
        }
        mutable.value = current.copy(
            points = current.points + point,
            chargeRecords = charge.takeLast(50),
            dischargeRecords = discharge.takeLast(50),
        )
    }

    private fun seedSessionRecord(
        point: ChartPoint?,
        records: List<ChargeRecord>,
    ): ChargeRecord? {
        if (point == null) return null
        return records.lastOrNull()?.takeIf { it.endedAtMillis == point.epochMillis }
            ?: toRecord(listOf(point))
    }

    private fun signature(points: List<ChartPoint>): String =
        points.lastOrNull()?.let { "${points.size}:${it.epochMillis}" }.orEmpty()

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

    private fun deriveRecords(points: List<ChartPoint>): Pair<List<ChargeRecord>, List<ChargeRecord>> {
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
        val records = groups.filter { it.size >= 2 }.map(::toRecord)
        return records.partition { record -> record.points.firstOrNull()?.acOnline == true }
    }

    private fun toRecord(group: List<ChartPoint>): ChargeRecord {
        val first = group.first()
        val last = group.last()
        val charging = first.acOnline
        return ChargeRecord(
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
    }
}
