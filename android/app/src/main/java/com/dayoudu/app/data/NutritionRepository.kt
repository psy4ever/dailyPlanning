package com.dayoudu.app.data

import androidx.room.withTransaction
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.Flow

data class FoodDraft(
    val name: String,
    val kcalPer100g: String,
    val preparedState: String,
    val sourceType: String,
    val brand: String = "",
    val proteinGPer100g: String = "",
    val fatGPer100g: String = "",
    val carbGPer100g: String = "",
    val sourceNote: String = "",
)

data class IntakeDraftItem(
    val itemId: String? = null,
    val foodId: String? = null,
    val foodVersion: Int? = null,
    val manualName: String? = null,
    val consumedGrams: String = "",
)

fun IntakeItemEntity.calculatedKcal(): BigDecimal? {
    val grams = consumedGrams?.toBigDecimalOrNull() ?: return null
    val kcal = kcalPer100gSnapshot?.toBigDecimalOrNull() ?: return null
    return kcal * grams / BigDecimal(100)
}

class NutritionRepository(private val database: NutritionDatabase) {
    fun observeDay(date: LocalDate, zone: ZoneId): Flow<List<IntakeWithItems>> {
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return database.intakeDao().observeDay(start, end)
    }

    suspend fun latestFoods(): List<FoodVersionEntity> = database.foodDao().latestFoods()
    suspend fun allLatestFoods(): List<FoodVersionEntity> = database.foodDao().allLatestFoods()

    suspend fun saveFood(foodId: String?, expectedVersion: Int?, draft: FoodDraft) {
        val name = draft.name.trim()
        require(name.isNotEmpty() && name.length <= 120) { "请填写 1–120 字的食物名称" }
        val kcal = requiredDecimal(draft.kcalPer100g, "每 100g 热量")
        require(draft.preparedState in setOf("RAW", "COOKED", "PACKAGED", "UNKNOWN")) { "请选择食物状态" }
        require(draft.sourceType in setOf("USER_LABEL", "USER_ESTIMATE")) { "请选择数据来源" }
        val protein = optionalDecimal(draft.proteinGPer100g, "蛋白质")
        val fat = optionalDecimal(draft.fatGPer100g, "脂肪")
        val carb = optionalDecimal(draft.carbGPer100g, "碳水")
        require(draft.sourceNote.length <= 300) { "来源说明不能超过 300 字" }
        database.withTransaction {
            val previous = foodId?.let { database.foodDao().latestVersion(it) }
            require(foodId == null || previous?.version == expectedVersion) { "食物版本已变化，请重新打开" }
            database.foodDao().insert(FoodVersionEntity(
                foodId ?: UUID.randomUUID().toString(), (previous?.version ?: 0) + 1,
                name, draft.preparedState, kcal, draft.sourceType, false,
                draft.brand.trim().takeIf { it.isNotEmpty() }, protein, fat, carb,
                draft.sourceNote.trim().takeIf { it.isNotEmpty() },
            ))
        }
    }

    suspend fun archiveFood(foodId: String, expectedVersion: Int) {
        database.withTransaction {
            val previous = database.foodDao().latestVersion(foodId) ?: throw IllegalArgumentException("食物不存在")
            require(previous.version == expectedVersion) { "食物版本已变化，请重新打开" }
            database.foodDao().insert(previous.copy(version = previous.version + 1, archived = true))
        }
    }

    suspend fun saveIntake(intakeId: String?, expectedVersion: Int?, mealType: String,
                           eatenAt: LocalDateTime, zone: ZoneId, note: String,
                           items: List<IntakeDraftItem>) {
        require(mealType in setOf("BREAKFAST", "LUNCH", "DINNER", "SNACK")) { "请选择餐次" }
        require(items.size in 1..30) { "一次摄入请填写 1–30 项食物" }
        require(note.length <= 500) { "备注不能超过 500 字" }
        val atMillis = eatenAt.atZone(zone).toInstant().toEpochMilli()
        database.withTransaction {
            val old = intakeId?.let { database.intakeDao().get(it) }
            require(intakeId == null || (old != null && old.version == expectedVersion && old.deletedAtMillis == null)) {
                "摄入记录已变化，请重新打开"
            }
            val id = intakeId ?: UUID.randomUUID().toString()
            val savedItems = items.map { draft ->
                val grams = optionalPositiveDecimal(draft.consumedGrams)
                if (draft.foodId != null) {
                    val version = draft.foodVersion ?: throw IllegalArgumentException("请选择食物版本")
                    val food = database.foodDao().version(draft.foodId, version)
                        ?: throw IllegalArgumentException("食物版本不存在，请重新选择")
                    IntakeItemEntity(draft.itemId ?: UUID.randomUUID().toString(), id, food.foodId, food.version,
                        food.name, grams, food.kcalPer100g, food.sourceType,
                        food.proteinGPer100g, food.fatGPer100g, food.carbGPer100g,
                        null, if (grams == null) "MISSING_AMOUNT" else "COMPLETE")
                } else {
                    val name = draft.manualName?.trim().orEmpty()
                    require(name.isNotEmpty() && name.length <= 120) { "请填写未知营养食物的名称" }
                    IntakeItemEntity(draft.itemId ?: UUID.randomUUID().toString(), id, null, null,
                        name, grams, null, null, null, null, null, name, "MISSING_FOOD")
                }
            }
            if (old == null) database.intakeDao().insert(IntakeEntity(id, atMillis, zone.id, mealType,
                note.trim().takeIf { it.isNotEmpty() }))
            else {
                database.intakeDao().update(old.copy(eatenAtMillis = atMillis, timezone = zone.id,
                    mealType = mealType, note = note.trim().takeIf { it.isNotEmpty() }, version = old.version + 1))
                database.intakeDao().deleteItems(id)
            }
            savedItems.forEach { database.intakeDao().insert(it) }
        }
    }

    suspend fun deleteIntake(intakeId: String, expectedVersion: Int) {
        database.withTransaction {
            val old = database.intakeDao().get(intakeId) ?: throw IllegalArgumentException("摄入记录不存在")
            require(old.version == expectedVersion && old.deletedAtMillis == null) { "摄入记录已变化，请重新打开" }
            database.intakeDao().update(old.copy(version = old.version + 1, deletedAtMillis = System.currentTimeMillis()))
        }
    }

    suspend fun restoreIntake(intakeId: String, expectedVersion: Int) {
        database.withTransaction {
            val old = database.intakeDao().get(intakeId) ?: throw IllegalArgumentException("摄入记录不存在")
            require(old.version == expectedVersion && old.deletedAtMillis != null) { "摄入记录已变化，无法撤销" }
            database.intakeDao().update(old.copy(version = old.version + 1, deletedAtMillis = null))
        }
    }

    private fun requiredDecimal(text: String, label: String): String {
        val decimal = text.trim().toBigDecimalOrNull()
        require(decimal != null && decimal >= BigDecimal.ZERO) { "$label 须为非负数" }
        return decimal.toPlainString()
    }

    private fun optionalDecimal(text: String, label: String): String? =
        if (text.isBlank()) null else requiredDecimal(text, label)

    private fun optionalPositiveDecimal(text: String): String? {
        if (text.isBlank()) return null
        val decimal = text.trim().toBigDecimalOrNull()
        require(decimal != null && decimal > BigDecimal.ZERO) { "食用量须大于 0，或留空待补" }
        return decimal.toPlainString()
    }
}
