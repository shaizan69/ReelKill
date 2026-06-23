package com.reelkill.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.reelkill.data.db.entity.AppSettings
import kotlinx.coroutines.flow.Flow

@Dao
interface AppSettingsDao {
    @Upsert
    suspend fun upsert(settings: AppSettings)

    @Upsert
    suspend fun upsertAll(settings: List<AppSettings>)

    @Query("SELECT * FROM app_settings WHERE appId = :appId LIMIT 1")
    suspend fun get(appId: String): AppSettings?

    @Query("SELECT * FROM app_settings WHERE appId = :appId LIMIT 1")
    fun observe(appId: String): Flow<AppSettings?>

    @Query("SELECT * FROM app_settings ORDER BY appId ASC")
    fun observeAll(): Flow<List<AppSettings>>
}
