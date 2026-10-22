package com.dayoudu.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "food_versions", primaryKeys = ["foodId", "version"])
data class FoodVersionEntity(
    val foodId: String,
    val version: Int,
    val name: String,
    val preparedState: String,
    val kcalPer100g: String,
    val sourceType: String,
    val archived: Boolean = false,
    val brand: String? = null,
    val proteinGPer100g: String? = null,
    val fatGPer100g: String? = null,
    val carbGPer100g: String? = null,
    val sourceNote: String? = null,
)

@Entity(tableName = "intakes", indices = [Index("eatenAtMillis")])
data class IntakeEntity(
    @PrimaryKey val id: String,
    val eatenAtMillis: Long,
    val timezone: String,
    val mealType: String,
    val note: String? = null,
    @ColumnInfo(defaultValue = "1") val version: Int = 1,
    val deletedAtMillis: Long? = null,
)

@Entity(tableName = "intake_items", indices = [Index("intakeId")])
data class IntakeItemEntity(
    @PrimaryKey val id: String,
    val intakeId: String,
    val foodId: String?,
    val foodVersion: Int?,
    val displayName: String,
    val consumedGrams: String?,
    val kcalPer100gSnapshot: String?,
    val sourceTypeSnapshot: String?,
    val proteinGPer100gSnapshot: String? = null,
    val fatGPer100gSnapshot: String? = null,
    val carbGPer100gSnapshot: String? = null,
    val manualName: String? = null,
    val nutritionStatus: String = "COMPLETE",
)

data class IntakeWithItems(
    @Embedded val intake: IntakeEntity,
    @Relation(parentColumn = "id", entityColumn = "intakeId")
    val items: List<IntakeItemEntity>,
)

@Dao
interface FoodDao {
    @Insert
    suspend fun insert(food: FoodVersionEntity)

    @Query("""SELECT f.* FROM food_versions f
        WHERE f.version = (SELECT MAX(v.version) FROM food_versions v WHERE v.foodId = f.foodId)
        AND f.archived = 0 ORDER BY f.name""")
    suspend fun latestFoods(): List<FoodVersionEntity>

    @Query("""SELECT f.* FROM food_versions f
        WHERE f.version = (SELECT MAX(v.version) FROM food_versions v WHERE v.foodId = f.foodId)
        ORDER BY f.name""")
    suspend fun allLatestFoods(): List<FoodVersionEntity>

    @Query("SELECT * FROM food_versions WHERE foodId = :foodId AND version = :version LIMIT 1")
    suspend fun version(foodId: String, version: Int): FoodVersionEntity?

    @Query("SELECT * FROM food_versions WHERE foodId = :foodId ORDER BY version DESC LIMIT 1")
    suspend fun latestVersion(foodId: String): FoodVersionEntity?
}

@Dao
interface IntakeDao {
    @Insert
    suspend fun insert(intake: IntakeEntity)

    @Insert
    suspend fun insert(item: IntakeItemEntity)

    @androidx.room.Update suspend fun update(intake: IntakeEntity)

    @Query("SELECT * FROM intakes WHERE id = :id LIMIT 1")
    suspend fun get(id: String): IntakeEntity?

    @Query("DELETE FROM intake_items WHERE intakeId = :intakeId")
    suspend fun deleteItems(intakeId: String)

    @Transaction
    @Query("SELECT * FROM intakes WHERE eatenAtMillis >= :fromMillis AND eatenAtMillis < :toMillis AND deletedAtMillis IS NULL ORDER BY eatenAtMillis")
    fun observeDay(fromMillis: Long, toMillis: Long): Flow<List<IntakeWithItems>>
}

@Database(entities = [FoodVersionEntity::class, IntakeEntity::class, IntakeItemEntity::class,
    PlanEntity::class, PlanRevisionEntity::class, ActivityEntity::class, ActivitySegmentEntity::class,
    SleepLogEntity::class, ValueModelVersionEntity::class, ValueDimensionEntity::class,
    ActivityEvaluationEntity::class, DimensionRatingEntity::class,
    TimeCategoryEntity::class, ProjectEntity::class, ActivityTagEntity::class,
    LocalUserEntity::class, LedgerEntryEntity::class, NutritionDayEntity::class],
    version = 11, exportSchema = true)
abstract class NutritionDatabase : RoomDatabase() {
    abstract fun foodDao(): FoodDao
    abstract fun intakeDao(): IntakeDao
    abstract fun planDao(): PlanDao
    abstract fun activityDao(): ActivityDao
    abstract fun sleepDao(): SleepDao
    abstract fun valueDao(): ValueDao
    abstract fun classificationDao(): ClassificationDao
    abstract fun localUserDao(): LocalUserDao
    abstract fun ledgerDao(): LedgerDao
    abstract fun nutritionDayDao(): NutritionDayDao

