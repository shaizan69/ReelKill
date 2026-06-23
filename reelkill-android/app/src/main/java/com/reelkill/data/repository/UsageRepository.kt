package com.reelkill.data.repository

import com.reelkill.data.db.dao.UsageEventDao
import com.reelkill.data.db.entity.UsageEvent
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class UsageRepository @Inject constructor(
    private val usageEventDao: UsageEventDao
) {
    suspend fun insert(event: UsageEvent): Long = usageEventDao.insert(event)

    suspend fun createEvent(
        eventType: String,
        appId: String,
        sessionId: String,
        reelId: String? = null,
        watchDuration: Long? = null,
        clock: Clock = Clock.systemUTC(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): UsageEvent {
        val now = Instant.now(clock)
        return UsageEvent(
            eventType = eventType,
            appId = appId,
            reelId = reelId,
            watchDuration = watchDuration,
            timestamp = now.toString(),
            sessionId = sessionId,
            day = now.atZone(zoneId).toLocalDate().toString(),
            hour = now.atZone(zoneId).hour
        )
    }

    fun observeForDay(appId: String, day: String): Flow<List<UsageEvent>> {
        return usageEventDao.observeForDay(appId, day)
    }

    suspend fun countReelViewsSince(appId: String, fromInclusiveUtc: String): Int {
        return usageEventDao.countSince(appId, UsageEvent.TYPE_REEL_VIEWED, fromInclusiveUtc)
    }

    suspend fun countEventsForDay(appId: String, eventType: String, day: String): Int {
        return usageEventDao.countForDay(appId, eventType, day)
    }
}
