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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

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
        shizuku = ShizukuGateway(this)
        prefs = PreferencesStore(this)
        cachedSettings = prefs.load()
        prefsFile = getSharedPreferences("oppower", MODE_PRIVATE)
        prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            cachedSettings = prefs.load()
            ChargeControlBus.requestUpdate()
        }
        prefsFile.registerOnSharedPreferenceChangeListener(prefsListener)
        controller = ChargingController(shizuku)
        batteryReader = BatteryReader(this)
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
        val initialSettings = cachedSettings
        val initialBattery = batteryReader.snapshot(initialSettings)
        notificationDismissed = false
        val shouldRun = initialBattery.acOnline &&
            (initialSettings.masterEnabled || initialSettings.liveNotificationEnabled)
        // startForegroundService() requires startForeground() even when the
        // charger is absent. Call it before the control loop so Android never
        // kills the process for missing the five-second foreground contract.
        // The first status sync immediately removes the notification if the
        // device is unplugged or the master switch is off.
        if (shouldRun) {
            startForeground(
                NOTIFICATION_ID,
                notification("0.0", "等待充电状态", "", initialBattery, initialSettings),
            )
            foregroundStarted = true
        }
        if (!shouldRun) {
            stopSelf()
            return
        }
        scope.launch {
            while (true) {
                val settings = cachedSettings
                val battery = batteryReader.snapshot(settings)
                val status = if (settings.masterEnabled) {
                    controller.evaluate(settings, battery)
                } else {
                    ControllerStatus(ControllerPhase.DISABLED, "亮屏快充未启用")
                }
                ChargeControlBus.status.value = status
                if (lastOverlayEnabled != settings.overlayKeepAliveEnabled) {
                    lastOverlayEnabled = settings.overlayKeepAliveEnabled
                    overlayScope.launch { overlay.sync(settings.overlayKeepAliveEnabled) }
                }
                syncStatusNotification(status, battery, settings)
                if (settings.historyEnabled) {
                    historyStore.record(battery, screenOn = screenOn)?.let(ChartHistoryBus::append)
                }
                if (!battery.acOnline) {
                    if (foregroundStarted) stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    break
                }
                if (!settings.masterEnabled && !settings.liveNotificationEnabled) {
                    if (foregroundStarted) stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    break
                }
                // The notification obeys its configured cadence. Chart history is
                // throttled by ChartHistoryStore to LLPower's sampling intervals.
                withTimeoutOrNull(250L) { ChargeControlBus.wakeRequests.receive() }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // This also covers a second startForegroundService() while the service
        // already exists in the background (for example, when AC is connected).
        val startSettings = cachedSettings
        val startBattery = batteryReader.snapshot(startSettings)
        if (!foregroundStarted && (startSettings.masterEnabled || startSettings.liveNotificationEnabled)) {
            if (startBattery.acOnline) {
                startForeground(
                    NOTIFICATION_ID,
                    notification("0.0", "等待充电状态", "", startBattery, startSettings),
                )
                foregroundStarted = true
            } else {
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }
        if (intent?.action == ACTION_RESUME_NOTIFICATION) {
            val settings = cachedSettings
            if (!settings.masterEnabled && !settings.liveNotificationEnabled) {
                return START_NOT_STICKY
            }
            notificationDismissed = false
            scope.launch {
                val battery = batteryReader.snapshot(settings)
                if (!battery.acOnline) return@launch
                val status = if (settings.masterEnabled) {
                    controller.evaluate(settings, battery)
                } else {
                    ControllerStatus(ControllerPhase.DISABLED, "亮屏快充未启用")
                }
                syncStatusNotification(status, battery, settings, force = true)
            }
            return START_STICKY
        }
        if (intent?.action == ACTION_RESTORE_ALL) {
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
        if (intent?.action == ACTION_HISTORY_SETTING_CHANGED) {
            scope.launch {
                if (!cachedSettings.historyEnabled) {
                    historyStore.clear()
                    ChartHistoryBus.reset()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        if (::prefsFile.isInitialized && ::prefsListener.isInitialized) {
            prefsFile.unregisterOnSharedPreferenceChangeListener(prefsListener)
        }
        if (::overlay.isInitialized) overlay.remove()
        if (::controller.isInitialized) controller.restoreAll()
        ChargeControlBus.status.value = ControllerStatus(
            phase = ControllerPhase.DISABLED,
            message = "控制服务已停止",
        )
        scope.cancel()
        if (::overlayScope.isInitialized) overlayScope.cancel()
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
            if (settings.notificationShowVoltage) add("${(battery.voltageMv / 1000f).oneDecimal()} V")
            if (settings.notificationShowCurrent) add("${battery.currentMa} mA")
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
        val body = listOf(data, tier).filter { it.isNotBlank() }.joinToString("\n")
        val usesLiveStyle = Build.VERSION.SDK_INT >= 36
        val builder = Notification.Builder(this, if (usesLiveStyle) LIVE_CHANNEL_ID else CHANNEL_ID)
            // Android requires a small icon. ColorOS can replace ordinary
            .setSmallIcon(R.drawable.ic_notification_transparent)
            .setContentTitle("$power W")
            .setContentText(body)
            .setContentIntent(pending)
            .setDeleteIntent(dismissIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(
                if (usesLiveStyle) Notification.VISIBILITY_PRIVATE else Notification.VISIBILITY_PUBLIC,
            )
            .setOngoing(false)
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

    private fun notificationPower(battery: BatterySnapshot, settings: RuntimeSettings): String =
        if (settings.notificationIntegerPower && kotlin.math.abs(battery.powerW) >= 1f) {
            "${battery.powerW.roundToInt()}"
        } else {
            battery.powerW.oneDecimal()
        }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "充电状态", NotificationManager.IMPORTANCE_LOW).apply {
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
                    LIVE_CHANNEL_ID,
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
        const val CHANNEL_ID = "charge_control"
        const val LIVE_CHANNEL_ID = "battery_live_update_v2"
        const val NOTIFICATION_ID = 2108
        const val ACTION_RESTORE_ALL = "com.oppower.action.RESTORE_ALL"
        const val ACTION_HISTORY_SETTING_CHANGED = "com.oppower.action.HISTORY_SETTING_CHANGED"
        const val ACTION_RESUME_NOTIFICATION = "com.oppower.action.RESUME_NOTIFICATION"
        private val BATTERY_GREEN = android.graphics.Color.rgb(76, 175, 80)
        private val AMBER = android.graphics.Color.rgb(255, 193, 7)

        @Volatile
        private var notificationDismissed = false

        internal fun markNotificationDismissed() {
            notificationDismissed = true
        }

        fun start(context: Context): Boolean {
            val settings = PreferencesStore(context).load()
            if (!settings.masterEnabled && !settings.liveNotificationEnabled) return false
            val batteryIntent = context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            )
            val plugged = (batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            if (!plugged) return false
            val intent = Intent(context, ChargeControlService::class.java)
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
                    if (start(appContext) || !enabled || completedAttempts >= attempts) {
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

        fun resumeNotification(context: Context) {
            val settings = PreferencesStore(context).load()
            if (!settings.masterEnabled && !settings.liveNotificationEnabled) return
            runCatching {
                context.startService(
                    Intent(context, ChargeControlService::class.java)
                        .setAction(ACTION_RESUME_NOTIFICATION),
                )
            }
        }
    }
}

class NotificationDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        ChargeControlService.markNotificationDismissed()
    }
}
