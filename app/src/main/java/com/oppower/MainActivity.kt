package com.oppower

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oppower.ui.MainViewModel
import com.oppower.ui.OPPowerApp
import com.oppower.ui.OPPowerTheme
import rikka.shizuku.Shizuku

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
            OPPowerTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                OPPowerApp(
                    state = state,
                    onSettingsChange = viewModel::updateSettings,
                    onRequestShizuku = viewModel::requestShizukuPermission,
                    onRestore = viewModel::restoreAll,
                    onDismissIntro = viewModel::dismissIntro,
                )
            }
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        super.onDestroy()
    }
}
