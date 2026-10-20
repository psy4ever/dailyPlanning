package com.dayoudu.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dayoudu.app.data.FoodVersionEntity
import com.dayoudu.app.data.FoodDraft
import com.dayoudu.app.data.IntakeDraftItem
import com.dayoudu.app.data.IntakeWithItems
import com.dayoudu.app.data.NutritionRepository
import com.dayoudu.app.data.LedgerEntryEntity
import com.dayoudu.app.data.LedgerRepository
import com.dayoudu.app.data.SleepLogEntity
import com.dayoudu.app.data.SleepRepository
import com.dayoudu.app.data.calculatedKcal
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

private val meals = linkedMapOf("BREAKFAST" to "早餐", "LUNCH" to "午餐", "DINNER" to "晚餐", "SNACK" to "加餐")
private val prepared = linkedMapOf("RAW" to "生", "COOKED" to "熟", "PACKAGED" to "包装", "UNKNOWN" to "未知")
private val sources = linkedMapOf("USER_LABEL" to "包装标识", "USER_ESTIMATE" to "用户手填/估算")

@Composable
fun RecordScreen(repository: NutritionRepository, sleepRepository: SleepRepository,
                 ledgerRepository: LedgerRepository, padding: PaddingValues) {
    val zone = remember { ZoneId.systemDefault() }
    var date by remember { mutableStateOf(LocalDate.now(zone)) }
    val dayFlow = remember(date, zone) { repository.observeDay(date, zone) }
    val intakes by dayFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val sleepFlow = remember(date, zone) { sleepRepository.observeWakeDay(date, zone) }
    val sleepLogs by sleepFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val ledgerEntries by remember(date, zone) { ledgerRepository.observeDay(date, zone) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var showFood by remember { mutableStateOf(false) }
    var showFoodList by remember { mutableStateOf(false) }
    var editingFood by remember { mutableStateOf<FoodVersionEntity?>(null) }
    var showIntake by remember { mutableStateOf(false) }
    var editingIntake by remember { mutableStateOf<IntakeWithItems?>(null) }
    var lastDeleted by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var undoMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var showSleep by remember { mutableStateOf(false) }
    var editingSleep by remember { mutableStateOf<SleepLogEntity?>(null) }
    var editingLedger by remember { mutableStateOf<LedgerEntryEntity?>(null) }
    var showLedger by remember { mutableStateOf(false) }
    val items = intakes.flatMap { it.items }
    val known = items.fold(BigDecimal.ZERO) { total, item -> total + (item.calculatedKcal() ?: BigDecimal.ZERO) }
    val incompleteCount = items.count { it.calculatedKcal() == null }
    val knownCount = items.size - incompleteCount

    Column(
        modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("记录", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Ink)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { date = date.minusDays(1) }) { Text("‹ 前一天") }
            Surface(onClick = { date = LocalDate.now(zone) }, shape = RoundedCornerShape(14.dp), color = Color.White,
                border = BorderStroke(1.dp, SoftLine)) {
                Text(date.format(DateTimeFormatter.ofPattern("M月d日")),
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = Ink, fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = { date = date.plusDays(1) }) { Text("后一天 ›") }
        }
        Surface(shape = RoundedCornerShape(24.dp), color = Mist) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("当日已知摄入", color = Pine, fontWeight = FontWeight.SemiBold)
                Text(if (knownCount == 0) "—" else "${known.display()} kcal",
                    style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = Ink)
                Text(if (intakes.isEmpty()) "记录饮食后显示" else if (incompleteCount > 0) "$incompleteCount 项热量待补全" else "根据已录入食物计算",
                    color = MutedInk, style = MaterialTheme.typography.bodySmall)
            }
        }
        RecordModule("饮食记录", "食", Mist, "＋ 添加", { editingIntake = null; showIntake = true }) {
            lastDeleted?.let { deleted -> TextButton(onClick = { scope.launch {
                runCatching { repository.restoreIntake(deleted.first, deleted.second) }
                    .onSuccess { lastDeleted = null; undoMessage = "已撤销删除" }
                    .onFailure { undoMessage = it.message ?: "撤销失败" }
            } }) { Text("撤销上次删除的摄入") } }
            undoMessage?.let { Text(it) }
            if (intakes.isEmpty()) Text("还没有饮食记录", color = MutedInk)
            intakes.forEach { intake -> IntakeRow(intake, zone, onEdit = { editingIntake = intake; showIntake = true }) }
            TextButton(onClick = { showFoodList = true }) { Text("食物与营养数据 ›") }
        }
        RecordModule("睡眠", "眠", Peach, "＋ 添加", { editingSleep = null; showSleep = true }) {
            if (sleepLogs.isEmpty()) Text("还没有睡眠记录", color = MutedInk)
            sleepLogs.forEach { log -> SleepRow(log, zone) { editingSleep = log; showSleep = true } }
        }
        RecordModule("记账本", "账", Mist, "＋ 记一笔", { editingLedger = null; showLedger = true }) {
            if (ledgerEntries.isEmpty()) Text("还没有账目", color = MutedInk)
            ledgerEntries.forEach { entry ->
                Surface(onClick = { editingLedger = entry; showLedger = true }, shape = RoundedCornerShape(14.dp),
                    color = Cream) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text(entry.source, fontWeight = FontWeight.Bold, color = Ink)
                            Text("${if (entry.type == "INCOME") "收入" else "支出"} · ${Instant.ofEpochMilli(entry.occurredAtMillis).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))}", color = MutedInk)
                        }
                        Text("${if (entry.type == "INCOME") "+" else "−"}${entry.amount}",
                            fontWeight = FontWeight.Bold, color = if (entry.type == "INCOME") Pine else Ink)
                    }
                }
            }
        }
    }

    if (showFoodList) FoodListDialog(repository, onEdit = { editingFood = it; showFoodList = false; showFood = true },
        onAdd = { editingFood = null; showFoodList = false; showFood = true }, onClose = { showFoodList = false })
    if (showFood) FoodDialog(repository, editingFood, onClose = { showFood = false })
    if (showIntake) IntakeDialog(repository, zone, date, editingIntake,
        onDeleted = { id, version -> lastDeleted = id to version; showIntake = false },
        onClose = { showIntake = false })
    if (showSleep) SleepDialog(sleepRepository, zone, date, editingSleep, onClose = { showSleep = false })
    if (showLedger) LedgerDialog(ledgerRepository, zone, date, editingLedger, onClose = { showLedger = false })
}

