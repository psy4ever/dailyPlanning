package com.dayoudu.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.dayoudu.app.data.*
import java.math.BigDecimal
import java.time.*
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
private fun EntryTypeTabs(type: String,onChange: (String)->Unit) {
    Surface(shape=RoundedCornerShape(24.dp),color=Color(0xFFF2F4F1)) {
        Row(Modifier.fillMaxWidth()) {
            listOf("EXPENSE" to "支出","INCOME" to "收入").forEach { (key,title) ->
                val tint=if(key=="INCOME") RecordPink else RecordGreen
                Surface(onClick={onChange(key)},modifier=Modifier.weight(1f),shape=RoundedCornerShape(24.dp),
                    color=if(type==key) (if(key=="INCOME") RecordRose else RecordMint) else Color.Transparent) {
                    Column(Modifier.padding(vertical=12.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                        Text(title,color=if(type==key) tint else RecordMuted,fontWeight=if(type==key) FontWeight.Bold else FontWeight.Normal)
                        if(type==key) HorizontalDivider(Modifier.padding(top=5.dp).width(26.dp),thickness=2.dp,color=tint)
                    }
                }
            }
        }
    }
}

@Composable
internal fun ColumnScope.LedgerEntryEditor(repository: LedgerRepository,zone: ZoneId,date: LocalDate,
    existing: LedgerEntryEntity?,onClose: ()->Unit) {
    var type by rememberSaveable(existing?.id) { mutableStateOf(existing?.type ?: "EXPENSE") }
    var category by rememberSaveable(existing?.id) {mutableStateOf(existing?.category ?: if(existing==null) "FOOD" else "OTHER")}
    var amount by rememberSaveable(existing?.id) {mutableStateOf(existing?.amount.orEmpty())}
    var note by rememberSaveable(existing?.id) {mutableStateOf(existing?.note.orEmpty())}
    var time by rememberSaveable(existing?.id) {mutableStateOf(existing?.occurredAtMillis?.let {
        Instant.ofEpochMilli(it).atZone(zone).toLocalTime().let { local->local.hour*60+local.minute }
    } ?: LocalTime.now(zone).let {it.hour*60+it.minute})}
    var error by remember {mutableStateOf<String?>(null)}
    var saving by remember {mutableStateOf(false)}
    var delete by remember {mutableStateOf(false)}
    val expectedVersion=remember(existing?.id) {existing?.version}
    val scope=rememberCoroutineScope()
    val tint=if(type=="INCOME") RecordPink else RecordGreen
    val categories=if(type=="INCOME") incomeCategories else expenseCategories
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).imePadding(),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        EntryTypeTabs(type) {if(type!=it) {type=it;category=if(it=="INCOME") "SALARY" else "FOOD"}}
        Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            categories.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    row.forEach { item ->
                        val selected=category==item.key
                        Surface(onClick={category=item.key},modifier=Modifier.weight(1f),shape=RoundedCornerShape(18.dp),
                            color=if(selected) (if(type=="INCOME") RecordRose else RecordMint) else Color.White.copy(alpha=.6f),
                            border=if(selected) BorderStroke(1.3.dp,tint) else null) {
                            Column(Modifier.padding(vertical=12.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(7.dp)) {
                                RecordGlyph(item.icon,tint=if(selected) tint else RecordInk)
                                Text(item.title,fontSize=12.sp,color=if(selected) tint else RecordMuted)
                            }
                        }
                    }
                }
            }
        }
        Surface(shape=RoundedCornerShape(22.dp),color=Color.White.copy(alpha=.9f)) {
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(15.dp)) {
                TextField(value=amount,onValueChange={amount=it},modifier=Modifier.fillMaxWidth(),singleLine=true,
                    label={Text("金额")},prefix={Text("¥ ",fontSize=27.sp,color=tint)},
                    textStyle=LocalTextStyle.current.copy(fontSize=32.sp,fontWeight=FontWeight.Bold,color=tint),
                    placeholder={Text("0.00",color=RecordMuted,fontSize=32.sp)},
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),
                    colors=recordTextFieldColors(),trailingIcon={if(amount.isNotEmpty()) TextButton(onClick={amount=""}) {Text("×",color=RecordMuted,fontSize=24.sp)}})
                OutlinedTextField(note,{note=it},label={Text("备注（可选）")},modifier=Modifier.fillMaxWidth(),maxLines=3,shape=RoundedCornerShape(14.dp))
                if(existing!=null && existing.category==null) Text("原来源：${existing.source}",color=RecordMuted,fontSize=12.sp)
                RecordTimeRow(date,time) {time=it}
            }
        }
        error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        if(existing!=null) TextButton(onClick={delete=true}) {Text("删除这笔账",color=RecordPink)}
        Spacer(Modifier.height(6.dp))
    }
    Box(Modifier.padding(vertical=12.dp).imePadding()) {
        RecordPrimaryButton(if(saving) "保存中…" else "保存",tint,!saving) {
            saving=true
            scope.launch {
                runCatching {
                    val source=if(existing?.category==null && existing!=null && category=="OTHER") existing.source
                        else categories.first {it.key==category}.title
                    repository.save(existing?.id,expectedVersion,date.atStartOfDay().plusMinutes(time.toLong()),zone,
                        type,source,amount,category,note)
                }.onSuccess {onClose()}.onFailure {error=it.message;saving=false}
            }
        }
    }
    if(delete && existing!=null) AlertDialog(onDismissRequest={delete=false},title={Text("删除这笔账？")},
        text={Text("删除后，当天收支汇总会同步更新。")},
        confirmButton={TextButton(onClick={scope.launch {runCatching {repository.delete(existing.id,existing.version)}
            .onSuccess {onClose()}.onFailure {error=it.message;delete=false}}}) {Text("删除",color=RecordPink)}},
        dismissButton={TextButton(onClick={delete=false}) {Text("取消")}})
}

