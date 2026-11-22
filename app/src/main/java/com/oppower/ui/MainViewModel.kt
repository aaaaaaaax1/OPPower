package com.oppower.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Application
import android.app.NotificationManager
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.oppower.battery.BatteryReader
import com.oppower.controller.ChargingController
import com.oppower.data.PreferencesStore
import com.oppower.history.ChargeRecordAccumulator
import com.oppower.history.ChartHistoryBus
import com.oppower.history.ChartHistoryStore
import com.oppower.history.CurveProcessing
import com.oppower.history.buildChargeRecords
import com.oppower.model.BatterySnapshot
import com.oppower.model.ChargeRecord
import com.oppower.model.ChartPoint
import com.oppower.model.ControllerPhase
import com.oppower.model.ControllerStatus
import com.oppower.model.GearReadResult
import com.oppower.model.RuntimeSettings
import com.oppower.model.SystemAccessState
import com.oppower.model.conservatismRank
import com.oppower.model.toControlTier
import com.oppower.service.ChargeControlBus
import com.oppower.service.ChargeControlService
import com.oppower.service.StatusNotificationChannels
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
    val shizukuServiceConnected: Boolean = false,
    val systemAccess: SystemAccessState = SystemAccessState(),
    val showIntro: Boolean = false,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val shizuku = ShizukuGateway(application)
    private val reader = BatteryReader(application, shizuku::readBatteryFallback)
    private val store = PreferencesStore(application)
    private val historyStore = ChartHistoryStore(application)
    private val pageVisible = MutableStateFlow(false)
    private var historySignature = ""
    private val historyWriteInFlight = AtomicBoolean(false)
    @Volatile private var historyLoaded = false
    private var currentSessionAccumulator: ChargeRecordAccumulator? = null
    private val mutable = MutableStateFlow(
        UiState(
            settings = store.load(),
            showIntro = !store.introSeen(),
        ),
    )
    val state: StateFlow<UiState> = mutable.asStateFlow()

    init {
        shizuku.bind()
        viewModelScope.launch {
            val initial = withContext(Dispatchers.IO) {
                historyStore.load() to readRuntimeAccessSnapshot()
            }
            val storedPoints = initial.first
            val recentPoints = CurveProcessing.historyWithinWindow(storedPoints)
            val access = initial.second
            currentSessionAccumulator = null
            mutable.value = mutable.value.copy(
                points = recentPoints,
                shizukuInstalled = access.shizukuInstalled,
                shizukuAvailable = access.shizukuAvailable,
                shizukuPermission = access.shizukuPermission,
                shizukuServiceConnected = access.shizukuServiceConnected,
                systemAccess = access.systemAccess,
            )
            historySignature = signature(recentPoints)
            historyLoaded = true
            // The database keeps 24 hours for record summaries, while only the
            // drawable eight-hour chart window stays resident in memory.
            ChartHistoryBus.mergeLoaded(recentPoints)

            val records = withContext(Dispatchers.Default) { buildChargeRecords(storedPoints) }
            currentSessionAccumulator = records.currentSession
            if (mutable.value.points.lastOrNull()?.epochMillis == recentPoints.lastOrNull()?.epochMillis) {
                mutable.value = mutable.value.copy(
                    chargeRecords = records.chargingRecords.takeLast(50),
                    dischargeRecords = records.dischargingRecords.takeLast(50),
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
                    currentSessionAccumulator = null
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
                    mutable.value = mutable.value.copy(points = points)
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
                        val shared = ChargeControlBus.battery.value
                        val nowMillis = SystemClock.elapsedRealtime()
                        val sharedIsFresh = shared != null &&
                            nowMillis - shared.capturedAtElapsedMillis in 0L..1_500L
                        val battery = if (sharedIsFresh) {
                            shared?.snapshot
                        } else {
                            withContext(Dispatchers.IO) {
                                runCatching { reader.snapshot(settings) }
                                    .onFailure { android.util.Log.w("OPPower", "Battery sample failed", it) }
                                    .getOrNull()
                            }
                        }
                        if (battery != null) {
                            mutable.value = mutable.value.copy(
                                battery = battery,
                                controllerStatus = controllerStatusForDisplay(
                                    settings = settings,
                                    battery = battery,
                                    status = ChargeControlBus.status.value,
                                ),
                            )
                            if (!sharedIsFresh && settings.historyEnabled &&
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
                oldSettings.nativeLevelZeroEnabled != updated.nativeLevelZeroEnabled ||
                oldSettings.screenOffBoostEnabled != updated.screenOffBoostEnabled ||
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
        viewModelScope.launch {
            delay(500)
            refreshRuntimeStatuses()
        }
        ChargeControlBus.requestUpdate()
    }

    fun refreshShizukuState() {
        refreshRuntimeStatuses()
    }

    fun refreshRuntimeStatuses() {
        viewModelScope.launch {
            val access = withContext(Dispatchers.IO) { readRuntimeAccessSnapshot() }
            mutable.value = mutable.value.copy(
                shizukuInstalled = access.shizukuInstalled,
                shizukuAvailable = access.shizukuAvailable,
                shizukuPermission = access.shizukuPermission,
                shizukuServiceConnected = access.shizukuServiceConnected,
                systemAccess = access.systemAccess,
            )
        }
    }

    fun restoreAll() {
        updateSettings {
            it.copy(
                masterEnabled = false,
                nativeLevelZeroEnabled = false,
            )
        }
        ChargeControlService.restore(getApplication())
    }

    fun clearHistory() {
        currentSessionAccumulator = null
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

    private fun controllerStatusForDisplay(
        settings: RuntimeSettings,
        battery: BatterySnapshot,
        status: ControllerStatus,
    ): ControllerStatus = if (
        (settings.masterEnabled || settings.nativeLevelZeroEnabled) &&
        status.phase == ControllerPhase.DISABLED
    ) {
        val enabledLabel = if (settings.nativeLevelZeroEnabled) "高功率保活已开启" else "强制亮屏快充已开启"
        ControllerStatus(
            phase = ControllerPhase.WAITING_FOR_POWER,
            message = if (battery.acOnline) "$enabledLabel · 正在连接" else "$enabledLabel · 接电后自动开始",
        )
    } else {
        status
    }

    private fun readRuntimeAccessSnapshot(): RuntimeAccessSnapshot {
        val app = getApplication<Application>()
        val notificationManager = app.getSystemService(NotificationManager::class.java)
        val accessibilityManager = app.getSystemService(AccessibilityManager::class.java)
        val accessibilityId =
            "${app.packageName}/${app.packageName}.service.KeepAliveAccessibilityService"
        return RuntimeAccessSnapshot(
            shizukuInstalled = shizuku.isInstalled,
            shizukuAvailable = shizuku.isAvailable,
            shizukuPermission = shizuku.hasPermission,
            shizukuServiceConnected = shizuku.isServiceConnected,
            systemAccess = SystemAccessState(
                notificationsAllowed = runCatching {
                    notificationManager.areNotificationsEnabled()
                }.getOrDefault(false),
                statusNotificationChannelEnabled = notificationChannelEnabled(
                    notificationManager,
                    StatusNotificationChannels.STATUS,
                ),
                liveNotificationChannelEnabled = notificationChannelEnabled(
                    notificationManager,
                    StatusNotificationChannels.LIVE,
                ),
                batteryUnrestricted = app.getSystemService(PowerManager::class.java)
                    .isIgnoringBatteryOptimizations(app.packageName),
                overlayAllowed = Settings.canDrawOverlays(app),
                accessibilityEnabled = accessibilityManager
                    .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                    .any { it.id.equals(accessibilityId, ignoreCase = true) },
            ),
        )
    }

    private fun notificationChannelEnabled(
        manager: NotificationManager,
        channelId: String,
    ): Boolean = runCatching {
        manager.getNotificationChannel(channelId)?.importance != NotificationManager.IMPORTANCE_NONE
    }.getOrDefault(false)

    private fun appendHistoryPoint(point: ChartPoint) {
        val current = mutable.value
        val existing = currentSessionAccumulator?.takeIf { it.isCharging == point.acOnline }
        val accumulator = existing ?: ChargeRecordAccumulator(point)
        if (existing != null) accumulator.append(point)
        currentSessionAccumulator = accumulator
        val updated = accumulator.toRecord()

        val charge = current.chargeRecords.toMutableList()
        val discharge = current.dischargeRecords.toMutableList()
        val target = if (point.acOnline) charge else discharge
        val alreadyListed = existing != null && accumulator.sampleCount >= 2 &&
            target.lastOrNull()?.startedAtMillis == updated.startedAtMillis
        if (alreadyListed) {
            target[target.lastIndex] = updated
        } else if (accumulator.sampleCount >= 2) {
            target += updated
        }
        mutable.value = current.copy(
            points = CurveProcessing.historyWithinWindow(current.points + point),
            chargeRecords = charge.takeLast(50),
            dischargeRecords = discharge.takeLast(50),
        )
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

    private data class RuntimeAccessSnapshot(
        val shizukuInstalled: Boolean,
        val shizukuAvailable: Boolean,
        val shizukuPermission: Boolean,
        val shizukuServiceConnected: Boolean,
        val systemAccess: SystemAccessState,
    )
}
