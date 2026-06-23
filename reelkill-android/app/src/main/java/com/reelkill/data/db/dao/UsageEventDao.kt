package com.reelkill.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.reelkill.data.db.entity.UsageEvent
import kotlinx.coroutines.flow.Flow

@Dao
interface UsageEventDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: UsageEvent): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(events: List<UsageEvent>): List<Long>

    @Query("SELECT * FROM usage_events WHERE appId = :appId ORDER BY timestamp DESC")
    fun observeForApp(appId: String): Flow<List<UsageEvent>>

    @Query("SELECT * FROM usage_events WHERE appId = :appId AND day = :day ORDER BY timestamp DESC")
    fun observeForDay(appId: String, day: String): Flow<List<UsageEvent>>

    @Query("SELECT * FROM usage_events WHERE appId = :appId AND day = :day ORDER BY timestamp ASC")
    suspend fun getForDay(appId: String, day: String): List<UsageEvent>

    @Query(
        """
        SELECT COUNT(*) FROM usage_events
        WHERE appId = :appId
          AND eventType = :eventType
          AND timestamp >= :fromInclusiveUtc
        """
    )
    suspend fun countSince(appId: String, eventType: String, fromInclusiveUtc: String): Int

    @Query(
        """
        SELECT COUNT(*) FROM usage_events
        WHERE appId = :appId
          AND eventType = :eventType
          AND day = :day
        """
    )
    suspend fun countForDay(appId: String, eventType: String, day: String): Int

    @Query(
        """
        SELECT COUNT(*) FROM usage_events
        WHERE appId = :appId
          AND eventType = :eventType
          AND day = :day
          AND hour = :hour
        """
    )
    suspend fun countForHour(appId: String, eventType: String, day: String, hour: Int): Int

    @Query(
        """
        SELECT COALESCE(SUM(watchDuration), 0) FROM usage_events
        WHERE appId = :appId
          AND eventType = :eventType
          AND day = :day
        """
    )
    suspend fun sumWatchDurationForDay(appId: String, eventType: String, day: String): Long

    @Query(
        """
        SELECT * FROM usage_events
        WHERE appId = :appId
          AND eventType = :eventType
          AND timestamp >= :fromInclusiveUtc
        ORDER BY timestamp ASC
        """
    )
    suspend fun getSince(appId: String, eventType: String, fromInclusiveUtc: String): List<UsageEvent>

    @Query("DELETE FROM usage_events WHERE timestamp < :beforeExclusiveUtc")
    suspend fun deleteOlderThan(beforeExclusiveUtc: String): Int
}
