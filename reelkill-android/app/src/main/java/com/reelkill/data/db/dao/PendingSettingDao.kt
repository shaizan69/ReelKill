package com.reelkill.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.reelkill.data.db.entity.PendingSetting
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingSettingDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(setting: PendingSetting): Long

    @Query("SELECT * FROM pending_settings WHERE appId = :appId ORDER BY appliesAt ASC")
    fun observeForApp(appId: String): Flow<List<PendingSetting>>

    @Query("SELECT * FROM pending_settings WHERE appliesAt <= :nowUtc ORDER BY appliesAt ASC")
    suspend fun getDue(nowUtc: String): List<PendingSetting>

    @Query("DELETE FROM pending_settings WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("DELETE FROM pending_settings WHERE appId = :appId")
    suspend fun deleteForApp(appId: String): Int
}
