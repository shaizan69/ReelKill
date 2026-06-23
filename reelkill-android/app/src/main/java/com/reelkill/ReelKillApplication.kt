package com.reelkill

import android.app.Application
import com.reelkill.worker.ReelKillWorkScheduler
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class ReelKillApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        ReelKillWorkScheduler.schedule(this)
    }
}