@Composable
private fun recordTextFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor=Color.Transparent,unfocusedContainerColor=Color.Transparent,
    focusedIndicatorColor=RecordGreen,unfocusedIndicatorColor=SoftLine)

@Composable
internal fun RecordTimeRow(date: LocalDate,time: Int,onChange: (Int)->Unit) {
    var open by remember {mutableStateOf(false)}
    Row(Modifier.fillMaxWidth().clickable {open=true}.padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
        Text("时间",color=RecordMuted,modifier=Modifier.weight(1f))
        Text("${date.format(DateTimeFormatter.ofPattern("M月d日"))}  ${"%02d:%02d".format(time/60,time%60)}  ›",color=RecordInk,fontSize=14.sp)
    }
    if(open) {
        var picked by remember {mutableStateOf(time)}
        AlertDialog(onDismissRequest={open=false},title={Text("选择时间")},text={ClockDropdown("时刻",picked) {picked=it}},
            confirmButton={TextButton(onClick={onChange(picked);open=false}) {Text("确定")}},dismissButton={TextButton(onClick={open=false}) {Text("取消")}})
    }
}

private val draftSaver=listSaver<androidx.compose.runtime.snapshots.SnapshotStateList<IntakeDraftItem>,String>(
    save={list->list.map { item->JSONObject().put("id",item.itemId).put("food",item.foodId).put("version",item.foodVersion)
        .put("manual",item.manualName).put("grams",item.consumedGrams).toString()}},
    restore={list->list.map {text->val json=JSONObject(text)
        IntakeDraftItem(json.optString("id").takeIf {it.isNotEmpty()},json.optString("food").takeIf {it.isNotEmpty()},
            if(json.has("version")) json.getInt("version") else null,json.optString("manual").takeIf {json.has("manual")},json.getString("grams"))}.toMutableStateList()})

