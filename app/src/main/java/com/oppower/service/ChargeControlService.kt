package com.oppower.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.oppower.MainActivity
import com.oppower.battery.BatteryReader
import com.oppower.controller.ChargingController
import com.oppower.data.PreferencesStore
import com.oppower.history.ChartHistoryBus
import com.oppower.history.ChartHistoryStore
import com.oppower.model.ControllerPhase
import com.oppower.model.ControllerStatus
import com.oppower.shizuku.ShizukuGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class ChargeControlService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var shizuku: ShizukuGateway
    private lateinit var controller: ChargingController
    private lateinit var batteryReader: BatteryReader
    private lateinit var prefs: PreferencesStore
    private lateinit var historyStore: ChartHistoryStore
    private var lastNotificationText = ""

    override fun onCreate() {
        super.onCreate()
        shizuku = ShizukuGateway(this)
        controller = ChargingController(shizuku)
        batteryReader = BatteryReader(this)
        prefs = PreferencesStore(this)
        historyStore = ChartHistoryStore(this)
        ChartHistoryBus.points.value = historyStore.load()
        createChannel()
        startForeground(NOTIFICATION_ID, notification("等待接入电源"))
        scope.launch {
            // A previous process can leave the persistent setting behind after
            // a crash. Restore it before the normal control loop starts.
            runCatching { controller.restoreAll() }
            while (true) {
                val settings = prefs.load()
                val battery = batteryReader.snapshot()
                val status = controller.evaluate(settings, battery)
                ChargeControlBus.status.value = status
                updateNotification(
                    if (settings.liveNotificationEnabled) status.message else "亮屏快充控制运行中",
                )
                val waitMillis = when {
                    !battery.acOnline -> 30_000L
                    status.phase == ControllerPhase.ERROR -> 10_000L
                    else -> 5_000L
                }
                withTimeoutOrNull(waitMillis) { ChargeControlBus.wakeRequests.receive() }
                // Sampling follows the service cadence. The store coalesces
                // samples to one point every 30 seconds and persists 24 hours.
                ChartHistoryBus.points.value = historyStore.record(batteryReader.snapshot())
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RESTORE_ALL) {
            scope.launch {
                val status = controller.restoreAll()
                ChargeControlBus.status.value = status
                updateNotification("已恢复系统默认")
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (::controller.isInitialized) controller.restoreAll()
        ChargeControlBus.status.value = ControllerStatus(
            phase = ControllerPhase.DISABLED,
            message = "控制服务已停止",
        )
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "亮屏快充状态", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun notification(text: String): Notification {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("OPPower · 亮屏快充")
            .setContentText(text)
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(text: String) {
        if (text == lastNotificationText) return
        lastNotificationText = text
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    companion object {
        const val CHANNEL_ID = "charge_control"
        const val NOTIFICATION_ID = 2108
        const val ACTION_RESTORE_ALL = "com.oppower.action.RESTORE_ALL"

        fun start(context: Context) {
            val intent = Intent(context, ChargeControlService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
    }
}
