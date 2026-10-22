package com.dayoudu.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dayoudu.app.data.*
import java.math.BigDecimal
import java.math.RoundingMode

internal val RecordGreen = Color(0xFF20987F)
internal val RecordMint = Color(0xFFE8FAF2)
internal val RecordPink = Color(0xFFE64270)
internal val RecordRose = Color(0xFFFFEEF1)
internal val RecordInk = Color(0xFF111D36)
internal val RecordMuted = Color(0xFF899096)

@Composable
internal fun RecordBackdrop() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFFF2F8F4), Color(0xFFFCFAF5), Color(0xFFF5F9F5))))
        drawOval(Color(0xFFFFF3DF).copy(alpha = .5f), Offset(size.width * .45f, -90f), Size(size.width, size.height * .32f))
        drawOval(Color(0xFFE2F4EC).copy(alpha = .45f), Offset(-size.width * .3f, size.height * .08f), Size(size.width * 1.15f, size.height * .3f))
        val leafX=size.width - 24.dp.toPx()
        val leafY=95.dp.toPx()
        withTransform({ translate(leafX, leafY); rotate(-25f, pivot=Offset.Zero) }) {
            drawLine(Color(0xFFCEE4C9), Offset(0f, 65.dp.toPx()), Offset(0f, -25.dp.toPx()), 2.dp.toPx())
            drawOval(Color(0xFFBBE1BB).copy(alpha = .6f), Offset(-21.dp.toPx(), -35.dp.toPx()), Size(21.dp.toPx(), 39.dp.toPx()))
            drawOval(Color(0xFFA5D7BF).copy(alpha = .6f), Offset(1.dp.toPx(), -12.dp.toPx()), Size(19.dp.toPx(), 32.dp.toPx()))
        }
    }
}

/** Small vector illustrations, kept sharp at both dashboard and category-grid sizes. */
@Composable
internal fun RecordGlyph(kind: String, modifier: Modifier = Modifier, tint: Color = RecordGreen) {
    Canvas(modifier.size(25.dp)) {
        val unit = size.minDimension / 32f
        withTransform({ scale(unit, unit, pivot = Offset.Zero) }) {
            fun line(x: Float, y: Float, x2: Float, y2: Float) = drawLine(tint, Offset(x,y), Offset(x2,y2), 1.8f, StrokeCap.Round)
            fun circle(x: Float,y: Float,r: Float) = drawCircle(tint,r,Offset(x,y),style=Stroke(1.7f))
            fun rect(x: Float,y: Float,w: Float,h: Float,r: Float=3f) = drawRoundRect(tint,Offset(x,y),Size(w,h),androidx.compose.ui.geometry.CornerRadius(r),style=Stroke(1.8f))
            when(kind) {
                "meal" -> {
                    line(7f,3f,7f,12f);line(11f,3f,11f,12f);line(15f,3f,15f,12f)
                    drawArc(tint,0f,180f,false,Offset(7f,8f),Size(8f,10f),style=Stroke(1.8f))
                    line(11f,18f,11f,29f)
                    drawOval(tint,Offset(21f,3f),Size(8f,13f),style=Stroke(1.8f));line(25f,16f,25f,29f)
                }
                "wallet" -> { rect(4f,9f,24f,20f);line(5f,9f,21f,3f);rect(20f,16f,9f,8f,2f);circle(24f,20f,1f) }
                "up", "down" -> {
                    val path = Path().apply { moveTo(12f,29f);lineTo(12f,15f);lineTo(5f,15f);lineTo(16f,3f);lineTo(27f,15f);lineTo(20f,15f);lineTo(20f,29f);close() }
                    withTransform({ if(kind=="down") rotate(180f,Offset(16f,16f)) }) { drawPath(path,tint) }
                }
                "coins" -> { for(y in listOf(22f,16f,10f)) { drawOval(tint,Offset(6f,y-4f),Size(20f,9f),style=Stroke(1.8f));line(6f,y,6f,y+4f);line(26f,y,26f,y+4f) } }
                "bag" -> { rect(6f,10f,20f,19f);drawArc(tint,180f,180f,false,Offset(11f,3f),Size(10f,15f),style=Stroke(1.8f)) }
                "bus" -> { rect(6f,4f,20f,22f);rect(9f,7f,14f,9f,1f);circle(10f,21f,1f);circle(22f,21f,1f);line(9f,26f,9f,29f);line(23f,26f,23f,29f) }
                "home" -> { line(3f,15f,16f,4f);line(16f,4f,29f,15f);line(7f,12f,7f,29f);line(25f,12f,25f,29f);line(7f,29f,25f,29f);rect(13f,18f,6f,11f,0f) }
                "medical" -> { rect(5f,9f,22f,20f);rect(12f,3f,8f,6f,1f);line(16f,14f,16f,24f);line(11f,19f,21f,19f) }
                "book" -> { val path=Path().apply { moveTo(3f,7f);lineTo(16f,10f);lineTo(29f,7f);lineTo(29f,26f);lineTo(16f,29f);lineTo(3f,26f);close() };drawPath(path,tint,style=Stroke(1.8f));line(16f,10f,16f,29f) }
                "phone" -> {rect(9f,2f,14f,28f);line(13f,6f,19f,6f);circle(16f,26f,1f)}
                "gift" -> {rect(5f,13f,22f,16f,1f);rect(3f,9f,26f,5f,1f);line(16f,9f,16f,29f);circle(12f,6f,4f);circle(20f,6f,4f)}
                "person" -> {circle(16f,9f,5f);drawArc(tint,180f,180f,false,Offset(6f,17f),Size(20f,20f),style=Stroke(1.8f));line(6f,27f,26f,27f)}
                "shirt" -> {val p=Path().apply {moveTo(10f,4f);lineTo(4f,9f);lineTo(7f,16f);lineTo(11f,13f);lineTo(11f,29f);lineTo(24f,29f);lineTo(24f,13f);lineTo(28f,16f);lineTo(31f,9f);lineTo(24f,4f);lineTo(20f,8f);lineTo(15f,8f);close()};drawPath(p,tint,style=Stroke(1.8f))}
                "sleep" -> {drawArc(tint,35f,280f,false,Offset(4f,3f),Size(24f,24f),style=Stroke(2f));line(22f,4f,27f,4f);line(27f,4f,22f,10f);line(22f,10f,27f,10f)}
                "fun" -> {circle(18f,11f,7f);line(13f,17f,4f,27f);line(4f,27f,9f,30f);line(9f,30f,19f,18f);line(14f,6f,23f,15f)}
                else -> {circle(16f,16f,12f);for(x in listOf(10f,16f,22f))drawCircle(tint,1f,Offset(x,16f))}
            }
        }
    }
}