@Composable
private fun RecordModule(title: String, symbol: String, tint: Color, action: String,
                         onAction: () -> Unit, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(22.dp), color = Color.White, border = BorderStroke(1.dp, SoftLine)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                        Surface(shape = CircleShape, color = tint, modifier = Modifier.size(34.dp)) {}
                        Text(symbol, color = Pine, fontWeight = FontWeight.Bold)
                    }
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Ink)
                }
                TextButton(onClick = onAction) { Text(action, color = Pine) }
            }
            content()
        }
    }
}

@Composable
private fun SleepRow(log: SleepLogEntity, zone: ZoneId, onEdit: () -> Unit) {
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
private fun SleepDialog(repository: SleepRepository, zone: ZoneId, date: LocalDate,
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
private fun IntakeRow(record: IntakeWithItems, zone: ZoneId, onEdit: () -> Unit) {
    val time = Instant.ofEpochMilli(record.intake.eatenAtMillis).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))
    val known = record.items.fold(BigDecimal.ZERO) { total, item -> total + (item.calculatedKcal() ?: BigDecimal.ZERO) }
    val missing = record.items.count { it.calculatedKcal() == null }
    Surface(shape = RoundedCornerShape(18.dp), color = Color.White, border = BorderStroke(1.dp, SoftLine)) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${meals[record.intake.mealType] ?: "餐食"} · $time", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Ink)
            Text(if (record.items.size == missing) "热量待补" else "${known.display()} kcal",
                color = Pine, fontWeight = FontWeight.Bold)
        }
        Text(record.items.joinToString(" · ") { it.displayName + (it.consumedGrams?.let { grams -> " ${grams}g" } ?: " · 份量待补") }, color = MutedInk)
        if (missing > 0) Text("$missing 项待补全", color = Gold)
        record.intake.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MutedInk) }
        TextButton(onClick = onEdit) { Text("编辑 / 删除") }
    }
    }
}