@Composable
internal fun ColumnScope.FoodIntakeEditor(repository: NutritionRepository,zone: ZoneId,date: LocalDate,
    existing: IntakeWithItems?,onClose: ()->Unit) {
    val expectedVersion=remember(existing?.intake?.id) {existing?.intake?.version}
    var foods by remember {mutableStateOf<List<FoodVersionEntity>>(emptyList())}
    var query by rememberSaveable {mutableStateOf("")}
    var meal by rememberSaveable(existing?.intake?.id) {mutableStateOf(existing?.intake?.mealType ?: "BREAKFAST")}
    var note by rememberSaveable(existing?.intake?.id) {mutableStateOf(existing?.intake?.note.orEmpty())}
    var time by rememberSaveable(existing?.intake?.id) {mutableStateOf(existing?.intake?.eatenAtMillis?.let {
        Instant.ofEpochMilli(it).atZone(zone).toLocalTime().let {local->local.hour*60+local.minute}
    } ?: LocalTime.now(zone).let {it.hour*60+it.minute})}
    val drafts=rememberSaveable(existing?.intake?.id,saver=draftSaver) {
        (existing?.items?.map {IntakeDraftItem(it.id,it.foodId,it.foodVersion,it.manualName,it.consumedGrams.orEmpty())} ?: emptyList()).toMutableStateList()
    }
    var selectedFood by remember {mutableStateOf<FoodVersionEntity?>(null)}
    var selectedIndex by remember {mutableStateOf<Int?>(null)}
    var editingAmount by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    var saving by remember {mutableStateOf(false)}
    var addFood by remember {mutableStateOf(false)}
    var refresh by remember {mutableStateOf(0)}
    var manual by remember {mutableStateOf(false)}
    var manualName by rememberSaveable {mutableStateOf("")}
    var delete by remember {mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    LaunchedEffect(refresh) {runCatching {repository.latestFoods()}.onSuccess {foods=it}.onFailure {error=it.message}}
    fun draftName(item: IntakeDraftItem): String = item.manualName ?: foods.firstOrNull {it.foodId==item.foodId && it.version==item.foodVersion}?.name
        ?: existing?.items?.firstOrNull {it.id==item.itemId}?.displayName ?: "食物"
    RecordHeader(if(editingAmount || existing!=null) "编辑摄入" else "添加饮食") {
        if(editingAmount) editingAmount=false else onClose()
    }
    if(editingAmount) {
        val draft=selectedIndex?.let {drafts[it]} ?: IntakeDraftItem(foodId=selectedFood?.foodId,foodVersion=selectedFood?.version)
        val snapshot=existing?.items?.firstOrNull {it.id==draft.itemId}
        val food=selectedFood ?: snapshot?.let {if(it.foodId!=null) FoodVersionEntity(it.foodId,it.foodVersion!!,it.displayName,"UNKNOWN",it.kcalPer100gSnapshot ?: "0","USER_ESTIMATE",
            proteinGPer100g=it.proteinGPer100gSnapshot,fatGPer100g=it.fatGPer100gSnapshot,carbGPer100g=it.carbGPer100gSnapshot) else null}
        FoodAmountEditor(food,draft,if(draft.foodId==null) draftName(draft) else food?.name ?: draftName(draft),
            onRemove=selectedIndex?.let {index->{drafts.removeAt(index);editingAmount=false}},
            onBack={editingAmount=false},onSave={grams->
                val updated=draft.copy(consumedGrams=grams)
                if(selectedIndex!=null) drafts[selectedIndex!!]=updated else drafts.add(updated)
                editingAmount=false
            })
        return
    }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).imePadding(),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            mealNames.forEach {(key,label)->
                FilterChip(selected=meal==key,onClick={meal=key},label={Text(label)},modifier=Modifier.weight(1f),
                    colors=FilterChipDefaults.filterChipColors(selectedContainerColor=Color(0xFFCBEADF),selectedLabelColor=RecordGreen))
            }
        }
        OutlinedTextField(query,{query=it},modifier=Modifier.fillMaxWidth(),placeholder={Text("搜索食物名称")},singleLine=true,shape=RoundedCornerShape(24.dp))
        if(drafts.isNotEmpty()) {
            Text("已选 ${drafts.size} 项 · 点击调整食用量",color=RecordGreen,fontSize=13.sp)
            drafts.forEachIndexed {index,draft->
                Surface(onClick={selectedIndex=index;selectedFood=foods.firstOrNull {it.foodId==draft.foodId && it.version==draft.foodVersion};editingAmount=true},shape=RoundedCornerShape(16.dp),color=RecordMint) {
                    Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        FoodAvatar(draftName(draft))
                        Text(draftName(draft),color=RecordInk,modifier=Modifier.weight(1f))
                        Text("${draft.consumedGrams.ifBlank {"待补"}} 克  ›",color=RecordGreen,fontSize=12.sp)
                    }
                }
            }
        }
        Surface(shape=RoundedCornerShape(22.dp),color=Color.White) {
            Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                val filtered=foods.filter {it.name.contains(query,true) || it.brand?.contains(query,true)==true}
                if(filtered.isEmpty()) Text(if(foods.isEmpty()) "食物库为空，请先添加食物营养数据。" else "没有找到食物",color=RecordMuted,modifier=Modifier.padding(12.dp))
                filtered.forEach {food->
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        FoodAvatar(food.name)
                        Column(Modifier.weight(1f)) {
                            Text(food.name,color=RecordInk,fontWeight=FontWeight.Medium)
                            Text("100克 · ${food.kcalPer100g} 千卡",color=RecordMuted,fontSize=12.sp)
                        }
                        FilledTonalIconButton(onClick={
                            selectedFood=food;selectedIndex=drafts.indexOfFirst {it.foodId==food.foodId && it.foodVersion==food.version}.takeIf {it>=0};editingAmount=true
                        },modifier=Modifier.size(36.dp).semantics {contentDescription="添加${food.name}"},colors=IconButtonDefaults.filledTonalIconButtonColors(containerColor=RecordGreen,contentColor=Color.White)) {
                            Text("＋",fontSize=24.sp)
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            TextButton(onClick={addFood=true}) {Text("＋ 新建食物")}
            TextButton(onClick={manual=true}) {Text("手动记名称")}
        }
        OutlinedTextField(note,{note=it},label={Text("备注（可选）")},modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp))
        RecordTimeRow(date,time) {time=it}
        error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        if(existing!=null) TextButton(onClick={delete=true}) {Text("删除这餐",color=RecordPink)}
    }
    Box(Modifier.padding(vertical=12.dp).imePadding()) {
        RecordPrimaryButton(if(saving) "保存中…" else "保存${if(drafts.isNotEmpty()) "（${drafts.size} 项）" else ""}",enabled=!saving) {
            saving=true
            scope.launch {runCatching {repository.saveIntake(existing?.intake?.id,expectedVersion,meal,date.atStartOfDay().plusMinutes(time.toLong()),zone,note,drafts.toList())}
                .onSuccess {onClose()}.onFailure {error=it.message;saving=false}}
        }
    }
    if(addFood) FoodDialog(repository,null) {addFood=false;refresh++}
    if(manual) AlertDialog(onDismissRequest={manual=false},title={Text("记录未知营养食物")},text={
        Column {OutlinedTextField(manualName,{manualName=it},label={Text("食物名称")});Text("营养数值将显示为待补全。",fontSize=12.sp,color=RecordMuted)}
    },confirmButton={TextButton(onClick={if(manualName.isNotBlank()) {drafts.add(IntakeDraftItem(manualName=manualName.trim()));manualName="";manual=false}}) {Text("添加")}},dismissButton={TextButton(onClick={manual=false}) {Text("取消")}})
    if(delete && existing!=null) AlertDialog(onDismissRequest={delete=false},title={Text("删除这餐？")},
        confirmButton={TextButton(onClick={scope.launch {runCatching {repository.deleteIntake(existing.intake.id,existing.intake.version)}
            .onSuccess {onClose()}.onFailure {error=it.message;delete=false}}}) {Text("删除",color=RecordPink)}},dismissButton={TextButton(onClick={delete=false}) {Text("取消")}})
}

