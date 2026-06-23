package com.reelkill.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.reelkill.data.db.entity.AppState
import kotlinx.coroutines.flow.Flow

@Dao
interface AppStateDao {
    @Upsert
    suspend fun upsert(state: AppState)

    @Query("SELECT * FROM app_state WHERE appId = :appId LIMIT 1")
    suspend fun get(appId: String): AppState?

    @Query("SELECT * FROM app_state WHERE appId = :appId LIMIT 1")
    fun observe(appId: String): Flow<AppState?>

    @Query("SELECT * FROM app_state")
    fun observeAll(): Flow<List<AppState>>

    @Query("SELECT * FROM app_state WHERE hardBlockActive = 1 OR cooldownActive = 1")
    suspend fun getActiveEnforcementStates(): List<AppState>
}