@Composable
internal fun RecordBadge(icon: String, tint: Color = RecordGreen, background: Color = RecordMint, size: Int = 38) {
    Box(Modifier.size(size.dp).background(background, CircleShape), contentAlignment = Alignment.Center) {
        RecordGlyph(icon, Modifier.size((size * .58f).dp), tint)
    }
}

@Composable
internal fun NutritionCard(totals: NutritionTotals, day: NutritionDayEntity?, compact: Boolean = false, onClick: (() -> Unit)? = null) {
    val budget = day?.budgetKcal?.toBigDecimalOrNull()
    val exercise = day?.exerciseKcal?.toBigDecimalOrNull()
    // The prototype defines remaining = budget - intake; exercise is displayed independently.
    val remaining = budget?.minus(totals.kcal)
    val progress = if (budget != null && budget > BigDecimal.ZERO) (remaining!!.toFloat()/budget.toFloat()).coerceIn(0f,1f) else 0f
    @Composable fun Body() {
        Column(Modifier.fillMaxWidth().padding(if(compact) 14.dp else 18.dp), verticalArrangement = Arrangement.spacedBy(if(compact) 9.dp else 12.dp)) {
            if(!compact) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RecordBadge("meal")
                Text("今日热量", color = RecordInk, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("单位：千卡", color = RecordMuted, fontSize = 11.sp)
                if(onClick != null) Text("›", color = RecordMuted, fontSize = 20.sp)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                CalorieSide("已摄入", totals.kcal.display(), Modifier.weight(1f))
                Box(Modifier.size(if(compact) 132.dp else 160.dp).semantics { contentDescription = "热量环，剩余${remaining?.display() ?: "未设置预算"}" }, contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize().padding(5.dp)) {
                        drawArc(Color(0xFFEDF1F2),-90f,360f,false,style=Stroke(9.dp.toPx(),cap=StrokeCap.Round))
                        if(progress > 0) drawArc(Brush.sweepGradient(listOf(Color(0xFF10D1AA),RecordGreen,Color(0xFF10D1AA))),-90f,360*progress,false,style=Stroke(9.dp.toPx(),cap=StrokeCap.Round))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if(remaining != null && remaining < BigDecimal.ZERO) "已超出" else "还可吃", color = RecordMuted, fontSize = 13.sp)
                        Text(remaining?.abs()?.setScale(0,RoundingMode.HALF_UP)?.toPlainString() ?: "—", color = RecordInk, fontSize = if(compact) 29.sp else 34.sp, fontWeight = FontWeight.Bold)
                        Text("千卡", color = RecordMuted, fontSize = 12.sp)
                        Text(budget?.let { "预算 ${it.display()}" } ?: "点击设置预算", color = RecordMuted, fontSize = 10.sp, modifier = Modifier.padding(top=5.dp))
                    }
                }
                CalorieSide("运动消耗", exercise?.display() ?: "—", Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                MacroBar("蛋白质",totals.protein,day?.proteinGoal,totals.missingProtein,RecordGreen,Modifier.weight(1f))
                MacroBar("脂肪",totals.fat,day?.fatGoal,totals.missingFat,RecordGreen,Modifier.weight(1f))
                MacroBar("碳水",totals.carb,day?.carbGoal,totals.missingCarb,Color(0xFFFF9EAE),Modifier.weight(1f))
            }
            if(totals.missingKcal > 0) Text("${totals.missingKcal} 项热量待补全，当前为已知合计", color = Gold, fontSize = 11.sp)
        }
    }
    if(onClick != null) Surface(onClick = onClick, shape = RoundedCornerShape(26.dp), color = Color.White.copy(alpha=.96f)) { Body() }
    else Surface(shape=RoundedCornerShape(26.dp),color=Color.White.copy(alpha=.96f)) { Body() }
}

