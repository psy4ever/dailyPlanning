package com.dayoudu.app

import android.Manifest
import android.app.DatePickerDialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dayoudu.app.data.ActivityWithSegments
import com.dayoudu.app.data.DailyPlanCodec
import com.dayoudu.app.data.DailyPlanDocument
import com.dayoudu.app.data.PlanEntity
import com.dayoudu.app.data.PlanningRepository
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.DayOfWeek
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val dateTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private val planStates = mapOf("SCHEDULED" to "已安排", "COMPLETE" to "已完成", "RESCHEDULED" to "已改期", "CANCELLED" to "已取消")
private val sessionStates = mapOf("RUNNING" to "计时中", "PAUSED" to "已暂停", "FINISHED" to "已结束")

@Composable
fun TodayScreen(repository: PlanningRepository, padding: PaddingValues) {
    val zone = remember { ZoneId.systemDefault() }
    var date by remember { mutableStateOf(LocalDate.now(zone)) }
    val plans by remember(date) { repository.observePlans(date, zone) }.collectAsStateWithLifecycle(emptyList())
    val activities by remember(date) { repository.observeActivities(date, zone) }.collectAsStateWithLifecycle(emptyList())
    val planActivities by remember(date) { repository.observePlanActivities(date, zone) }.collectAsStateWithLifecycle(emptyList())
    val active by remember { repository.observeActive() }.collectAsStateWithLifecycle(null)
    var editingPlan by remember { mutableStateOf<PlanEntity?>(null) }
    var showPlanEditor by remember { mutableStateOf(false) }
    var showManual by remember { mutableStateOf(false) }
    var manualPlan by remember { mutableStateOf<PlanEntity?>(null) }
    var startPlan by remember { mutableStateOf<PlanEntity?>(null) }
    var showStart by remember { mutableStateOf(false) }
    var editingCash by remember { mutableStateOf<ActivityWithSegments?>(null) }
    var correcting by remember { mutableStateOf<ActivityWithSegments?>(null) }
    var selectedPlan by remember { mutableStateOf<PlanEntity?>(null) }
    var selectedActivity by remember { mutableStateOf<ActivityWithSegments?>(null) }
    var pendingDocument by remember { mutableStateOf<DailyPlanDocument?>(null) }
    var batchDocument by remember { mutableStateOf<DailyPlanDocument?>(null) }
    var exportJson by remember { mutableStateOf<String?>(null) }
    var showDayMenu by remember { mutableStateOf(false) }
    var lastRemovedPlan by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var lastDeletedActivity by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val json = exportJson
        exportJson = null
        if (uri != null && json != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(json) }
                    ?: throw IllegalStateException("无法写入文件")
            } }.onSuccess { notice = "日计划已导出"; message = null }.onFailure { message = it.message ?: "导出失败" }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytesLimited(512_000) }
                    ?: throw IllegalStateException("无法读取文件")
                DailyPlanCodec.decode(bytes.toString(Charsets.UTF_8))
            } }.onSuccess { pendingDocument = it }.onFailure { message = it.message ?: "导入失败" }
        }
    }

    val timeline = remember(plans, planActivities) {
        val linked = planActivities.groupBy { it.activity.planId }
        plans.sortedWith(compareBy<PlanEntity> { it.scheduledStartMillis }.thenBy { it.id })
            .map { DailyTimelineEntry(it, linked[it.id].orEmpty()) }
    }
    val isToday = date == LocalDate.now(zone)
    val currentActive = active.takeIf { isToday }
    val weekStart = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(if (isToday) "今天" else "日计划", style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold, color = Ink)
            Text(date.format(DateTimeFormatter.ofPattern("M月d日 EEEE", java.util.Locale.CHINA)), color = MutedInk)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            (0..6).forEach { index ->
                val day = weekStart.plusDays(index.toLong())
                Surface(onClick = { date = day }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(18.dp),
                    color = if (day == date) Pine else Color.Transparent) {
                    Column(Modifier.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[index],
                            style = MaterialTheme.typography.labelSmall, color = if (day == date) Color.White else MutedInk)
                        Text("${day.dayOfMonth}", style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (day == date) FontWeight.Bold else FontWeight.Medium,
                            color = if (day == date) Color.White else Ink)
                    }
                }
            }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        notice?.let { Text(it, color = Pine) }
        if (timeline.isEmpty()) Surface(shape = RoundedCornerShape(24.dp), color = Mist) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, top = 16.dp, bottom = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (currentActive != null) "正在记录" else if (isToday) "今日计划" else "当日计划", color = Pine,
                        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Text(currentActive?.activity?.title ?: "安排一件事", color = Ink,
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    if (currentActive != null) Text(sessionStates[currentActive.activity.sessionStatus] ?: "", color = MutedInk)
                    val current = currentActive
                    if (current != null) {
                        Row {
                            TextButton(onClick = { scope.launch { runCatching {
                            if (current.activity.sessionStatus == "RUNNING") repository.pause(current.activity.id) else repository.resume(current.activity.id)
                            }.onFailure { message = it.message } } }) { Text(if (current.activity.sessionStatus == "RUNNING") "暂停" else "继续") }
                            TextButton(onClick = { scope.launch { runCatching { repository.finish(current.activity.id) }.onFailure { message = it.message } } }) { Text("结束") }
                        }
                    } else {
                        Button(onClick = { editingPlan = null; showPlanEditor = true },
                            shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 7.dp)) {
                            Text("新建计划")
                        }
                    }
                }
                Image(painterResource(R.drawable.diary_sprout), contentDescription = null,
                    modifier = Modifier.size(106.dp), contentScale = ContentScale.Fit)
            }
        } else {
            Surface(shape = RoundedCornerShape(20.dp), color = Mist) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 15.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text(if (isToday) "今日计划" else "当日计划", color = MutedInk,
                            style = MaterialTheme.typography.bodySmall)
                        Text("${plans.count { it.status != "CANCELLED" }} 项", color = Ink,
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    Text("已完成 ${plans.count { it.status == "COMPLETE" }} 项", color = Pine,
                        fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(if (isToday) "今日时间线" else "当日时间线", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, color = Ink)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (timeline.isNotEmpty()) TextButton(onClick = { editingPlan = null; showPlanEditor = true }) { Text("＋ 计划") }
                Box {
                    TextButton(onClick = { showDayMenu = true }) { Text("更多") }
                    DropdownMenu(expanded = showDayMenu, onDismissRequest = { showDayMenu = false }) {
                        DropdownMenuItem(text = { Text("复用前一天") }, onClick = {
                            showDayMenu = false
                            scope.launch { runCatching { repository.dayDocument(date.minusDays(1), zone) }
                                .onSuccess { if (it.items.isEmpty()) message = "前一天没有可复用的计划" else pendingDocument = it }
                                .onFailure { message = it.message } }
                        })
                        DropdownMenuItem(text = { Text("批量复用这一天") }, onClick = {
                            showDayMenu = false
                            scope.launch { runCatching { repository.dayDocument(date, zone) }
                                .onSuccess { if (it.items.isEmpty()) message = "这一天没有可复用的计划" else batchDocument = it }
                                .onFailure { message = it.message ?: "读取计划失败" } }
                        })
                        DropdownMenuItem(text = { Text("导入日计划") }, onClick = {
                            showDayMenu = false; importLauncher.launch(arrayOf("application/json", "text/plain"))
                        })
                        DropdownMenuItem(text = { Text("导出这一天") }, onClick = {
                            showDayMenu = false
                            scope.launch { runCatching { repository.dayDocument(date, zone) }.onSuccess { document ->
                                if (document.items.isEmpty()) message = "这一天没有可导出的计划"
                                else { exportJson = DailyPlanCodec.encode(document); exportLauncher.launch("日有度日计划-$date.json") }
                            }.onFailure { message = it.message } }
                        })
                    }
                }
            }
        }
        Column {
            timeline.forEachIndexed { index, entry ->
                PlanRow(entry.plan, zone, linkedCount = entry.linked.size,
                    isFirst = index == 0, isLast = index == timeline.lastIndex,
                    onOpen = { selectedPlan = entry.plan },
                    onComplete = { scope.launch { runCatching { repository.changePlanStatus(entry.plan.id, entry.plan.version, "COMPLETE") }
                        .onFailure { message = it.message } } })
            }
        }
        lastRemovedPlan?.let { removed -> TextButton(onClick = { scope.launch {
            runCatching { repository.restorePlan(removed.first, removed.second) }
                .onSuccess { lastRemovedPlan = null; notice = "已恢复计划"; message = null }
                .onFailure { message = it.message ?: "无法恢复" }
        } }) { Text("撤销移除计划") } }
        lastDeletedActivity?.let { deleted -> TextButton(onClick = { scope.launch {
            runCatching { repository.restoreActivity(deleted.first, deleted.second) }
                .onSuccess { lastDeletedActivity = null; notice = "已撤销活动删除"; message = null }
                .onFailure { message = it.message ?: "撤销失败" }
        } }) { Text("撤销上次删除的活动") } }
        val unlinked = activities.filter { it.activity.planId == null || plans.none { plan -> plan.id == it.activity.planId } }
        if (unlinked.isNotEmpty()) Text("其他活动", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Ink)
        unlinked.forEach { activity -> ActivityRow(activity, zone, onOpen = { selectedActivity = activity }) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { startPlan = null; showStart = true }, modifier = Modifier.weight(1f)) { Text("开始活动") }
            OutlinedButton(onClick = { manualPlan = null; showManual = true }, modifier = Modifier.weight(1f)) { Text("补记活动") }
        }
    }

    if (showPlanEditor) PlanEditor(repository, zone, date, editingPlan) { showPlanEditor = false }
    if (showStart) StartDialog(repository, startPlan) { showStart = false }
    if (showManual) ManualActivityDialog(repository, zone, date, manualPlan) { showManual = false; manualPlan = null }
    selectedPlan?.let { plan ->
        val current = plans.firstOrNull { it.id == plan.id } ?: plan
        PlanDetailDialog(current, planActivities.filter { it.activity.planId == plan.id }, zone,
            onOpenActivity = { selectedPlan = null; selectedActivity = it },
            onStart = { selectedPlan = null; startPlan = current; showStart = true },
            onManual = { selectedPlan = null; manualPlan = current; showManual = true },
            onEdit = { selectedPlan = null; editingPlan = current; showPlanEditor = true },
            onCopy = {
                selectedPlan = null
                pickDate(context, date.plusDays(1)) { target ->
                    scope.launch { runCatching { repository.copyPlanToDate(current.id, current.version, target, zone) }
                        .onSuccess { notice = "已复制到 $target"; message = null }.onFailure { message = it.message } }
                }
            },
            onCancel = { selectedPlan = null; scope.launch { runCatching { repository.changePlanStatus(current.id, current.version, "CANCELLED") }.onFailure { message = it.message } } },
            onRemove = { selectedPlan = null; scope.launch { runCatching { repository.removePlan(current.id, current.version) }
                .onSuccess { lastRemovedPlan = current.id to current.version + 1 }.onFailure { message = it.message } } },
            onClose = { selectedPlan = null })
    }
    selectedActivity?.let { activity -> ActivityDetailDialog(activity, zone,
        onCash = { selectedActivity = null; editingCash = activity },
        onCorrect = { selectedActivity = null; correcting = activity },
        onPause = { selectedActivity = null; scope.launch { runCatching { repository.pause(activity.activity.id) }.onFailure { message = it.message } } },
        onResume = { selectedActivity = null; scope.launch { runCatching { repository.resume(activity.activity.id) }.onFailure { message = it.message } } },
        onFinish = { selectedActivity = null; scope.launch { runCatching { repository.finish(activity.activity.id) }.onFailure { message = it.message } } },
        onClose = { selectedActivity = null }) }
    pendingDocument?.let { document ->
        AlertDialog(onDismissRequest = { pendingDocument = null }, title = { Text("添加到 $date？") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("来源 ${document.date} · ${document.items.size} 项计划")
                Text("将追加到当天，保留现有计划。时间按当天本地时间安排。", color = MutedInk)
                document.items.take(5).forEach { item -> Text("${"%02d:%02d".format(item.startMinute / 60, item.startMinute % 60)}  ${item.title}") }
                if (document.items.size > 5) Text("另有 ${document.items.size - 5} 项")
            } },
            confirmButton = { TextButton(enabled = document.items.isNotEmpty(), onClick = {
                pendingDocument = null
                scope.launch { runCatching { repository.applyDayDocument(document, date, zone) }
                    .onSuccess { notice = "已添加 $it 项计划"; message = null }.onFailure { message = it.message ?: "添加失败" } }
            }) { Text("添加计划") } },
            dismissButton = { TextButton(onClick = { pendingDocument = null }) { Text("返回") } })
    }
    batchDocument?.let { document ->
        BatchPlanDialog(document, date, context, onClose = { batchDocument = null }, onApply = { first, last, weekdays, skip ->
            batchDocument = null
            scope.launch { runCatching { repository.applyDocumentRange(document, first, last, weekdays, skip, zone) }
                .onSuccess { (days, count) -> notice = "已安排 $days 天、$count 项计划"; message = null }
                .onFailure { message = it.message ?: "批量安排失败" } }
        })
    }
    editingCash?.let { activity -> CashDialog(repository, activity) { editingCash = null } }
    correcting?.let { activity -> CorrectionDialog(repository, activity, zone,
        onDeleted = { id, version -> lastDeletedActivity = id to version; correcting = null },
        onClose = { correcting = null }) }
}

