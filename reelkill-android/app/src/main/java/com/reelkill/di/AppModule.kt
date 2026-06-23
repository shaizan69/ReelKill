package com.reelkill.di

import android.content.Context
import com.reelkill.data.db.ReelKillDatabase
import com.reelkill.data.db.dao.AppSettingsDao
import com.reelkill.data.db.dao.AppStateDao
import com.reelkill.data.db.dao.BlockingRuleDao
import com.reelkill.data.db.dao.DailySummaryDao
import com.reelkill.data.db.dao.PendingSettingDao
import com.reelkill.data.db.dao.UsageEventDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ReelKillDatabase {
        return ReelKillDatabase.build(context)
    }

    @Provides fun provideUsageEventDao(database: ReelKillDatabase): UsageEventDao = database.usageEventDao()
    @Provides fun provideDailySummaryDao(database: ReelKillDatabase): DailySummaryDao = database.dailySummaryDao()
    @Provides fun provideAppStateDao(database: ReelKillDatabase): AppStateDao = database.appStateDao()
    @Provides fun provideAppSettingsDao(database: ReelKillDatabase): AppSettingsDao = database.appSettingsDao()
    @Provides fun providePendingSettingDao(database: ReelKillDatabase): PendingSettingDao = database.pendingSettingDao()
    @Provides fun provideBlockingRuleDao(database: ReelKillDatabase): BlockingRuleDao = database.blockingRuleDao()
}
