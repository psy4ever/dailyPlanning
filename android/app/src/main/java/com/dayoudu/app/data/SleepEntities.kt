package com.dayoudu.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "sleep_logs", indices = [Index("wakeMillis")])
data class SleepLogEntity(
    @PrimaryKey val id: String,
    val onsetMillis: Long,
    val wakeMillis: Long,
    val awakeSeconds: Long?,
    val timezone: String,
    val version: Int,
)

@Dao
interface SleepDao {
    @Insert suspend fun insert(log: SleepLogEntity)
    @Update suspend fun update(log: SleepLogEntity)

    @Query("DELETE FROM sleep_logs WHERE id = :id AND version = :version")
    suspend fun delete(id: String, version: Int): Int

    @Query("SELECT * FROM sleep_logs WHERE id = :id LIMIT 1")
    suspend fun get(id: String): SleepLogEntity?

    @Query("SELECT * FROM sleep_logs WHERE wakeMillis >= :fromMillis AND wakeMillis < :toMillis ORDER BY wakeMillis")
    fun observeWakeDay(fromMillis: Long, toMillis: Long): Flow<List<SleepLogEntity>>

    @Query("SELECT * FROM sleep_logs WHERE onsetMillis < :toMillis AND wakeMillis > :fromMillis ORDER BY onsetMillis")
    fun observeRange(fromMillis: Long, toMillis: Long): Flow<List<SleepLogEntity>>

    @Query("SELECT COUNT(*) FROM sleep_logs WHERE onsetMillis < :endMillis AND wakeMillis > :startMillis")
    suspend fun overlapCount(startMillis: Long, endMillis: Long): Int

    @Query("SELECT COUNT(*) FROM sleep_logs WHERE id != :id AND onsetMillis < :endMillis AND wakeMillis > :startMillis")
    suspend fun otherOverlapCount(id: String, startMillis: Long, endMillis: Long): Int
}
