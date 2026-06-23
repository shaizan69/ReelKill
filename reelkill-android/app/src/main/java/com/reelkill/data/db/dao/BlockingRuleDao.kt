package com.reelkill.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.reelkill.data.db.entity.BlockingRule
import kotlinx.coroutines.flow.Flow

@Dao
interface BlockingRuleDao {
    @Upsert
    suspend fun upsert(rule: BlockingRule)

    @Upsert
    suspend fun upsertAll(rules: List<BlockingRule>)

    @Query("SELECT * FROM blocking_rules WHERE appPackage = :appPackage AND isActive = 1")
    suspend fun getActiveForPackage(appPackage: String): List<BlockingRule>

    @Query("SELECT * FROM blocking_rules WHERE appPackage = :appPackage AND isActive = 1")
    fun observeActiveForPackage(appPackage: String): Flow<List<BlockingRule>>

    @Query("SELECT * FROM blocking_rules WHERE isActive = 1")
    fun observeActive(): Flow<List<BlockingRule>>

    @Query(
        """
        SELECT * FROM blocking_rules
        WHERE appPackage = :appPackage
          AND isActive = 1
          AND viewId = :viewId
        LIMIT 1
        """
    )
    suspend fun findActiveByViewId(appPackage: String, viewId: String): BlockingRule?

    @Query("UPDATE blocking_rules SET isActive = :isActive WHERE id = :id")
    suspend fun setActive(id: String, isActive: Boolean): Int
}
