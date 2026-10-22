package com.dayoudu.app

import android.graphics.Bitmap
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.dayoudu.app.data.*
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.assertEquals

/** Prototype checks run against a disposable database, separate from the installed user's records. */
class RecordPrototypeTest {
    @get:Rule val compose=createComposeRule()
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val name="record-ui-${UUID.randomUUID()}.db"
    private lateinit var db: NutritionDatabase
    private lateinit var focus: FocusManager
    private val previousZone=TimeZone.getDefault()
    private val zone=ZoneId.of("Asia/Shanghai")
    private val date=LocalDate.now(zone)
    @Before fun setup() = runBlocking {
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        db=Room.databaseBuilder(context,NutritionDatabase::class.java,name).build()
        val nutrition=NutritionRepository(db)
        nutrition.saveFood(null,null,FoodDraft("全麦面包","512.5","PACKAGED","USER_LABEL",proteinGPer100g="20",fatGPer100g="7.5",carbGPer100g="95"))
        nutrition.saveFood(null,null,FoodDraft("牛奶","60","PACKAGED","USER_LABEL",proteinGPer100g="3",fatGPer100g="3",carbGPer100g="5"))
        nutrition.saveFood(null,null,FoodDraft("西兰花","94","COOKED","USER_ESTIMATE"))
        val foods=nutrition.latestFoods()
        val bread=foods.first {it.name=="全麦面包"};val milk=foods.first {it.name=="牛奶"};val broccoli=foods.first {it.name=="西兰花"}
        nutrition.saveIntake(null,null,"BREAKFAST",date.atTime(8,20),zone,"",listOf(
            IntakeDraftItem(foodId=bread.foodId,foodVersion=1,consumedGrams="40"),IntakeDraftItem(foodId=milk.foodId,foodVersion=1,consumedGrams="200")))
        nutrition.saveIntake(null,null,"LUNCH",date.atTime(12,30),zone,"",listOf(IntakeDraftItem(foodId=broccoli.foodId,foodVersion=1,consumedGrams="100")))
        nutrition.saveNutritionDay(date,zone,null,"1570","230","249","72","49")
        val ledger=LedgerRepository(db)
        ledger.save(null,null,date.atTime(8,0),zone,"INCOME","工资","100.00","SALARY","5月工资")
        ledger.save(null,null,date.atTime(9,15),zone,"EXPENSE","购物","189.00","SHOPPING","日用品")
        ledger.save(null,null,date.atTime(10,20),zone,"EXPENSE","交通","10.00","TRANSPORT","地铁充值")
        ledger.save(null,null,date.atTime(12,30),zone,"EXPENSE","餐饮","85.00","FOOD","午餐")
        compose.setContent {
            val manager=LocalFocusManager.current
            SideEffect {focus=manager}
            MaterialTheme(colorScheme=lightColorScheme(primary=RecordGreen,onSurface=RecordInk,outline=SoftLine)) {
                RecordScreen(nutrition,SleepRepository(db),ledger,PaddingValues(0.dp))
            }
        }
        compose.waitUntil(10000) {compose.onAllNodesWithText("1151").fetchSemanticsNodes().isNotEmpty()}
    }
    @After fun cleanup() {if(::db.isInitialized) db.close();context.deleteDatabase(name);TimeZone.setDefault(previousZone)}
    private fun screenshot(label: String) {
        compose.runOnIdle {focus.clearFocus()}
        compose.waitForIdle()
        val file=File(context.getExternalFilesDir(null),"record-preview-$label.png")
        file.outputStream().use {compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun cardsNavigateAndLedgerIncomeExpenseEditorsPersist() {
        screenshot("home")
        compose.onNodeWithText("今日收支").performClick()
        compose.onNodeWithText("收支记录").assertIsDisplayed()
        screenshot("ledger")
        compose.onNodeWithText("＋ 记一笔").performClick()
        compose.onNodeWithText("金额").performTextInput("85.00")
        compose.onNodeWithText("备注（可选）").performTextInput("晚餐")
        screenshot("expense-editor")
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(10000) {compose.onAllNodesWithText("¥ 369.00").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("＋ 记一笔").performClick()
        compose.onNodeWithText("收入",useUnmergedTree=true).performClick()
        compose.onNodeWithText("金额").performTextInput("50.00")
        screenshot("income-editor")
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(10000) {compose.onAllNodesWithText("¥ 150.00").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithContentDescription("前一天").performClick()
        compose.waitUntil(10000) {compose.onAllNodesWithText("¥ 0.00").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithContentDescription("后一天").performClick()
        compose.waitUntil(10000) {compose.onAllNodesWithText("¥ 150.00").fetchSemanticsNodes().isNotEmpty()}
    }
    @Test fun foodSelectionQuantityAndSaveRecalculateDashboard() {
        compose.onNodeWithText("今日热量").performClick()
        compose.onNodeWithText("饮食记录").assertIsDisplayed()
        screenshot("food")
        compose.onNodeWithText("＋ 记录饮食").performClick()
        screenshot("food-picker")
        compose.onNodeWithContentDescription("添加全麦面包").performClick()
        compose.onNodeWithContentDescription("食用克数").performTextReplacement("40")
        compose.onNodeWithText("205 千卡").assertIsDisplayed()
        screenshot("food-amount")
        compose.onNodeWithText("保存食用量").performClick()
        compose.onNodeWithText("保存（1 项）").performClick()
        compose.waitUntil(10000) {compose.onAllNodesWithText("946").fetchSemanticsNodes().isNotEmpty()}
        runBlocking {assertEquals(0,java.math.BigDecimal("624").compareTo(nutritionTotals(NutritionRepository(db).observeDay(date,zone).first()).kcal))}
    }
}