private data class DailyTimelineEntry(val plan: PlanEntity, val linked: List<ActivityWithSegments>)

@Composable
private fun BatchPlanDialog(document: DailyPlanDocument, selectedDate: LocalDate, context: Context,
                            onClose: () -> Unit,
                            onApply: (LocalDate, LocalDate, Set<DayOfWeek>, Boolean) -> Unit) {
    var first by remember(document) { mutableStateOf(selectedDate.plusDays(1)) }
    var last by remember(document) { mutableStateOf(selectedDate.plusDays(7)) }
    var weekdays by remember(document) { mutableStateOf(DayOfWeek.entries.toSet()) }
    var skipExisting by remember(document) { mutableStateOf(true) }
    val selectedDays = if (last.isBefore(first)) emptyList() else generateSequence(first) {
        if (it.isBefore(last)) it.plusDays(1) else null
    }.take(121).filter { it.dayOfWeek in weekdays }.toList()
    AlertDialog(onDismissRequest = onClose, title = { Text("批量复用 · ${document.date}") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("将 ${document.items.size} 项计划作为一天的模板，安排到所选日期。")
            OutlinedButton(onClick = { pickDate(context, first) { first = it } }) { Text("开始日期  $first") }
            OutlinedButton(onClick = { pickDate(context, last) { last = it } }) { Text("结束日期  $last") }
            DayOfWeek.entries.forEach { day ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = day in weekdays, onCheckedChange = { checked ->
                        weekdays = if (checked) weekdays + day else weekdays - day
                    })
                    Text(mapOf(DayOfWeek.MONDAY to "周一", DayOfWeek.TUESDAY to "周二",
                        DayOfWeek.WEDNESDAY to "周三", DayOfWeek.THURSDAY to "周四",
                        DayOfWeek.FRIDAY to "周五", DayOfWeek.SATURDAY to "周六",
                        DayOfWeek.SUNDAY to "周日").getValue(day))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(skipExisting, onCheckedChange = { skipExisting = it })
                Text("跳过已有计划的日期")
            }
            Text("最多涉及 ${selectedDays.size} 天、${selectedDays.size * document.items.size} 项；不会复制完成或执行记录。", color = MutedInk)
            if (last.isBefore(first)) Text("结束日期不能早于开始日期", color = MaterialTheme.colorScheme.error)
            if (java.time.temporal.ChronoUnit.DAYS.between(first, last) >= 120) Text("范围最多 120 天", color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { TextButton(enabled = selectedDays.isNotEmpty() &&
            java.time.temporal.ChronoUnit.DAYS.between(first, last) < 120 &&
            selectedDays.size * document.items.size <= 5000,
            onClick = { onApply(first, last, weekdays, skipExisting) }) { Text("确认批量安排") } },
        dismissButton = { TextButton(onClick = onClose) { Text("返回") } })
}

@Composable
private fun PlanRow(plan: PlanEntity, zone: ZoneId, linkedCount: Int, isFirst: Boolean, isLast: Boolean,
                    onOpen: () -> Unit, onComplete: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(plan.scheduledStartMillis.asClock(zone), Modifier.width(44.dp), color = Pine,
            style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        Canvas(Modifier.width(24.dp).height(92.dp)) {
            val x = size.width / 2
            if (!isFirst) drawLine(SoftLine, Offset(x, 0f), Offset(x, size.height / 2), strokeWidth = 3.dp.toPx())
            if (!isLast) drawLine(SoftLine, Offset(x, size.height / 2), Offset(x, size.height), strokeWidth = 3.dp.toPx())
            drawCircle(if (plan.status == "COMPLETE") Pine else if (plan.status == "CANCELLED") MutedInk else Gold,
                radius = 6.dp.toPx(), center = Offset(x, size.height / 2))
        }
        Surface(onClick = onOpen, modifier = Modifier.weight(1f), shape = RoundedCornerShape(18.dp),
            color = Color.White, border = androidx.compose.foundation.BorderStroke(1.dp, SoftLine)) {
            Row(Modifier.fillMaxWidth().padding(start = 15.dp, top = 11.dp, bottom = 11.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(plan.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Ink,
                        maxLines = 1)
                    Text("${plan.scheduledEndMillis.asClock(zone)} · ${planStates[plan.status] ?: plan.status}" +
                        if (linkedCount > 0) " · $linkedCount 条执行" else "",
                        color = MutedInk, style = MaterialTheme.typography.bodySmall)
                }
                if (plan.status !in setOf("COMPLETE", "CANCELLED")) {
                    TextButton(onClick = onComplete) { Text("完成") }
                } else Text("›", Modifier.padding(end = 10.dp), color = MutedInk)
            }
        }
    }
}

@Composable
private fun ActivityRow(item: ActivityWithSegments, zone: ZoneId, onOpen: () -> Unit) {
    val minutes = item.segments.sumOf { ((it.endMillis ?: System.currentTimeMillis()) - it.startMillis).coerceAtLeast(0) } / 60_000
    Surface(onClick = onOpen, shape = RoundedCornerShape(18.dp), color = Color.White,
        border = androidx.compose.foundation.BorderStroke(1.dp, SoftLine)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(item.activity.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Ink)
                Text("${item.activity.categoryName} · $minutes 分钟", color = MutedInk)
            }
            Text("›", color = MutedInk)
        }
    }
}

