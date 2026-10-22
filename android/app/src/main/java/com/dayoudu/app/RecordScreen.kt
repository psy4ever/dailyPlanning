package com.dayoudu.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.dayoudu.app.data.FoodVersionEntity
import com.dayoudu.app.data.FoodDraft
import com.dayoudu.app.data.NutritionRepository
import com.dayoudu.app.data.SleepLogEntity
import com.dayoudu.app.data.SleepRepository
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

private val prepared = linkedMapOf("RAW" to "生", "COOKED" to "熟", "PACKAGED" to "包装", "UNKNOWN" to "未知")
private val sources = linkedMapOf("USER_LABEL" to "包装标识", "USER_ESTIMATE" to "用户手填/估算")
@Composable
internal fun SleepRow(log: SleepLogEntity, zone: ZoneId, onEdit: () -> Unit) {
    val format = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    val onset = Instant.ofEpochMilli(log.onsetMillis).atZone(zone).format(format)
    val wake = Instant.ofEpochMilli(log.wakeMillis).atZone(zone).format(format)
    val minutes = (log.wakeMillis - log.onsetMillis) / 60_000 - (log.awakeSeconds ?: 0) / 60
    Surface(shape = RoundedCornerShape(18.dp), color = Color.White, border = BorderStroke(1.dp, SoftLine)) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("${minutes / 60} 小时 ${minutes % 60} 分钟", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Ink)
        Text("$onset → $wake", color = MutedInk)
        TextButton(onClick = onEdit) { Text("编辑 / 删除") }
    }
    }
}

@Composable
internal fun SleepDialog(repository: SleepRepository, zone: ZoneId, date: LocalDate,
                        existing: SleepLogEntity?, onClose: () -> Unit) {
    var onset by remember(existing?.id) { mutableStateOf(existing?.let {
        Instant.ofEpochMilli(it.onsetMillis).atZone(zone).toLocalTime().let { time -> time.hour * 60 + time.minute }
    }) }
    var wake by remember(existing?.id) { mutableStateOf(existing?.let {
        Instant.ofEpochMilli(it.wakeMillis).atZone(zone).toLocalTime().let { time -> time.hour * 60 + time.minute }
    }) }
    var awakeMinutes by remember(existing?.id) { mutableStateOf(existing?.awakeSeconds?.div(60)?.toString().orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = onClose, title = { Text(if (existing == null) "记录睡眠" else "编辑睡眠") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ClockDropdown("入睡时间", onset) { onset = it }
                ClockDropdown("醒来时间", wake) { wake = it }
                Text("醒来日为 ${existing?.let { Instant.ofEpochMilli(it.wakeMillis).atZone(zone).toLocalDate() } ?: date}；入睡时间晚于醒来时间时，按前一晚记录。", color = MutedInk)
                OutlinedTextField(awakeMinutes, { awakeMinutes = it }, label = { Text("夜间清醒分钟数（可空）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                if (existing != null) TextButton(onClick = { confirmDelete = true }) { Text("删除这条睡眠记录") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = {
            scope.launch {
                runCatching {
                    val minutes = if (awakeMinutes.isBlank()) null else awakeMinutes.toLongOrNull()
                        ?: throw IllegalArgumentException("请填写有效的清醒分钟数")
                    val onsetMinute = onset ?: throw IllegalArgumentException("请选择入睡时间")
                    val wakeMinute = wake ?: throw IllegalArgumentException("请选择醒来时间")
                    val wakeDate = existing?.let { Instant.ofEpochMilli(it.wakeMillis).atZone(zone).toLocalDate() } ?: date
                    val onsetDate = if (onsetMinute > wakeMinute) wakeDate.minusDays(1) else wakeDate
                    repository.save(existing?.id, existing?.version,
                        onsetDate.atStartOfDay().plusMinutes(onsetMinute.toLong()),
                        wakeDate.atStartOfDay().plusMinutes(wakeMinute.toLong()), minutes, zone)
                }.onSuccess { onClose() }.onFailure { error = it.message ?: "请检查入睡与醒来时间" }
            }
        }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } })
    if (confirmDelete && existing != null) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text("删除睡眠记录？") }, text = { Text("删除后这条记录不再出现在统计中。") },
        confirmButton = { TextButton(onClick = { scope.launch {
            runCatching { repository.delete(existing.id, existing.version) }
                .onSuccess { onClose() }.onFailure { error = it.message ?: "删除失败"; confirmDelete = false }
        } }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("返回") } })
}

@Composable
internal fun FoodDialog(repository: NutritionRepository, food: FoodVersionEntity?, onClose: () -> Unit) {
    var name by remember(food?.foodId) { mutableStateOf(food?.name.orEmpty()) }
    var kcal by remember(food?.foodId) { mutableStateOf(food?.kcalPer100g.orEmpty()) }
    var state by remember(food?.foodId) { mutableStateOf(food?.preparedState.orEmpty()) }
    var source by remember(food?.foodId) { mutableStateOf(food?.sourceType.orEmpty()) }
    var brand by remember(food?.foodId) { mutableStateOf(food?.brand.orEmpty()) }
    var protein by remember(food?.foodId) { mutableStateOf(food?.proteinGPer100g.orEmpty()) }
    var fat by remember(food?.foodId) { mutableStateOf(food?.fatGPer100g.orEmpty()) }
    var carb by remember(food?.foodId) { mutableStateOf(food?.carbGPer100g.orEmpty()) }
    var sourceNote by remember(food?.foodId) { mutableStateOf(food?.sourceNote.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (food == null) "添加食物营养数据" else "编辑食物 · 新版本") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("食物名称") }, singleLine = true)
                OutlinedTextField(brand, { brand = it }, label = { Text("品牌（可空）") }, singleLine = true)
                OutlinedTextField(
                    kcal, { kcal = it }, label = { Text("每 100g 热量（kcal）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true,
                )
                OutlinedTextField(protein, { protein = it }, label = { Text("蛋白质 g/100g（可空）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(fat, { fat = it }, label = { Text("脂肪 g/100g（可空）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(carb, { carb = it }, label = { Text("碳水 g/100g（可空）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                Choice("生熟或包装状态", state, prepared) { state = it }
                Choice("数据来源", source, sources) { source = it }
                OutlinedTextField(sourceNote, { sourceNote = it }, label = { Text("来源说明（可空）") })
                if (food != null) Text("保存会生成新版本；旧摄入保留原营养快照。")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    runCatching { repository.saveFood(food?.foodId, food?.version,
                        FoodDraft(name, kcal, state, source, brand, protein, fat, carb, sourceNote)) }
                        .onSuccess { onClose() }
                        .onFailure { error = it.message ?: "保存失败，请重试" }
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } },
    )
}

@Composable
private fun Choice(label: String, value: String, options: Map<String, String>, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { expanded = true }) { Text(options[value] ?: "请选择") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (key, title) ->
                DropdownMenuItem(text = { Text(title) }, onClick = { onChange(key); expanded = false })
            }
        }
    }
}

internal fun BigDecimal.display(): String = setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
