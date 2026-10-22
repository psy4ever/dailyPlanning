package com.dayoudu.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dayoudu.app.data.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun RecordScreen(repository: NutritionRepository, sleepRepository: SleepRepository,
    ledgerRepository: LedgerRepository, padding: PaddingValues, onDetailChange: (Boolean) -> Unit = {}) {
    val zone = remember { ZoneId.systemDefault() }
    var dateText by rememberSaveable { mutableStateOf(LocalDate.now(zone).toString()) }
    val date = LocalDate.parse(dateText)
    var page by rememberSaveable { mutableStateOf("home") }
    var intakeId by rememberSaveable { mutableStateOf<String?>(null) }
    var ledgerId by rememberSaveable { mutableStateOf<String?>(null) }
    val intakes by key(repository,date,zone) { remember { repository.observeDay(date,zone) }.collectAsStateWithLifecycle(emptyList()) }
    val entries by key(ledgerRepository,date,zone) { remember { ledgerRepository.observeDay(date,zone) }.collectAsStateWithLifecycle(emptyList()) }
    val day by key(repository,date,zone) { remember { repository.observeNutritionDay(date,zone) }.collectAsStateWithLifecycle(null) }
    val totals = remember(intakes) { nutritionTotals(intakes) }
    fun back() { page=when(page) { "intake","goals","foods" -> "food"; "ledgerEdit" -> "ledger"; else -> "home" } }
    BackHandler(page!="home") { back() }
    LaunchedEffect(page) { onDetailChange(page!="home") }
    DisposableEffect(Unit) { onDispose { onDetailChange(false) } }
    Box(Modifier.fillMaxSize()) {
        RecordBackdrop()
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal=18.dp)) {
            val title=when(page) {
                "food"->"饮食记录"; "ledger"->"收支记录"; "sleep"->"睡眠记录"
                "intake"->if(intakeId==null) "添加饮食" else "编辑摄入"
                "ledgerEdit"->if(ledgerId==null) "记一笔" else "编辑账目"
                "goals"->"当日营养目标"; "foods"->"我的食物"; else->"记录"
            }
            if(page!="intake") RecordHeader(title,page=="home") { back() }
            if(page in listOf("home","food","ledger","sleep")) RecordDate(date) { dateText=it.toString() }
            when(page) {
                "home" -> Column(Modifier.verticalScroll(rememberScrollState()).weight(1f).padding(bottom=20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                    NutritionCard(totals,day) { page="food" }
                    LedgerSummary(entries) { page="ledger" }
                    Surface(onClick={page="sleep"},shape=RoundedCornerShape(22.dp),color=Color.White.copy(alpha=.85f)) {
                        Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            RecordBadge("sleep",background=Peach)
                            Text("睡眠记录",fontWeight=FontWeight.SemiBold,color=RecordInk,modifier=Modifier.weight(1f))
                            Text("查看 ›",color=RecordMuted,fontSize=13.sp)
                        }
                    }
                }
                "food" -> FoodRecordDetail(intakes,totals,day,repository,zone,
                    onEdit={ intakeId=it?.intake?.id;page="intake" },onGoals={page="goals"},onFoods={page="foods"})
                "ledger" -> LedgerRecordDetail(entries,zone) { ledgerId=it?.id;page="ledgerEdit" }
                "sleep" -> SleepRecordDetail(sleepRepository,zone,date)
                "intake" -> {
                    val existing=intakes.firstOrNull { it.intake.id==intakeId }
                    if(intakeId==null || existing!=null) FoodIntakeEditor(repository,zone,date,existing) { page="food" }
                    else Text("正在读取摄入记录…",color=RecordMuted)
                }
                "ledgerEdit" -> {
                    val existing=entries.firstOrNull { it.id==ledgerId }
                    if(ledgerId==null || existing!=null) LedgerEntryEditor(ledgerRepository,zone,date,existing) { page="ledger" }
                    else Text("正在读取账目…",color=RecordMuted)
                }
                "goals" -> NutritionGoalsEditor(repository,date,zone,day) { page="food" }
                "foods" -> FoodLibrary(repository)
            }
        }
    }
}

