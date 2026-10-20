package com.dayoudu.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "value_model_versions", primaryKeys = ["modelId", "version"])
data class ValueModelVersionEntity(
    val modelId: String,
    val version: Int,
    val confirmedAtMillis: Long,
)

@Entity(tableName = "value_dimensions", primaryKeys = ["modelId", "modelVersion", "dimensionId"],
    indices = [Index(value = ["modelId", "modelVersion"])])
data class ValueDimensionEntity(
    val modelId: String,
    val modelVersion: Int,
    val dimensionId: String,
    val name: String,
    val weight: String,
)

@Entity(tableName = "activity_evaluations", primaryKeys = ["activityId", "modelId", "modelVersion"])
data class ActivityEvaluationEntity(
    val activityId: String,
    val modelId: String,
    val modelVersion: Int,
    val evaluatedAtMillis: Long,
    val version: Int,
)

@Entity(tableName = "dimension_ratings", primaryKeys = ["activityId", "modelId", "modelVersion", "dimensionId"],
    indices = [Index(value = ["modelId", "modelVersion"])])
data class DimensionRatingEntity(
    val activityId: String,
    val modelId: String,
    val modelVersion: Int,
    val dimensionId: String,
    val score: String?,
)

@Dao
interface ValueDao {
    @Query("SELECT * FROM value_model_versions ORDER BY confirmedAtMillis DESC, version DESC LIMIT 1")
    fun observeLatestModel(): Flow<ValueModelVersionEntity?>

    @Query("SELECT * FROM value_model_versions ORDER BY confirmedAtMillis DESC, version DESC LIMIT 1")
    suspend fun latestModel(): ValueModelVersionEntity?

    @Query("SELECT * FROM value_dimensions WHERE modelId = :modelId AND modelVersion = :version ORDER BY rowid")
    fun observeDimensions(modelId: String, version: Int): Flow<List<ValueDimensionEntity>>

    @Query("SELECT * FROM value_dimensions WHERE modelId = :modelId AND modelVersion = :version ORDER BY rowid")
    suspend fun dimensions(modelId: String, version: Int): List<ValueDimensionEntity>

    @Insert suspend fun insert(model: ValueModelVersionEntity)
    @Insert suspend fun insertDimensions(dimensions: List<ValueDimensionEntity>)

    @Query("SELECT * FROM activity_evaluations WHERE activityId = :activityId AND modelId = :modelId AND modelVersion = :version LIMIT 1")
    suspend fun evaluation(activityId: String, modelId: String, version: Int): ActivityEvaluationEntity?

    @Query("SELECT * FROM dimension_ratings WHERE activityId = :activityId AND modelId = :modelId AND modelVersion = :version")
    suspend fun ratings(activityId: String, modelId: String, version: Int): List<DimensionRatingEntity>

    @Query("DELETE FROM dimension_ratings WHERE activityId = :activityId AND modelId = :modelId AND modelVersion = :version")
    suspend fun deleteRatings(activityId: String, modelId: String, version: Int)

    @Insert suspend fun insert(evaluation: ActivityEvaluationEntity)
    @Insert suspend fun insertRatings(ratings: List<DimensionRatingEntity>)

    @Query("UPDATE activity_evaluations SET evaluatedAtMillis = :atMillis, version = version + 1 WHERE activityId = :activityId AND modelId = :modelId AND modelVersion = :modelVersion")
    suspend fun touchEvaluation(activityId: String, modelId: String, modelVersion: Int, atMillis: Long)

    @Query("""SELECT DISTINCT e.* FROM activity_evaluations e
        JOIN activity_segments s ON s.activityId = e.activityId
        WHERE s.startMillis < :toMillis AND (s.endMillis IS NULL OR s.endMillis > :fromMillis)""")
    fun observeEvaluations(fromMillis: Long, toMillis: Long): Flow<List<ActivityEvaluationEntity>>

    @Query("SELECT * FROM dimension_ratings WHERE activityId IN (:activityIds)")
    suspend fun ratingsForActivities(activityIds: List<String>): List<DimensionRatingEntity>
}
