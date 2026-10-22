package com.dayoudu.app.data

import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class NutritionTotalsTest {
    @Test fun totalsUseRecordedSnapshotsAndKeepMissingNutrientsVisible() {
        val intake=IntakeEntity("meal",0,"Asia/Shanghai","BREAKFAST")
        val bread=IntakeItemEntity("bread","meal","food",1,"全麦面包","40","512.5","USER_LABEL",
            proteinGPer100gSnapshot="20",fatGPer100gSnapshot="7.5",carbGPer100gSnapshot="95")
        val unknown=IntakeItemEntity("unknown","meal",null,null,"手动食物","100",null,null,manualName="手动食物")
        val result=nutritionTotals(listOf(IntakeWithItems(intake,listOf(bread,unknown))))
        assertEquals(0,BigDecimal("205").compareTo(result.kcal))
        assertEquals(0,BigDecimal("8").compareTo(result.protein))
        assertEquals(0,BigDecimal("3").compareTo(result.fat))
        assertEquals(0,BigDecimal("38").compareTo(result.carb))
        assertEquals(1,result.missingKcal)
        assertEquals(1,result.missingProtein)
    }

    @Test fun unknownWeightDoesNotBecomeZeroOrCompleteNutrition() {
        val item=IntakeItemEntity("item","meal","food",1,"牛奶",null,"60","USER_LABEL")
        val result=nutritionTotals(listOf(IntakeWithItems(IntakeEntity("meal",0,"Asia/Shanghai","SNACK"),listOf(item))))
        assertEquals(BigDecimal.ZERO,result.kcal)
        assertEquals(1,result.missingKcal)
        assertEquals(1,result.missingFat)
        val empty=nutritionTotals(emptyList())
        assertEquals(0,empty.missingKcal)
    }
}