@Composable
private fun PlanDetailDialog(plan: PlanEntity, linked: List<ActivityWithSegments>, zone: ZoneId,
                             onOpenActivity: (ActivityWithSegments) -> Unit, onStart: () -> Unit,
                             onManual: () -> Unit,
                             onEdit: () -> Unit, onCopy: () -> Unit, onCancel: () -> Unit,
                             onRemove: () -> Unit, onClose: () -> Unit) {
    var confirmRemove by remember(plan.id) { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onClose, title = { Text(plan.title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("${plan.scheduledStartMillis.asLocal(zone)} – ${plan.scheduledEndMillis.asLocal(zone)}", color = Ink)
            Text("${if (plan.fixed) "固定" else "灵活"} · ${planStates[plan.status] ?: plan.status}", color = MutedInk)
            if (plan.scheduledStartMillis != plan.baselineStartMillis || plan.scheduledEndMillis != plan.baselineEndMillis)
                Text("原计划 ${plan.baselineStartMillis.asLocal(zone)} – ${plan.baselineEndMillis.asLocal(zone)}", color = MutedInk)
            Text("执行记录", fontWeight = FontWeight.Bold, color = Ink)
            if (linked.isEmpty()) Text("暂无执行记录", color = MutedInk)
            linked.forEach { activity ->
                val minutes = activity.segments.sumOf { ((it.endMillis ?: System.currentTimeMillis()) - it.startMillis).coerceAtLeast(0) } / 60_000
                Surface(onClick = { onOpenActivity(activity) }, shape = RoundedCornerShape(12.dp), color = Mist) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(activity.activity.title)
                        Text("$minutes 分钟 ›", color = MutedInk)
                    }
                }
            }
            if (plan.status != "CANCELLED") {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onStart) { Text("开始活动") }
                    TextButton(onClick = onManual) { Text("补记执行") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onEdit) { Text("调整计划") }
                    TextButton(onClick = onCopy) { Text("复制到某天") }
                }
                TextButton(onClick = onCancel) { Text("取消计划") }
            }
            TextButton(onClick = { confirmRemove = true }) { Text("移除计划", color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(onClick = onClose) { Text("关闭") } })
    if (confirmRemove) AlertDialog(onDismissRequest = { confirmRemove = false },
        title = { Text("移除这项计划？") },
        text = { Text("计划会从时间线隐藏；已发生的活动记录仍保留。移除后可在本页撤销。") },
        confirmButton = { TextButton(onClick = onRemove) { Text("移除") } },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("返回") } })
}