@Composable
private fun ColumnScope.FoodAmountEditor(food: FoodVersionEntity?,draft: IntakeDraftItem,name: String,
    onRemove: (()->Unit)?,onBack: ()->Unit,onSave: (String)->Unit) {
    var grams by rememberSaveable(draft.itemId,food?.foodId) {mutableStateOf(draft.consumedGrams.ifBlank {"100"})}
    var error by remember {mutableStateOf<String?>(null)}
    BackHandler {onBack()}
    fun amount(value: String?): String {
        val per100=value?.toBigDecimalOrNull()
        val weight=grams.toBigDecimalOrNull()
        return if(per100==null || weight==null) "待补全" else (per100*weight/BigDecimal(100)).display()
    }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).imePadding(),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            FoodAvatar(name);Text(name,color=RecordInk,fontSize=20.sp,fontWeight=FontWeight.Bold)
        }
        Surface(shape=RoundedCornerShape(22.dp),color=Color.White) {
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("食用量",color=RecordMuted)
                    FilledTonalIconButton(onClick={grams=((grams.toBigDecimalOrNull() ?: BigDecimal.ZERO)-BigDecimal.TEN).coerceAtLeast(BigDecimal.ONE).toPlainString()},modifier=Modifier.size(36.dp),colors=IconButtonDefaults.filledTonalIconButtonColors(containerColor=Color(0xFFF0F3F0),contentColor=RecordInk)) {Text("−")}
                    OutlinedTextField(grams,{grams=it},modifier=Modifier.weight(1f).semantics {contentDescription="食用克数"},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
                    FilledTonalIconButton(onClick={grams=((grams.toBigDecimalOrNull() ?: BigDecimal.ZERO)+BigDecimal.TEN).toPlainString()},modifier=Modifier.size(36.dp),colors=IconButtonDefaults.filledTonalIconButtonColors(containerColor=Color(0xFFF0F3F0),contentColor=RecordInk)) {Text("＋")}
                    Text("克",color=RecordGreen)
                }
                listOf("热量" to "${amount(food?.kcalPer100g)} 千卡","蛋白质" to "${amount(food?.proteinGPer100g)} 克",
                    "脂肪" to "${amount(food?.fatGPer100g)} 克","碳水" to "${amount(food?.carbGPer100g)} 克").forEach {(label,value)->
                    HorizontalDivider(color=SoftLine)
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {Text(label,color=RecordMuted);Text(value,color=RecordInk)}
                }
            }
        }
        error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    }
    Row(Modifier.padding(vertical=12.dp).imePadding(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        if(onRemove!=null) OutlinedButton(onClick=onRemove,modifier=Modifier.height(52.dp)) {Text("移除",color=RecordPink)}
        Box(Modifier.weight(1f)) {RecordPrimaryButton("保存食用量") {
            val value=grams.toBigDecimalOrNull()
            if(value==null || value<=BigDecimal.ZERO) error="请填写大于 0 的食用克数" else onSave(value.toPlainString())
        }}
    }
}

