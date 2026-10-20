package com.dayoudu.app.data

import androidx.room.withTransaction
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.Flow

class SleepRepository(private val database: NutritionDatabase) {
    fun observeRange(fromMillis: Long, toMillis: Long): Flow<List<SleepLogEntity>> =
        database.sleepDao().observeRange(fromMillis, toMillis)

    fun observeWakeDay(date: LocalDate, zone: ZoneId): Flow<List<SleepLogEntity>> =
        database.sleepDao().observeWakeDay(
            date.atStartOfDay(zone).toInstant().toEpochMilli(),
            date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
        )

    suspend fun add(onset: LocalDateTime, wake: LocalDateTime, awakeMinutes: Long?, zone: ZoneId) {
        save(null, null, onset, wake, awakeMinutes, zone)
    }

    suspend fun save(id: String?, expectedVersion: Int?, onset: LocalDateTime, wake: LocalDateTime,
                     awakeMinutes: Long?, zone: ZoneId) {
        val start = onset.atZone(zone).toInstant().toEpochMilli()
        val end = wake.atZone(zone).toInstant().toEpochMilli()
        require(end > start) { "醒来时间须晚于入睡时间" }
        val awakeSeconds = awakeMinutes?.let {
            require(it in 0..10_080) { "夜间清醒分钟数须在 0–10080 之间" }
            it * 60
        }
        require(awakeSeconds == null || awakeSeconds * 1000 < end - start) { "清醒时长须小于睡眠时段" }
        database.withTransaction {
            val old = id?.let { database.sleepDao().get(it) }
            require(id == null || old?.version == expectedVersion) { "睡眠记录已变化，请重新打开" }
            val sleepOverlap = if (id == null) database.sleepDao().overlapCount(start, end)
                else database.sleepDao().otherOverlapCount(id, start, end)
            require(sleepOverlap == 0) { "与已有睡眠时段重叠" }
            require(database.activityDao().overlapCount(start, end) == 0) { "与实际活动重叠，请先核实时间" }
            if (old == null) database.sleepDao().insert(SleepLogEntity(UUID.randomUUID().toString(), start, end, awakeSeconds, zone.id, 1))
            else database.sleepDao().update(old.copy(onsetMillis = start, wakeMillis = end,
                awakeSeconds = awakeSeconds, timezone = zone.id, version = old.version + 1))
        }
    }

    suspend fun delete(id: String, expectedVersion: Int) {
        require(database.sleepDao().delete(id, expectedVersion) == 1) { "睡眠记录已变化，请重新打开" }
    }
}
