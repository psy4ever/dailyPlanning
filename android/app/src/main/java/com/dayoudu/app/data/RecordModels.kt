package com.dayoudu.app.data

import androidx.room.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.Flow

data class LedgerCategory(val key: String, val title: String, val icon: String)

val expenseCategories = listOf(
    LedgerCategory("FOOD", "餐饮", "meal"), LedgerCategory("SHOPPING", "购物", "bag"),
    LedgerCategory("TRANSPORT", "交通", "bus"), LedgerCategory("HOME", "居住", "home"),
    LedgerCategory("DAILY", "日用品", "bag"), LedgerCategory("MEDICAL", "医疗", "medical"),
    LedgerCategory("FUN", "娱乐", "fun"), LedgerCategory("STUDY", "学习", "book"),
    LedgerCategory("PHONE", "通讯", "phone"), LedgerCategory("CLOTHES", "衣服", "shirt"),
    LedgerCategory("GIFT", "礼物", "gift"), LedgerCategory("OTHER", "其他", "more"),
)
val incomeCategories = listOf(
    LedgerCategory("SALARY", "工资", "coins"), LedgerCategory("SIDE_JOB", "兼职", "person"),
    LedgerCategory("INVESTMENT", "理财", "coins"), LedgerCategory("OTHER", "其他", "more"),
)
fun LedgerEntryEntity.recordCategory(): LedgerCategory =
    (if (type == "INCOME") incomeCategories else expenseCategories).firstOrNull { it.key == category }
        ?: LedgerCategory("OTHER", source, if (type == "INCOME") "coins" else "more")

/** Daily targets are explicit user input; missing targets must never be displayed as recommendations. */
@Entity(tableName = "nutrition_days", indices = [Index(value = ["date", "timezone"], unique = true)])
data class NutritionDayEntity(
    @PrimaryKey val id: String,
    val date: String,
    val timezone: String,
    val budgetKcal: String?,
    val exerciseKcal: String?,
    val proteinGoal: String?,
    val fatGoal: String?,
    val carbGoal: String?,
    val version: Int,
)

@Dao
interface NutritionDayDao {
    @Query("SELECT * FROM nutrition_days WHERE date = :date AND timezone = :zone LIMIT 1")
    fun observe(date: String, zone: String): Flow<NutritionDayEntity?>
    @Query("SELECT * FROM nutrition_days WHERE date = :date AND timezone = :zone LIMIT 1")
    suspend fun get(date: String, zone: String): NutritionDayEntity?
    @Insert suspend fun insert(day: NutritionDayEntity)
    @Update suspend fun update(day: NutritionDayEntity)
}

internal fun nutritionDayId(date: LocalDate, zone: ZoneId): String =
    UUID.nameUUIDFromBytes("nutrition-day:$date:${zone.id}".toByteArray(Charsets.UTF_8)).toString()

data class NutritionTotals(
    val kcal: BigDecimal, val protein: BigDecimal, val fat: BigDecimal, val carb: BigDecimal,
    val missingKcal: Int, val missingProtein: Int, val missingFat: Int, val missingCarb: Int,
)

fun nutritionTotals(intakes: List<IntakeWithItems>): NutritionTotals {
    val items = intakes.flatMap { it.items }
    fun values(field: (IntakeItemEntity) -> String?): List<BigDecimal?> = items.map { item ->
        val grams = item.consumedGrams?.toBigDecimalOrNull()
        val per100 = field(item)?.toBigDecimalOrNull()
        if (grams == null || per100 == null) null else grams * per100 / BigDecimal(100)
    }
    val kcal = values { it.kcalPer100gSnapshot }
    val protein = values { it.proteinGPer100gSnapshot }
    val fat = values { it.fatGPer100gSnapshot }
    val carb = values { it.carbGPer100gSnapshot }
    fun sum(values: List<BigDecimal?>) = values.filterNotNull().fold(BigDecimal.ZERO, BigDecimal::add)
    return NutritionTotals(sum(kcal), sum(protein), sum(fat), sum(carb), kcal.count { it == null },
        protein.count { it == null }, fat.count { it == null }, carb.count { it == null })
}
