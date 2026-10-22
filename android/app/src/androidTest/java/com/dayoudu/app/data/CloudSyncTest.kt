package com.dayoudu.app.data

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/** Uses isolated databases and a loopback test server. Never opens the user's database. */
@RunWith(AndroidJUnit4::class)
class CloudSyncTest {
    private val context: Context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val opened=mutableListOf<Pair<String,NutritionDatabase>>()
    private fun database(): NutritionDatabase {
        val name="cloud-test-${UUID.randomUUID()}.db"
        val result=Room.databaseBuilder(context,NutritionDatabase::class.java,name).addCallback(object: RoomDatabase.Callback(){
            override fun onOpen(db: SupportSQLiteDatabase){CloudStore.install(db,context)}
        }).build()
        opened.add(name to result);return result
    }
    @After fun cleanup(){opened.forEach { (name,db)->db.close();context.deleteDatabase(name) }}
    private val zone=ZoneId.of("Asia/Shanghai")
    private val at=LocalDateTime.of(2026,10,20,12,0)
    @Test fun sameNameCategoryMergeRequeuesActivityWithCanonicalId() = runBlocking {
        val db=database();val store=CloudStore(db,context)
        PlanningRepository(db,context).addManual("合并关联验收","同名类别",at,at.plusHours(1),zone)
        val original=store.pending().first { it.kind=="category" };val canonical=UUID.randomUUID().toString()
        val payload=JSONObject(original.request.getJSONObject("payload").toString())
        payload.getJSONObject("tables").getJSONArray("time_categories").getJSONObject(0).put("id",canonical)
        val remote=JSONObject().put("kind","category").put("id",canonical).put("serverVersion",1).put("payload",payload)
        store.conflict("category",original.id,remote,"同名类别")
        store.resolve("category",original.id,true)
        val pending=store.pending()
        assertFalse(pending.any { it.kind=="category"&&it.id==original.id })
        val activity=pending.first { it.kind=="activity" }
        assertEquals(canonical,activity.request.getJSONObject("payload").getJSONObject("tables").getJSONArray("activities").getJSONObject(0).getString("categoryId"))
    }
    @Test fun unboundBackupRestoreDiscardsRemovedDirtyIdsBeforeInitialImport() = runBlocking {
        val db=database();val store=CloudStore(db,context);val ledger=LedgerRepository(db)
        val file=java.io.File(context.cacheDir,"cloud-backup-test-${UUID.randomUUID()}.json")
        try {
            ledger.save(null,null,at,zone,"EXPENSE","备份内","12.34")
            val first=store.pending().single().id
            val backup=BackupRepository(context,db);backup.export(android.net.Uri.fromFile(file))
            ledger.save(null,null,at,zone,"EXPENSE","备份后新增","56.78")
            backup.restore(android.net.Uri.fromFile(file))
            assertEquals(0,store.count("cloud_dirty"))
            store.bind(UUID.randomUUID().toString(),"https://example.test")
            assertEquals(first,store.pending().single().id)
        }finally{file.delete()}
    }
    @Test fun plainTextTemplateCannotReplaceDatabaseBackup() = runBlocking {
        val db=database();val ledger=LedgerRepository(db)
        val file=java.io.File(context.cacheDir,"not-a-backup-${UUID.randomUUID()}.txt")
        try {
            ledger.save(null,null,at,zone,"EXPENSE","应保留","12.34")
            file.writeText("每日计划表\n07:30—08:00 早餐")
            val failure=runCatching { BackupRepository(context,db).restore(android.net.Uri.fromFile(file)) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertTrue(failure!!.message!!.contains("JSON 备份"))
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM ledger_entries").use {
                assertTrue(it.moveToFirst());assertEquals(1,it.getInt(0))
            }
        }finally{file.delete()}
    }
    @Test fun tokenUsesKeystoreEncryptionAndLogoutClearsIt() {
        val name="cloud-token-test-${UUID.randomUUID()}"
        val credentials=CloudCredentials(context,name,name)
        try {
            val secret="isolated-session-token-${UUID.randomUUID()}"
            credentials.save(secret)
            assertEquals(secret,CloudCredentials(context,name,name).token())
            assertFalse(context.getSharedPreferences(name,Context.MODE_PRIVATE).all.values.any { it==secret })
            credentials.clear();assertNull(credentials.token())
        } finally {
            context.deleteSharedPreferences(name)
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null);deleteEntry(name) }
        }
    }
    @Test fun remoteActivityConflictingWithSleepIsDeferredWithoutPartialWrites() = runBlocking {
        val source=database();val sourceStore=CloudStore(source,context)
        PlanningRepository(source,context).addManual("活动","类别",at,at.plusHours(1),zone)
        val pending=sourceStore.pending().first { it.kind=="activity" }
        val target=database();val targetStore=CloudStore(target,context)
        val start=at.atZone(zone).toInstant().toEpochMilli()
        target.sleepDao().insert(SleepLogEntity(UUID.randomUUID().toString(),start,start+3_600_000,null,zone.id,1))
        val change=JSONObject().put("kind","activity").put("id",pending.id).put("serverVersion",1).put("seq",1).put("payload",pending.request.getJSONObject("payload"))
        targetStore.acceptChange(change)
        assertEquals(1,targetStore.count("cloud_conflicts"));assertEquals("1",targetStore.meta("cursor"))
        assertNull(target.activityDao().get(pending.id))
    }
    @Test fun outboxRetainsInFlightPayloadAndNewerEdits() = runBlocking {
        val db=database();val store=CloudStore(db,context);val ledger=LedgerRepository(db)
        ledger.save(null,null,at,zone,"EXPENSE","第一版","12.34")
        val first=store.pending().single();val original=first.request.toString()
        ledger.save(first.id,1,at,zone,"EXPENSE","第二版","56.78")
        assertEquals(original,store.pending().single().request.toString())
        store.acknowledge(first,JSONObject().put("serverVersion",1))
        val second=store.pending().single()
        assertEquals(1,second.request.getLong("expectedVersion"))
        assertNotEquals(first.request.getString("operationId"),second.request.getString("operationId"))
        assertEquals("56.78",second.request.getJSONObject("payload").getJSONObject("tables").getJSONArray("ledger_entries").getJSONObject(0).getString("amount"))
        store.acknowledge(second,JSONObject().put("serverVersion",2));assertEquals(0,store.count("cloud_dirty"))
    }
    @Test fun failedBusinessTransactionDoesNotLeaveDirtyWork() = runBlocking {
        val db=database();val store=CloudStore(db,context)
        try { db.withTransaction {
            db.ledgerDao().insert(LedgerEntryEntity(UUID.randomUUID().toString(),0,"Asia/Shanghai","EXPENSE","回滚","1.00",1))
            throw IllegalArgumentException("abort test transaction")
        } } catch (_: IllegalArgumentException) {}
        assertEquals(0,store.count("cloud_dirty"));assertEquals(0,store.count("cloud_outbox"))
    }
    @Test fun threeModulesRoundTripThroughRealHttpAndNewDatabase() = runBlocking {
        val endpoint="http://127.0.0.1:18080"
        val registered=CloudHttp(endpoint).call("/api/v1/auth/register",JSONObject()
            .put("accountName","可重复昵称").put("password","Testpw8!"))
        val uid=registered.getString("uid")
        assertTrue(uid.matches(Regex("[0-9]{8}")))
        val login=CloudHttp(endpoint).call("/api/v1/auth/login",JSONObject().put("uid",uid).put("password","Testpw8!"))
        assertEquals(registered.getString("ownerId"),login.getString("ownerId"))
        val http=CloudHttp(endpoint,login.getString("token"))
        val owner=login.getString("ownerId")
        val source=database();val sourceStore=CloudStore(source,context);sourceStore.bind(owner,endpoint)
        val target=database();val targetStore=CloudStore(target,context);targetStore.bind(owner,endpoint)
        val plans=PlanningRepository(source,context);val nutrition=NutritionRepository(source);val ledger=LedgerRepository(source)
        plans.addPlan("云端计划",at,at.plusHours(1),zone,false)
        val plan=source.planDao().startedOnDay(at.minusHours(1).atZone(zone).toInstant().toEpochMilli(),at.plusHours(2).atZone(zone).toInstant().toEpochMilli()).single()
        plans.startPlan(plan.id,"云端分类-${plan.id.take(8)}",tagsText="学习,专注")
        val activity=source.activityDao().active()!!
        delay(25);plans.pause(activity.id);delay(25);plans.resume(activity.id);delay(25);plans.finish(activity.id)
        ledger.save(null,null,at,zone,"EXPENSE","中文's 午餐","12.34","FOOD","午餐备注")
        nutrition.saveNutritionDay(at.toLocalDate(),zone,null,"1570","230","249","72","49")
        nutrition.saveFood(null,null,FoodDraft("酸奶","66.5","PACKAGED","USER_LABEL"))
        val food=nutrition.latestFoods().single()
        nutrition.saveIntake(null,null,"SNACK",at,zone,"加餐",listOf(IntakeDraftItem(foodId=food.foodId,foodVersion=1,consumedGrams="125.5")))
        nutrition.saveFood(food.foodId,1,FoodDraft("酸奶","77.5","PACKAGED","USER_LABEL"))
        fun push() { sourceStore.pending().forEach { item -> sourceStore.acknowledge(item,http.call("/api/v1/sync/operations",item.request)) } }
        fun pull() { var more=true;while(more){
            val page=http.call("/api/v1/sync/changes?cursor=${targetStore.meta("cursor") ?: "0"}&limit=5")
            val rows=page.getJSONArray("items");for(i in 0 until rows.length())targetStore.acceptChange(rows.getJSONObject(i));more=page.getBoolean("hasMore")
        } }
        push();pull()
        assertEquals(0,targetStore.count("cloud_conflicts"));assertEquals(0,targetStore.count("cloud_dirty"))
        assertEquals("云端计划",target.planDao().get(plan.id)!!.title)
        assertEquals(2,target.activityDao().segmentsForActivity(activity.id).size)
        assertEquals("77.5",target.foodDao().latestVersion(food.foodId)!!.kcalPer100g)
        val restoredDay=target.nutritionDayDao().get(at.toLocalDate().toString(),zone.id)!!
        assertEquals("1570",restoredDay.budgetKcal);assertEquals("230",restoredDay.exerciseKcal)
        target.openHelper.readableDatabase.query("SELECT category,note FROM ledger_entries").use {row->
            assertTrue(row.moveToFirst());assertEquals("FOOD",row.getString(0));assertEquals("午餐备注",row.getString(1))
        }
        source.openHelper.readableDatabase.query("SELECT id FROM intakes").use { cursor ->
            assertTrue(cursor.moveToFirst());val intakeId=cursor.getString(0)
            target.openHelper.readableDatabase.query("SELECT consumedGrams,kcalPer100gSnapshot FROM intake_items WHERE intakeId=?",arrayOf(intakeId)).use { item ->
                assertTrue(item.moveToFirst());assertEquals("125.5",item.getString(0));assertEquals("66.5",item.getString(1))
            }
            nutrition.deleteIntake(intakeId,1)
        }
        source.openHelper.readableDatabase.query("SELECT id FROM ledger_entries").use { cursor ->assertTrue(cursor.moveToFirst());ledger.delete(cursor.getString(0),1) }
        plans.updatePlan(plan.id,source.planDao().get(plan.id)!!.version,"云端计划",at.plusMinutes(15),at.plusHours(1).plusMinutes(15),zone,false,"改期验收")
        push();pull()
        target.openHelper.readableDatabase.query("SELECT COUNT(*) FROM intakes WHERE deletedAtMillis IS NOT NULL").use { it.moveToFirst();assertTrue(it.getInt(0)>=1) }
        target.openHelper.readableDatabase.query("SELECT COUNT(*) FROM ledger_entries WHERE deletedAtMillis IS NOT NULL").use { it.moveToFirst();assertTrue(it.getInt(0)>=1) }
        target.openHelper.readableDatabase.query("SELECT COUNT(*) FROM plan_revisions WHERE planId=?",arrayOf(plan.id)).use { it.moveToFirst();assertEquals(1,it.getInt(0)) }
        assertEquals(0,targetStore.count("cloud_dirty"))
    }
}
