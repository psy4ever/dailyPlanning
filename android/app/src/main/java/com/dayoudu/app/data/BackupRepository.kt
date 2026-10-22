package com.dayoudu.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class BackupRepository(private val context: Context, private val database: NutritionDatabase) {
    private val tables = listOf(
        "food_versions", "intakes", "intake_items", "plans", "plan_revisions",
        "time_categories", "projects", "activities", "activity_segments", "activity_tags",
        "sleep_logs", "value_model_versions",
        "value_dimensions", "activity_evaluations", "dimension_ratings", "local_users", "ledger_entries", "nutrition_days",
    )

    suspend fun export(uri: Uri): Int = withContext(Dispatchers.IO) {
        val payload = database.runInTransaction<JSONObject> {
            val sqlite = database.openHelper.readableDatabase
            val all = JSONObject()
            var count = 0
            tables.forEach { table ->
                val rows = JSONArray()
                sqlite.query("SELECT * FROM `$table`").use { cursor ->
                    while (cursor.moveToNext()) {
                        val row = JSONObject()
                        for (column in 0 until cursor.columnCount) {
                            row.put(cursor.getColumnName(column), cursorValue(cursor, column))
                        }
                        rows.put(row)
                        count++
                    }
                }
                all.put(table, rows)
            }
            JSONObject().put("schemaVersion", 11).put("exportedAt", Instant.now().toString())
                .put("tables", all).put("recordCount", count)
        }
        context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use {
            it.write(payload.toString())
        } ?: throw IllegalStateException("无法打开导出文件")
        payload.getInt("recordCount")
    }

    suspend fun restore(uri: Uri): Int = withContext(Dispatchers.IO) {
        database.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type='table' AND name='cloud_meta'").use { table ->
            if (table.moveToFirst()) database.openHelper.readableDatabase.query("SELECT value FROM cloud_meta WHERE key='owner'").use {
                require(!it.moveToFirst()) { "已绑定云端的安装不能整库替换；请在独立安装中恢复备份，避免覆盖已同步数据" }
            }
        }
        val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(8192)
            val output = ByteArrayOutputStream()
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                require(output.size() + read <= 64 * 1024 * 1024) { "备份文件超过 64 MB，暂不支持导入" }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        } ?: throw IllegalArgumentException("无法读取备份文件")
        val text = bytes.toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        require(text.startsWith("{")) { "请选择日有度导出的 JSON 备份；普通 TXT 日程请到“今天 → 更多 → 导入日计划”" }
        val root = try { JSONObject(text) } catch (_: org.json.JSONException) {
            throw IllegalArgumentException("备份 JSON 格式无效；请选择日有度导出的完整备份文件")
        }
        val backupVersion = root.optInt("schemaVersion", -1)
        require(backupVersion in 8..11) { "备份版本不兼容" }
        val content = root.optJSONObject("tables")
            ?: throw IllegalArgumentException("文件不是日有度整库备份；日计划文件请到“今天 → 更多”导入")
        val legacyNewTables = setOf("local_users", "ledger_entries")
        val expectedTables = tables.toSet() -
            (if (backupVersion < 10) legacyNewTables else emptySet()) -
            (if (backupVersion < 11) setOf("nutrition_days") else emptySet())
        require(content.keys().asSequence().toSet() == expectedTables) { "备份表清单不完整" }
        var count = 0
        database.runInTransaction {
            val sqlite = database.openHelper.writableDatabase
            sqlite.query("SELECT name FROM sqlite_master WHERE type='table' AND name='cloud_meta'").use { table ->
                if (table.moveToFirst()) sqlite.query("SELECT value FROM cloud_meta WHERE key='owner'").use {
                    require(!it.moveToFirst()) { "本机已经绑定云端，不能整库替换" }
                }
            }
            tables.asReversed().forEach { sqlite.delete(it, null, null) }
            tables.forEach { table ->
                val expectedColumns = mutableSetOf<String>()
                sqlite.query("PRAGMA table_info(`$table`)").use { cursor ->
                    while (cursor.moveToNext()) expectedColumns.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                val rows = if (content.has(table)) content.getJSONArray(table) else JSONArray()
                for (index in 0 until rows.length()) {
                    val row = rows.getJSONObject(index)
                    val rowColumns = row.keys().asSequence().toSet()
                    val legacyPlan = backupVersion == 8 && table == "plans" &&
                        rowColumns + "deletedAtMillis" == expectedColumns
                    val legacyLedger = backupVersion < 11 && table == "ledger_entries" &&
                        rowColumns + setOf("category", "note") == expectedColumns
                    require(rowColumns == expectedColumns || legacyPlan || legacyLedger) { "$table 字段不匹配" }
                    val values = ContentValues()
                    expectedColumns.forEach { column ->
                        if ((legacyPlan && column == "deletedAtMillis") ||
                            (legacyLedger && column in setOf("category", "note"))) {
                            values.putNull(column)
                            return@forEach
                        }
                        when (val value = row.get(column)) {
                            JSONObject.NULL -> values.putNull(column)
                            is String -> values.put(column, value)
                            is Int -> values.put(column, value)
                            is Long -> values.put(column, value)
                            is Double -> values.put(column, value)
                            else -> throw IllegalArgumentException("$table 含无效字段类型")
                        }
                    }
                    require(sqlite.insert(table, SQLiteDatabase.CONFLICT_ABORT, values) >= 0) { "$table 写入失败" }
                    count++
                }
            }
            val broken = listOf(
                "SELECT COUNT(*) FROM intake_items i LEFT JOIN intakes e ON e.id=i.intakeId WHERE e.id IS NULL",
                "SELECT COUNT(*) FROM intake_items i LEFT JOIN food_versions f ON f.foodId=i.foodId AND f.version=i.foodVersion WHERE i.foodId IS NOT NULL AND f.foodId IS NULL",
                "SELECT COUNT(*) FROM plan_revisions r LEFT JOIN plans p ON p.id=r.planId WHERE p.id IS NULL",
                "SELECT COUNT(*) FROM activities a LEFT JOIN plans p ON p.id=a.planId WHERE a.planId IS NOT NULL AND p.id IS NULL",
                "SELECT COUNT(*) FROM activities a LEFT JOIN time_categories c ON c.id=a.categoryId WHERE a.categoryId IS NOT NULL AND c.id IS NULL",
                "SELECT COUNT(*) FROM activities a LEFT JOIN projects p ON p.id=a.projectId WHERE a.projectId IS NOT NULL AND p.id IS NULL",
                "SELECT COUNT(*) FROM activity_segments s LEFT JOIN activities a ON a.id=s.activityId WHERE a.id IS NULL",
                "SELECT COUNT(*) FROM activity_tags t LEFT JOIN activities a ON a.id=t.activityId WHERE a.id IS NULL",
                "SELECT COUNT(*) FROM value_dimensions d LEFT JOIN value_model_versions m ON m.modelId=d.modelId AND m.version=d.modelVersion WHERE m.modelId IS NULL",
                "SELECT COUNT(*) FROM activity_evaluations e LEFT JOIN activities a ON a.id=e.activityId WHERE a.id IS NULL",
                "SELECT COUNT(*) FROM dimension_ratings r LEFT JOIN activity_evaluations e ON e.activityId=r.activityId AND e.modelId=r.modelId AND e.modelVersion=r.modelVersion WHERE e.activityId IS NULL",
                "SELECT COUNT(*) FROM dimension_ratings r LEFT JOIN value_dimensions d ON d.modelId=r.modelId AND d.modelVersion=r.modelVersion AND d.dimensionId=r.dimensionId WHERE d.dimensionId IS NULL",
            ).any { sql -> sqlite.query(sql).use { cursor -> cursor.moveToFirst() && cursor.getLong(0) > 0 } }
            require(!broken) { "备份中的关联记录不完整，原数据已保留" }
            // Before first binding, a file restore replaces the local dataset. Discard dirty
            // IDs of removed rows; first binding will seed only the restored records.
            sqlite.query("SELECT name FROM sqlite_master WHERE type='table' AND name='cloud_dirty'").use { table ->
                if (table.moveToFirst()) listOf("cloud_dirty","cloud_outbox","cloud_versions","cloud_conflicts").forEach {
                    sqlite.execSQL("DELETE FROM $it")
                }
            }
        }
        val scheduler = ReminderScheduler(context)
        database.planDao().futureReminders(System.currentTimeMillis()).forEach { scheduler.sync(it) }
        count
    }

    private fun cursorValue(cursor: Cursor, column: Int): Any = when (cursor.getType(column)) {
        Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(column)
        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(column)
        Cursor.FIELD_TYPE_STRING -> cursor.getString(column)
        else -> throw IllegalStateException("备份不支持二进制字段")
    }
}