@Composable
internal fun RecordHeader(title: String, home: Boolean=false, onBack: () -> Unit) {
    if(home) Row(Modifier.fillMaxWidth().padding(top=18.dp,bottom=4.dp),verticalAlignment=Alignment.CenterVertically) {
        Text(title,color=RecordInk,fontSize=34.sp,fontWeight=FontWeight.Bold)
        Text("  ˊ",color=RecordGreen,fontSize=32.sp,fontWeight=FontWeight.Bold)
    } else Row(Modifier.fillMaxWidth().height(56.dp),verticalAlignment=Alignment.CenterVertically) {
        TextButton(onClick=onBack,modifier=Modifier.size(48.dp).semantics { contentDescription="返回" },contentPadding=PaddingValues(0.dp)) { Text("‹",color=RecordInk,fontSize=32.sp) }
        Box(Modifier.weight(1f),contentAlignment=Alignment.Center) { Text(title,color=RecordInk,fontSize=18.sp,fontWeight=FontWeight.SemiBold) }
        Spacer(Modifier.width(48.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordDate(date: LocalDate,onChange: (LocalDate)->Unit) {
    var showCalendar by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(bottom=10.dp),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically) {
        TextButton(onClick={onChange(date.minusDays(1))},modifier=Modifier.semantics { contentDescription="前一天" }) { Text("‹",color=RecordMuted,fontSize=23.sp) }
        TextButton(onClick={showCalendar=true}) { Text(date.format(DateTimeFormatter.ofPattern("M月d日  EEEE",Locale.CHINESE))+"  ⌄",color=RecordInk,fontSize=14.sp) }
        TextButton(onClick={onChange(date.plusDays(1))},modifier=Modifier.semantics { contentDescription="后一天" }) { Text("›",color=RecordMuted,fontSize=23.sp) }
    }
    if(showCalendar) {
        val state=rememberDatePickerState(initialSelectedDateMillis=date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest={showCalendar=false},confirmButton={TextButton(onClick={
            state.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) };showCalendar=false
        }) {Text("确定")}},dismissButton={TextButton(onClick={showCalendar=false}) {Text("取消")}}) {
            DatePicker(state=state)
            TextButton(onClick={onChange(LocalDate.now());showCalendar=false}) {Text("回到今天")}
        }
    }
}

@Composable
private fun ColumnScope.FoodRecordDetail(intakes: List<IntakeWithItems>,totals: NutritionTotals,day: NutritionDayEntity?,
    repository: NutritionRepository,zone: ZoneId,onEdit: (IntakeWithItems?)->Unit,onGoals: ()->Unit,onFoods: ()->Unit) {
    var deleted by remember { mutableStateOf<Pair<String,Int>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope=rememberCoroutineScope()
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        NutritionCard(totals,day,compact=true)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            TextButton(onClick=onGoals) {Text("预算与运动 ›",color=RecordGreen)}
            TextButton(onClick=onFoods) {Text("管理食物 ›",color=RecordGreen)}
        }
        if(intakes.isEmpty()) RecordEmpty("还没有饮食记录","记录第一餐，开始了解今天的摄入。")
        intakes.forEach { intake ->
            Surface(onClick={onEdit(intake)},shape=RoundedCornerShape(22.dp),color=Color.White) {
                Column(Modifier.fillMaxWidth().padding(15.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    val kcal=nutritionTotals(listOf(intake)).kcal
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(9.dp)) {
                        RecordBadge("meal",background=Color(0xFFFFF2CB),size=34)
                        Text(mealNames[intake.intake.mealType] ?: "餐食",fontWeight=FontWeight.SemiBold,color=RecordInk)
                        Text(Instant.ofEpochMilli(intake.intake.eatenAtMillis).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm")),color=RecordMuted,fontSize=12.sp,modifier=Modifier.weight(1f))
                        Text("${kcal.display()} 千卡  ›",color=RecordInk,fontSize=12.sp)
                    }
                    intake.items.forEach { item ->
                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            FoodAvatar(item.displayName)
                            Column(Modifier.weight(1f)) {
                                Text(item.displayName,color=RecordInk,fontSize=14.sp)
                                Text(item.consumedGrams?.let { "${it}克" } ?: "份量待补",color=RecordMuted,fontSize=12.sp)
                            }
                            Text(item.calculatedKcal()?.let { "${it.display()} 千卡" } ?: "热量待补",color=RecordMuted,fontSize=12.sp)
                        }
                    }
                    intake.intake.note?.let { Text(it,color=RecordMuted,fontSize=12.sp) }
                    TextButton(onClick={scope.launch {runCatching { repository.deleteIntake(intake.intake.id,intake.intake.version) }
                        .onSuccess {deleted=intake.intake.id to intake.intake.version+1;message="已删除，可撤销"}.onFailure {message=it.message}}}) {Text("删除这餐",color=RecordMuted,fontSize=12.sp)}
                }
            }
        }
        message?.let { Text(it,color=RecordMuted) }
        deleted?.let { (id,version) -> TextButton(onClick={scope.launch {runCatching {repository.restoreIntake(id,version)}
            .onSuccess {deleted=null;message="已恢复"}.onFailure {message=it.message}}}) {Text("撤销删除")} }
        Spacer(Modifier.height(8.dp))
    }
    Box(Modifier.padding(vertical=12.dp)) {RecordPrimaryButton("＋ 记录饮食") {onEdit(null)}}
}