@Composable
private fun CalorieSide(label: String, value: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment=Alignment.CenterHorizontally) {
        Text(label,color=RecordMuted,fontSize=12.sp)
        Text(value,color=RecordInk,fontSize=23.sp,fontWeight=FontWeight.Bold,maxLines=1)
        Text("千卡",color=RecordMuted,fontSize=12.sp)
    }
}

@Composable
private fun MacroBar(label: String, value: BigDecimal, goalText: String?, missing: Int, color: Color, modifier: Modifier) {
    val goal=goalText?.toBigDecimalOrNull()
    val progress=if(goal != null && goal > BigDecimal.ZERO) (value.toFloat()/goal.toFloat()).coerceIn(0f,1f) else 0f
    Column(modifier, horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text(label,color=RecordMuted,fontSize=12.sp)
        LinearProgressIndicator(progress={progress},modifier=Modifier.fillMaxWidth().height(5.dp),color=color,trackColor=Color(0xFFEDF1F2),drawStopIndicator={})
        Text("${value.display()}/${goal?.display() ?: "—"}克",fontSize=11.sp,color=RecordMuted,maxLines=1)
        if(missing>0) Text("${missing}项待补",fontSize=10.sp,color=Gold)
    }
}

internal fun money(amount: BigDecimal) = amount.setScale(2, RoundingMode.HALF_UP).toPlainString()
internal fun List<LedgerEntryEntity>.total(type: String): BigDecimal = filter { it.type==type }
    .fold(BigDecimal.ZERO) { sum,entry -> sum+(entry.amount.toBigDecimalOrNull() ?: BigDecimal.ZERO) }

@Composable
internal fun LedgerSummary(entries: List<LedgerEntryEntity>, onClick: (() -> Unit)? = null) {
    @Composable fun Body() {
        Column(Modifier.fillMaxWidth().padding(if(onClick!=null) 16.dp else 0.dp),verticalArrangement=Arrangement.spacedBy(13.dp)) {
            if(onClick!=null) Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                RecordBadge("wallet")
                Text("今日收支",color=RecordInk,fontSize=20.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
                Text("›",color=RecordMuted,fontSize=24.sp)
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(if(onClick!=null) 10.dp else 0.dp)) {
                for(type in listOf("INCOME","EXPENSE")) {
                    val income=type=="INCOME"
                    val tint=if(income) RecordPink else RecordGreen
                    Column(Modifier.weight(1f).background(
                        Brush.linearGradient(if(income) listOf(RecordRose,Color(0xFFFFF8F9)) else listOf(RecordMint,Color(0xFFF6FFFB))),
                        RoundedCornerShape(20.dp)).padding(horizontal=14.dp,vertical=18.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            if(onClick!=null) RecordBadge(if(income) "up" else "down",tint,if(income) Color(0xFFFFDFE6) else Color(0xFFCFF8E9),32)
                            Text(if(income) "总收入" else "总支出",color=if(onClick!=null) RecordInk else tint,fontWeight=FontWeight.SemiBold,fontSize=14.sp)
                        }
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val amount="¥ ${money(entries.total(type))}"
                            val amountSize=minOf(24f,maxWidth.value/(amount.length*.62f))
                            Text(amount,color=tint,fontWeight=FontWeight.Bold,fontSize=amountSize.sp,maxLines=1)
                        }
                        Text(if(onClick!=null) (if(income) "今日收入" else "今日支出") else "${entries.count { it.type==type }} 笔",color=if(onClick!=null) RecordMuted else tint,fontSize=12.sp)
                    }
                }
            }
        }
    }
    if(onClick!=null) Surface(onClick=onClick,shape=RoundedCornerShape(26.dp),color=Color.White.copy(alpha=.96f)) { Body() }
    else Body()
}

@Composable
internal fun RecordPrimaryButton(text: String, tint: Color = RecordGreen, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick=onClick,enabled=enabled,modifier=Modifier.fillMaxWidth().height(52.dp),shape=RoundedCornerShape(27.dp),
        colors=ButtonDefaults.buttonColors(containerColor=tint)) { Text(text,fontSize=16.sp,fontWeight=FontWeight.SemiBold) }
}
