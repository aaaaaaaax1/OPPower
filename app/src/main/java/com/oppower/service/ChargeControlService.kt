package com.oppower.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.os.BatteryManager
import androidx.annotation.RequiresApi
import com.oppower.MainActivity
import com.oppower.R
import com.oppower.battery.BatteryReader
import com.oppower.controller.ChargingController
import com.oppower.data.PreferencesStore
import com.oppower.history.ChartHistoryBus
import com.oppower.history.ChartHistoryStore
import com.oppower.model.BatterySnapshot
import com.oppower.model.ControllerPhase
import com.oppower.model.ControllerStatus
import com.oppower.model.RuntimeSettings
import com.oppower.model.powerTierLabel
import com.oppower.shizuku.ShizukuGateway
import com.oppower.ui.oneDecimal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

internal object StatusNotificationChannels {
    const val STATUS = "charge_control"
    const val LIVE = "battery_live_update_v2"
}

class ChargeControlService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var shizuku: ShizukuGateway
    private lateinit var controller: ChargingController
    private lateinit var batteryReader: BatteryReader
    private lateinit var prefs: PreferencesStore
    private lateinit var prefsFile: SharedPreferences
    private lateinit var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener
    @Volatile private var cachedSettings = RuntimeSettings()
    private lateinit var historyStore: ChartHistoryStore
    private lateinit var overlay: KeepAliveOverlay
    private lateinit var overlayScope: CoroutineScope
    private var foregroundStarted = false
    private var screenOn = true
    private var lastNotificationText = ""
    private var lastNotificationTitle = ""
    private var lastNotificationAt = 0L
    private var lastOverlayEnabled: Boolean? = null
    @Volatile private var controlLoopStarted = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> screenOn = true
                Intent.ACTION_SCREEN_OFF -> screenOn = false
            }
            ChargeControlBus.requestUpdate()
        }
    }

    override fun onCreate() {
        super.onCreate()
        android.util.Log.i("OPPower", "ChargeControlService created")
        shizuku = ShizukuGateway(this)
        shizuku.bind()
        prefs = PreferencesStore(this)
        cachedSettings = prefs.load()
        prefsFile = getSharedPreferences("oppower", MODE_PRIVATE)
        prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            cachedSettings = prefs.load()
            ChargeControlBus.requestUpdate()
        }
        prefsFile.registerOnSharedPreferenceChangeListener(prefsListener)
        controller = ChargingController(shizuku = shizuku)
        batteryReader = BatteryReader(this, shizuku::readBatteryFallback)
        historyStore = ChartHistoryStore(this)
        overlay = KeepAliveOverlay(this)
        overlayScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        screenOn = getSystemService(PowerManager::class.java).isInteractive
        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
        )
        createChannel()
        notificationDismissed = false
    }

    private fun startControlLoop() {
        if (controlLoopStarted) return
        controlLoopStarted = true
        scope.launch {
            try {
                while (true) {
                    val cycleStartedAt = SystemClock.elapsedRealtime()
                    var keepRunning = true
                    try {
                        val settings = cachedSettings
                        val battery = batteryReader.snapshot(settings)
                        ChargeControlBus.battery.value = SharedBatterySample(
                            snapshot = battery,
                            capturedAtElapsedMillis = SystemClock.elapsedRealtime(),
                        )
                        // evaluate() also performs the safe cosa=0 restoration when
                        // the master switch is off. Keeping that path in the normal
                        // cycle prevents a disabled switch from leaving an old
                        // offset active after a settings change.
                        val status = controller.evaluate(settings, battery)
                        ChargeControlBus.status.value = status
                        if (lastOverlayEnabled != settings.overlayKeepAliveEnabled) {
                            lastOverlayEnabled = settings.overlayKeepAliveEnabled
                            overlayScope.launch { overlay.sync(settings.overlayKeepAliveEnabled) }
                        }
                        syncStatusNotification(status, battery, settings)
                        if (settings.historyEnabled) {
                            runCatching {
                                historyStore.record(battery, screenOn = screenOn)
                                    ?.let(ChartHistoryBus::append)
                            }.onFailure {
                                android.util.Log.w("OPPower", "History sample failed", it)
                            }
                        }
                        if (!battery.acOnline) {
                            if (foregroundStarted) stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                            keepRunning = false
                        } else if (!settings.masterEnabled && !settings.liveNotificationEnabled) {
                            if (foregroundStarted) stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                            keepRunning = false
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        // Keep the control loop alive after a transient Shizuku,
                        // battery or notification failure. The next cycle retries.
                        android.util.Log.w("OPPower", "Charge control cycle failed", error)
                    }
                    if (!keepRunning) break
                    // The notification obeys its configured cadence. Chart history is
                    // throttled by ChartHistoryStore to LLPower's sampling intervals.
                    val remaining = (CONTROL_CYCLE_MILLIS -
                        (SystemClock.elapsedRealtime() - cycleStartedAt)).coerceAtLeast(0L)
                    if (remaining > 0L) {
                        withTimeoutOrNull(remaining) { ChargeControlBus.wakeRequests.receive() }
                    }
                }
            } finally {
                controlLoopStarted = false
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        android.util.Log.i(
            "OPPower",
            "ChargeControlService started action=${action ?: "sticky-restart"}",
        )
        // Preferences are applied synchronously to the in-process instance, but
        // the service may have been created before the toggle was saved. Reload
        // on every command so the immediate pass always sees the new target.
        cachedSettings = prefs.load()
        val settings = cachedSettings
        val enabled = settings.masterEnabled || settings.liveNotificationEnabled
        if (enabled) {
            // Android requires a foreground notification promptly. Start it with
            // an inexpensive placeholder, then fill real battery data on IO.
            if (!foregroundStarted) {
                startForeground(
                    NOTIFICATION_ID,
                    notification("0.0", "等待充电状态", "", BatterySnapshot(), settings),
                )
                foregroundStarted = true
            }
            startControlLoop()
        }
        if (action == ACTION_RESUME_NOTIFICATION) {
            if (!enabled) return START_NOT_STICKY
            notificationDismissed = false
        }
        if (action == ACTION_RESTORE_ALL) {
            scope.launch {
                val status = controller.restoreAll()
                ChargeControlBus.status.value = status
                val settings = cachedSettings
                val battery = batteryReader.snapshot(settings)
                syncStatusNotification(
                    ControllerStatus(ControllerPhase.DISABLED, "已恢复系统默认"),
                    battery,
                    settings,
                    force = true,
                )
            }
        }
        if (action == ACTION_HISTORY_SETTING_CHANGED) {
            scope.launch {
                if (!cachedSettings.historyEnabled) {
                    historyStore.clear()
                    ChartHistoryBus.reset()
                }
            }
        }
        if (action in IMMEDIATE_CONTROL_ACTIONS) {
            ChargeControlBus.requestUpdate()
            scope.launch {
                val battery = batteryReader.snapshot(settings)
                val status = controller.evaluate(settings, battery)
                ChargeControlBus.status.value = status
                syncStatusNotification(status, battery, settings, force = true)
                android.util.Log.i(
                    "OPPower",
                    "Immediate control action=$action phase=${status.phase} " +
                        "targetGear=${status.desiredGear} cosa=${status.appliedCosa}",
                )
                if (!enabled) stopSelf()
            }
        }
        return if (enabled) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        if (::prefsFile.isInitialized && ::prefsListener.isInitialized) {
            prefsFile.unregisterOnSharedPreferenceChangeListener(prefsListener)
        }
        if (::overlay.isInitialized) overlay.remove()
        scope.cancel()
        if (::overlayScope.isInitialized) overlayScope.cancel()
        // Unplugging and disabling the master switch already restore the system
        // settings synchronously in the control loop. A system-driven service
        // destroy must not undo them: doing so caused background power drops and
        // made reopening the app silently reinstate the target gear.
        android.util.Log.i(
            "OPPower",
            "ChargeControlService destroyed; persisted control intent retained",
        )
        ChargeControlBus.status.value = ControllerStatus(
            phase = ControllerPhase.DISABLED,
            message = "控制服务已停止",
        )
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun syncStatusNotification(
        status: ControllerStatus,
        battery: BatterySnapshot,
        settings: RuntimeSettings,
        force: Boolean = false,
    ) {
        if (!force && notificationDismissed) return
        if (!settings.masterEnabled || !battery.acOnline) {
            if (settings.liveNotificationEnabled && battery.acOnline) {
                val power = notificationPower(battery, settings)
                val data = notificationBatteryData(battery, settings)
                val tier = "亮屏快充未启用"
                val notification = notification(power, data, tier, battery, settings)
                if (!foregroundStarted) {
                    startForeground(NOTIFICATION_ID, notification)
                    foregroundStarted = true
                } else {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
                }
                lastNotificationText = "$data\n$tier"
                lastNotificationTitle = power
                lastNotificationAt = System.currentTimeMillis()
                return
            }
            getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
            if (foregroundStarted) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                foregroundStarted = false
            }
            lastNotificationText = ""
            lastNotificationTitle = ""
            lastNotificationAt = 0L
            return
        }
        val full = battery.levelPercent >= 100 || battery.status == 5 || status.phase == ControllerPhase.FULL_PAUSED
        // A foreground service must keep a visible notification. The two auto-close
        // settings therefore switch to a compact notification instead of removing it.
        val detailAllowed = settings.liveNotificationEnabled &&
            !(settings.notificationAutoCloseOnFull && full) &&
            !(settings.notificationAutoCloseOnScreenOff && !screenOn)
        val power = notificationPower(battery, settings)
        val data = if (detailAllowed) {
            notificationBatteryData(battery, settings)
        } else {
            if (full) {
                "已充满 · 充电增强已交还系统"
            } else {
                "亮屏快充保护正在运行"
            }
        }
        val tier = if (detailAllowed) powerTierLabel(status.desiredGear) else ""
        val title = power
        val text = "$data\n$tier"
        val now = System.currentTimeMillis()
        val interval = if (screenOn) {
            settings.notificationRefreshSeconds.coerceIn(2, 30)
        } else {
            settings.notificationScreenOffRefreshSeconds.coerceIn(3, 300)
        }
        val refreshMillis = interval * 1_000L
        if (!force && foregroundStarted && text == lastNotificationText && title == lastNotificationTitle) return
        if (!force && foregroundStarted && title == lastNotificationTitle && now - lastNotificationAt < refreshMillis) return
        val notification = notification(power, data, tier, battery, settings)
        if (!foregroundStarted) {
            startForeground(NOTIFICATION_ID, notification)
            foregroundStarted = true
        } else {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        }
        lastNotificationText = text
        lastNotificationTitle = title
        lastNotificationAt = now
    }

    private fun notificationBatteryData(
        battery: BatterySnapshot,
        settings: RuntimeSettings,
    ): String {
        val values = buildList {
            if (settings.notificationShowBatteryPercent) add("${battery.levelPercent}%")
            if (settings.notificationShowVoltage && battery.sampleValid) {
                add("${(battery.voltageMv / 1000f).oneDecimal()} V")
            }
            if (settings.notificationShowCurrent && battery.sampleValid) {
                add("${battery.currentMa} mA")
            }
            if (settings.notificationShowTemperature) add("${battery.temperatureC.oneDecimal()}°C")
        }
        return values.joinToString("  ·  ")
    }

    private fun notification(
        power: String,
        data: String,
        tier: String,
        battery: BatterySnapshot,
        settings: RuntimeSettings,
    ): Notification {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val dismissIntent = PendingIntent.getBroadcast(
            this,
            1,
            Intent(this, NotificationDismissReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val level = battery.levelPercent.coerceIn(0, 100)
        val body = listOf(data, tier).filter { it.isNotBlank() }.joinToString("  ·  ")
        val usesLiveStyle = Build.VERSION.SDK_INT >= 36
        val title = if (battery.sampleValid) "$power W" else "功率读取中"
        val builder = Notification.Builder(this, if (usesLiveStyle) StatusNotificationChannels.LIVE else StatusNotificationChannels.STATUS)
            // Android requires a small icon. ColorOS can replace ordinary
            .setSmallIcon(R.drawable.ic_notification_transparent)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pending)
            .setDeleteIntent(dismissIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(
                if (usesLiveStyle) Notification.VISIBILITY_PRIVATE else Notification.VISIBILITY_PUBLIC,
            )
            .setOngoing(usesLiveStyle)
            .setOnlyAlertOnce(true)
            .setShowWhen(usesLiveStyle)
            .setWhen(if (usesLiveStyle) System.currentTimeMillis() else 0L)
        if (usesLiveStyle) {
            builder.setStyle(liveProgressStyle(level, settings.masterEnabled))
        } else {
            builder.setProgress(100, level, false)
        }
        builder.addExtras(
            Bundle().apply {
                if (usesLiveStyle) {
                    putBoolean("android.requestPromotedOngoing", true)
                } else {
                    putBoolean("android.app.preferSmallIcon", true)
                }
            },
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    @RequiresApi(36)
    private fun liveProgressStyle(level: Int, controllerEnabled: Boolean): Notification.ProgressStyle =
        Notification.ProgressStyle()
            .setProgressTrackerIcon(
                Icon.createWithResource(this, R.drawable.ic_notification_bolt)
                    .setTint(if (controllerEnabled) AMBER else Color.WHITE),
            )
            .addProgressSegment(
                Notification.ProgressStyle.Segment(100)
                    .setColor(BATTERY_GREEN)
                    .setId(0),
            )
            .setProgress(level)

    private fun notificationPower(battery: BatterySnapshot, settings: RuntimeSettings): String = when {
        !battery.sampleValid -> "—"
        settings.notificationIntegerPower && kotlin.math.abs(battery.powerW) >= 1f ->
            "${battery.powerW.roundToInt()}"
        else -> battery.powerW.oneDecimal()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(StatusNotificationChannels.STATUS, "充电状态", NotificationManager.IMPORTANCE_LOW).apply {
                description = "显示亮屏快充状态、电池功率和充电保护"
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        if (Build.VERSION.SDK_INT >= 36) {
            manager.createNotificationChannel(
                NotificationChannel(
                    StatusNotificationChannels.LIVE,
                    "实时充电状态",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "显示功率、电量、温度与亮屏快充档位"
                    setShowBadge(false)
                    enableVibration(false)
                    enableLights(false)
                    setSound(null, null)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
            )
        }
    }

    private fun phaseLabel(phase: ControllerPhase): String = when (phase) {
        ControllerPhase.DISABLED -> "已关闭"
        ControllerPhase.WAITING_FOR_POWER -> "等待接电"
        ControllerPhase.ACTIVE -> "快速充电中"
        ControllerPhase.TEMPERATURE_PAUSED -> "温度保护"
        ControllerPhase.SOC_PAUSED -> "电量保护"
        ControllerPhase.FULL_PAUSED -> "已充满"
        ControllerPhase.ERROR -> "需要检查"
    }

    companion object {
        const val NOTIFICATION_ID = 2108
        const val ACTION_RESTORE_ALL = "com.oppower.action.RESTORE_ALL"
        const val ACTION_HISTORY_SETTING_CHANGED = "com.oppower.action.HISTORY_SETTING_CHANGED"
        const val ACTION_RESUME_NOTIFICATION = "com.oppower.action.RESUME_NOTIFICATION"
        const val ACTION_CONTROL_SETTINGS_CHANGED = "com.oppower.action.CONTROL_SETTINGS_CHANGED"
        const val ACTION_POWER_CONNECTED = "com.oppower.action.POWER_CONNECTED"
        private val BATTERY_GREEN = android.graphics.Color.rgb(76, 175, 80)
        private val AMBER = android.graphics.Color.rgb(255, 193, 7)
        private const val CONTROL_CYCLE_MILLIS = 1_000L
        private val IMMEDIATE_CONTROL_ACTIONS = setOf(
            ACTION_RESUME_NOTIFICATION,
            ACTION_CONTROL_SETTINGS_CHANGED,
            ACTION_POWER_CONNECTED,
        )

        @Volatile
        private var notificationDismissed = false

        internal fun markNotificationDismissed() {
            notificationDismissed = true
        }

        fun start(
            context: Context,
            action: String = ACTION_CONTROL_SETTINGS_CHANGED,
        ): Boolean {
            val settings = PreferencesStore(context).load()
            if (!settings.masterEnabled && !settings.liveNotificationEnabled) return false
            val batteryIntent = context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            )
            val plugged = (batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            if (!plugged) return false
            val intent = Intent(context, ChargeControlService::class.java)
                .setAction(action)
            return runCatching {
                context.startForegroundService(intent)
                true
            }.getOrElse { error ->
                android.util.Log.e("OPPower", "Unable to start charge control service", error)
                false
            }
        }

        fun startWhenAvailable(
            context: Context,
            attempts: Int = 6,
            retryDelayMillis: Long = 300L,
            action: String = ACTION_CONTROL_SETTINGS_CHANGED,
            onFinished: (() -> Unit)? = null,
        ) {
            require(attempts > 0)
            val appContext = context.applicationContext
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            var completedAttempts = 0
            val attempt = object : Runnable {
                override fun run() {
                    completedAttempts += 1
                    val settings = PreferencesStore(appContext).load()
                    val enabled = settings.masterEnabled || settings.liveNotificationEnabled
                    if (start(appContext, action) || !enabled || completedAttempts >= attempts) {
                        onFinished?.invoke()
                        return
                    }
                    handler.postDelayed(this, retryDelayMillis)
                }
            }
            handler.post(attempt)
        }

        fun restore(context: Context) {
            val intent = Intent(context, ChargeControlService::class.java).setAction(ACTION_RESTORE_ALL)
            runCatching { context.startService(intent) }
        }

        fun historySettingsChanged(context: Context) {
            val intent = Intent(context, ChargeControlService::class.java)
                .setAction(ACTION_HISTORY_SETTING_CHANGED)
            runCatching { context.startService(intent) }
        }

        fun controlSettingsChanged(context: Context) {
            val appContext = context.applicationContext
            val settings = PreferencesStore(appContext).load()
            if (settings.masterEnabled || settings.liveNotificationEnabled) {
                startWhenAvailable(appContext, action = ACTION_CONTROL_SETTINGS_CHANGED)
                return
            }
            // This transition may be turning the last enabled feature off. The
            // existing service still needs one command so it can restore cosa=0.
            val intent = Intent(appContext, ChargeControlService::class.java)
                .setAction(ACTION_CONTROL_SETTINGS_CHANGED)
            runCatching { appContext.startService(intent) }
        }

        fun resumeNotification(context: Context) {
            start(context, ACTION_RESUME_NOTIFICATION)
        }
    }
}

class NotificationDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        ChargeControlService.markNotificationDismissed()
    }
}
