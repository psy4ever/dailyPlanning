package com.dayoudu.app.data

import android.content.Context
import androidx.room.withTransaction
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.Duration
import java.time.DayOfWeek
import java.math.BigDecimal
import java.util.UUID
import kotlinx.coroutines.flow.Flow

class PlanningRepository(private val database: NutritionDatabase, context: Context) {
    private val plans = database.planDao()
    private val activities = database.activityDao()
    private val classifications = database.classificationDao()
    private val reminders = ReminderScheduler(context)

    fun observePlans(date: LocalDate, zone: ZoneId): Flow<List<PlanEntity>> =
        plans.observeDay(date.atStartOfDay(zone).toInstant().toEpochMilli(), date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())

    fun observeActivities(date: LocalDate, zone: ZoneId): Flow<List<ActivityWithSegments>> =
        activities.observeDay(date.atStartOfDay(zone).toInstant().toEpochMilli(), date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())

    fun observePlanActivities(date: LocalDate, zone: ZoneId): Flow<List<ActivityWithSegments>> =
        activities.observeForVisiblePlans(date.atStartOfDay(zone).toInstant().toEpochMilli(),
            date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())

    fun observeActive(): Flow<ActivityWithSegments?> = activities.observeActive()
    fun observeProjects(): Flow<List<ProjectEntity>> = classifications.observeProjects()

    fun observeActivitiesRange(fromMillis: Long, toMillis: Long): Flow<List<ActivityWithSegments>> =
        activities.observeDay(fromMillis, toMillis)

    suspend fun dayDocument(date: LocalDate, zone: ZoneId): DailyPlanDocument {
        val from = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val items = plans.startedOnDay(from, to).map { plan ->
            val start = java.time.Instant.ofEpochMilli(plan.scheduledStartMillis).atZone(zone).toLocalDateTime()
            DailyPlanItem(plan.title, (start.hour * 60 + start.minute),
                Duration.ofMillis(plan.scheduledEndMillis - plan.scheduledStartMillis).toMinutes().toInt(),
                plan.fixed, plan.reminderMinutesBefore, plan.reminderSilent)
        }
        return DailyPlanDocument(date, zone, items).also(DailyPlanCodec::validate)
    }

    suspend fun applyDayDocument(document: DailyPlanDocument, targetDate: LocalDate, zone: ZoneId): Int {
        DailyPlanCodec.validate(document)
        require(document.items.isNotEmpty()) { "日计划没有可导入的项目" }
        val inserted = database.withTransaction {
            val now = System.currentTimeMillis()
            document.items.map { item ->
                val start = targetDate.atStartOfDay().plusMinutes(item.startMinute.toLong())
                val startMs = start.atZone(zone).toInstant().toEpochMilli()
                val endMs = start.plusMinutes(item.durationMinutes.toLong()).atZone(zone).toInstant().toEpochMilli()
                require(endMs > startMs) { "计划结束时间须晚于开始时间" }
                PlanEntity(UUID.randomUUID().toString(), item.title.trim(), startMs, endMs, zone.id,
                    item.fixed, "SCHEDULED", startMs, endMs, 1, now, now,
                    item.reminderMinutesBefore, item.reminderSilent).also { plans.insert(it) }
            }
        }
        inserted.forEach(reminders::sync)
        return inserted.size
    }

