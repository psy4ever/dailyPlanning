package com.dayoudu.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import androidx.room.withTransaction
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "local_users")
data class LocalUserEntity(
    @PrimaryKey val id: String,
    val displayName: String?,
    val createdAtMillis: Long,
)

@Dao
interface LocalUserDao {
    @Query("SELECT * FROM local_users LIMIT 1") fun observe(): Flow<LocalUserEntity?>
    @Query("SELECT * FROM local_users LIMIT 1") suspend fun get(): LocalUserEntity?
    @Insert suspend fun insert(user: LocalUserEntity)
    @Update suspend fun update(user: LocalUserEntity)
}

class LocalUserRepository(private val database: NutritionDatabase) {
    fun observe(): Flow<LocalUserEntity?> = database.localUserDao().observe()

    suspend fun ensure(): LocalUserEntity = database.withTransaction {
        database.localUserDao().get() ?: LocalUserEntity(UUID.randomUUID().toString(), null,
            System.currentTimeMillis()).also { database.localUserDao().insert(it) }
    }

    suspend fun rename(name: String) {
        val clean = name.trim()
        require(clean.length <= 40) { "昵称不能超过 40 字" }
        database.withTransaction {
            val user = database.localUserDao().get() ?: throw IllegalStateException("本机档案尚未建立")
            database.localUserDao().update(user.copy(displayName = clean.ifEmpty { null }))
        }
    }
}

@Entity(tableName = "ledger_entries", indices = [Index("occurredAtMillis")])
data class LedgerEntryEntity(
    @PrimaryKey val id: String,
    val occurredAtMillis: Long,
    val timezone: String,
    val type: String,
    val source: String,
    val amount: String,
    val version: Int,
    val deletedAtMillis: Long? = null,
    val category: String? = null,
    val note: String? = null,
)

@Dao
interface LedgerDao {
    @Query("SELECT * FROM ledger_entries WHERE occurredAtMillis >= :fromMillis AND occurredAtMillis < :toMillis AND deletedAtMillis IS NULL ORDER BY occurredAtMillis DESC")
    fun observeDay(fromMillis: Long, toMillis: Long): Flow<List<LedgerEntryEntity>>
    @Query("SELECT * FROM ledger_entries WHERE id = :id LIMIT 1") suspend fun get(id: String): LedgerEntryEntity?
    @Insert suspend fun insert(entry: LedgerEntryEntity)
    @Update suspend fun update(entry: LedgerEntryEntity)
}

class LedgerRepository(private val database: NutritionDatabase) {
    fun observeDay(date: LocalDate, zone: ZoneId): Flow<List<LedgerEntryEntity>> =
        database.ledgerDao().observeDay(date.atStartOfDay(zone).toInstant().toEpochMilli(),
            date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())

    suspend fun save(id: String?, expectedVersion: Int?, at: LocalDateTime, zone: ZoneId,
                     type: String, source: String, amountText: String,
                     category: String? = null, note: String? = null) {
        require(type in setOf("INCOME", "EXPENSE")) { "请选择收入或支出" }
        val cleanSource = source.trim()
        require(cleanSource.isNotEmpty() && cleanSource.length <= 100) { "来源须为 1–100 字" }
        require(category == null || (if (type == "INCOME") incomeCategories else expenseCategories).any { it.key == category }) { "请选择有效分类" }
        require((note?.length ?: 0) <= 500) { "备注不能超过 500 字" }
        val amount = amountText.trim().toBigDecimalOrNull() ?: throw IllegalArgumentException("请填写有效金额")
        require(amount > BigDecimal.ZERO && amount.scale() <= 2 && amount.precision() <= 14) {
            "金额须大于 0，最多两位小数"
        }
        val millis = at.atZone(zone).toInstant().toEpochMilli()
        database.withTransaction {
            val old = id?.let { database.ledgerDao().get(it) }
            require(id == null || old?.version == expectedVersion && old?.deletedAtMillis == null) {
                "账目已变化，请重新打开"
            }
            if (old == null) database.ledgerDao().insert(LedgerEntryEntity(UUID.randomUUID().toString(),
                millis, zone.id, type, cleanSource, amount.stripTrailingZeros().toPlainString(), 1,
                category = category, note = note?.trim()?.takeIf { it.isNotEmpty() }))
            else database.ledgerDao().update(old.copy(occurredAtMillis = millis, timezone = zone.id,
                type = type, source = cleanSource, amount = amount.stripTrailingZeros().toPlainString(),
                version = old.version + 1, category = category, note = note?.trim()?.takeIf { it.isNotEmpty() }))
        }
    }

    suspend fun delete(id: String, expectedVersion: Int) {
        database.withTransaction {
            val old = database.ledgerDao().get(id) ?: throw IllegalArgumentException("账目不存在")
            require(old.version == expectedVersion && old.deletedAtMillis == null) { "账目已变化，请重新打开" }
            database.ledgerDao().update(old.copy(version = old.version + 1, deletedAtMillis = System.currentTimeMillis()))
        }
    }
}
