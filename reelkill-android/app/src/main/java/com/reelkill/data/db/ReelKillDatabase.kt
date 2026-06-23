package com.reelkill.data.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.reelkill.data.db.dao.AppSettingsDao
import com.reelkill.data.db.dao.AppStateDao
import com.reelkill.data.db.dao.BlockingRuleDao
import com.reelkill.data.db.dao.DailySummaryDao
import com.reelkill.data.db.dao.PendingSettingDao
import com.reelkill.data.db.dao.UsageEventDao
import com.reelkill.data.db.entity.AppSettings
import com.reelkill.data.db.entity.AppState
import com.reelkill.data.db.entity.BlockingRule
import com.reelkill.data.db.entity.DailySummary
import com.reelkill.data.db.entity.PendingSetting
import com.reelkill.data.db.entity.UsageEvent
import java.time.Instant

@Database(
    entities = [
        UsageEvent::class,
        DailySummary::class,
        AppState::class,
        AppSettings::class,
        PendingSetting::class,
        BlockingRule::class
    ],
    version = 1,
    exportSchema = true
)
abstract class ReelKillDatabase : RoomDatabase() {

    abstract fun usageEventDao(): UsageEventDao
    abstract fun dailySummaryDao(): DailySummaryDao
    abstract fun appStateDao(): AppStateDao
    abstract fun appSettingsDao(): AppSettingsDao
    abstract fun pendingSettingDao(): PendingSettingDao
    abstract fun blockingRuleDao(): BlockingRuleDao

    companion object {
        const val DATABASE_NAME = "reelkill.db"

        val MIGRATIONS: Array<Migration> = emptyArray()

        fun build(context: Context): ReelKillDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                ReelKillDatabase::class.java,
                DATABASE_NAME
            )
                .addCallback(SeedBlockingRulesCallback)
                .addMigrations(*MIGRATIONS)
                .build()
        }
    }

    private object SeedBlockingRulesCallback : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)

            // Seed only immutable defaults here. User/runtime rule changes go through BlockingRuleDao later.
            val addedAt = Instant.now().toString()
            defaultInstagramRules(addedAt).forEach { rule ->
                db.insert(
                    "blocking_rules",
                    SQLiteDatabase.CONFLICT_IGNORE,
                    rule.toContentValues()
                )
            }
        }

        private fun defaultInstagramRules(addedAt: String): List<BlockingRule> {
            return listOf(
                BlockingRule(
                    id = "instagram_reels_tab_clips_tab",
                    appPackage = BlockingRule.INSTAGRAM_PACKAGE,
                    viewId = "com.instagram.android:id/clips_tab",
                    contentDescContains = null,
                    action = BlockingRule.ACTION_BACK,
                    isActive = true,
                    addedAt = addedAt
                ),
                BlockingRule(
                    id = "instagram_reels_tab_tab_clips",
                    appPackage = BlockingRule.INSTAGRAM_PACKAGE,
                    viewId = "com.instagram.android:id/tab_clips",
                    contentDescContains = null,
                    action = BlockingRule.ACTION_BACK,
                    isActive = true,
                    addedAt = addedAt
                ),
                BlockingRule(
                    id = "instagram_reels_viewer_root_clips_layout",
                    appPackage = BlockingRule.INSTAGRAM_PACKAGE,
                    viewId = "com.instagram.android:id/root_clips_layout",
                    contentDescContains = null,
                    action = BlockingRule.ACTION_BACK,
                    isActive = true,
                    addedAt = addedAt
                ),
                BlockingRule(
                    id = "instagram_explore_tab",
                    appPackage = BlockingRule.INSTAGRAM_PACKAGE,
                    viewId = "com.instagram.android:id/explore_tab",
                    contentDescContains = null,
                    action = BlockingRule.ACTION_BACK,
                    isActive = true,
                    addedAt = addedAt
                ),
                BlockingRule(
                    id = "instagram_main_feed_recycler",
                    appPackage = BlockingRule.INSTAGRAM_PACKAGE,
                    viewId = "com.instagram.android:id/feed_row_recycler_view",
                    contentDescContains = null,
                    action = BlockingRule.ACTION_ALLOW,
                    isActive = true,
                    addedAt = addedAt
                ),
                BlockingRule(
                    id = "instagram_stories_tray_content_desc",
                    appPackage = BlockingRule.INSTAGRAM_PACKAGE,
                    viewId = null,
                    contentDescContains = "stories",
                    action = BlockingRule.ACTION_HIDE,
                    isActive = true,
                    addedAt = addedAt
                )
            )
        }

        private fun BlockingRule.toContentValues(): ContentValues {
            return ContentValues().apply {
                put("id", id)
                put("appPackage", appPackage)
                put("viewId", viewId)
                put("contentDescContains", contentDescContains)
                put("action", action)
                put("isActive", isActive)
                put("addedAt", addedAt)
            }
        }
    }
}
