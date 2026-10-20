package com.dayoudu.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/** Portable, versioned plan intent. Execution state and actual activity stay in Room. */
data class DailyPlanDocument(
    val date: LocalDate,
    val timezone: ZoneId,
    val items: List<DailyPlanItem>,
)

data class DailyPlanItem(
    val title: String,
    val startMinute: Int,
    val durationMinutes: Int,
    val fixed: Boolean,
    val reminderMinutesBefore: Int?,
    val reminderSilent: Boolean,
)

object DailyPlanCodec {
    const val SCHEMA_VERSION = 1

    fun encode(document: DailyPlanDocument): String {
        validate(document)
        val items = JSONArray()
        document.items.forEach { item ->
            items.put(JSONObject()
                .put("title", item.title)
                .put("startMinute", item.startMinute)
                .put("durationMinutes", item.durationMinutes)
                .put("fixed", item.fixed)
                .put("reminderMinutesBefore", item.reminderMinutesBefore ?: JSONObject.NULL)
                .put("reminderSilent", item.reminderSilent))
        }
        return JSONObject().put("schemaVersion", SCHEMA_VERSION)
            .put("date", document.date.toString())
            .put("timezone", document.timezone.id)
            .put("items", items).toString(2)
    }

    fun decode(json: String): DailyPlanDocument {
        require(json.length <= 512_000) { "日计划文件过大" }
        val root = JSONObject(json)
        require(root.getInt("schemaVersion") == SCHEMA_VERSION) { "不支持的日计划版本" }
        val rawItems = root.getJSONArray("items")
        val document = DailyPlanDocument(
            LocalDate.parse(root.getString("date")),
            ZoneId.of(root.getString("timezone")),
            (0 until rawItems.length()).map { index ->
                val item = rawItems.getJSONObject(index)
                DailyPlanItem(item.getString("title"), item.getInt("startMinute"),
                    item.getInt("durationMinutes"), item.getBoolean("fixed"),
                    if (item.isNull("reminderMinutesBefore")) null else item.getInt("reminderMinutesBefore"),
                    item.getBoolean("reminderSilent"))
            },
        )
        validate(document)
        return document
    }

    fun validate(document: DailyPlanDocument) {
        require(document.items.size <= 100) { "每天最多导入 100 项计划" }
        document.items.forEach { item ->
            require(item.title.trim().isNotEmpty() && item.title.length <= 120) { "计划标题须为 1–120 字" }
            require(item.startMinute in 0..1439) { "开始时间超出当天范围" }
            require(item.durationMinutes in 1..(1440 - item.startMinute)) { "每日计划必须在当天结束" }
            require(item.reminderMinutesBefore == null || item.reminderMinutesBefore in 0..10_080) { "提醒提前时间无效" }
        }
    }
}
