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
import com.oppower.model.RuntimeSettings
import com.oppower.model.conservatismRank
import com.oppower.model.toControlTier
import com.oppower.service.ChargeControlBus
import com.oppower.service.ChargeControlService
import com.oppower.shizuku.ShizukuGateway
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class UiState(
    val battery: BatterySnapshot = BatterySnapshot(),
    val settings: RuntimeSettings = RuntimeSettings(),
    val controllerStatus: ControllerStatus = ControllerStatus(),
    val points: List<ChartPoint> = emptyList(),
    val chargeRecords: List<ChargeRecord> = emptyList(),
    val dischargeRecords: List<ChargeRecord> = emptyList(),
    val shizukuAvailable: Boolean = false,
    val shizukuPermission: Boolean = false,
    val showIntro: Boolean = false,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val reader = BatteryReader(application)
    private val store = PreferencesStore(application)
    private val shizuku = ShizukuGateway(application)
    private val historyStore = ChartHistoryStore(application)
    private val mutable = MutableStateFlow(
        UiState(
            settings = store.load(),
            points = historyStore.load(),
            showIntro = !store.introSeen(),
            shizukuAvailable = shizuku.isAvailable,
            shizukuPermission = shizuku.hasPermission,
        ),
    )
    val state: StateFlow<UiState> = mutable.asStateFlow()

    init {
        viewModelScope.launch {
            ChartHistoryBus.points.collectLatest { points ->
                mutable.value = mutable.value.copy(points = points)
            }
        }
        viewModelScope.launch {
            while (true) {
                val battery = reader.snapshot()
                val previous = mutable.value.points.lastOrNull()
                val points = mutable.value.points.ifEmpty { historyStore.load() }
                val completed = if (previous != null && previous.acOnline != battery.acOnline) {
                    toRecord(points)
                } else {
                    null
                }
                mutable.value = mutable.value.copy(
                    battery = battery,
                    controllerStatus = ChargeControlBus.status.value,
                    points = points,
                    chargeRecords = if (completed?.first == true) {
                        (mutable.value.chargeRecords + completed.second).takeLast(10)
                    } else mutable.value.chargeRecords,
                    dischargeRecords = if (completed?.first == false) {
                        (mutable.value.dischargeRecords + completed.second).takeLast(10)
                    } else mutable.value.dischargeRecords,
                    shizukuAvailable = shizuku.isAvailable,
                    shizukuPermission = shizuku.hasPermission,
                )
                delay(2_000)
            }
        }
    }

    fun updateSettings(transform: (RuntimeSettings) -> RuntimeSettings) {
        val updated = transform(mutable.value.settings).sanitized()
        store.save(updated)
        mutable.value = mutable.value.copy(settings = updated)
        ChargeControlBus.requestUpdate()
    }

    fun requestShizukuPermission() {
        if (shizuku.hasPermission) {
            shizuku.bind()
            ChargeControlBus.requestUpdate()
            return
        }
        shizuku.requestPermission()
    }

    fun onShizukuPermissionGranted() {
        shizuku.bind()
        mutable.value = mutable.value.copy(shizukuPermission = shizuku.hasPermission)
        ChargeControlBus.requestUpdate()
    }

    fun restoreAll() {
        updateSettings { it.copy(masterEnabled = false) }
        ChargeControlService.restore(getApplication())
    }

    fun dismissIntro() {
        store.markIntroSeen()
        mutable.value = mutable.value.copy(showIntro = false)
    }

    private fun RuntimeSettings.sanitized(): RuntimeSettings {
        var previous = 29.5f
        val normalized = customRules.temperatureRules.map { rule ->
            val threshold = rule.threshold.coerceIn((previous + 0.5f).coerceAtMost(46f), 46f)
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
        return copy(
            customRules = customRules.copy(
                temperatureRules = orderedTargets,
                socStopPercent = customRules.socStopPercent.coerceIn(1, 100),
            ),
        )
    }

    private fun toRecord(points: List<ChartPoint>): Pair<Boolean, ChargeRecord>? {
        if (points.size < 2) return null
        val charging = points.last().acOnline
        val group = points.filter { it.acOnline == charging }
        if (group.size < 2) return null
        val first = group.first()
        val last = group.last()
        val record = ChargeRecord(
            startedAtMillis = first.epochMillis,
            endedAtMillis = last.epochMillis,
            startPercent = first.levelPercent,
            endPercent = last.levelPercent,
            peakPowerW = group.maxOf { it.powerW },
            averagePowerW = group.map { it.powerW }.average().toFloat(),
            maxTemperatureC = group.maxOf { it.temperatureC },
        )
        return charging to record
    }
}
