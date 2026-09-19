package com.reelkill.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            Timber.d("Boot completed; restarting ReelKill foreground service")
            runCatching {
                ReelKillForegroundService.start(context)
            }.onFailure { error ->
                Timber.e(error, "Failed to restart ReelKill foreground service on boot")
            }
        }
    }
}