    /** Applies one template to chosen dates atomically. Existing days are skipped unless append is explicit. */
    suspend fun applyDocumentRange(document: DailyPlanDocument, first: LocalDate, last: LocalDate,
                                   weekdays: Set<DayOfWeek>, skipExisting: Boolean, zone: ZoneId): Pair<Int, Int> {
        DailyPlanCodec.validate(document)
        require(document.items.isNotEmpty()) { "来源日没有可复用的计划" }
        require(!last.isBefore(first) && java.time.temporal.ChronoUnit.DAYS.between(first, last) < 120) {
            "日期范围最多 120 天"
        }
        require(weekdays.isNotEmpty()) { "至少选择一个星期" }
        val dates = generateSequence(first) { if (it.isBefore(last)) it.plusDays(1) else null }
            .filter { it.dayOfWeek in weekdays }.toList()
        require(dates.size * document.items.size <= 5000) { "一次最多生成 5000 项计划" }
        val inserted = database.withTransaction {
            val now = System.currentTimeMillis()
            dates.flatMap { day ->
                val startOfDay = day.atStartOfDay(zone).toInstant().toEpochMilli()
                val endOfDay = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                if (skipExisting && plans.startedOnDay(startOfDay, endOfDay).isNotEmpty()) emptyList()
                else document.items.map { item ->
                    val start = day.atStartOfDay().plusMinutes(item.startMinute.toLong())
                    val startMs = start.atZone(zone).toInstant().toEpochMilli()
                    val endMs = start.plusMinutes(item.durationMinutes.toLong()).atZone(zone).toInstant().toEpochMilli()
                    require(endMs > startMs && endMs <= endOfDay) { "目标日有无效时段，请调整范围" }
                    PlanEntity(UUID.randomUUID().toString(), item.title.trim(), startMs, endMs, zone.id,
                        item.fixed, "SCHEDULED", startMs, endMs, 1, now, now,
                        item.reminderMinutesBefore, item.reminderSilent).also { plans.insert(it) }
                }
            }
        }
        inserted.forEach(reminders::sync)
        return (inserted.size / document.items.size) to inserted.size
    }

    suspend fun removePlan(id: String, expectedVersion: Int) {
        database.withTransaction {
            val old = plans.get(id) ?: throw IllegalArgumentException("计划不存在")
            require(old.version == expectedVersion && old.deletedAtMillis == null) { "计划已变化，请重新打开" }
            plans.update(old.copy(deletedAtMillis = System.currentTimeMillis(), version = old.version + 1,
                updatedAtMillis = System.currentTimeMillis()))
        }
        reminders.cancel(id)
    }

    suspend fun restorePlan(id: String, expectedVersion: Int) {
        val restored = database.withTransaction {
            val old = plans.get(id) ?: throw IllegalArgumentException("计划不存在")
            require(old.version == expectedVersion && old.deletedAtMillis != null) { "计划已变化，无法撤销" }
            old.copy(deletedAtMillis = null, version = old.version + 1, updatedAtMillis = System.currentTimeMillis())
                .also { plans.update(it) }
        }
        reminders.sync(restored)
    }

    suspend fun addPlan(title: String, start: LocalDateTime, end: LocalDateTime, zone: ZoneId, fixed: Boolean,
                        reminderMinutesBefore: Int? = null, reminderSilent: Boolean = false) {
        val clean = title.trim()
        require(clean.isNotEmpty() && clean.length <= 120) { "请填写 1–120 字的计划标题" }
        val startMs = start.atZone(zone).toInstant().toEpochMilli()
        val endMs = end.atZone(zone).toInstant().toEpochMilli()
        require(endMs > startMs) { "结束时间须晚于开始时间" }
        require(start.toLocalDate() == end.toLocalDate()) { "每日计划须在同一天结束" }
        val now = System.currentTimeMillis()
        require(reminderMinutesBefore == null || reminderMinutesBefore in 0..10_080) { "提醒提前时间须在 0–10080 分钟之间" }
        val plan = PlanEntity(UUID.randomUUID().toString(), clean, startMs, endMs, zone.id, fixed,
            "SCHEDULED", startMs, endMs, 1, now, now, reminderMinutesBefore, reminderSilent)
        plans.insert(plan)
        reminders.sync(plan)
    }

    suspend fun copyPlan(id: String, expectedVersion: Int) {
        val copied = database.withTransaction {
            val old = plans.get(id) ?: throw IllegalArgumentException("计划不存在")
            require(old.version == expectedVersion) { "计划已变化，请重新打开" }
            require(old.deletedAtMillis == null) { "计划已移除" }
            val now = System.currentTimeMillis()
            old.copy(id = UUID.randomUUID().toString(), status = "SCHEDULED",
                baselineStartMillis = old.scheduledStartMillis, baselineEndMillis = old.scheduledEndMillis,
                version = 1, createdAtMillis = now, updatedAtMillis = now).also { plans.insert(it) }
        }
        reminders.sync(copied)
    }

