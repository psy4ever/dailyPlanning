package com.dayoudu.app.data

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DailyPlanDocumentTest {
    @Test fun readsFlattenedCopiedTemplateWithoutTreatingWorkHoursInHeadingAsPlan() {
        val text = "每日计划表 一、在职日时间安排（上班 08:30—18:00） ---- 07:30—08:00 起床 08:00—08:30 通勤 " +
            "二、自由日时间安排 ---- 09:00—09:30 散步 三、今日执行记录"
        val modes = DailyPlanCodec.decodeTextTemplate(text, LocalDate.of(2026, 10, 22), ZoneId.of("Asia/Shanghai"))
        assertEquals(listOf(450, 480), modes[0].second.items.map { it.startMinute })
        assertEquals("散步", modes[1].second.items.single().title)
    }

    @Test fun importsTwoSchedulesButLeavesOvernightSleepOutOfSameDayPlans() {
        val text = """每日计划表
            一、在职日时间安排（上班 08:30—18:00）
            ----------------------------------------
            07:30—08:00  起床、洗漱、早餐
            08:00—08:30  通勤
            23:00—07:30  睡眠
            二、自由日时间安排
            ----------------------------------------
            08:30—09:00  起床、洗漱、早餐
            23:30—08:30  睡眠
            三、今日执行记录
            实际起床：____:____
        """.trimIndent()
        val modes = DailyPlanCodec.decodeTextTemplate(text, LocalDate.of(2026, 10, 22), ZoneId.of("Asia/Shanghai"))
        assertEquals(listOf("在职日", "自由日"), modes.map { it.first })
        assertEquals(listOf("起床、洗漱、早餐", "通勤"), modes[0].second.items.map { it.title })
        assertEquals(1, modes[1].second.items.size)
        assertEquals(30, modes[1].second.items.single().durationMinutes)
        assertFalse(modes.flatMap { it.second.items }.any { it.title.contains("睡眠") })
    }
}
