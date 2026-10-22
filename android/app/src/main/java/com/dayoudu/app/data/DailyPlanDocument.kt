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
        val root = try { JSONObject(json) } catch (_: org.json.JSONException) {
            throw IllegalArgumentException("文件不是日计划 JSON；普通 TXT 请导入带“在职日时间安排”或“自由日时间安排”的模板")
        }
        require(root.getInt("schemaVersion") == SCHEMA_VERSION) { "不支持的日计划版本" }
        val rawItems = root.getJSONArray("items")
        val document = DailyPlanDocument(
            LocalDate.parse(root.getString("date")),
            ZoneId.of(root.getString("timezone")),
            (0 until rawItems.length()).map { index ->
                val item = rawItems.optJSONObject(index)
                    ?: throw IllegalArgumentException("第 ${index + 1} 项计划不是对象；请使用本应用导出的 JSON 文件")
                DailyPlanItem(item.getString("title"), item.getInt("startMinute"),
                    item.getInt("durationMinutes"), item.getBoolean("fixed"),
                    if (item.isNull("reminderMinutesBefore")) null else item.getInt("reminderMinutesBefore"),
                    item.getBoolean("reminderSilent"))
            },
        )
        validate(document)
        return document
    }

    /** Extract only timed plans from the user's two-mode plain-text template. */
    fun decodeTextTemplate(text: String, date: LocalDate, zone: ZoneId): List<Pair<String, DailyPlanDocument>> {
        require(text.length <= 512_000) { "日计划文件过大" }
        val headings = listOf("一、在职日时间安排" to "在职日", "二、自由日时间安排" to "自由日")
        val end = text.indexOf("三、今日执行记录").let { if (it < 0) text.length else it }
        val spans = headings.mapNotNull { (heading, label) -> text.indexOf(heading).takeIf { it >= 0 && it < end }?.let { Triple(it, heading.length, label) } }
            .sortedBy { it.first }
        require(spans.isNotEmpty()) { "TXT 中没有找到“在职日时间安排”或“自由日时间安排”；请选择对应模板" }
        val clock = Regex("""(?<!\d)([01]\d|2[0-3]):([0-5]\d)\s*[—–-]\s*([01]\d|2[0-3]):([0-5]\d)\s+""")
        return spans.mapIndexedNotNull { index, (start, length, label) ->
            val next = if (index + 1 < spans.size) spans[index + 1].first else end
            val section = text.substring(start + length, next)
            val divider = Regex("-{5,}").find(section)
            val body = if (divider == null) section else section.substring(divider.range.last + 1)
            val matches = clock.findAll(body).toList()
            val items = matches.mapIndexedNotNull { itemIndex, match ->
                val from = match.groupValues[1].toInt() * 60 + match.groupValues[2].toInt()
                val to = match.groupValues[3].toInt() * 60 + match.groupValues[4].toInt()
                // Overnight sleep belongs to the sleep module, not a same-day plan.
                if (to <= from) return@mapIndexedNotNull null
                val nextStart = matches.getOrNull(itemIndex + 1)?.range?.first ?: body.length
                val title = body.substring(match.range.last + 1, nextStart).lineSequence().first().orEmpty()
                    .trim().trim('-', '—', '–', ' ').take(120)
                if (title.isBlank()) return@mapIndexedNotNull null
                DailyPlanItem(title, from, to - from, false, null, false)
            }
            if (items.isEmpty()) null else (label to DailyPlanDocument(date, zone, items.also { require(it.size <= 100) { "每天最多导入 100 项计划" } }))
        }.also { require(it.isNotEmpty()) { "TXT 中没有可导入的同日时间安排" } }
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
