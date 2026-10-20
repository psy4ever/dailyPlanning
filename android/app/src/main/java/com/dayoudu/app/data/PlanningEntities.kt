package com.dayoudu.app.data

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Update
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "plans", indices = [Index("scheduledStartMillis")])
data class PlanEntity(
    @PrimaryKey val id: String,
    val title: String,
    val scheduledStartMillis: Long,
    val scheduledEndMillis: Long,
    val timezone: String,
    val fixed: Boolean,
    val status: String,
    val baselineStartMillis: Long,
    val baselineEndMillis: Long,
    val version: Int,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val reminderMinutesBefore: Int? = null,
    @ColumnInfo(defaultValue = "0") val reminderSilent: Boolean = false,
    val deletedAtMillis: Long? = null,
)

@Entity(tableName = "plan_revisions", indices = [Index("planId")])
data class PlanRevisionEntity(
    @PrimaryKey val id: String,
    val planId: String,
    val revision: Int,
    val scheduledStartMillis: Long,
    val scheduledEndMillis: Long,
    val changeReason: String,
    val changedAtMillis: Long,
)

@Entity(tableName = "activities", indices = [Index("planId")])
data class ActivityEntity(
    @PrimaryKey val id: String,
    val title: String,
    val categoryName: String,
    val planId: String?,
    val sessionStatus: String,
    val version: Int,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    @ColumnInfo(defaultValue = "0") val isWork: Boolean = false,
    val incomeAmount: String? = null,
    val expenseAmount: String? = null,
    val currencyCode: String? = null,
    val commuteMinutes: Long? = null,
    val categoryId: String? = null,
    val projectId: String? = null,
    val projectNameSnapshot: String? = null,
    val deletedAtMillis: Long? = null,
)

@Entity(tableName = "activity_segments", indices = [Index("activityId"), Index("startMillis")])
data class ActivitySegmentEntity(
    @PrimaryKey val id: String,
    val activityId: String,
    val startMillis: Long,
    val endMillis: Long?,
)

data class ActivityWithSegments(
    @Embedded val activity: ActivityEntity,
    @Relation(parentColumn = "id", entityColumn = "activityId")
    val segments: List<ActivitySegmentEntity>,
    @Relation(parentColumn = "id", entityColumn = "activityId")
    val tags: List<ActivityTagEntity>,
)

@Dao
interface PlanDao {
    @Query("SELECT * FROM plans WHERE deletedAtMillis IS NULL AND scheduledStartMillis < :toMillis AND scheduledEndMillis > :fromMillis ORDER BY scheduledStartMillis")
    fun observeDay(fromMillis: Long, toMillis: Long): Flow<List<PlanEntity>>

    @Query("SELECT * FROM plans WHERE deletedAtMillis IS NULL AND status != 'CANCELLED' AND scheduledStartMillis >= :fromMillis AND scheduledStartMillis < :toMillis ORDER BY scheduledStartMillis")
    suspend fun startedOnDay(fromMillis: Long, toMillis: Long): List<PlanEntity>

    @Query("SELECT * FROM plans WHERE id = :id LIMIT 1")
    suspend fun get(id: String): PlanEntity?

    @Query("SELECT * FROM plans WHERE deletedAtMillis IS NULL AND reminderMinutesBefore IS NOT NULL AND status NOT IN ('CANCELLED','COMPLETE') AND scheduledStartMillis > :nowMillis")
    suspend fun futureReminders(nowMillis: Long): List<PlanEntity>

    @Insert suspend fun insert(plan: PlanEntity)
    @Update suspend fun update(plan: PlanEntity)
    @Insert suspend fun insert(revision: PlanRevisionEntity)
}

@Dao
interface ActivityDao {
    @Transaction
    @Query("""SELECT DISTINCT a.* FROM activities a JOIN activity_segments s ON s.activityId = a.id
        WHERE a.deletedAtMillis IS NULL AND s.startMillis < :toMillis AND (s.endMillis IS NULL OR s.endMillis > :fromMillis)
        ORDER BY a.createdAtMillis""")
    fun observeDay(fromMillis: Long, toMillis: Long): Flow<List<ActivityWithSegments>>

    @Transaction
    @Query("""SELECT DISTINCT a.* FROM activities a JOIN plans p ON p.id = a.planId
        WHERE a.deletedAtMillis IS NULL AND p.deletedAtMillis IS NULL
        AND p.scheduledStartMillis < :toMillis AND p.scheduledEndMillis > :fromMillis""")
    fun observeForVisiblePlans(fromMillis: Long, toMillis: Long): Flow<List<ActivityWithSegments>>

    @Transaction
    @Query("SELECT * FROM activities WHERE deletedAtMillis IS NULL AND sessionStatus IN ('RUNNING','PAUSED') ORDER BY createdAtMillis DESC LIMIT 1")
    fun observeActive(): Flow<ActivityWithSegments?>

    @Query("SELECT * FROM activities WHERE deletedAtMillis IS NULL AND sessionStatus IN ('RUNNING','PAUSED') LIMIT 1")
    suspend fun active(): ActivityEntity?

    @Query("SELECT * FROM activities WHERE id = :id LIMIT 1")
    suspend fun get(id: String): ActivityEntity?

    @Query("SELECT * FROM activity_segments WHERE activityId = :activityId AND endMillis IS NULL LIMIT 1")
    suspend fun openSegment(activityId: String): ActivitySegmentEntity?

    @Query("SELECT * FROM activity_segments WHERE activityId = :activityId ORDER BY startMillis")
    suspend fun segmentsForActivity(activityId: String): List<ActivitySegmentEntity>

    @Query("""SELECT COUNT(*) FROM activity_segments s JOIN activities a ON a.id=s.activityId
        WHERE a.deletedAtMillis IS NULL AND s.startMillis < :endMillis AND COALESCE(s.endMillis, :endMillis) > :startMillis""")
    suspend fun overlapCount(startMillis: Long, endMillis: Long): Int

    @Query("""SELECT COUNT(*) FROM activity_segments s JOIN activities a ON a.id=s.activityId
        WHERE a.deletedAtMillis IS NULL AND a.id != :activityId
        AND s.startMillis < :endMillis AND COALESCE(s.endMillis, :endMillis) > :startMillis""")
    suspend fun overlapCountExcept(activityId: String, startMillis: Long, endMillis: Long): Int

    @Insert suspend fun insert(activity: ActivityEntity)
    @Update suspend fun update(activity: ActivityEntity)
    @Insert suspend fun insert(segment: ActivitySegmentEntity)
    @Update suspend fun update(segment: ActivitySegmentEntity)

    @Query("SELECT * FROM activity_segments WHERE startMillis < :toMillis AND (endMillis IS NULL OR endMillis > :fromMillis) ORDER BY startMillis")
    suspend fun segments(fromMillis: Long, toMillis: Long): List<ActivitySegmentEntity>

    @Query("SELECT * FROM activities WHERE id IN (:ids)")
    suspend fun activities(ids: List<String>): List<ActivityEntity>
}
