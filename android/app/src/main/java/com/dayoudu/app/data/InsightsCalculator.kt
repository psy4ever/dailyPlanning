package com.dayoudu.app.data

import java.math.BigDecimal
import java.math.RoundingMode

data class TimeBreakdown(
    val windowMillis: Long,
    val categoryMillis: Map<String, Long>,
    val unknownMillis: Long,
    val conflictMillis: Long,
)

data class ValueBreakdown(
    val score: BigDecimal?,
    val ratedMillis: Long,
    val recordedMillis: Long,
    val missingActivityCount: Int,
    val dimensionMeans: Map<String, BigDecimal>,
)

data class CashBreakdown(
    val currencyCode: String?,
    val workMillis: Long,
    val knownIncome: BigDecimal,
    val knownExpense: BigDecimal,
    val missingAmountCount: Int,
    val commuteMinutes: Long?,
    val netHourly: BigDecimal?,
    val withCommuteHourly: BigDecimal?,
)

object InsightsCalculator {
    private data class Span(val start: Long, val end: Long, val category: String)
    private data class Boundary(val at: Long, val spanIndex: Int, val delta: Int)

    fun time(fromMillis: Long, toMillis: Long, activities: List<ActivityWithSegments>,
             sleep: List<SleepLogEntity>, nowMillis: Long): TimeBreakdown {
        if (toMillis <= fromMillis) return TimeBreakdown(0, emptyMap(), 0, 0)
        val spans = activities.flatMap { item ->
            item.segments.mapNotNull { segment ->
                clip(segment.startMillis, segment.endMillis ?: nowMillis, fromMillis, toMillis)
                    ?.let { Span(it.first, it.second, item.activity.categoryName) }
            }
        } + sleep.mapNotNull { log ->
            clip(log.onsetMillis, log.wakeMillis, fromMillis, toMillis)
                ?.let { Span(it.first, it.second, "睡眠") }
        }
        val boundaries = spans.flatMapIndexed { spanIndex, span ->
            listOf(Boundary(span.start, spanIndex, 1), Boundary(span.end, spanIndex, -1))
        }
            .sortedBy { it.at }
        val totals = linkedMapOf<String, Long>()
        val active = mutableSetOf<Int>()
        var cursor = fromMillis
        var unknown = 0L
        var conflict = 0L
        var index = 0
        while (cursor < toMillis) {
            val next = if (index < boundaries.size) boundaries[index].at.coerceAtMost(toMillis) else toMillis
            val duration = (next - cursor).coerceAtLeast(0)
            when (active.size) {
                0 -> unknown += duration
                1 -> { val category = spans[active.first()].category; totals[category] = (totals[category] ?: 0) + duration }
                else -> conflict += duration
            }
            cursor = next
            while (index < boundaries.size && boundaries[index].at <= cursor) {
                val boundary = boundaries[index++]
                if (boundary.delta > 0) active.add(boundary.spanIndex) else active.remove(boundary.spanIndex)
            }
        }
        return TimeBreakdown(toMillis - fromMillis, totals, unknown, conflict)
    }