    suspend fun copyPlanToDate(id: String, expectedVersion: Int, targetDate: LocalDate, zone: ZoneId) {
        val original = plans.get(id) ?: throw IllegalArgumentException("计划不存在")
        require(original.version == expectedVersion && original.deletedAtMillis == null) { "计划已变化，请重新打开" }
        require(original.status != "CANCELLED") { "已取消的计划不能复制" }
        val start = java.time.Instant.ofEpochMilli(original.scheduledStartMillis).atZone(zone)
        val minutes = Duration.ofMillis(original.scheduledEndMillis - original.scheduledStartMillis).toMinutes().toInt()
        val document = DailyPlanDocument(start.toLocalDate(), zone, listOf(DailyPlanItem(original.title,
            start.hour * 60 + start.minute, minutes, original.fixed,
            original.reminderMinutesBefore, original.reminderSilent)))
        applyDayDocument(document, targetDate, zone)
    }

    suspend fun updatePlan(id: String, expectedVersion: Int, title: String, start: LocalDateTime,
                           end: LocalDateTime, zone: ZoneId, fixed: Boolean, reason: String,
                           reminderMinutesBefore: Int? = null, reminderSilent: Boolean = false) {
        val clean = title.trim()
        require(clean.isNotEmpty() && clean.length <= 120) { "请填写计划标题" }
        val startMs = start.atZone(zone).toInstant().toEpochMilli()
        val endMs = end.atZone(zone).toInstant().toEpochMilli()
        require(endMs > startMs) { "结束时间须晚于开始时间" }
        require(start.toLocalDate() == end.toLocalDate()) { "每日计划须在同一天结束" }
        require(reminderMinutesBefore == null || reminderMinutesBefore in 0..10_080) { "提醒提前时间须在 0–10080 分钟之间" }
        val updated = database.withTransaction {
            val old = plans.get(id) ?: throw IllegalArgumentException("计划不存在")
            require(old.version == expectedVersion) { "计划已变化，请重新打开" }
            require(old.deletedAtMillis == null) { "计划已移除" }
            require(old.status != "CANCELLED") { "已取消的计划不能编辑" }
            val now = System.currentTimeMillis()
            val changedTime = startMs != old.scheduledStartMillis || endMs != old.scheduledEndMillis
            if (changedTime) {
                require(reason.trim().isNotEmpty()) { "改期时请填写调整原因" }
                plans.insert(PlanRevisionEntity(UUID.randomUUID().toString(), id, old.version,
                    old.scheduledStartMillis, old.scheduledEndMillis, reason.trim(), now))
            }
            old.copy(title = clean, scheduledStartMillis = startMs, scheduledEndMillis = endMs,
                timezone = zone.id, fixed = fixed,
                status = if (changedTime) "RESCHEDULED" else old.status,
                version = old.version + 1, updatedAtMillis = now,
                reminderMinutesBefore = reminderMinutesBefore, reminderSilent = reminderSilent).also { plans.update(it) }
        }
        reminders.sync(updated)
    }

    suspend fun changePlanStatus(id: String, expectedVersion: Int, status: String) {
        require(status in setOf("COMPLETE", "CANCELLED")) { "不支持的计划状态" }
        val updated = database.withTransaction {
            val old = plans.get(id) ?: throw IllegalArgumentException("计划不存在")
            require(old.version == expectedVersion) { "计划已变化，请重新打开" }
            require(old.deletedAtMillis == null) { "计划已移除" }
            require(old.status != "CANCELLED") { "计划已取消" }
            old.copy(status = status, version = old.version + 1, updatedAtMillis = System.currentTimeMillis())
                .also { plans.update(it) }
        }
        reminders.sync(updated)
    }

