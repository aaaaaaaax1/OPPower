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
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.BatteryManager
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
    private lateinit var historyStore: ChartHistoryStore
    private lateinit var overlay: KeepAliveOverlay
    private var foregroundStarted = false
    private var screenOn = true
    private var lastNotificationText = ""
    private var lastNotificationTitle = ""
    private var lastNotificationAt = 0L

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
        controller = ChargingController(shizuku)
        batteryReader = BatteryReader(this)
        prefs = PreferencesStore(this)
        historyStore = ChartHistoryStore(this)
        overlay = KeepAliveOverlay(this)
        screenOn = getSystemService(PowerManager::class.java).isInteractive
        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
        )
        ChartHistoryBus.points.value = historyStore.load()
        createChannel()
        val initialSettings = prefs.load()
        val initialBattery = batteryReader.snapshot(initialSettings)
        if (initialSettings.masterEnabled && initialBattery.acOnline) {
            startForeground(
                NOTIFICATION_ID,
                notification("正在启动", "等待充电控制服务", initialBattery),
            )
            foregroundStarted = true
        }
        scope.launch {
            while (true) {
                val settings = prefs.load()
                val battery = batteryReader.snapshot(settings)
                val status = controller.evaluate(settings, battery)
                ChargeControlBus.status.value = status
                overlay.sync(settings.overlayKeepAliveEnabled)
                syncStatusNotification(status, battery, settings)
                val waitMillis = when {
                    !battery.acOnline -> 30_000L
                    status.phase == ControllerPhase.ERROR -> 10_000L
                    else -> 2_000L
                }
                withTimeoutOrNull(waitMillis) { ChargeControlBus.wakeRequests.receive() }
                // Sampling follows the service cadence. The store coalesces
                // samples to one point every 30 seconds and persists 24 hours.
                if (settings.historyEnabled) {
                    ChartHistoryBus.points.value = historyStore.record(batteryReader.snapshot(settings))
                }
                if (!settings.masterEnabled) {
                    if (foregroundStarted) stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    break
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RESUME_NOTIFICATION) {
            if (!prefs.load().masterEnabled) return START_NOT_STICKY
            ChargeControlBus.requestUpdate()
            return START_STICKY
        }
        if (intent?.action == ACTION_RESTORE_ALL) {
            scope.launch {
                val status = controller.restoreAll()
                ChargeControlBus.status.value = status
                val settings = prefs.load()
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
                if (!prefs.load().historyEnabled) {
                    historyStore.clear()
                    ChartHistoryBus.points.value = emptyList()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        if (::overlay.isInitialized) overlay.remove()
        if (::controller.isInitialized) controller.restoreAll()
        ChargeControlBus.status.value = ControllerStatus(
            phase = ControllerPhase.DISABLED,
            message = "控制服务已停止",
        )
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun syncStatusNotification(
        status: ControllerStatus,
        battery: BatterySnapshot,
        settings: RuntimeSettings,
        force: Boolean = false,
    ) {
        if (!settings.masterEnabled || !battery.acOnline) {
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
        val title = if (detailAllowed) notificationPower(battery, settings) else "OPPower · 后台保护"
        val text = if (detailAllowed) {
            notificationText(status, battery, settings)
        } else {
            if (full) {
                "已充满 · 充电增强已交还系统 · 点击查看"
            } else {
                "亮屏快充保护正在运行 · 点击查看"
            }
        }
        val now = System.currentTimeMillis()
        val interval = if (screenOn) {
            settings.notificationRefreshSeconds.coerceIn(2, 30)
        } else {
            settings.notificationScreenOffRefreshSeconds.coerceIn(3, 300)
        }
        val refreshMillis = interval * 1_000L
        if (!force && foregroundStarted && text == lastNotificationText && title == lastNotificationTitle) return
        if (!force && foregroundStarted && title == lastNotificationTitle && now - lastNotificationAt < refreshMillis) return
        val notification = notification(title, text, battery)
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

    private fun notificationText(
        status: ControllerStatus,
        battery: BatterySnapshot,
        settings: RuntimeSettings,
    ): String {
        val values = buildList {
            add(powerTierLabel(status.desiredGear))
            if (settings.notificationShowBatteryPercent) add("${battery.levelPercent}%")
            if (settings.notificationShowSupply) add(if (battery.acOnline) "电源适配器" else "电池供电")
            if (settings.notificationShowVoltage) add("${(battery.voltageMv / 1000f).toString().let { if (it.length < 4) it else it.take(4) }}V")
            if (settings.notificationShowCurrent) add("${battery.currentMa} mA")
            if (settings.notificationShowTemperature) add("${battery.temperatureC.oneDecimal()}℃")
        }
        return values.joinToString(" · ")
    }

    private fun notification(
        title: String,
        text: String,
        battery: BatterySnapshot,
    ): Notification {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_power)
            .setColor(android.graphics.Color.rgb(121, 214, 193))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setProgress(100, battery.levelPercent.coerceIn(0, 100), false)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                }
            }
            .build()
    }

    private fun notificationPower(battery: BatterySnapshot, settings: RuntimeSettings): String =
        if (settings.notificationIntegerPower && kotlin.math.abs(battery.powerW) >= 1f) {
            "${battery.powerW.roundToInt()} W"
        } else {
            "${battery.powerW.oneDecimal()} W"
        }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "亮屏快充状态", NotificationManager.IMPORTANCE_LOW).apply {
                description = "显示亮屏快充状态、电池功率和充电保护"
                setShowBadge(false)
            },
        )
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
        const val NOTIFICATION_ID = 2108
        const val ACTION_RESTORE_ALL = "com.oppower.action.RESTORE_ALL"
        const val ACTION_HISTORY_SETTING_CHANGED = "com.oppower.action.HISTORY_SETTING_CHANGED"
        const val ACTION_RESUME_NOTIFICATION = "com.oppower.action.RESUME_NOTIFICATION"

        fun start(context: Context) {
            if (!PreferencesStore(context).load().masterEnabled) return
            val intent = Intent(context, ChargeControlService::class.java)
            runCatching {
                val battery = context.registerReceiver(
                    null,
                    IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                )
                val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && plugged) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun restore(context: Context) {
            val intent = Intent(context, ChargeControlService::class.java).setAction(ACTION_RESTORE_ALL)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun historySettingsChanged(context: Context) {
            val intent = Intent(context, ChargeControlService::class.java)
                .setAction(ACTION_HISTORY_SETTING_CHANGED)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun resumeNotification(context: Context) {
            if (!PreferencesStore(context).load().masterEnabled) return
            context.startService(Intent(context, ChargeControlService::class.java).setAction(ACTION_RESUME_NOTIFICATION))
        }
    }
}
