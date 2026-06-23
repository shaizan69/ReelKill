package com.reelkill.analytics

import com.reelkill.data.db.entity.UsageEvent
import javax.inject.Inject

class HeatmapBuilder @Inject constructor() {
    fun hourlyBuckets(events: List<UsageEvent>): List<Int> {
        val buckets = IntArray(24)
        events.forEach { event ->
            if (event.hour in 0..23) buckets[event.hour] += 1
        }
        return buckets.toList()
    }
}