    suspend fun startPlan(planId: String, categoryName: String, projectId: String? = null, tagsText: String = "") {
        val category = categoryName.trim()
        require(category.isNotEmpty()) { "请填写主时间类别" }
        val tags = parseTags(tagsText)
        database.withTransaction {
            require(activities.active() == null) { "请先结束当前计时" }
            val plan = plans.get(planId) ?: throw IllegalArgumentException("计划不存在")
            require(plan.deletedAtMillis == null) { "计划已移除" }
            require(plan.status != "CANCELLED") { "已取消的计划不能开始" }
            val now = System.currentTimeMillis()
            require(database.sleepDao().overlapCount(now, now + 1) == 0) { "当前时间与睡眠记录重叠，请核实" }
            require(activities.overlapCount(now, now + 1) == 0) { "当前时间与已有实际活动重叠，请核实" }
            val id = UUID.randomUUID().toString()
            val categoryId = ensureCategory(category)
            val project = projectId?.let { classifications.project(it) ?: throw IllegalArgumentException("项目不存在") }
            require(project == null || !project.archived) { "项目已停用" }
            activities.insert(ActivityEntity(id, plan.title, category, planId, "RUNNING", 1, now, now,
                categoryId = categoryId, projectId = project?.id, projectNameSnapshot = project?.name))
            activities.insert(ActivitySegmentEntity(UUID.randomUUID().toString(), id, now, null))
            classifications.insert(tags.map { ActivityTagEntity(id, it) })
        }
    }

    suspend fun startUnplanned(title: String, categoryName: String, projectId: String? = null, tagsText: String = "") {
        val clean = title.trim()
        val category = categoryName.trim()
        require(clean.isNotEmpty() && category.isNotEmpty()) { "请填写活动标题和主时间类别" }
        val tags = parseTags(tagsText)
        database.withTransaction {
            require(activities.active() == null) { "请先结束当前计时" }
            val now = System.currentTimeMillis()
            require(database.sleepDao().overlapCount(now, now + 1) == 0) { "当前时间与睡眠记录重叠，请核实" }
            require(activities.overlapCount(now, now + 1) == 0) { "当前时间与已有实际活动重叠，请核实" }
            val id = UUID.randomUUID().toString()
            val categoryId = ensureCategory(category)
            val project = projectId?.let { classifications.project(it) ?: throw IllegalArgumentException("项目不存在") }
            require(project == null || !project.archived) { "项目已停用" }
            activities.insert(ActivityEntity(id, clean, category, null, "RUNNING", 1, now, now,
                categoryId = categoryId, projectId = project?.id, projectNameSnapshot = project?.name))
            activities.insert(ActivitySegmentEntity(UUID.randomUUID().toString(), id, now, null))
            classifications.insert(tags.map { ActivityTagEntity(id, it) })
        }
    }

    suspend fun pause(id: String) = transition(id, "RUNNING", "PAUSED", closeSegment = true)
    suspend fun resume(id: String) = transition(id, "PAUSED", "RUNNING", openSegment = true)
    suspend fun finish(id: String) {
        database.withTransaction {
            val old = activities.get(id) ?: throw IllegalArgumentException("活动不存在")
            if (old.sessionStatus == "FINISHED") return@withTransaction
            require(old.sessionStatus in setOf("RUNNING", "PAUSED")) { "当前活动无法结束" }
            val now = System.currentTimeMillis()
            activities.openSegment(id)?.let { segment ->
                require(now >= segment.startMillis) { "系统时间早于计时开始，请核实设备时间" }
                activities.update(segment.copy(endMillis = now))
            }
            activities.update(old.copy(sessionStatus = "FINISHED", version = old.version + 1, updatedAtMillis = now))
        }
    }