@Composable
internal fun ColumnScope.NutritionGoalsEditor(repository: NutritionRepository,date: LocalDate,zone: ZoneId,day: NutritionDayEntity?,onClose: ()->Unit) {
    var budget by rememberSaveable {mutableStateOf(day?.budgetKcal.orEmpty())}
    var exercise by rememberSaveable {mutableStateOf(day?.exerciseKcal.orEmpty())}
    var protein by rememberSaveable {mutableStateOf(day?.proteinGoal.orEmpty())}
    var fat by rememberSaveable {mutableStateOf(day?.fatGoal.orEmpty())}
    var carb by rememberSaveable {mutableStateOf(day?.carbGoal.orEmpty())}
    val expectedVersion=remember {day?.version}
    var error by remember {mutableStateOf<String?>(null)}
    var saving by remember {mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).imePadding(),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text(date.format(DateTimeFormatter.ofPattern("M月d日")),color=RecordGreen,fontWeight=FontWeight.SemiBold)
        Text("填写你为当天设定的目标；未填写的项目显示为未设置。",color=RecordMuted,fontSize=13.sp)
        GoalField("热量预算（千卡）",budget) {budget=it}
        GoalField("运动消耗（千卡）",exercise) {exercise=it}
        GoalField("蛋白质目标（克）",protein) {protein=it}
        GoalField("脂肪目标（克）",fat) {fat=it}
        GoalField("碳水目标（克）",carb) {carb=it}
        Text("还可吃 = 热量预算 − 已摄入。运动消耗单独展示。",color=RecordMuted,fontSize=12.sp)
        error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    }
    Box(Modifier.padding(vertical=12.dp).imePadding()) {RecordPrimaryButton(if(saving) "保存中…" else "保存",enabled=!saving) {
        saving=true
        scope.launch {runCatching {repository.saveNutritionDay(date,zone,expectedVersion,budget,exercise,protein,fat,carb)}
            .onSuccess {onClose()}.onFailure {error=it.message;saving=false}}
    }}
}

@Composable
private fun GoalField(label: String,value: String,onChange: (String)->Unit) {
    OutlinedTextField(value,onChange,label={Text(label)},modifier=Modifier.fillMaxWidth(),singleLine=true,
        shape=RoundedCornerShape(16.dp),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
}

@Composable
internal fun ColumnScope.FoodLibrary(repository: NutritionRepository) {
    var foods by remember {mutableStateOf<List<FoodVersionEntity>>(emptyList())}
    var refresh by remember {mutableStateOf(0)}
    var editor by remember {mutableStateOf(false)}
    var editing by remember {mutableStateOf<FoodVersionEntity?>(null)}
    var error by remember {mutableStateOf<String?>(null)}
    val scope=rememberCoroutineScope()
    LaunchedEffect(refresh) {runCatching {repository.allLatestFoods()}.onSuccess {foods=it}.onFailure {error=it.message}}
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if(foods.isEmpty()) RecordEmpty("食物库还是空的","添加包装上的营养信息，供记录饮食时选择。")
        foods.forEach {food->
            Surface(onClick={editing=food;editor=true},shape=RoundedCornerShape(18.dp),color=Color.White) {
                Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    FoodAvatar(food.name)
                    Column(Modifier.weight(1f)) {Text(food.name,color=RecordInk);Text("${food.kcalPer100g} 千卡/100克${if(food.archived) " · 已停用" else ""}",color=RecordMuted,fontSize=12.sp)}
                    if(!food.archived) TextButton(onClick={scope.launch {runCatching {repository.archiveFood(food.foodId,food.version)}.onSuccess {refresh++}.onFailure {error=it.message}}}) {Text("停用")}
                    Text("›",color=RecordMuted)
                }
            }
        }
        error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    }
    Box(Modifier.padding(vertical=12.dp)) {RecordPrimaryButton("＋ 新建食物") {editing=null;editor=true}}
    if(editor) FoodDialog(repository,editing) {editor=false;refresh++}
}
