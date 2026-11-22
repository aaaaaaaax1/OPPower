package com.oppower.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import com.oppower.BuildConfig
import com.oppower.battery.PrivilegedBatteryReading
import com.oppower.battery.PrivilegedBatterySource
import com.oppower.controller.ChargingGateway
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ShizukuGateway(context: Context) : ChargingGateway {
    private val appContext = context.applicationContext
    private val bindLock = Any()
    private var commandService: IPrivilegedCommandService? = null
    private var binding = false
    private var connectedLatch: CountDownLatch? = null

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(appContext, PrivilegedCommandService::class.java),
    )
        .daemon(false)
        .processNameSuffix("oppower")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            synchronized(bindLock) {
                commandService = binder?.let(IPrivilegedCommandService.Stub::asInterface)
                binding = false
                connectedLatch?.countDown()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(bindLock) {
                commandService = null
                binding = false
            }
        }

        override fun onBindingDied(name: ComponentName?) {
            synchronized(bindLock) {
                commandService = null
                binding = false
            }
        }
    }

    override val isAvailable: Boolean
        get() = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    val isInstalled: Boolean
        get() = runCatching {
            appContext.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        }.getOrDefault(false)

    override val hasPermission: Boolean
        get() = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

    val isServiceConnected: Boolean
        get() = synchronized(bindLock) { commandService != null }

    fun requestPermission() {
        if (!isAvailable || hasPermission) return
        runCatching { Shizuku.requestPermission(1001) }
    }

    /** Opens the manager so the user can start its service before granting OPPower access. */
    fun openManager(): Boolean = runCatching {
        val launchIntent = appContext.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
            ?: return@runCatching false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(launchIntent)
        true
    }.getOrDefault(false)

    /** The app never requests network access; this only hands installation to a browser/store. */
    fun openInstallPage() {
        runCatching {
            appContext.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/RikkaApps/Shizuku/releases/latest"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun bind() {
        if (!isAvailable || !hasPermission) return
        synchronized(bindLock) {
            if (commandService != null || binding) return
            connectedLatch = CountDownLatch(1)
            binding = true
            runCatching {
                Shizuku.bindUserService(userServiceArgs, connection)
            }.onFailure {
                binding = false
                connectedLatch?.countDown()
            }
        }
    }

    fun unbind() {
        synchronized(bindLock) {
            commandService = null
            binding = false
            connectedLatch = null
        }
        runCatching { Shizuku.unbindUserService(userServiceArgs, connection, true) }
    }

    override fun readGlobal(name: String): String = execute("settings get global ${name.requireAllowedName()}")

    override fun putGlobal(name: String, value: Int) {
        execute("settings put global ${name.requireAllowedName()} $value")
    }

    override fun readThermalPolicy(): String =
        execute("dumpsys activity service com.oplus.battery/.OplusBatteryService getCurThermalPolicy")

    override fun readThermalStatus(): String =
        execute("dumpsys activity service com.oplus.battery/.OplusBatteryService getThermalStatus")

    /**
     * Reads current and voltage from one privileged `dumpsys battery` sample. This
     * path is used only when BatteryManager reports an AC-online zero, matching
     * LLPower's shell-level fallback without adding any new shell-command surface.
     */
    fun readBatteryFallback(): PrivilegedBatteryReading? = runCatching {
        if (!isAvailable || !hasPermission) return@runCatching null
        val values = awaitService(BATTERY_SAMPLE_TIMEOUT_MILLIS).readBatteryFallback()
        if (values.size !in 2..3) return@runCatching null
        PrivilegedBatteryReading(
            currentMa = values[0],
            voltageMv = values[1],
            source = if (values.size >= 3) {
                PrivilegedBatterySource.fromProtocolCode(values[2])
            } else {
                PrivilegedBatterySource.UNKNOWN
            },
        )
    }.getOrNull()

    /**
     * ThermalControlUtils observes both settings. A float-window change calls
     * j2(null, 0ms), which requests an immediate policy recomputation. The
     * camera callback calls m2(), whose internal delay is about five seconds,
     * so it remains only as a compatibility fallback.
     *
     * Rewriting the same integer with an equivalent textual representation
     * (0/00 or 1/01) preserves the system value while triggering one observer.
     */
    override fun requestThermalPolicyRefresh(): Boolean {
        if (runCatching { refreshViaAlias("oplus_float_window_show") }.getOrDefault(false)) {
            return true
        }
        return runCatching { refreshViaAlias("oplus_camera_3rd_activity") }.getOrDefault(false)
    }

    private fun refreshViaAlias(name: String): Boolean {
        val current = execute("settings get system $name")
        val write = thermalRefreshWrite(current) ?: return false
        execute("settings put system $name ${write.value}")
        return execute("settings get system $name").trim().toIntOrNull() == write.expectedInt
    }
    private fun execute(command: String): String = awaitService().execute(command)

    private fun awaitService(
        timeoutMillis: Long = COMMAND_TIMEOUT_MILLIS,
    ): IPrivilegedCommandService {
        bind()
        val latch: CountDownLatch
        synchronized(bindLock) {
            commandService?.let { return it }
            latch = connectedLatch ?: CountDownLatch(0)
        }
        latch.await(timeoutMillis, TimeUnit.MILLISECONDS)
        return synchronized(bindLock) {
            commandService ?: throw IllegalStateException("Shizuku 特权服务尚未连接")
        }
    }

    private fun String.requireAllowedName(): String {
        require(this == "cosa_ctc_value")
        return this
    }

    companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val COMMAND_TIMEOUT_MILLIS = 4_000L
        private const val BATTERY_SAMPLE_TIMEOUT_MILLIS = 350L
    }
}

internal data class ThermalRefreshWrite(
    val value: String,
    val expectedInt: Int,
)

/**
 * ThermalControlUtils treats only integer 1 as an active float-window scene. If the
 * provider has no row, it falls back to the same inactive state as 0. Normalizing
 * that one absent row to 0 therefore preserves policy semantics while notifying the
 * observer, which immediately runs j2(null, 0 ms).
 */
internal fun thermalRefreshWrite(current: String): ThermalRefreshWrite? =
    when (current.trim().lowercase()) {
        "0" -> ThermalRefreshWrite("00", 0)
        "00" -> ThermalRefreshWrite("0", 0)
        "1" -> ThermalRefreshWrite("01", 1)
        "01" -> ThermalRefreshWrite("1", 1)
        "null" -> ThermalRefreshWrite("0", 0)
        else -> null
    }