    private suspend fun transition(id: String, from: String, to: String,
                                   closeSegment: Boolean = false, openSegment: Boolean = false) {
        database.withTransaction {
            val old = activities.get(id) ?: throw IllegalArgumentException("活动不存在")
            require(old.sessionStatus == from) { "计时状态已变化，请重试" }
            val now = System.currentTimeMillis()
            if (closeSegment) {
                val segment = activities.openSegment(id) ?: throw IllegalStateException("运行片段缺失")
                require(now >= segment.startMillis) { "系统时间早于计时开始，请核实设备时间" }
                activities.update(segment.copy(endMillis = now))
            }
            if (openSegment) {
                require(database.sleepDao().overlapCount(now, now + 1) == 0) { "当前时间与睡眠记录重叠，请核实" }
                require(activities.overlapCount(now, now + 1) == 0) { "当前时间与已有实际活动重叠，请核实" }
                activities.insert(ActivitySegmentEntity(UUID.randomUUID().toString(), id, now, null))
            }
            activities.update(old.copy(sessionStatus = to, version = old.version + 1, updatedAtMillis = now))
        }
    }

    suspend fun addManual(title: String, categoryName: String, start: LocalDateTime, end: LocalDateTime,
                          zone: ZoneId, projectId: String? = null, tagsText: String = "", planId: String? = null) {
        val clean = title.trim()
        val category = categoryName.trim()
        require(clean.isNotEmpty() && category.isNotEmpty()) { "请填写活动标题和主时间类别" }
        val tags = parseTags(tagsText)
        val startMs = start.atZone(zone).toInstant().toEpochMilli()
        val endMs = end.atZone(zone).toInstant().toEpochMilli()
        require(endMs > startMs) { "结束时间须晚于开始时间" }
        database.withTransaction {
            if (planId != null) {
                val plan = plans.get(planId) ?: throw IllegalArgumentException("计划不存在")
                require(plan.deletedAtMillis == null && plan.status != "CANCELLED") { "计划已取消或移除" }
            }
            require(activities.overlapCount(startMs, endMs) == 0) { "与已有实际活动重叠，请调整时段" }
            require(database.sleepDao().overlapCount(startMs, endMs) == 0) { "与睡眠记录重叠，请调整时段" }
            val now = System.currentTimeMillis()
            val id = UUID.randomUUID().toString()
            val categoryId = ensureCategory(category)
            val project = projectId?.let { classifications.project(it) ?: throw IllegalArgumentException("项目不存在") }
            require(project == null || !project.archived) { "项目已停用" }
            activities.insert(ActivityEntity(id, clean, category, planId, "FINISHED", 1, now, now,
                categoryId = categoryId, projectId = project?.id, projectNameSnapshot = project?.name))
            activities.insert(ActivitySegmentEntity(UUID.randomUUID().toString(), id, startMs, endMs))
            classifications.insert(tags.map { ActivityTagEntity(id, it) })
        }
    }

    private suspend fun ensureCategory(name: String): String {
        require(name.length <= 50) { "主时间类别不能超过 50 字" }
        val existing = classifications.categoryByName(name)
        require(existing?.archived != true) { "该类别已停用，请在“我的”重新启用" }
        if (existing != null) return existing.id
        val id = UUID.randomUUID().toString()
        classifications.insert(TimeCategoryEntity(id, name, false))
        return id
    }

    private fun parseTags(text: String): List<String> {
        val tags = text.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        require(tags.size <= 8 && tags.all { it.length <= 30 }) { "最多 8 个标签，每个不超过 30 字" }
        return tags
    }

    suspend fun saveCash(activityId: String, expectedVersion: Int, isWork: Boolean,
                         currencyText: String, incomeText: String, expenseText: String, commuteText: String) {
        val income = amountOrNull(incomeText, "收入")
        val expense = amountOrNull(expenseText, "直接支出")
        val currency = currencyText.trim().uppercase().takeIf { it.isNotEmpty() }
        require(currency == null || currency.matches(Regex("[A-Z]{3}"))) { "币种请填写三个字母的货币代码" }
        require((income == null && expense == null) || currency != null) { "填写金额时请指定币种" }
        val commute = if (commuteText.isBlank()) null else commuteText.trim().toLongOrNull()
            ?: throw IllegalArgumentException("通勤分钟数须为整数")
        require(commute == null || commute in 0..525_600) { "通勤分钟数须在 0–525600 之间" }
        require(isWork || (income == null && expense == null && commute == null && currency == null)) { "请先标记这是工作活动" }
        database.withTransaction {
            val old = activities.get(activityId) ?: throw IllegalArgumentException("活动不存在")
            require(old.version == expectedVersion && old.deletedAtMillis == null) { "活动已变化，请重新打开" }
            require(old.sessionStatus == "FINISHED") { "请先结束活动再记录现金" }
            activities.update(old.copy(isWork = isWork, incomeAmount = income,
                expenseAmount = expense, currencyCode = currency, commuteMinutes = commute,
                version = old.version + 1, updatedAtMillis = System.currentTimeMillis()))
        }
    }

