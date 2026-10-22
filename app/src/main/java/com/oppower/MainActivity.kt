package com.oppower

import android.Manifest
import android.app.ActivityManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oppower.service.ChargeControlService
import com.oppower.ui.MainViewModel
import com.oppower.ui.OPPowerApp
import com.oppower.ui.OPPowerTheme
import rikka.shizuku.Shizuku
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == 1001 && grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            viewModel.onShizukuPermissionGranted()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            LaunchedEffect(state.settings.hideFromRecents) {
                setTaskExclusion(state.settings.hideFromRecents)
            }
            OPPowerTheme(state.settings) {
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
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            ChargeControlService.start(this@MainActivity)
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshShizukuState()
    }

    private fun setTaskExclusion(excluded: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(!excluded)
        }
        getSystemService(ActivityManager::class.java).appTasks.firstOrNull()
            ?.setExcludeFromRecents(excluded)
    }

}
