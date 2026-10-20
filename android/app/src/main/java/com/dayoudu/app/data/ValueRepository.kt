package com.dayoudu.app.data

import androidx.room.withTransaction
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

data class ModelSnapshot(val model: ValueModelVersionEntity, val dimensions: List<ValueDimensionEntity>)
data class DimensionDraft(val dimensionId: String?, val name: String, val weight: String)
data class EvaluationSnapshot(val evaluation: ActivityEvaluationEntity, val ratings: List<DimensionRatingEntity>)

class ValueRepository(private val database: NutritionDatabase) {
    private val dao = database.valueDao()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeLatestModel(): Flow<ModelSnapshot?> = dao.observeLatestModel().flatMapLatest { model ->
        if (model == null) flowOf(null)
        else dao.observeDimensions(model.modelId, model.version).map { ModelSnapshot(model, it) }
    }

    suspend fun latestModel(): ModelSnapshot? = dao.latestModel()?.let { ModelSnapshot(it, dao.dimensions(it.modelId, it.version)) }

    suspend fun saveModel(drafts: List<DimensionDraft>) {
        require(drafts.isNotEmpty()) { "至少设置一个回报维度" }
        val names = drafts.map { it.name.trim() }
        require(names.all { it.isNotEmpty() } && names.toSet().size == names.size) { "维度名称不能为空或重复" }
        val weights = drafts.map { it.weight.toBigDecimalOrNull() }
        require(weights.all { it != null && it > BigDecimal.ZERO }) { "权重须为正数" }
        database.withTransaction {
            val latest = dao.latestModel()
            val modelId = latest?.modelId ?: UUID.randomUUID().toString()
            val version = (latest?.version ?: 0) + 1
            dao.insert(ValueModelVersionEntity(modelId, version, Instant.now().toEpochMilli()))
            dao.insertDimensions(drafts.mapIndexed { index, draft ->
                ValueDimensionEntity(modelId, version, draft.dimensionId ?: UUID.randomUUID().toString(),
                    names[index], weights[index]!!.toPlainString())
            })
        }
    }

    suspend fun evaluation(activityId: String, model: ModelSnapshot): EvaluationSnapshot? {
        val saved = dao.evaluation(activityId, model.model.modelId, model.model.version) ?: return null
        return EvaluationSnapshot(saved, dao.ratings(activityId, model.model.modelId, model.model.version))
    }

    suspend fun saveEvaluation(activityId: String, model: ModelSnapshot, scores: Map<String, String?>) {
        val modelIds = model.dimensions.map { it.dimensionId }.toSet()
        require(scores.keys == modelIds) { "评分维度与当前模型不一致" }
        val normalized = scores.mapValues { (_, text) ->
            text?.trim()?.takeIf { it.isNotEmpty() }?.toBigDecimalOrNull()
        }
        require(scores.all { (id, text) -> text.isNullOrBlank() || normalized[id] != null }) { "评分须为 0–10" }
        require(normalized.values.all { it == null || (it >= BigDecimal.ZERO && it <= BigDecimal.TEN) }) { "评分须为 0–10" }
        database.withTransaction {
            val activity = database.activityDao().get(activityId) ?: throw IllegalArgumentException("活动不存在")
            require(activity.sessionStatus == "FINISHED" && activity.deletedAtMillis == null) { "请先结束活动再评价" }
            val existing = dao.evaluation(activityId, model.model.modelId, model.model.version)
            val now = System.currentTimeMillis()
            if (existing == null) dao.insert(ActivityEvaluationEntity(activityId, model.model.modelId, model.model.version, now, 1))
            else {
                dao.deleteRatings(activityId, model.model.modelId, model.model.version)
                dao.touchEvaluation(activityId, model.model.modelId, model.model.version, now)
            }
            dao.insertRatings(model.dimensions.map { dimension ->
                DimensionRatingEntity(activityId, model.model.modelId, model.model.version,
                    dimension.dimensionId, normalized[dimension.dimensionId]?.toPlainString())
            })
        }
    }

    fun observeEvaluations(fromMillis: Long, toMillis: Long): Flow<List<ActivityEvaluationEntity>> =
        dao.observeEvaluations(fromMillis, toMillis)

    suspend fun ratingsForActivities(ids: List<String>): List<DimensionRatingEntity> =
        if (ids.isEmpty()) emptyList() else dao.ratingsForActivities(ids)
}