    suspend fun correctSingleSegment(activityId: String, expectedVersion: Int, title: String,
                                     categoryName: String, start: LocalDateTime, end: LocalDateTime, zone: ZoneId) {
        val clean = title.trim()
        val category = categoryName.trim()
        require(clean.isNotEmpty() && clean.length <= 120 && category.isNotEmpty()) { "请填写活动标题与主类别" }
        val startMs = start.atZone(zone).toInstant().toEpochMilli()
        val endMs = end.atZone(zone).toInstant().toEpochMilli()
        require(endMs > startMs) { "结束时间须晚于开始时间" }
        database.withTransaction {
            val old = activities.get(activityId) ?: throw IllegalArgumentException("活动不存在")
            require(old.version == expectedVersion && old.deletedAtMillis == null) { "活动已变化，请重新打开" }
            require(old.sessionStatus == "FINISHED") { "只能修改已结束活动" }
            val segments = activities.segmentsForActivity(activityId)
            require(segments.size == 1 && segments.first().endMillis != null) { "多段计时请先保留原记录，暂不支持整体改时段" }
            require(activities.overlapCountExcept(activityId, startMs, endMs) == 0) { "与其他实际活动重叠，请调整时段" }
            require(database.sleepDao().overlapCount(startMs, endMs) == 0) { "与睡眠记录重叠，请调整时段" }
            val categoryId = ensureCategory(category)
            activities.update(segments.first().copy(startMillis = startMs, endMillis = endMs))
            activities.update(old.copy(title = clean, categoryName = category, categoryId = categoryId,
                version = old.version + 1, updatedAtMillis = System.currentTimeMillis()))
        }
    }

    suspend fun deleteActivity(activityId: String, expectedVersion: Int) {
        database.withTransaction {
            val old = activities.get(activityId) ?: throw IllegalArgumentException("活动不存在")
            require(old.version == expectedVersion && old.deletedAtMillis == null) { "活动已变化，请重新打开" }
            require(old.sessionStatus == "FINISHED") { "请先结束活动再删除" }
            activities.update(old.copy(deletedAtMillis = System.currentTimeMillis(), version = old.version + 1))
        }
    }

    suspend fun restoreActivity(activityId: String, expectedVersion: Int) {
        database.withTransaction {
            val old = activities.get(activityId) ?: throw IllegalArgumentException("活动不存在")
            require(old.version == expectedVersion && old.deletedAtMillis != null) { "活动已变化，无法撤销" }
            val segments = activities.segmentsForActivity(activityId)
            segments.forEach { segment ->
                val end = segment.endMillis ?: throw IllegalArgumentException("活动片段不完整")
                require(activities.overlapCountExcept(activityId, segment.startMillis, end) == 0 &&
                    database.sleepDao().overlapCount(segment.startMillis, end) == 0) { "原时段已被占用，无法撤销" }
            }
            activities.update(old.copy(deletedAtMillis = null, version = old.version + 1))
        }
    }

    private fun amountOrNull(text: String, label: String): String? {
        if (text.isBlank()) return null
        val amount = text.trim().toBigDecimalOrNull()
        require(amount != null && amount >= BigDecimal.ZERO && amount.scale() <= 2) { "$label 须为非负金额，最多两位小数" }
        return amount.toPlainString()
    }
}
