package com.dayoudu.app.data

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Uses a separate on-device file; never opens or clears daily-rhythm.db. */
@RunWith(AndroidJUnit4::class)
class LocalDatabaseTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var database: NutritionDatabase

    @Before
    fun openTestDatabase() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseName = "room-verification-${UUID.randomUUID()}.db"
        database = openDatabase()
    }

    @After
    fun removeTestDatabase() {
        if (::database.isInitialized) database.close()
        if (::databaseName.isInitialized) context.deleteDatabase(databaseName)
    }

    private fun openDatabase(): NutritionDatabase = Room.databaseBuilder(
        context, NutritionDatabase::class.java, databaseName,
    ).build()

    @Test
    fun localProfilePersistsAcrossReopen() = runBlocking {
        val repository = LocalUserRepository(database)
        val original = repository.ensure()
        repository.rename("本地持久化验证")

        database.close()
        database = openDatabase()

        val restored = LocalUserRepository(database).ensure()
        assertEquals(original.id, restored.id)
        assertEquals(original.createdAtMillis, restored.createdAtMillis)
        assertEquals("本地持久化验证", restored.displayName)
        database.openHelper.readableDatabase.query("PRAGMA integrity_check").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("ok", cursor.getString(0))
        }
    }

    @Test
    fun failedTransactionRollsBackWithoutLosingExistingRecords() = runBlocking {
        val original = LocalUserRepository(database).ensure()
        val entry = LedgerEntryEntity(
            id = UUID.randomUUID().toString(), occurredAtMillis = 0L,
            timezone = "Asia/Shanghai", type = "EXPENSE", source = "事务验证",
            amount = "1.00", version = 1,
        )
        try {
            database.withTransaction {
                database.ledgerDao().insert(entry)
                assertNotNull(database.ledgerDao().get(entry.id))
                database.ledgerDao().insert(entry) // Primary-key conflict must roll back both writes.
            }
            fail("Expected duplicate primary key to abort the transaction")
        } catch (_: SQLiteConstraintException) {
            // Expected. Reopen the file to verify the rollback also survives a new connection.
        }

        database.close()
        database = openDatabase()
        assertNull(database.ledgerDao().get(entry.id))
        assertEquals(original, database.localUserDao().get())
    }

    @Test
    fun parameterizedSqlWritesAreReadableByRoomAfterReopen() = runBlocking {
        val at = 1_700_000_000_000L
        val quotedText = "SQL's 参数绑定验证"
        fun row(vararg values: Pair<String, Any?>): Map<String, Any?> = linkedMapOf(*values)
        val rows = linkedMapOf(
            "time_categories" to row("id" to "sql-category", "name" to "SQL测试类别", "archived" to 0),
            "projects" to row("id" to "sql-project", "name" to "SQL测试项目", "archived" to 0),
            "local_users" to row("id" to "sql-user", "displayName" to quotedText, "createdAtMillis" to at),
            "plans" to row("id" to "sql-plan", "title" to quotedText,
                "scheduledStartMillis" to at, "scheduledEndMillis" to at + 60_000,
                "timezone" to "Asia/Shanghai", "fixed" to 0, "status" to "SCHEDULED",
                "baselineStartMillis" to at, "baselineEndMillis" to at + 60_000,
                "version" to 1, "createdAtMillis" to at, "updatedAtMillis" to at,
                "reminderMinutesBefore" to null, "reminderSilent" to 0),
            "plan_revisions" to row("id" to "sql-revision", "planId" to "sql-plan", "revision" to 1,
                "scheduledStartMillis" to at, "scheduledEndMillis" to at + 60_000,
                "changeReason" to "SQL测试调整", "changedAtMillis" to at),
            "activities" to row("id" to "sql-activity", "title" to quotedText,
                "categoryName" to "SQL测试类别", "categoryId" to "sql-category", "planId" to "sql-plan",
                "projectId" to "sql-project", "projectNameSnapshot" to "SQL测试项目",
                "sessionStatus" to "FINISHED", "version" to 1, "createdAtMillis" to at,
                "updatedAtMillis" to at, "isWork" to 0),
            "activity_segments" to row("id" to "sql-segment", "activityId" to "sql-activity",
                "startMillis" to at, "endMillis" to at + 60_000),
            "activity_tags" to row("activityId" to "sql-activity", "name" to "SQL测试标签"),
            "food_versions" to row("foodId" to "sql-food", "version" to 1, "name" to "SQL测试食物",
                "preparedState" to "PACKAGED", "kcalPer100g" to "100.25", "sourceType" to "USER_LABEL",
                "archived" to 0),
            "intakes" to row("id" to "sql-intake", "eatenAtMillis" to at, "timezone" to "Asia/Shanghai",
                "mealType" to "SNACK", "note" to quotedText, "version" to 1),
            "intake_items" to row("id" to "sql-intake-item", "intakeId" to "sql-intake",
                "foodId" to "sql-food", "foodVersion" to 1, "displayName" to "SQL测试食物",
                "consumedGrams" to "50", "kcalPer100gSnapshot" to "100.25",
                "sourceTypeSnapshot" to "USER_LABEL", "nutritionStatus" to "COMPLETE"),
            "sleep_logs" to row("id" to "sql-sleep", "onsetMillis" to at - 28_800_000,
                "wakeMillis" to at - 60_000, "awakeSeconds" to 60, "timezone" to "Asia/Shanghai", "version" to 1),
            "ledger_entries" to row("id" to "sql-ledger", "occurredAtMillis" to at,
                "timezone" to "Asia/Shanghai", "type" to "EXPENSE", "source" to quotedText,
                "amount" to "12.30", "version" to 1),
            // These four tables are retained in storage; their current UI entry is paused.
            "value_model_versions" to row("modelId" to "sql-model", "version" to 1, "confirmedAtMillis" to at),
            "value_dimensions" to row("modelId" to "sql-model", "modelVersion" to 1,
                "dimensionId" to "sql-dimension", "name" to "SQL测试维度", "weight" to "1"),
            "activity_evaluations" to row("activityId" to "sql-activity", "modelId" to "sql-model",
                "modelVersion" to 1, "evaluatedAtMillis" to at, "version" to 1),
            "dimension_ratings" to row("activityId" to "sql-activity", "modelId" to "sql-model",
                "modelVersion" to 1, "dimensionId" to "sql-dimension", "score" to "8.5"),
        )
        database.withTransaction {
            val sql = database.openHelper.writableDatabase
            rows.forEach { (table, values) ->
                // Identifiers come only from the test constants above; bind every data value.
                val columns = values.keys.joinToString { "`$it`" }
                val placeholders = values.keys.joinToString { "?" }
                sql.execSQL("INSERT INTO `$table` ($columns) VALUES ($placeholders)", values.values.toTypedArray())
            }
        }
        database.close()
        database = openDatabase()

        assertEquals(quotedText, database.planDao().get("sql-plan")!!.title)
        val activity = database.activityDao().observeDay(at, at + 60_000).first().single()
        assertEquals("sql-plan", activity.activity.planId)
        assertEquals("sql-project", activity.activity.projectId)
        assertEquals(60_000L, activity.segments.single().endMillis!! - activity.segments.single().startMillis)
        assertEquals("SQL测试标签", activity.tags.single().name)
        assertEquals("SQL测试类别", database.classificationDao().categoryByName("SQL测试类别")!!.name)
        assertEquals("SQL测试项目", database.classificationDao().project("sql-project")!!.name)
        assertEquals("100.25", database.foodDao().version("sql-food", 1)!!.kcalPer100g)
        val intake = database.intakeDao().observeDay(at, at + 1).first().single()
        assertEquals(quotedText, intake.intake.note)
        assertEquals("50", intake.items.single().consumedGrams)
        assertEquals("100.25", intake.items.single().kcalPer100gSnapshot)
        assertEquals(60L, database.sleepDao().get("sql-sleep")!!.awakeSeconds)
        assertEquals(quotedText, database.localUserDao().get()!!.displayName)
        assertEquals("12.30", database.ledgerDao().get("sql-ledger")!!.amount)
        assertEquals(quotedText, database.ledgerDao().get("sql-ledger")!!.source)
        assertEquals("sql-model", database.valueDao().latestModel()!!.modelId)
        assertEquals("1", database.valueDao().dimensions("sql-model", 1).single().weight)
        assertNotNull(database.valueDao().evaluation("sql-activity", "sql-model", 1))
        assertEquals("8.5", database.valueDao().ratings("sql-activity", "sql-model", 1).single().score)
        rows.keys.forEach { table ->
            database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("$table row did not persist", 1, cursor.getInt(0))
            }
        }
        database.openHelper.readableDatabase.query("PRAGMA integrity_check").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("ok", cursor.getString(0))
        }
    }
}
