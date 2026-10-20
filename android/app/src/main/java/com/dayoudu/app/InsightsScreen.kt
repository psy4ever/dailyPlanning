package com.dayoudu.app

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dayoudu.app.data.ActivityWithSegments
import com.dayoudu.app.data.DimensionRatingEntity
import com.dayoudu.app.data.InsightsCalculator
import com.dayoudu.app.data.PlanningRepository
import com.dayoudu.app.data.SleepRepository
import com.dayoudu.app.data.TimeBreakdown
import com.dayoudu.app.data.ValueRepository
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.delay

private val barColors = listOf(Color(0xFF224F46), Color(0xFF8DA98F), Color(0xFFD9AA57), Color(0xFF668A80), Color(0xFFB7A57F))
private val unknownColor = Color(0xFFD7D8D7)
private val conflictColor = Color(0xFFB96B56)

@Composable
fun InsightsScreen(planning: PlanningRepository, sleep: SleepRepository, value: ValueRepository, padding: PaddingValues) {
    val zone = remember { ZoneId.systemDefault() }
    var date by remember { mutableStateOf(LocalDate.now(zone)) }
    var weekly by remember { mutableStateOf(false) }
    var showValue by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); now = System.currentTimeMillis() } }
    val startDate = if (weekly) date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) else date
    val endDate = if (weekly) startDate.plusDays(7) else date.plusDays(1)
    val from = startDate.atStartOfDay(zone).toInstant().toEpochMilli()
    val to = endDate.atStartOfDay(zone).toInstant().toEpochMilli()
    val effectiveTo = minOf(to, now)
    val activities by remember(from, to) { planning.observeActivitiesRange(from, to) }.collectAsStateWithLifecycle(emptyList())
    val sleepLogs by remember(from, to) { sleep.observeRange(from, to) }.collectAsStateWithLifecycle(emptyList())
    val time = remember(from, effectiveTo, activities, sleepLogs) {
        InsightsCalculator.time(from, effectiveTo, activities, sleepLogs, now)
    }

    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("时间去向", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Ink)
            Row {
                TextButton(onClick = { weekly = false }) { Text(if (weekly) "日" else "日 ✓") }
                TextButton(onClick = { weekly = true }) { Text(if (weekly) "周 ✓" else "周") }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { date = date.minusDays(if (weekly) 7 else 1) }) { Text("‹") }
            TextButton(onClick = { date = LocalDate.now(zone) }) { Text(if (weekly) "$startDate 起" else "$date") }
            TextButton(onClick = { date = date.plusDays(if (weekly) 7 else 1) }) { Text("›") }
        }
        Surface(shape = RoundedCornerShape(20.dp), color = Color.White, border = BorderStroke(1.dp, SoftLine)) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (time.windowMillis == 0L) Text("尚未开始", color = MutedInk)
                else {
                    TimeBar(time)
                    time.categoryMillis.entries.forEachIndexed { index, (name, millis) ->
                        LegendRow(name, millis, time.windowMillis, barColors[index % barColors.size])
                    }
                    if (time.conflictMillis > 0) LegendRow("重叠待确认", time.conflictMillis, time.windowMillis, conflictColor)
                    LegendRow("未记录", time.unknownMillis, time.windowMillis, unknownColor)
                    val known = time.categoryMillis.values.sum()
                    Text("已记录 ${percent(known, time.windowMillis)} · 共 ${duration(time.windowMillis)}", color = MutedInk)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("个人回报", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Ink)
            TextButton(onClick = { showValue = !showValue }) { Text(if (showValue) "收起" else "查看") }
        }
        if (showValue) Surface(shape = RoundedCornerShape(20.dp), color = Mist) {
            Column(Modifier.padding(20.dp)) { ValueSection(value, from, effectiveTo, activities) }
        }
        val cash = remember(from, effectiveTo, activities) { InsightsCalculator.cash(from, effectiveTo, activities) }
        if (cash.isNotEmpty()) Text("工作现金", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Ink)
        cash.forEach { group ->
          Surface(shape = RoundedCornerShape(18.dp), color = Color.White, border = BorderStroke(1.dp, SoftLine)) {
          Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val unit = group.currencyCode ?: "币种未填"
            Text("$unit · 工作 ${duration(group.workMillis)}", fontWeight = FontWeight.Bold, color = Ink)
            Text("已知收入 ${group.knownIncome} $unit · 已知直接支出 ${group.knownExpense} $unit")
            if (group.missingAmountCount > 0) Text("${group.missingAmountCount} 项金额待补", color = MutedInk)
            else group.netHourly?.let { Text("净工作时薪 $it $unit/小时") }
            group.withCommuteHourly?.let { Text("含通勤 $it $unit/小时") }
          }
          }
        }
    }
}

