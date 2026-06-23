package com.reelkill.data.repository

import androidx.room.withTransaction
import com.reelkill.data.db.ReelKillDatabase
import com.reelkill.data.db.dao.AppStateDao
import com.reelkill.data.db.entity.AppState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StateRepository @Inject constructor(
    private val database: ReelKillDatabase,
    private val appStateDao: AppStateDao
) {
    fun observeState(appId: String): Flow<AppState> {
        return appStateDao.observe(appId).map { it ?: AppState.initial(appId) }
    }

    suspend fun getOrCreateState(appId: String): AppState {
        return database.withTransaction {
            val existing = appStateDao.get(appId)
            if (existing != null) {
                existing
            } else {
                val initial = AppState.initial(appId)
                appStateDao.upsert(initial)
                initial
            }
        }
    }

    suspend fun updateState(appId: String, reducer: (AppState) -> AppState): AppState {
        return database.withTransaction {
            val current = appStateDao.get(appId) ?: AppState.initial(appId)
            val updated = reducer(current)
            appStateDao.upsert(updated)
            updated
        }
    }
}