    companion object {
        @Volatile private var instance: NutritionDatabase? = null

        fun get(context: Context): NutritionDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                NutritionDatabase::class.java,
                "daily-rhythm.db",
            ).addCallback(object : Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) { CloudStore.install(db, context.applicationContext) }
            }).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11).build().also { instance = it }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS `plans` (`id` TEXT NOT NULL, `title` TEXT NOT NULL,
                    `scheduledStartMillis` INTEGER NOT NULL, `scheduledEndMillis` INTEGER NOT NULL,
                    `timezone` TEXT NOT NULL, `fixed` INTEGER NOT NULL, `status` TEXT NOT NULL,
                    `baselineStartMillis` INTEGER NOT NULL, `baselineEndMillis` INTEGER NOT NULL,
                    `version` INTEGER NOT NULL, `createdAtMillis` INTEGER NOT NULL,
                    `updatedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`id`))""")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_plans_scheduledStartMillis` ON `plans` (`scheduledStartMillis`)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS `plan_revisions` (`id` TEXT NOT NULL,
                    `planId` TEXT NOT NULL, `revision` INTEGER NOT NULL, `scheduledStartMillis` INTEGER NOT NULL,
                    `scheduledEndMillis` INTEGER NOT NULL, `changeReason` TEXT NOT NULL,
                    `changedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`id`))""")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_plan_revisions_planId` ON `plan_revisions` (`planId`)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS `activities` (`id` TEXT NOT NULL,
                    `title` TEXT NOT NULL, `categoryName` TEXT NOT NULL, `planId` TEXT,
                    `sessionStatus` TEXT NOT NULL, `version` INTEGER NOT NULL,
                    `createdAtMillis` INTEGER NOT NULL, `updatedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`id`))""")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_activities_planId` ON `activities` (`planId`)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS `activity_segments` (`id` TEXT NOT NULL,
                    `activityId` TEXT NOT NULL, `startMillis` INTEGER NOT NULL,
                    `endMillis` INTEGER, PRIMARY KEY(`id`))""")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_segments_activityId` ON `activity_segments` (`activityId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_segments_startMillis` ON `activity_segments` (`startMillis`)")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS `sleep_logs` (`id` TEXT NOT NULL,
                    `onsetMillis` INTEGER NOT NULL, `wakeMillis` INTEGER NOT NULL,
                    `awakeSeconds` INTEGER, `timezone` TEXT NOT NULL, `version` INTEGER NOT NULL,
                    PRIMARY KEY(`id`))""")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sleep_logs_wakeMillis` ON `sleep_logs` (`wakeMillis`)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS `value_model_versions` (`modelId` TEXT NOT NULL,
                    `version` INTEGER NOT NULL, `confirmedAtMillis` INTEGER NOT NULL,
                    PRIMARY KEY(`modelId`, `version`))""")
                db.execSQL("""CREATE TABLE IF NOT EXISTS `value_dimensions` (`modelId` TEXT NOT NULL,
                    `modelVersion` INTEGER NOT NULL, `dimensionId` TEXT NOT NULL,
                    `name` TEXT NOT NULL, `weight` TEXT NOT NULL,
                    PRIMARY KEY(`modelId`, `modelVersion`, `dimensionId`))""")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_value_dimensions_modelId_modelVersion` ON `value_dimensions` (`modelId`, `modelVersion`)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS `activity_evaluations` (`activityId` TEXT NOT NULL,
                    `modelId` TEXT NOT NULL, `modelVersion` INTEGER NOT NULL,
                    `evaluatedAtMillis` INTEGER NOT NULL, `version` INTEGER NOT NULL,
                    PRIMARY KEY(`activityId`, `modelId`, `modelVersion`))""")
                db.execSQL("""CREATE TABLE IF NOT EXISTS `dimension_ratings` (`activityId` TEXT NOT NULL,
                    `modelId` TEXT NOT NULL, `modelVersion` INTEGER NOT NULL,
                    `dimensionId` TEXT NOT NULL, `score` TEXT,
                    PRIMARY KEY(`activityId`, `modelId`, `modelVersion`, `dimensionId`))""")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_dimension_ratings_modelId_modelVersion` ON `dimension_ratings` (`modelId`, `modelVersion`)")
            }
        }
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf("brand", "proteinGPer100g", "fatGPer100g", "carbGPer100g", "sourceNote")
                    .forEach { db.execSQL("ALTER TABLE `food_versions` ADD COLUMN `$it` TEXT") }
                db.execSQL("ALTER TABLE `intakes` ADD COLUMN `note` TEXT")
                db.execSQL("ALTER TABLE `intakes` ADD COLUMN `version` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `intakes` ADD COLUMN `deletedAtMillis` INTEGER")
                db.execSQL("""CREATE TABLE IF NOT EXISTS `intake_items_new` (`id` TEXT NOT NULL,
                    `intakeId` TEXT NOT NULL, `foodId` TEXT, `foodVersion` INTEGER,
                    `displayName` TEXT NOT NULL, `consumedGrams` TEXT,
                    `kcalPer100gSnapshot` TEXT, `sourceTypeSnapshot` TEXT,
                    `proteinGPer100gSnapshot` TEXT, `fatGPer100gSnapshot` TEXT,
                    `carbGPer100gSnapshot` TEXT, `manualName` TEXT,
                    `nutritionStatus` TEXT NOT NULL, PRIMARY KEY(`id`))""")
                db.execSQL("""INSERT INTO `intake_items_new`
                    (`id`,`intakeId`,`foodId`,`foodVersion`,`displayName`,`consumedGrams`,
                     `kcalPer100gSnapshot`,`sourceTypeSnapshot`,`proteinGPer100gSnapshot`,
                     `fatGPer100gSnapshot`,`carbGPer100gSnapshot`,`manualName`,`nutritionStatus`)
                    SELECT `id`,`intakeId`,`foodId`,`foodVersion`,`displayName`,`consumedGrams`,
                     `kcalPer100gSnapshot`,`sourceTypeSnapshot`,NULL,NULL,NULL,NULL,
                     CASE WHEN `consumedGrams` IS NULL THEN 'MISSING_AMOUNT' ELSE 'COMPLETE' END
                    FROM `intake_items`""")
                db.execSQL("DROP TABLE `intake_items`")
                db.execSQL("ALTER TABLE `intake_items_new` RENAME TO `intake_items`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_intake_items_intakeId` ON `intake_items` (`intakeId`)")
            }
        }
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `plans` ADD COLUMN `reminderMinutesBefore` INTEGER")
                db.execSQL("ALTER TABLE `plans` ADD COLUMN `reminderSilent` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `activities` ADD COLUMN `isWork` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `activities` ADD COLUMN `incomeAmount` TEXT")
                db.execSQL("ALTER TABLE `activities` ADD COLUMN `expenseAmount` TEXT")
                db.execSQL("ALTER TABLE `activities` ADD COLUMN `commuteMinutes` INTEGER")
            }
        }
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `activities` ADD COLUMN `categoryId` TEXT")
                db.execSQL("ALTER TABLE `activities` ADD COLUMN `projectId` TEXT")
                db.execSQL("ALTER TABLE `activities` ADD COLUMN `projectNameSnapshot` TEXT")
                db.execSQL("""CREATE TABLE IF NOT EXISTS `time_categories` (`id` TEXT NOT NULL,
                    `name` TEXT NOT NULL, `archived` INTEGER NOT NULL, PRIMARY KEY(`id`))""")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_time_categories_name` ON `time_categories` (`name`)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS `projects` (`id` TEXT NOT NULL,
                    `name` TEXT NOT NULL, `archived` INTEGER NOT NULL, PRIMARY KEY(`id`))""")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_projects_name` ON `projects` (`name`)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS `activity_tags` (`activityId` TEXT NOT NULL,
                    `name` TEXT NOT NULL, PRIMARY KEY(`activityId`, `name`))""")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_tags_activityId` ON `activity_tags` (`activityId`)")
            }
        }
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `activities` ADD COLUMN `deletedAtMillis` INTEGER")
            }
        }
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `activities` ADD COLUMN `currencyCode` TEXT")
            }
        }
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `plans` ADD COLUMN `deletedAtMillis` INTEGER")
            }
        }
        internal val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `ledger_entries` ADD COLUMN `category` TEXT")
                db.execSQL("ALTER TABLE `ledger_entries` ADD COLUMN `note` TEXT")
                db.execSQL("""CREATE TABLE IF NOT EXISTS `nutrition_days` (`id` TEXT NOT NULL,
                    `date` TEXT NOT NULL, `timezone` TEXT NOT NULL, `budgetKcal` TEXT,
                    `exerciseKcal` TEXT, `proteinGoal` TEXT, `fatGoal` TEXT, `carbGoal` TEXT,
                    `version` INTEGER NOT NULL, PRIMARY KEY(`id`))""")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_nutrition_days_date_timezone` ON `nutrition_days` (`date`, `timezone`)")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS `local_users` (`id` TEXT NOT NULL,
                    `displayName` TEXT, `createdAtMillis` INTEGER NOT NULL, PRIMARY KEY(`id`))""")
                db.execSQL("""CREATE TABLE IF NOT EXISTS `ledger_entries` (`id` TEXT NOT NULL,
                    `occurredAtMillis` INTEGER NOT NULL, `timezone` TEXT NOT NULL,
                    `type` TEXT NOT NULL, `source` TEXT NOT NULL, `amount` TEXT NOT NULL,
                    `version` INTEGER NOT NULL, `deletedAtMillis` INTEGER, PRIMARY KEY(`id`))""")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ledger_entries_occurredAtMillis` ON `ledger_entries` (`occurredAtMillis`)")
            }
        }
    }
}