@Composable
private fun FoodListDialog(repository: NutritionRepository, onEdit: (FoodVersionEntity) -> Unit,
                           onAdd: () -> Unit, onClose: () -> Unit) {
    var foods by remember { mutableStateOf<List<FoodVersionEntity>>(emptyList()) }
    var refresh by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(refresh) {
        runCatching { repository.allLatestFoods() }
            .onSuccess { foods = it }.onFailure { error = it.message ?: "食物表读取失败" }
    }
    AlertDialog(onDismissRequest = onClose, title = { Text("我的营养表") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (foods.isEmpty()) Text("还没有食物数据。")
                foods.forEach { food ->
                    Text("${food.name} · v${food.version} · ${food.kcalPer100g} kcal/100g${if (food.archived) " · 已停用" else ""}")
                    Row {
                        TextButton(onClick = { onEdit(food) }) { Text(if (food.archived) "编辑并启用" else "编辑") }
                        if (!food.archived) TextButton(onClick = {
                            scope.launch {
                                runCatching { repository.archiveFood(food.foodId, food.version) }
                                    .onSuccess { refresh++ }.onFailure { error = it.message }
                            }
                        }) { Text("停用") }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = onAdd) { Text("新增食物") } },
        dismissButton = { TextButton(onClick = onClose) { Text("关闭") } })
}

@Composable
private fun FoodDialog(repository: NutritionRepository, food: FoodVersionEntity?, onClose: () -> Unit) {
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
private fun IntakeDialog(repository: NutritionRepository, zone: ZoneId, date: LocalDate,
                         existing: IntakeWithItems?, onDeleted: (String, Int) -> Unit, onClose: () -> Unit) {
    var foods by remember { mutableStateOf<List<FoodVersionEntity>>(emptyList()) }
    var meal by remember(existing?.intake?.id) { mutableStateOf(existing?.intake?.mealType.orEmpty()) }
    var eatenAt by remember(existing?.intake?.id) {
        mutableStateOf(existing?.intake?.eatenAtMillis?.let {
            val time = Instant.ofEpochMilli(it).atZone(zone).toLocalTime(); time.hour * 60 + time.minute
        })
    }
    var note by remember(existing?.intake?.id) { mutableStateOf(existing?.intake?.note.orEmpty()) }
    val drafts = remember(existing?.intake?.id) {
        mutableStateListOf<IntakeDraftItem>().also { list ->
            existing?.items?.forEach { item ->
                list.add(IntakeDraftItem(item.id, item.foodId, item.foodVersion, item.manualName,
                    item.consumedGrams.orEmpty()))
            }
        }
    }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        runCatching { repository.latestFoods() }
            .onSuccess { foods = it }.onFailure { error = "食物表读取失败，请重试" }
    }
    AlertDialog(onDismissRequest = onClose, title = { Text(if (existing == null) "记录一次摄入" else "编辑一次摄入") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("餐次", meal, meals) { meal = it }
                ClockDropdown("时间", eatenAt) { eatenAt = it }
                OutlinedTextField(note, { note = it }, label = { Text("备注（可空）") })
                drafts.forEachIndexed { index, draft ->
                    val selected = when {
                        draft.foodId != null -> "${draft.foodId}@${draft.foodVersion}"
                        draft.manualName != null -> "__manual__"
                        else -> ""
                    }
                    val choices = linkedMapOf("__manual__" to "手动名称（营养未知）")
                    foods.forEach { choices["${it.foodId}@${it.version}"] = "${it.name} · v${it.version}" }
                    if (selected.isNotEmpty() && selected !in choices && draft.foodId != null) {
                        val oldName = existing?.items?.firstOrNull { it.id == draft.itemId }?.displayName ?: "旧版食物"
                        choices[selected] = "$oldName · v${draft.foodVersion}（原记录）"
                    }
                    Text("食物项 ${index + 1}", style = MaterialTheme.typography.titleSmall)
                    Choice("食物", selected, choices) { key ->
                        drafts[index] = if (key == "__manual__") draft.copy(foodId = null, foodVersion = null, manualName = "")
                        else draft.copy(foodId = key.substringBeforeLast("@"), foodVersion = key.substringAfterLast("@").toInt(), manualName = null)
                    }
                    if (draft.manualName != null) OutlinedTextField(draft.manualName, { drafts[index] = drafts[index].copy(manualName = it) },
                        label = { Text("食物名称（热量待补全）") })
                    OutlinedTextField(draft.consumedGrams, { drafts[index] = drafts[index].copy(consumedGrams = it) },
                        label = { Text("实际食用量（克，可留空）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    TextButton(onClick = { drafts.removeAt(index) }) { Text("移除此项") }
                }
                OutlinedButton(onClick = { drafts.add(IntakeDraftItem()) }) { Text("添加食物项") }
                if (existing != null) TextButton(onClick = { confirmDelete = true }) { Text("删除这次摄入") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = {
            scope.launch {
                runCatching { repository.saveIntake(existing?.intake?.id, existing?.intake?.version, meal,
                    date.atStartOfDay().plusMinutes((eatenAt ?: throw IllegalArgumentException("请选择时间")).toLong()),
                    zone, note, drafts.toList()) }
                    .onSuccess { onClose() }.onFailure { error = it.message ?: "请检查时间与食物项" }
            }
        }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } })
    if (confirmDelete && existing != null) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text("删除这次摄入？") }, text = { Text("此记录会从日列表隐藏；备份中仍保留删除标记。") },
        confirmButton = { TextButton(onClick = {
            confirmDelete = false
            scope.launch {
                runCatching { repository.deleteIntake(existing.intake.id, existing.intake.version) }
                    .onSuccess { onDeleted(existing.intake.id, existing.intake.version + 1) }
                    .onFailure { error = it.message ?: "删除失败" }
            }
        }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } })
}

@Composable
private fun LedgerDialog(repository: LedgerRepository, zone: ZoneId, date: LocalDate,
                         existing: LedgerEntryEntity?, onClose: () -> Unit) {
    var type by remember(existing?.id) { mutableStateOf(existing?.type.orEmpty()) }
    var source by remember(existing?.id) { mutableStateOf(existing?.source.orEmpty()) }
    var amount by remember(existing?.id) { mutableStateOf(existing?.amount.orEmpty()) }
    var time by remember(existing?.id) { mutableStateOf(existing?.occurredAtMillis?.let {
        val local = Instant.ofEpochMilli(it).atZone(zone).toLocalTime()
        local.hour * 60 + local.minute
    }) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = onClose, title = { Text(if (existing == null) "记一笔" else "编辑账目") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { type = "INCOME" }) { Text(if (type == "INCOME") "✓ 收入" else "收入") }
                OutlinedButton(onClick = { type = "EXPENSE" }) { Text(if (type == "EXPENSE") "✓ 支出" else "支出") }
            }
            OutlinedTextField(source, { source = it }, label = { Text("来源") }, singleLine = true)
            OutlinedTextField(amount, { amount = it }, label = { Text("金额") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            ClockDropdown("时间", time) { time = it }
            Text("归属 $date。账目与活动中的收入、支出独立记录。", color = MutedInk)
            if (existing != null) TextButton(onClick = { confirmDelete = true }) { Text("删除这笔账") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(onClick = { scope.launch {
            runCatching { repository.save(existing?.id, existing?.version,
                date.atStartOfDay().plusMinutes((time ?: throw IllegalArgumentException("请选择时间")).toLong()),
                zone, type, source, amount) }
                .onSuccess { onClose() }.onFailure { error = it.message ?: "保存失败" }
        } }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } })
    if (confirmDelete && existing != null) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text("删除这笔账？") }, text = { Text("删除后将从当天列表隐藏。") },
        confirmButton = { TextButton(onClick = { scope.launch {
            runCatching { repository.delete(existing.id, existing.version) }
                .onSuccess { onClose() }.onFailure { error = it.message ?: "删除失败"; confirmDelete = false }
        } }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("返回") } })
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

private fun BigDecimal.display(): String = setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
