package com.dayoudu.app.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordMigrationTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun version10MigrationPreservesLedgerAndOldBackupCanRestore() = runBlocking {
        val name="record-migration-${UUID.randomUUID()}.db"
        val schema=JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
            .open("com.dayoudu.app.data.NutritionDatabase/10.json").bufferedReader().use {it.readText()}).getJSONObject("database")
        val entities=schema.getJSONArray("entities")
        val tables=JSONObject()
        val id=UUID.randomUUID().toString()
        val oldLedger=JSONObject().put("id",id).put("occurredAtMillis",1700000000000L).put("timezone","Asia/Shanghai")
            .put("type","EXPENSE").put("source","旧来源's 午餐").put("amount","85.00").put("version",1).put("deletedAtMillis",JSONObject.NULL)
        context.openOrCreateDatabase(name,Context.MODE_PRIVATE,null).use {sqlite->
            for(index in 0 until entities.length()) {
                val entity=entities.getJSONObject(index)
                val table=entity.getString("tableName")
                sqlite.execSQL(entity.getString("createSql").replace("\u0024{TABLE_NAME}",table))
                val indices=entity.optJSONArray("indices") ?: JSONArray()
                for(i in 0 until indices.length()) sqlite.execSQL(indices.getJSONObject(i).getString("createSql").replace("\u0024{TABLE_NAME}",table))
                tables.put(table,JSONArray())
            }
            val setup=schema.getJSONArray("setupQueries")
            for(i in 0 until setup.length()) sqlite.execSQL(setup.getString(i))
            sqlite.execSQL("INSERT INTO ledger_entries(id,occurredAtMillis,timezone,type,source,amount,version) VALUES(?,?,?,?,?,?,?)",
                arrayOf(id,1700000000000L,"Asia/Shanghai","EXPENSE","旧来源's 午餐","85.00",1))
            sqlite.version=10
        }
        tables.getJSONArray("ledger_entries").put(oldLedger)
        val backup=File(context.cacheDir,"legacy-record-${UUID.randomUUID()}.json")
        var db: NutritionDatabase?=null
        try {
            db=Room.databaseBuilder(context,NutritionDatabase::class.java,name).addMigrations(NutritionDatabase.MIGRATION_10_11).build()
            val migrated=db.ledgerDao().get(id)!!
            assertEquals("旧来源's 午餐",migrated.source)
            assertNull(migrated.category);assertNull(migrated.note)
            val date=LocalDate.of(2026,10,22);val zone=ZoneId.of("Asia/Shanghai")
            val nutrition=NutritionRepository(db)
            nutrition.saveNutritionDay(date,zone,null,"1570","230","249","72","49")
            assertEquals("1570",nutrition.observeNutritionDay(date,zone).first()!!.budgetKcal)
            val failure=runCatching {nutrition.saveNutritionDay(date,zone,null,"1600","230","249","72","49")}.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            backup.writeText(JSONObject().put("schemaVersion",10).put("tables",tables).toString())
            BackupRepository(context,db).restore(Uri.fromFile(backup))
            assertEquals("85.00",db.ledgerDao().get(id)!!.amount)
            assertNull(nutrition.observeNutritionDay(date,zone).first())
            LedgerRepository(db).save(id,1,java.time.LocalDateTime.of(2026,10,22,12,30),zone,"EXPENSE","餐饮","85.00","FOOD","午餐")
            nutrition.saveNutritionDay(date,zone,null,"1570","230","249","72","49")
            val exported=File(context.cacheDir,"new-record-${UUID.randomUUID()}.json")
            try {
                BackupRepository(context,db).export(Uri.fromFile(exported))
                BackupRepository(context,db).restore(Uri.fromFile(exported))
                assertEquals("FOOD",db.ledgerDao().get(id)!!.category)
                assertEquals("午餐",db.ledgerDao().get(id)!!.note)
                assertEquals("1570",nutrition.observeNutritionDay(date,zone).first()!!.budgetKcal)
            } finally {exported.delete()}
        } finally {db?.close();context.deleteDatabase(name);backup.delete()}
    }
}
