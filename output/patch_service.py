from pathlib import Path
p = Path("app/src/main/java/com/oppower/service/ChargeControlService.kt")
s = p.read_text(encoding="utf-8")
s = s.replace(
    'import kotlin.math.roundToInt\n\nclass ChargeControlService',
    'import kotlin.math.roundToInt\n\nprivate val controlRestoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)\n\nclass ChargeControlService',
)
s = s.replace(
    '    private var lastOverlayEnabled: Boolean? = null\n',
    '    private var lastOverlayEnabled: Boolean? = null\n    @Volatile private var controlLoopStarted = false\n',
)
start = s.index('        val initialSettings = cachedSettings\n')
end = s.index('    override fun onStartCommand', start)
new_on_create_tail = '''        notificationDismissed = false
    }

    private fun startControlLoop() {
        if (controlLoopStarted) return
        controlLoopStarted = true
        scope.launch {
            try {
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
            } finally {
                controlLoopStarted = false
            }
        }
    }

'''
s = s[:start] + new_on_create_tail + s[end:]
start = s.index('        // This also covers a second startForegroundService()')
end = s.index('        if (intent?.action == ACTION_RESTORE_ALL)', start)
new_start_command = '''        val settings = cachedSettings
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
        if (intent?.action == ACTION_RESUME_NOTIFICATION) {
            if (!enabled) return START_NOT_STICKY
            notificationDismissed = false
            scope.launch {
                val battery = batteryReader.snapshot(cachedSettings)
                if (!battery.acOnline) return@launch
                val status = if (cachedSettings.masterEnabled) {
                    controller.evaluate(cachedSettings, battery)
                } else {
                    ControllerStatus(ControllerPhase.DISABLED, "亮屏快充未启用")
                }
                syncStatusNotification(status, battery, cachedSettings, force = true)
            }
            return START_STICKY
        }
'''
s = s[:start] + new_start_command + s[end:]
old_destroy = '''        if (::overlay.isInitialized) overlay.remove()
        if (::controller.isInitialized) controller.restoreAll()
        ChargeControlBus.status.value = ControllerStatus(
            phase = ControllerPhase.DISABLED,
            message = "控制服务已停止",
        )
        scope.cancel()
        if (::overlayScope.isInitialized) overlayScope.cancel()
        super.onDestroy()
'''
new_destroy = '''        if (::overlay.isInitialized) overlay.remove()
        scope.cancel()
        if (::overlayScope.isInitialized) overlayScope.cancel()
        if (::controller.isInitialized) {
            val controllerToRestore = controller
            // Shizuku Binder calls may wait several seconds. Restoration must
            // never run on the lifecycle/main thread while the activity opens.
            controlRestoreScope.launch {
                runCatching { controllerToRestore.restoreAll() }
                    .onFailure { android.util.Log.w("OPPower", "Unable to restore charge control", it) }
            }
        }
        ChargeControlBus.status.value = ControllerStatus(
            phase = ControllerPhase.DISABLED,
            message = "控制服务已停止",
        )
        super.onDestroy()
'''
if old_destroy not in s:
    raise SystemExit("onDestroy block not found")
s = s.replace(old_destroy, new_destroy)
old_start = '''            if (!plugged) return false
            val intent = Intent(context, ChargeControlService::class.java)
            return runCatching {
'''
new_start = '''            if (!plugged) return false
            val intent = Intent(context, ChargeControlService::class.java)
                .setAction(ACTION_RESUME_NOTIFICATION)
            return runCatching {
'''
if old_start not in s:
    raise SystemExit("start block not found")
s = s.replace(old_start, new_start)
old_resume = '''        fun resumeNotification(context: Context) {
            val settings = PreferencesStore(context).load()
            if (!settings.masterEnabled && !settings.liveNotificationEnabled) return
            runCatching {
                context.startService(
                    Intent(context, ChargeControlService::class.java)
                        .setAction(ACTION_RESUME_NOTIFICATION),
                )
            }
        }
'''
new_resume = '''        fun resumeNotification(context: Context) {
            start(context)
        }
'''
if old_resume not in s:
    raise SystemExit("resume block not found")
s = s.replace(old_resume, new_resume)
p.write_text(s, encoding="utf-8", newline="\n")