    fun value(fromMillis: Long, toMillis: Long, activities: List<ActivityWithSegments>,
              model: ModelSnapshot?, evaluations: List<ActivityEvaluationEntity>,
              ratings: List<DimensionRatingEntity>): ValueBreakdown {
        if (model == null) return ValueBreakdown(null, 0, 0, 0, emptyMap())
        val weights = model.dimensions.associate { it.dimensionId to it.weight.toBigDecimal() }
        val totalWeight = weights.values.fold(BigDecimal.ZERO) { sum, weight -> sum + weight }
        if (totalWeight <= BigDecimal.ZERO) return ValueBreakdown(null, 0, 0, 0, emptyMap())
        val relevant = evaluations.filter { it.modelId == model.model.modelId && it.modelVersion == model.model.version }
            .associateBy { it.activityId }
        val ratedByActivity = ratings.filter { it.modelId == model.model.modelId && it.modelVersion == model.model.version }
            .groupBy { it.activityId }
        var recorded = 0L
        var rated = 0L
        var missing = 0
        var weightedScore = BigDecimal.ZERO
        val dimensionSums = mutableMapOf<String, BigDecimal>()
        val dimensionDurations = mutableMapOf<String, Long>()
        activities.filter { it.activity.sessionStatus == "FINISHED" }.forEach { item ->
            val duration = item.segments.sumOf { segment ->
                clip(segment.startMillis, segment.endMillis ?: toMillis, fromMillis, toMillis)
                    ?.let { it.second - it.first } ?: 0
            }
            if (duration <= 0) return@forEach
            recorded += duration
            val scores = ratedByActivity[item.activity.id].orEmpty().associate { it.dimensionId to it.score?.toBigDecimalOrNull() }
            val complete = relevant.containsKey(item.activity.id) && weights.keys.all { scores[it] != null }
            if (!complete) { missing++; return@forEach }
            val activityScore = weights.entries.fold(BigDecimal.ZERO) { sum, (id, weight) ->
                val score = scores[id]!!
                dimensionSums[id] = (dimensionSums[id] ?: BigDecimal.ZERO) + score * BigDecimal(duration)
                dimensionDurations[id] = (dimensionDurations[id] ?: 0) + duration
                sum + weight * score
            }.multiply(BigDecimal.TEN).divide(totalWeight, 8, RoundingMode.HALF_UP)
            weightedScore += activityScore * BigDecimal(duration)
            rated += duration
        }
        val result = if (recorded > 0 && missing == 0 && rated > 0)
            weightedScore.divide(BigDecimal(rated), 1, RoundingMode.HALF_UP) else null
        val means = dimensionSums.mapValues { (id, sum) ->
            sum.divide(BigDecimal(dimensionDurations[id]!!), 1, RoundingMode.HALF_UP)
        }
        return ValueBreakdown(result, rated, recorded, missing, means)
    }

    fun cash(fromMillis: Long, toMillis: Long, activities: List<ActivityWithSegments>): List<CashBreakdown> {
        val work = activities.filter { item ->
            item.activity.isWork && item.activity.sessionStatus == "FINISHED" &&
                item.segments.maxOfOrNull { it.endMillis ?: Long.MAX_VALUE }?.let { it >= fromMillis && it < toMillis } == true
        }
        return work.groupBy { it.activity.currencyCode }.map { (currency, group) -> cashGroup(currency, group) }
    }

    private fun cashGroup(currency: String?, work: List<ActivityWithSegments>): CashBreakdown {
        val millis = work.sumOf { item -> item.segments.sumOf { segment ->
            ((segment.endMillis ?: segment.startMillis) - segment.startMillis).coerceAtLeast(0)
        } }
        val income = work.mapNotNull { it.activity.incomeAmount?.toBigDecimalOrNull() }
            .fold(BigDecimal.ZERO, BigDecimal::add)
        val expense = work.mapNotNull { it.activity.expenseAmount?.toBigDecimalOrNull() }
            .fold(BigDecimal.ZERO, BigDecimal::add)
        val missing = work.count { it.activity.incomeAmount == null || it.activity.expenseAmount == null }
        val commute = if (work.all { it.activity.commuteMinutes != null }) work.sumOf { it.activity.commuteMinutes ?: 0 }
            else null
        val net = income - expense
        val hourly = if (missing == 0 && millis > 0) net.multiply(BigDecimal(3_600_000))
            .divide(BigDecimal(millis), 2, RoundingMode.HALF_UP) else null
        val withCommute = if (hourly != null && commute != null && millis + commute * 60_000 > 0)
            net.multiply(BigDecimal(3_600_000))
                .divide(BigDecimal(millis + commute * 60_000), 2, RoundingMode.HALF_UP) else null
        return CashBreakdown(currency, millis, income, expense, missing, commute, hourly, withCommute)
    }

    private fun clip(start: Long, end: Long, from: Long, to: Long): Pair<Long, Long>? {
        val left = maxOf(start, from)
        val right = minOf(end, to)
        return if (right > left) left to right else null
    }
}
