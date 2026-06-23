package com.reelkill.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.reelkill.permissions.ReelKillPermissionState
import com.reelkill.ui.theme.BorderHairline
import com.reelkill.ui.theme.Positive
import com.reelkill.ui.theme.TextSecondary
import com.reelkill.ui.theme.Warning

@Composable
fun PermissionGateScreen(
    modifier: Modifier = Modifier,
    permissionState: ReelKillPermissionState,
    openAccessibility: () -> Unit,
    openOverlay: () -> Unit,
    openUsageAccess: () -> Unit,
    requestNotifications: () -> Unit,
    openBatteryOptimization: () -> Unit,
    refreshPermissions: () -> Unit
) {
    Column(
        modifier = Modifier
            .then(modifier)
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "ReelKill",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Enable these permissions once. ReelKill will move to the dashboard automatically when the required ones are active.",
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(24.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp))
                .border(1.dp, BorderHairline, RoundedCornerShape(4.dp))
                .padding(16.dp)
        ) {
            PermissionRow(
                title = "Accessibility Service",
                description = "Detects Instagram Reels UI. Required.",
                granted = permissionState.accessibilityGranted,
                onClick = openAccessibility
            )
            PermissionRow(
                title = "Display Over Apps",
                description = "Shows cooldown and hard block overlays. Required.",
                granted = permissionState.overlayGranted,
                onClick = openOverlay
            )
            PermissionRow(
                title = "Usage Access",
                description = "Tracks app open/session state locally. Required.",
                granted = permissionState.usageAccessGranted,
                onClick = openUsageAccess
            )
            PermissionRow(
                title = "Notifications",
                description = "Keeps foreground monitoring visible. Required on Android 13+.",
                granted = permissionState.notificationsGranted,
                onClick = requestNotifications
            )
            PermissionRow(
                title = "Battery Optimization",
                description = "Recommended for OEM kill protection. Not required to continue.",
                granted = permissionState.batteryOptimizationIgnored,
                onClick = openBatteryOptimization,
                required = false
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = refreshPermissions
        ) {
            Text("I granted it, check again")
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    description: String,
    granted: Boolean,
    onClick: () -> Unit,
    required: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = when {
                    granted -> "Granted"
                    required -> "Required"
                    else -> "Recommended"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (granted) Positive else Warning
            )
        }
        Button(
            enabled = !granted,
            onClick = onClick
        ) {
            Text(if (granted) "Done" else "Open")
        }
    }
}