@Composable
private fun ActivityDetailDialog(item: ActivityWithSegments, zone: ZoneId,
                                 onCash: () -> Unit, onCorrect: () -> Unit,
                                 onPause: () -> Unit, onResume: () -> Unit, onFinish: () -> Unit,
                                 onClose: () -> Unit) {
    val minutes = item.segments.sumOf { ((it.endMillis ?: System.currentTimeMillis()) - it.startMillis).coerceAtLeast(0) } / 60_000
    AlertDialog(onDismissRequest = onClose, title = { Text(item.activity.title) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("${item.activity.categoryName} · ${sessionStates[item.activity.sessionStatus] ?: item.activity.sessionStatus}")
            Text("已记录 $minutes 分钟", color = MutedInk)
            item.segments.forEach { segment ->
                Text("${segment.startMillis.asLocal(zone)} – ${segment.endMillis?.asLocal(zone) ?: "进行中"}",
                    style = MaterialTheme.typography.bodySmall, color = MutedInk)
            }
            item.activity.projectNameSnapshot?.let { Text("项目：$it") }
            if (item.tags.isNotEmpty()) Text("标签：${item.tags.joinToString(" · ") { it.name }}")
            when (item.activity.sessionStatus) {
                "RUNNING" -> Row { TextButton(onClick = onPause) { Text("暂停") }; TextButton(onClick = onFinish) { Text("结束") } }
                "PAUSED" -> Row { TextButton(onClick = onResume) { Text("继续") }; TextButton(onClick = onFinish) { Text("结束") } }
                "FINISHED" -> {
                    TextButton(onClick = onCash) { Text(if (item.activity.isWork) "工作现金" else "标记工作") }
                    TextButton(onClick = onCorrect) { Text("纠错或删除") }
                }
            }
        } },
        confirmButton = { TextButton(onClick = onClose) { Text("关闭") } })
}

