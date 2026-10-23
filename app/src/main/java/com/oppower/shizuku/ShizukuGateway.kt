package com.oppower.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import com.oppower.BuildConfig
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
     * ThermalControlUtils observes this system setting and calls m2() when its
     * value changes. Rewriting the same integer with an equivalent textual
     * representation (0/00 or 1/01) triggers exactly one policy recomputation
     * without changing the camera state seen by Android.
     */
    override fun requestThermalPolicyRefresh(): Boolean {
        val current = execute("settings get system oplus_camera_3rd_activity").trim()
        val numeric = current.toIntOrNull() ?: return false
        val alias = thermalRefreshAlias(current) ?: return false
        execute("settings put system oplus_camera_3rd_activity $alias")
        return execute("settings get system oplus_camera_3rd_activity")
            .trim()
            .toIntOrNull() == numeric
    }

    override fun requestNativeChargeLevel(level: Int, speedAdd: Int): Boolean {
        if (speedAdd != 0 || level !in NATIVE_CHARGE_LEVELS) return false
        val output = execute(
            "am broadcast -a oplus.intent.action.ACTION_THERMAL_CONTROL_USER_SYSTEM " +
                "-p com.oplus.battery --es name set_charge --ei level $level " +
                "--ei speedChargeAdd 0 --es foregroundPkgName com.oppower",
        )
        return output.contains("Broadcast completed: result=0")
    }

    override fun readSmartChgMode(): Int? {
        val output = execute("service call guardelfthermalcontrol 19")
        val match = SMART_MODE_RESULT.find(output) ?: return null
        return match.groupValues[2].toIntOrNull(16)
    }

    override fun setSmartChgMode(value: Int): Boolean {
        if (value !in 0..255) return false
        val output = execute("service call guardelfthermalcontrol 20 s16 $value")
        return SMART_MODE_RESULT.find(output)?.groupValues?.get(1) == "00000000"
    }

    private fun execute(command: String): String = awaitService().execute(command)

    private fun awaitService(): IPrivilegedCommandService {
        bind()
        val latch: CountDownLatch
        synchronized(bindLock) {
            commandService?.let { return it }
            latch = connectedLatch ?: CountDownLatch(0)
        }
        latch.await(4, TimeUnit.SECONDS)
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
        val NATIVE_CHARGE_LEVELS = setOf(0, 2, 3, 5, 7)
        val SMART_MODE_RESULT = Regex("""Result: Parcel\(\s*([0-9a-fA-F]{8})\s+([0-9a-fA-F]{8})""")
    }
}

internal fun thermalRefreshAlias(current: String): String? = when (current.trim()) {
    "0" -> "00"
    "00" -> "0"
    "1" -> "01"
    "01" -> "1"
    else -> null
}
