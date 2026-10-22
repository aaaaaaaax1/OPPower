package com.oppower

import android.Manifest
import android.app.ActivityManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oppower.service.ChargeControlService
import com.oppower.ui.MainViewModel
import com.oppower.ui.OPPowerApp
import com.oppower.ui.OPPowerTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import rikka.shizuku.Shizuku


class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private var foregroundServiceRequested = false
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        ensureServiceStarted()
    }
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == 1001 && grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            viewModel.onShizukuPermissionGranted()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        setContent {
            val state = viewModel.state.collectAsStateWithLifecycle()
            val settingsFlow = remember(viewModel) {
                viewModel.state.map { it.settings }.distinctUntilChanged()
            }
            val settings by settingsFlow.collectAsStateWithLifecycle(
                initialValue = state.value.settings,
            )
            LaunchedEffect(viewModel) {
                viewModel.state
                    .map { it.settings.hideFromRecents }
                    .distinctUntilChanged()
                    .collect(::setTaskExclusion)
            }
            OPPowerTheme(settings) {
                OPPowerApp(
                    state = state,
                    onSettingsChange = viewModel::updateSettings,
                    onRequestShizuku = viewModel::requestShizukuPermission,
                    onReadGear = viewModel::readCurrentGear,
                    onRestore = viewModel::restoreAll,
                    onDismissIntro = viewModel::dismissIntro,
                    onClearHistory = viewModel::clearHistory,
                )
            }
        }
        if (
            Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        viewModel.setPageVisible(true)
        viewModel.refreshShizukuState()
        ensureServiceStarted()
    }

    override fun onStop() {
        viewModel.setPageVisible(false)
        foregroundServiceRequested = false
        super.onStop()
    }

    private fun ensureServiceStarted() {
        if (foregroundServiceRequested) return
        foregroundServiceRequested = true
        ChargeControlService.startWhenAvailable(
            this,
            onFinished = { foregroundServiceRequested = false },
        )
    }

    private fun setTaskExclusion(excluded: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(!excluded)
        }
        getSystemService(ActivityManager::class.java).appTasks.firstOrNull()
            ?.setExcludeFromRecents(excluded)
    }

}
