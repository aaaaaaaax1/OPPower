package com.oppower.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.oppower.BuildConfig
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ShizukuGateway(context: Context) {
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

    val isAvailable: Boolean
        get() = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    val hasPermission: Boolean
        get() = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

    fun requestPermission() {
        if (!isAvailable || hasPermission) return
        runCatching { Shizuku.requestPermission(1001) }
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

    fun readGlobal(name: String): String = execute("settings get global ${name.requireAllowedName()}")

    fun putGlobal(name: String, value: Int) {
        execute("settings put global ${name.requireAllowedName()} $value")
    }

    fun readSystem(name: String): String = execute("settings get system ${name.requireAllowedName()}")

    fun putSystem(name: String, value: Int) {
        execute("settings put system ${name.requireAllowedName()} $value")
    }

    fun readThermalPolicy(): String =
        execute("dumpsys activity service com.oplus.battery/.OplusBatteryService getCurThermalPolicy")

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
        require(this == "cosa_ctc_value" || this == "single_speed_charge_state")
        return this
    }
}