@Composable
private fun ColumnScope.LedgerRecordDetail(entries: List<LedgerEntryEntity>,zone: ZoneId,onEdit: (LedgerEntryEntity?)->Unit) {
    var filter by rememberSaveable { mutableStateOf("ALL") }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        LedgerSummary(entries)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            listOf("ALL" to "全部","INCOME" to "收入","EXPENSE" to "支出").forEach { (key,label) ->
                FilterChip(selected=filter==key,onClick={filter=key},label={Text(label)},colors=FilterChipDefaults.filterChipColors(selectedContainerColor=RecordMint,selectedLabelColor=RecordGreen))
            }
        }
        val filtered=entries.filter {filter=="ALL" || it.type==filter}
        if(filtered.isEmpty()) RecordEmpty("还没有${when(filter){"INCOME"->"收入";"EXPENSE"->"支出";else->"收支"}}记录","点击下方按钮，记下今天的一笔。")
        Surface(shape=RoundedCornerShape(24.dp),color=Color.White) {
            Column {
                filtered.forEach { entry ->
                    val income=entry.type=="INCOME"
                    val category=entry.recordCategory()
                    Surface(onClick={onEdit(entry)},color=Color.Transparent) {
                        Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            RecordBadge(category.icon,if(income) RecordPink else RecordGreen,if(income) Color(0xFFFFE8CC) else RecordMint,42)
                            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                                Text(category.title,color=RecordInk,fontWeight=FontWeight.SemiBold)
                                (entry.note ?: entry.source.takeIf { it!=category.title })?.let {Text(it,color=RecordMuted,fontSize=12.sp)}
                                Text(Instant.ofEpochMilli(entry.occurredAtMillis).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm")),color=RecordMuted,fontSize=11.sp)
                            }
                            Text("${if(income) "+" else "−"} ¥ ${money(entry.amount.toBigDecimal())}",color=if(income) RecordPink else RecordGreen,fontWeight=FontWeight.Bold,fontSize=16.sp)
                        }
                    }
                }
            }
        }
    }
    Box(Modifier.padding(vertical=12.dp)) {RecordPrimaryButton("＋ 记一笔") {onEdit(null)}}
}

@Composable
private fun ColumnScope.SleepRecordDetail(repository: SleepRepository,zone: ZoneId,date: LocalDate) {
    val logs by remember(repository,date) { repository.observeWakeDay(date,zone) }.collectAsStateWithLifecycle(emptyList())
    var showEditor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SleepLogEntity?>(null) }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if(logs.isEmpty()) RecordEmpty("还没有睡眠记录","记录入睡和醒来时间。")
        logs.forEach { log -> SleepRow(log,zone) {editing=log;showEditor=true} }
    }
    Box(Modifier.padding(vertical=12.dp)) {RecordPrimaryButton("＋ 记录睡眠") {editing=null;showEditor=true}}
    if(showEditor) SleepDialog(repository,zone,date,editing) {showEditor=false}
}

@Composable
internal fun RecordEmpty(title: String,subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(vertical=30.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(title,color=RecordInk,fontWeight=FontWeight.SemiBold)
        Text(subtitle,color=RecordMuted,fontSize=12.sp)
    }
}

internal val mealNames=linkedMapOf("BREAKFAST" to "早餐","LUNCH" to "午餐","DINNER" to "晚餐","SNACK" to "加餐")

@Composable
internal fun FoodAvatar(name: String) {
    val symbol=when { name.contains("奶")->"🥛";name.contains("蛋")->"🥚";name.contains("饭")->"🍚";name.contains("面包")->"🍞";name.contains("肉")->"🍗";name.contains("菜")||name.contains("花")->"🥦";else->"🥣" }
    Surface(shape=RoundedCornerShape(12.dp),color=Color(0xFFF8F4EC),modifier=Modifier.size(42.dp)) {
        Box(contentAlignment=Alignment.Center) {Text(symbol,fontSize=26.sp)}
    }
}