@Composable
private fun CorrectionDialog(repository: PlanningRepository, item: ActivityWithSegments, zone: ZoneId,
                             onDeleted: (String, Int) -> Unit, onClose: () -> Unit) {
    val segment = item.segments.singleOrNull()
    var title by remember(item.activity.id) { mutableStateOf(item.activity.title) }
    var category by remember(item.activity.id) { mutableStateOf(item.activity.categoryName) }
    var start by remember(item.activity.id) { mutableStateOf(segment?.startMillis?.asLocal(zone).orEmpty()) }
    var end by remember(item.activity.id) { mutableStateOf(segment?.endMillis?.asLocal(zone).orEmpty()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = onClose, title = { Text("纠错 · ${item.activity.title}") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (segment != null && segment.endMillis != null) {
                OutlinedTextField(title, { title = it }, label = { Text("活动标题") })
                OutlinedTextField(category, { category = it }, label = { Text("主时间类别") })
                OutlinedTextField(start, { start = it }, label = { Text("开始 yyyy-MM-dd HH:mm") })
                OutlinedTextField(end, { end = it }, label = { Text("结束 yyyy-MM-dd HH:mm") })
            } else Text("多段计时暂不支持整体修改时段，可保留原记录。")
            TextButton(onClick = { confirmDelete = true }) { Text("删除活动") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { if (segment != null && segment.endMillis != null) TextButton(onClick = { scope.launch {
            runCatching { repository.correctSingleSegment(item.activity.id, item.activity.version,
                title, category, LocalDateTime.parse(start, dateTimeFormat), LocalDateTime.parse(end, dateTimeFormat), zone) }
                .onSuccess { onClose() }.onFailure { error = it.message ?: "保存失败" }
        } }) { Text("保存纠错") } },
        dismissButton = { TextButton(onClick = onClose) { Text("关闭") } })
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text("删除这段活动？") }, text = { Text("删除后可在当前页面撤销。") },
        confirmButton = { TextButton(onClick = { scope.launch {
            runCatching { repository.deleteActivity(item.activity.id, item.activity.version) }
                .onSuccess { onDeleted(item.activity.id, item.activity.version + 1) }
                .onFailure { error = it.message ?: "删除失败"; confirmDelete = false }
        } }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("返回") } })
}

@Composable
private fun CashDialog(repository: PlanningRepository, item: ActivityWithSegments, onClose: () -> Unit) {
    var isWork by remember(item.activity.id) { mutableStateOf(item.activity.isWork) }
    var income by remember(item.activity.id) { mutableStateOf(item.activity.incomeAmount.orEmpty()) }
    var expense by remember(item.activity.id) { mutableStateOf(item.activity.expenseAmount.orEmpty()) }
    var currency by remember(item.activity.id) { mutableStateOf(item.activity.currencyCode.orEmpty()) }
    var commute by remember(item.activity.id) { mutableStateOf(item.activity.commuteMinutes?.toString().orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = onClose, title = { Text("工作现金 · ${item.activity.title}") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row { Checkbox(isWork, onCheckedChange = { isWork = it }); Text("这是工作活动", Modifier.padding(top = 12.dp)) }
            if (isWork) {
                Text("金额空白表示未知，0 表示已确认无收入或支出。不同币种分开统计。")
                OutlinedTextField(currency, { currency = it }, label = { Text("币种代码（如 CNY、USD；有金额时必填）") })
                OutlinedTextField(income, { income = it }, label = { Text("实际收入（可空）") })
                OutlinedTextField(expense, { expense = it }, label = { Text("直接支出（可空）") })
                OutlinedTextField(commute, { commute = it }, label = { Text("通勤分钟数（可空）") })
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(onClick = { scope.launch {
            runCatching { repository.saveCash(item.activity.id, item.activity.version, isWork,
                if (isWork) currency else "", if (isWork) income else "",
                if (isWork) expense else "", if (isWork) commute else "") }
                .onSuccess { onClose() }.onFailure { error = it.message ?: "保存失败" }
        } }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } })
}

@Composable
private fun PlanEditor(repository: PlanningRepository, zone: ZoneId, date: LocalDate,
                       plan: PlanEntity?, onClose: () -> Unit) {
    val planDate = remember(plan?.id, date) { plan?.scheduledStartMillis?.let {
        Instant.ofEpochMilli(it).atZone(zone).toLocalDate()
    } ?: date }
    var title by remember(plan?.id) { mutableStateOf(plan?.title.orEmpty()) }
    var startMinute by remember(plan?.id) { mutableStateOf(plan?.scheduledStartMillis?.let {
        val time = Instant.ofEpochMilli(it).atZone(zone).toLocalTime(); time.hour * 60 + time.minute
    }) }
    var endMinute by remember(plan?.id) { mutableStateOf(plan?.scheduledEndMillis?.let {
        val local = Instant.ofEpochMilli(it).atZone(zone)
        if (local.toLocalDate() == planDate) local.hour * 60 + local.minute else null
    }) }
    var fixed by remember(plan?.id) { mutableStateOf(plan?.fixed ?: false) }
    var reminderEnabled by remember(plan?.id) { mutableStateOf(plan?.reminderMinutesBefore != null) }
    var reminderMinutes by remember(plan?.id) { mutableStateOf(plan?.reminderMinutesBefore?.toString() ?: "0") }
    var reminderSilent by remember(plan?.id) { mutableStateOf(plan?.reminderSilent ?: false) }
    val context = LocalContext.current
    var permissionGranted by remember { mutableStateOf(Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionGranted = it
    }
    var reason by remember(plan?.id) { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = onClose, title = { Text(if (plan == null) "新建计划" else "调整计划") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("标题") }, singleLine = true)
                ClockDropdown("开始时间", startMinute) { startMinute = it }
                ClockDropdown("结束时间", endMinute) { endMinute = it }
                Row { Checkbox(checked = fixed, onCheckedChange = { fixed = it }); Text("固定时段", Modifier.padding(top = 12.dp)) }
                Row { Checkbox(checked = reminderEnabled, onCheckedChange = {
                    reminderEnabled = it
                    if (it && !permissionGranted && Build.VERSION.SDK_INT >= 33) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }); Text("提醒我", Modifier.padding(top = 12.dp)) }
                if (reminderEnabled) {
                    OutlinedTextField(reminderMinutes, { reminderMinutes = it }, label = { Text("提前分钟数（0 为开始时）") })
                    Row { Checkbox(checked = reminderSilent, onCheckedChange = { reminderSilent = it }); Text("静音通知", Modifier.padding(top = 12.dp)) }
                    if (!permissionGranted) Text("通知权限未开启；计划仍可保存，但不会弹出系统通知。")
                    Text("系统可能延后送达提醒；通知中可延后 10 分钟。")
                }
                if (plan != null) OutlinedTextField(reason, { reason = it }, label = { Text("改期原因（仅修改时段需要）") })
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = {
            scope.launch {
                runCatching {
                    val startAt = planDate.atStartOfDay().plusMinutes((startMinute
                        ?: throw IllegalArgumentException("请选择开始时间")).toLong())
                    val endAt = planDate.atStartOfDay().plusMinutes((endMinute
                        ?: throw IllegalArgumentException("请选择结束时间")).toLong())
                    require(endAt.isAfter(startAt)) { "结束时间须晚于开始时间" }
                    val minutes = if (reminderEnabled) reminderMinutes.toIntOrNull()
                        ?: throw IllegalArgumentException("请填写有效的提醒分钟数") else null
                    if (plan == null) repository.addPlan(title, startAt, endAt, zone, fixed, minutes, reminderSilent)
                    else repository.updatePlan(plan.id, plan.version, title, startAt, endAt, zone, fixed, reason, minutes, reminderSilent)
                }.onSuccess { onClose() }.onFailure { error = it.message ?: "请检查时间和输入内容" }
            }
        }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } })
}

@Composable
private fun StartDialog(repository: PlanningRepository, plan: PlanEntity?, onClose: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf("") }
    var projectId by remember { mutableStateOf<String?>(null) }
    var projectMenu by remember { mutableStateOf(false) }
    val projects by remember { repository.observeProjects() }.collectAsStateWithLifecycle(emptyList())
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = onClose, title = { Text(if (plan == null) "开始活动" else "开始 · ${plan.title}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (plan == null) OutlinedTextField(title, { title = it }, label = { Text("活动标题") })
                OutlinedTextField(category, { category = it }, label = { Text("主时间类别（由你命名）") })
                OutlinedButton(onClick = { projectMenu = true }) {
                    Text(projects.firstOrNull { it.id == projectId }?.name ?: "项目（可选）")
                }
                DropdownMenu(projectMenu, onDismissRequest = { projectMenu = false }) {
                    DropdownMenuItem(text = { Text("不关联项目") }, onClick = { projectId = null; projectMenu = false })
                    projects.filter { !it.archived }.forEach { project ->
                        DropdownMenuItem(text = { Text(project.name) }, onClick = { projectId = project.id; projectMenu = false })
                    }
                }
                OutlinedTextField(tags, { tags = it }, label = { Text("标签（可选，逗号分隔）") })
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = {
            scope.launch {
                runCatching { if (plan == null) repository.startUnplanned(title, category, projectId, tags)
                    else repository.startPlan(plan.id, category, projectId, tags) }
                    .onSuccess { onClose() }.onFailure { error = it.message ?: "开始失败" }
            }
        }) { Text("开始") } },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } })
}

