package com.reelkill.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.reelkill.data.db.entity.DailySummary
import kotlinx.coroutines.flow.Flow

@Dao
interface DailySummaryDao {
    @Upsert
    suspend fun upsert(summary: DailySummary)

    @Upsert
    suspend fun upsertAll(summaries: List<DailySummary>)

    @Query("SELECT * FROM daily_summaries WHERE appId = :appId AND day = :day LIMIT 1")
    suspend fun get(appId: String, day: String): DailySummary?

    @Query("SELECT * FROM daily_summaries WHERE appId = :appId AND day = :day LIMIT 1")
    fun observe(appId: String, day: String): Flow<DailySummary?>

    @Query(
        """
        SELECT * FROM daily_summaries
        WHERE appId = :appId
          AND day BETWEEN :startDayInclusive AND :endDayInclusive
        ORDER BY day ASC
        """
    )
    fun observeRange(
        appId: String,
        startDayInclusive: String,
        endDayInclusive: String
    ): Flow<List<DailySummary>>

    @Query(
        """
        SELECT * FROM daily_summaries
        WHERE appId = :appId
          AND day BETWEEN :startDayInclusive AND :endDayInclusive
        ORDER BY day ASC
        """
    )
    suspend fun getRange(
        appId: String,
        startDayInclusive: String,
        endDayInclusive: String
    ): List<DailySummary>
}
