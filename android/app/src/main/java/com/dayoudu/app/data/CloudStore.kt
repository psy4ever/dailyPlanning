package com.dayoudu.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Auxiliary sync metadata is versioned separately from business Room tables. */
class CloudStore(private val database: NutritionDatabase, context: Context) {
    private val contract = JSONObject(context.assets.open("sync-contract.json").bufferedReader().use { it.readText() })
    private val specs = contract.getJSONObject("kinds")
    private val sql get() = database.openHelper.writableDatabase
    fun <T> transaction(block: () -> T): T = database.runInTransaction<T> { block() }
    fun meta(key: String): String? = sql.query("SELECT value FROM cloud_meta WHERE key=?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }
    fun setMeta(key: String, value: String) { sql.execSQL("INSERT OR REPLACE INTO cloud_meta(key,value) VALUES(?,?)", arrayOf(key,value)) }
    fun count(table: String): Int = sql.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }
    fun localContract() = contract

    fun bind(owner: String, endpoint: String) = transaction {
        val existing = meta("owner")
        require(existing == null || existing == owner) { "本机已绑定其他账户，请使用独立安装以免混合数据" }
        if (existing == null) {
            specs.keys().forEach { kind ->
                val spec = specs.getJSONObject(kind)
                sql.query("SELECT DISTINCT `${spec.getString("key")}` FROM `${spec.getString("root")}`").use { cursor ->
                    while (cursor.moveToNext()) sql.execSQL("INSERT OR IGNORE INTO cloud_dirty(kind,id,revision) VALUES(?,?,1)",arrayOf(kind,cursor.getString(0)))
                }
            }
            setMeta("owner",owner); setMeta("endpoint",endpoint); setMeta("cursor","0")
        } else setMeta("endpoint",endpoint) // Same authenticated account may move from a tunnel to HTTPS.
    }
    private fun version(kind: String,id: String): Long = sql.query("SELECT version FROM cloud_versions WHERE kind=? AND id=?",arrayOf(kind,id)).use { if(it.moveToFirst()) it.getLong(0) else 0 }
    private fun setVersion(kind: String,id: String,version: Long) { sql.execSQL("INSERT OR REPLACE INTO cloud_versions(kind,id,version) VALUES(?,?,?)",arrayOf(kind,id,version)) }
    private fun rows(table: String,key: String,id: String,order: String): JSONArray {
        val result=JSONArray()
        sql.query("SELECT * FROM `$table` WHERE `$key`=? ORDER BY $order",arrayOf(id)).use { cursor ->
            while(cursor.moveToNext()) {
                val row=JSONObject()
                for(i in 0 until cursor.columnCount) row.put(cursor.getColumnName(i),when(cursor.getType(i)) {
                    Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                    Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                    Cursor.FIELD_TYPE_STRING -> cursor.getString(i)
                    else -> error("同步字段类型无效")
                })
                result.put(row)
            }
        }
        return result
    }
    private fun snapshot(kind: String,id: String): JSONObject {
        val spec=specs.getJSONObject(kind); val table=spec.getString("root")
        val data=JSONObject().put(table,rows(table,spec.getString("key"),id,if(kind=="food") "version" else "id"))
        val children=spec.getJSONObject("children")
        children.keys().forEach { child -> data.put(child,rows(child,children.getString(child),id,if(child=="activity_tags") "name" else "id")) }
        require(data.getJSONArray(table).length()>0) { "发现硬删除记录，请先核对本地恢复操作" }
        return JSONObject().put("tables",data)
    }
    data class Pending(val kind: String,val id: String,val revision: Long,val request: JSONObject)
    fun pending(): List<Pending> = transaction {
        // Dependencies are uploaded before their referring aggregates.
        val order="CASE d.kind WHEN 'category' THEN 0 WHEN 'project' THEN 1 WHEN 'plan' THEN 2 WHEN 'food' THEN 3 WHEN 'ledger' THEN 4 WHEN 'activity' THEN 5 ELSE 6 END"
        val keys=mutableListOf<Triple<String,String,Long>>()
        sql.query("SELECT d.kind,d.id,d.revision FROM cloud_dirty d LEFT JOIN cloud_conflicts c ON c.kind=d.kind AND c.id=d.id WHERE c.id IS NULL ORDER BY $order,d.id LIMIT 50").use { while(it.moveToNext()) keys.add(Triple(it.getString(0),it.getString(1),it.getLong(2))) }
        keys.map { (kind,id,revision) ->
            var request: JSONObject?=null; var savedRevision=revision
            sql.query("SELECT request,revision FROM cloud_outbox WHERE kind=? AND id=?",arrayOf(kind,id)).use { if(it.moveToFirst()){request=JSONObject(it.getString(0));savedRevision=it.getLong(1)} }
            if(request==null) {
                request=JSONObject().put("operationId",UUID.randomUUID().toString()).put("kind",kind).put("id",id)
                    .put("expectedVersion",version(kind,id)).put("payload",snapshot(kind,id))
                sql.execSQL("INSERT INTO cloud_outbox(kind,id,revision,request) VALUES(?,?,?,?)",arrayOf(kind,id,revision,request.toString()))
            }
            Pending(kind,id,savedRevision,request!!)
        }
    }
    fun acknowledge(pending: Pending,response: JSONObject) = transaction {
        setVersion(pending.kind,pending.id,response.getLong("serverVersion"))
        sql.execSQL("DELETE FROM cloud_outbox WHERE kind=? AND id=?",arrayOf(pending.kind,pending.id))
        sql.execSQL("DELETE FROM cloud_dirty WHERE kind=? AND id=? AND revision=?",arrayOf(pending.kind,pending.id,pending.revision))
    }
    fun conflict(kind: String,id: String,remote: JSONObject,message: String) {
        sql.execSQL("INSERT OR REPLACE INTO cloud_conflicts(kind,id,remote,message) VALUES(?,?,?,?)",arrayOf(kind,id,remote.toString(),message))
    }
    data class Conflict(val kind: String,val id: String,val message: String,val hasRemote: Boolean,val localPreview: String,val remotePreview: String)
    fun conflicts(): List<Conflict> {
        val result=mutableListOf<Conflict>()
        sql.query("SELECT kind,id,message,remote FROM cloud_conflicts ORDER BY kind,id").use {
            while(it.moveToNext()) {
                val kind=it.getString(0);val id=it.getString(1);val remote=JSONObject(it.getString(3))
                val local=runCatching { snapshot(kind,id).toString(2) }.getOrDefault("本机记录不存在")
                result.add(Conflict(kind,id,it.getString(2),remote.has("serverVersion"),preview(local),preview(remote.optJSONObject("payload")?.toString(2) ?: "没有可选云端版本")))
            }
        }
        return result
    }
    private fun preview(text: String)=if(text.length>8000)text.take(8000)+"\n…较长记录，请先导出备份查看完整内容" else text
    private fun dirty(kind: String,id: String): Boolean = sql.query("SELECT 1 FROM cloud_dirty WHERE kind=? AND id=?",arrayOf(kind,id)).use { it.moveToFirst() }
    fun acceptChange(change: JSONObject) = transaction {
        val kind=change.getString("kind"); val id=change.getString("id")
        if(kind=="category"||kind=="project") {
            var alias: String?=null
            sql.query("SELECT id,remote FROM cloud_conflicts WHERE kind=?",arrayOf(kind)).use { cursor ->
                while(cursor.moveToNext())if(JSONObject(cursor.getString(1)).optString("id")==id&&cursor.getString(0)!=id)alias=cursor.getString(0)
            }
            if(alias!=null){conflict(kind,alias!!,change,"云端已有同名分类或项目，请保留云端以合并关联");setMeta("cursor",change.getLong("seq").toString());return@transaction}
        }
        if(change.getLong("serverVersion")>version(kind,id)) {
            if(dirty(kind,id)) conflict(kind,id,change,"另一设备修改了相同记录，请选择保留哪一份")
            else {
                sql.execSQL("SAVEPOINT cloud_apply")
                try { apply(change);sql.execSQL("RELEASE cloud_apply") }
                catch (e: IllegalArgumentException) {
                    sql.execSQL("ROLLBACK TO cloud_apply");sql.execSQL("RELEASE cloud_apply")
                    conflict(kind,id,change,e.message ?: "本地关联校验失败")
                }
            }
        }
        setMeta("cursor",change.getLong("seq").toString())
    }
    fun resolve(kind: String,id: String,useCloud: Boolean) = transaction {
        val remote=sql.query("SELECT remote FROM cloud_conflicts WHERE kind=? AND id=?",arrayOf(kind,id)).use { require(it.moveToFirst());JSONObject(it.getString(0)) }
        require(remote.has("serverVersion")) { "请先修正此记录，再重试同步：云端没有可选版本" }
        require(useCloud || kind!="food") { "食物历史版本只读，请保留云端版本，再创建新的营养版本" }
        if(useCloud) {
            if(remote.getString("id")!=id) {
                require(kind=="category"||kind=="project") { "云端记录 ID 不匹配" }
                val column=if(kind=="category") "categoryId" else "projectId"
                val affected=mutableListOf<String>()
                sql.query("SELECT id FROM activities WHERE `$column`=?",arrayOf(id)).use { while(it.moveToNext())affected.add(it.getString(0)) }
                affected.forEach { activityId ->
                    val hasVersionConflict=sql.query("SELECT remote FROM cloud_conflicts WHERE kind='activity' AND id=?",arrayOf(activityId)).use {
                        it.moveToFirst()&&JSONObject(it.getString(0)).has("serverVersion")
                    }
                    if(!hasVersionConflict) {
                        // The confirmed merge is a new local intent. Re-capture references;
                        // server CAS still protects any previously unacknowledged write.
                        sql.execSQL("DELETE FROM cloud_outbox WHERE kind='activity' AND id=?",arrayOf(activityId))
                        sql.execSQL("DELETE FROM cloud_conflicts WHERE kind='activity' AND id=?",arrayOf(activityId))
                    }
                }
                sql.execSQL("UPDATE activities SET `$column`=? WHERE `$column`=?",arrayOf(remote.getString("id"),id))
                sql.execSQL("DELETE FROM `${specs.getJSONObject(kind).getString("root")}` WHERE id=?",arrayOf(id))
            }
            apply(remote);sql.execSQL("DELETE FROM cloud_dirty WHERE kind=? AND id=?",arrayOf(kind,id))
        }
        else { require(remote.getString("id")==id) { "同名记录须合并云端版本，或先在本机改名后重试" };setVersion(kind,id,remote.getLong("serverVersion")) }
        sql.execSQL("DELETE FROM cloud_outbox WHERE kind=? AND id=?",arrayOf(kind,id))
        sql.execSQL("DELETE FROM cloud_conflicts WHERE kind=? AND id=?",arrayOf(kind,id))
    }
    fun retry(kind: String,id: String) = transaction {
        // Rejected (not committed) operations can be recreated after the user edits the data.
        sql.execSQL("DELETE FROM cloud_conflicts WHERE kind=? AND id=?",arrayOf(kind,id))
        sql.execSQL("DELETE FROM cloud_outbox WHERE kind=? AND id=?",arrayOf(kind,id))
    }
    private fun apply(change: JSONObject) {
        val kind=change.getString("kind");val id=change.getString("id")
        require(specs.has(kind)) { "云端资源类型不兼容" }
        val spec=specs.getJSONObject(kind);val root=spec.getString("root");val data=change.getJSONObject("payload").getJSONObject("tables")
        val expected=spec.getJSONObject("children").keys().asSequence().toSet()+root
        require(data.keys().asSequence().toSet()==expected) { "云端表集合不兼容" }
        if(kind=="category"||kind=="project")sql.query("SELECT id FROM `$root` WHERE name=? AND id<>?",arrayOf(data.getJSONArray(root).getJSONObject(0).getString("name"),id)).use {
            require(!it.moveToFirst()) { "云端分类或项目与本机同名，请先处理合并冲突" }
        }
        if(kind=="activity" && data.getJSONArray(root).getJSONObject(0).isNull("deletedAtMillis")) {
            val segments=data.getJSONArray("activity_segments")
            for(i in 0 until segments.length()) {
                val s=segments.getJSONObject(i);val start=s.getLong("startMillis");val end=if(s.isNull("endMillis")) Long.MAX_VALUE else s.getLong("endMillis")
                sql.query("SELECT 1 FROM sleep_logs WHERE onsetMillis<? AND wakeMillis>? LIMIT 1",arrayOf(end,start)).use { require(!it.moveToFirst()) { "云端活动与本机睡眠重叠，请核实后重试" } }
                sql.query("SELECT 1 FROM activity_segments s JOIN activities a ON a.id=s.activityId WHERE a.id<>? AND a.deletedAtMillis IS NULL AND s.startMillis<? AND COALESCE(s.endMillis,9223372036854775807)>? LIMIT 1",arrayOf(id,end,start)).use { require(!it.moveToFirst()) { "云端活动与本机实际活动重叠，请核实后重试" } }
            }
        }
        // Validation above happens before writes. Exceptions during writes roll back the caller transaction.
        setMeta("applying","1")
        try {
            val children=spec.getJSONObject("children")
            children.keys().forEach { table -> sql.execSQL("DELETE FROM `$table` WHERE `${children.getString(table)}`=?",arrayOf(id)) }
            sql.execSQL("DELETE FROM `$root` WHERE `${spec.getString("key")}`=?",arrayOf(id))
            (listOf(root)+children.keys().asSequence().toList()).forEach { table ->
                val rows=data.getJSONArray(table)
                for(index in 0 until rows.length()) {
                    val row=rows.getJSONObject(index);val fields=contract.getJSONObject("tables").getJSONObject(table)
                    require(row.keys().asSequence().toSet()==fields.keys().asSequence().toSet()) { "云端字段集合不兼容" }
                    val values=ContentValues()
                    row.keys().forEach { key -> when(val value=row.get(key)) {
                        JSONObject.NULL -> values.putNull(key)
                        is String -> values.put(key,value)
                        is Number -> values.put(key,value.toLong())
                        else -> error("云端字段类型无效")
                    } }
                    sql.insert(table,SQLiteDatabase.CONFLICT_ABORT,values)
                }
            }
            setVersion(kind,id,change.getLong("serverVersion"))
        } finally { setMeta("applying","0") }
    }
    companion object {
        fun install(sql: SupportSQLiteDatabase,context: Context) {
            sql.execSQL("CREATE TABLE IF NOT EXISTS cloud_meta (`key` TEXT PRIMARY KEY NOT NULL,value TEXT NOT NULL)")
            sql.execSQL("INSERT OR IGNORE INTO cloud_meta(key,value) VALUES('protocol','1')")
            sql.query("SELECT value FROM cloud_meta WHERE key='protocol'").use { require(it.moveToFirst()&&it.getString(0)=="1") { "同步存储版本不兼容" } }
            sql.execSQL("INSERT OR IGNORE INTO cloud_meta(key,value) VALUES('applying','0')")
            sql.execSQL("CREATE TABLE IF NOT EXISTS cloud_dirty(kind TEXT NOT NULL,id TEXT NOT NULL,revision INTEGER NOT NULL,PRIMARY KEY(kind,id))")
            sql.execSQL("CREATE TABLE IF NOT EXISTS cloud_outbox(kind TEXT NOT NULL,id TEXT NOT NULL,revision INTEGER NOT NULL,request TEXT NOT NULL,PRIMARY KEY(kind,id))")
            sql.execSQL("CREATE TABLE IF NOT EXISTS cloud_versions(kind TEXT NOT NULL,id TEXT NOT NULL,version INTEGER NOT NULL,PRIMARY KEY(kind,id))")
            sql.execSQL("CREATE TABLE IF NOT EXISTS cloud_conflicts(kind TEXT NOT NULL,id TEXT NOT NULL,remote TEXT NOT NULL,message TEXT NOT NULL,PRIMARY KEY(kind,id))")
            val kinds=JSONObject(context.assets.open("sync-contract.json").bufferedReader().use { it.readText() }).getJSONObject("kinds")
            kinds.keys().forEach { kind ->
                val spec=kinds.getJSONObject(kind)
                val tables=mutableMapOf(spec.getString("root") to spec.getString("key"))
                val children=spec.getJSONObject("children");children.keys().forEach { tables[it]=children.getString(it) }
                tables.forEach { (table,key) -> listOf("INSERT","UPDATE","DELETE").forEach { event ->
                    val prefix=if(event=="DELETE") "OLD" else "NEW"
                    sql.execSQL("DROP TRIGGER IF EXISTS cloud_${table}_${event.lowercase()}")
                    sql.execSQL("""CREATE TRIGGER cloud_${table}_${event.lowercase()} AFTER $event ON `$table`
                        WHEN (SELECT value FROM cloud_meta WHERE key='applying')='0' BEGIN
                        INSERT INTO cloud_dirty(kind,id,revision) SELECT '$kind',$prefix.`$key`,0
                        WHERE NOT EXISTS(SELECT 1 FROM cloud_dirty WHERE kind='$kind' AND id=$prefix.`$key`);
                        UPDATE cloud_dirty SET revision=revision+1 WHERE kind='$kind' AND id=$prefix.`$key`;
                        END""")
                } }
            }
        }
    }
}