@Composable
private fun ManualActivityDialog(repository: PlanningRepository, zone: ZoneId, date: LocalDate,
                                 plan: PlanEntity?, onClose: () -> Unit) {
    var title by remember(plan?.id) { mutableStateOf(plan?.title.orEmpty()) }
    var category by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf("") }
    var projectId by remember { mutableStateOf<String?>(null) }
    var projectMenu by remember { mutableStateOf(false) }
    val projects by remember { repository.observeProjects() }.collectAsStateWithLifecycle(emptyList())
    var start by remember { mutableStateOf("") }
    var end by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = onClose, title = { Text(if (plan == null) "补记活动" else "补记执行 · ${plan.title}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("活动标题") })
                OutlinedTextField(category, { category = it }, label = { Text("主时间类别") })
                OutlinedButton(onClick = { projectMenu = true }) {
                    Text(projects.firstOrNull { it.id == projectId }?.name ?: "项目（可选）")
                }
                DropdownMenu(projectMenu, onDismissRequest = { projectMenu = false }) {
                    DropdownMenuItem(text = { Text("不关联项目") }, onClick = { projectId = null; projectMenu = false })
                    projects.filter { !it.archived }.forEach { project ->
                        DropdownMenuItem(text = { Text(project.name) }, onClick = { projectId = project.id; projectMenu = false })
                    }
                }
                OutlinedTextField(tags, { tags = it }, label = { Text("标签（可选，逗号分隔）") })
                OutlinedTextField(start, { start = it }, label = { Text("开始 yyyy-MM-dd HH:mm") }, placeholder = { Text("$date 09:00") })
                OutlinedTextField(end, { end = it }, label = { Text("结束 yyyy-MM-dd HH:mm") }, placeholder = { Text("$date 10:00") })
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = {
            scope.launch {
                runCatching { repository.addManual(title, category, LocalDateTime.parse(start, dateTimeFormat),
                    LocalDateTime.parse(end, dateTimeFormat), zone, projectId, tags, plan?.id) }
                    .onSuccess { onClose() }.onFailure { error = it.message ?: "请检查输入和重叠时段" }
            }
        }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } })
}

private fun Long.asLocal(zone: ZoneId): String = Instant.ofEpochMilli(this).atZone(zone).format(dateTimeFormat)
private fun Long.asClock(zone: ZoneId): String = Instant.ofEpochMilli(this).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))

private fun pickDate(context: Context, initial: LocalDate, onSelected: (LocalDate) -> Unit) {
    DatePickerDialog(context, { _, year, month, day -> onSelected(LocalDate.of(year, month + 1, day)) },
        initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
}

private fun InputStream.readBytesLimited(limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(output.size() + count <= limit) { "日计划文件超过 500 KB" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
