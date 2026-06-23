package com.reelkill.ui.app

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.reelkill.permissions.ReelKillPermissionState
import com.reelkill.ui.dashboard.DashboardScreen
import com.reelkill.ui.onboarding.PermissionGateScreen
import com.reelkill.ui.settings.SettingsScreen

@Composable
fun ReelKillAppShell(
    permissionState: ReelKillPermissionState,
    openAccessibility: () -> Unit,
    openOverlay: () -> Unit,
    openUsageAccess: () -> Unit,
    requestNotifications: () -> Unit,
    openBatteryOptimization: () -> Unit,
    refreshPermissions: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(AppTab.Dashboard) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                AppTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = { Text(tab.icon) },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { padding ->
        when (selectedTab) {
            AppTab.Dashboard -> DashboardScreen(modifier = Modifier.padding(padding))
            AppTab.Settings -> SettingsScreen(modifier = Modifier.padding(padding))
            AppTab.Permissions -> PermissionGateScreen(
                modifier = Modifier.padding(padding),
                permissionState = permissionState,
                openAccessibility = openAccessibility,
                openOverlay = openOverlay,
                openUsageAccess = openUsageAccess,
                requestNotifications = requestNotifications,
                openBatteryOptimization = openBatteryOptimization,
                refreshPermissions = refreshPermissions
            )
        }
    }
}

private enum class AppTab(val label: String, val icon: String) {
    Dashboard("Today", "T"),
    Settings("Rules", "R"),
    Permissions("Access", "A")
}
