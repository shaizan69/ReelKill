package com.reelkill.permissions

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.text.TextUtils
import androidx.core.content.ContextCompat
import com.reelkill.service.ReelKillAccessibilityService

data class ReelKillPermissionState(
    val accessibilityGranted: Boolean = false,
    val overlayGranted: Boolean = false,
    val usageAccessGranted: Boolean = false,
    val notificationsGranted: Boolean = true,
    val batteryOptimizationIgnored: Boolean = false
) {
    val allRequiredGranted: Boolean
        get() = accessibilityGranted && overlayGranted && usageAccessGranted && notificationsGranted
}

object ReelKillPermissionChecker {
    fun current(context: Context): ReelKillPermissionState {
        val appContext = context.applicationContext
        return ReelKillPermissionState(
            accessibilityGranted = isAccessibilityServiceEnabled(
                appContext,
                ReelKillAccessibilityService::class.java
            ),
            overlayGranted = hasOverlayPermission(appContext),
            usageAccessGranted = hasUsageAccess(appContext),
            notificationsGranted = hasNotificationPermission(appContext),
            batteryOptimizationIgnored = isIgnoringBatteryOptimizations(appContext)
        )
    }

    private fun hasOverlayPermission(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
    }

    private fun hasNotificationPermission(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
    }

    @Suppress("DEPRECATION")
    private fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(PowerManager::class.java) ?: return false
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun isAccessibilityServiceEnabled(
        context: Context,
        serviceClass: Class<out AccessibilityService>
    ): Boolean {
        val enabledFlag = Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0
        )
        if (enabledFlag != 1) return false

        val expected = ComponentName(context, serviceClass)
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            val enabledComponent = ComponentName.unflattenFromString(splitter.next()) ?: continue
            if (enabledComponent.packageName == expected.packageName &&
                enabledComponent.className == expected.className
            ) {
                return true
            }
        }
        return false
    }
}