@Composable
private fun TimeBar(time: TimeBreakdown) {
    Row(Modifier.fillMaxWidth().height(38.dp).clip(RoundedCornerShape(8.dp))) {
        time.categoryMillis.values.forEachIndexed { index, millis ->
            if (millis > 0) Box(Modifier.weight(millis.toFloat()).height(38.dp).background(barColors[index % barColors.size]))
        }
        if (time.conflictMillis > 0) Box(Modifier.weight(time.conflictMillis.toFloat()).height(38.dp).background(conflictColor))
        if (time.unknownMillis > 0) Box(Modifier.weight(time.unknownMillis.toFloat()).height(38.dp).background(unknownColor))
    }
}

@Composable
private fun LegendRow(name: String, millis: Long, window: Long, color: Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(11.dp).clip(CircleShape).background(color))
        Text(name, Modifier.weight(0.45f), color = Ink)
        Text("${duration(millis)} · ${percent(millis, window)}", Modifier.weight(0.55f), color = MutedInk)
    }
}

@Composable
private fun ValueSection(repository: ValueRepository, from: Long, to: Long, activities: List<ActivityWithSegments>) {
    val model by remember { repository.observeLatestModel() }.collectAsStateWithLifecycle(null)
    val evaluations by remember(from, to) { repository.observeEvaluations(from, to) }.collectAsStateWithLifecycle(emptyList())
    var ratings by remember { mutableStateOf<List<DimensionRatingEntity>>(emptyList()) }
    LaunchedEffect(evaluations) {
        ratings = repository.ratingsForActivities(evaluations.map { it.activityId }.distinct())
    }
    if (model == null) {
        Text("请先设置回报权重", color = MutedInk)
        return
    }
    val snapshot = model!!
    val result = remember(from, to, activities, snapshot, evaluations, ratings) {
        InsightsCalculator.value(from, to, activities, snapshot, evaluations, ratings)
    }
    Text("模型版本 ${snapshot.model.version} · 主观自评", color = MutedInk)
    Text(result.score?.let { "$it /100" } ?: "待补充评价", style = MaterialTheme.typography.headlineLarge)
    if (result.recordedMillis > 0) {
        Text("已完整评价 ${duration(result.ratedMillis)} / 已记录 ${duration(result.recordedMillis)}")
        if (result.missingActivityCount > 0) Text("${result.missingActivityCount} 段活动未完成当前模型评价")
    } else Text("暂无已结束的实际活动。")
    snapshot.dimensions.forEach { dimension ->
        val mean = result.dimensionMeans[dimension.dimensionId]
        Text("${dimension.name} · 权重 ${dimension.weight} · ${mean?.let { "已评价均分 $it" } ?: "未评价"}")
    }
}

private fun duration(millis: Long): String {
    val minutes = millis / 60_000
    return if (minutes >= 60) "${minutes / 60}小时${minutes % 60}分" else "${minutes}分"
}

private fun percent(part: Long, total: Long): String =
    if (total <= 0) "—" else BigDecimal(part).multiply(BigDecimal(100))
        .divide(BigDecimal(total), 1, RoundingMode.HALF_UP).toPlainString() + "%"
