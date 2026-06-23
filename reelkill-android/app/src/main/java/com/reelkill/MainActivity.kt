package com.reelkill

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.reelkill.data.datastore.ReelKillPreferences
import com.reelkill.permissions.ReelKillPermissionChecker
import com.reelkill.permissions.ReelKillPermissionState
import com.reelkill.service.ReelKillForegroundService
import com.reelkill.ui.app.ReelKillAppShell
import com.reelkill.ui.onboarding.PermissionGateScreen
import com.reelkill.ui.theme.ReelKillTheme
import com.reelkill.util.OemKillHelper
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var reelKillPreferences: ReelKillPreferences

    private var permissionState by mutableStateOf(ReelKillPermissionState())

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        refreshPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshPermissions()
        requestNotificationsIfNeeded()

        setContent {
            ReelKillTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (permissionState.allRequiredGranted) {
                        ReelKillAppShell(
                            permissionState = permissionState,
                            openAccessibility = ::openAccessibilitySettings,
                            openOverlay = ::openOverlaySettings,
                            openUsageAccess = ::openUsageAccessSettings,
                            requestNotifications = ::requestNotificationPermission,
                            openBatteryOptimization = ::openBatteryOptimizationSettings,
                            refreshPermissions = ::refreshPermissions
                        )
                    } else {
                        PermissionGateScreen(
                            permissionState = permissionState,
                            openAccessibility = ::openAccessibilitySettings,
                            openOverlay = ::openOverlaySettings,
                            openUsageAccess = ::openUsageAccessSettings,
                            requestNotifications = ::requestNotificationPermission,
                            openBatteryOptimization = ::openBatteryOptimizationSettings,
                            refreshPermissions = ::refreshPermissions
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
    }

    private fun refreshPermissions() {
        val latest = ReelKillPermissionChecker.current(this)
        permissionState = latest

        if (latest.allRequiredGranted) {
            ReelKillForegroundService.start(this)
            lifecycleScope.launch {
                reelKillPreferences.setOnboardingComplete(true)
            }
        }
    }

    private fun requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !permissionState.notificationsGranted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            refreshPermissions()
        }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openOverlaySettings() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun openUsageAccessSettings() {
        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    private fun openBatteryOptimizationSettings() {
        runCatching {
            startActivity(OemKillHelper.getBatteryOptimizationIntent(this))
        }.onFailure {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
