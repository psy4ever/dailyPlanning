# 本地 SQL 连接、可存储模块与字段

日期：2026-10-21。依据当前 Room v10 schema、实体、Repository 校验和三页 UI 核对。共 **17 张业务表、118 个表字段**。以下记录实际实现，不把规划 HTTP 字段视为已落库字段。

## SQL 连接与验证

当前连接为 `页面 → Repository → Room DAO / SQLite SQL → daily-rhythm.db`。入口为 `NutritionDatabase.get(context)`；同一进程通过单例连接应用私有数据库，`openHelper.writableDatabase` 可取得参数化 SQL 执行连接。文件在运行 App 的设备内：`/data/user/0/com.dayoudu.app/databases/daily-rhythm.db`。

本轮在随机命名的独立设备测试库执行参数化 `INSERT`，向 17 张表各保存 1 行并提交；关闭连接、重新打开后，通过 SQL 和 Room DAO 读回，确认关联记录、中文、单引号、金额与营养小数字符串均保留。当前暂停入口的四张表仅验证存储能力，不代表 UI 已启用。正式生活数据库未写入这些测试记录。

验证结果：模拟器执行 `LocalDatabaseTest` 返回 `OK (3 tests)`，覆盖 SQL→Room 持久化互通、本机档案重开、失败事务回滚。Debug APK 和测试 APK 构建通过。

连接与参数化 SQL 示例（在后台协程中执行；示例用于独立测试库，正常页面保存继续使用现有 Repository）：

```kotlin
val databaseName = "sql-example-${UUID.randomUUID()}.db"
val database = Room.databaseBuilder(
    context.applicationContext, NutritionDatabase::class.java, databaseName,
).build()
try {
    database.withTransaction {
        val sql = database.openHelper.writableDatabase
        sql.execSQL(
            """INSERT INTO ledger_entries
               (id, occurredAtMillis, timezone, type, source, amount, version)
               VALUES (?, ?, ?, ?, ?, ?, ?)""",
            arrayOf("sql-ledger", 1700000000000L, "Asia/Shanghai",
                    "EXPENSE", "SQL测试来源", "12.30", 1),
        )
    }
    check(database.ledgerDao().get("sql-ledger")?.amount == "12.30")
} finally {
    database.close()
    context.deleteDatabase(databaseName) // 只清理本例创建的独立测试库。
}
// 正式应用的连接入口：NutritionDatabase.get(context.applicationContext)
// 该入口会使用现有 daily-rhythm.db 和已注册的迁移。
```

示例放在已有 `Context` 的 suspend 函数或后台协程中，使用 `androidx.room.Room`、`androidx.room.withTransaction`、`java.util.UUID` 和项目 `NutritionDatabase`。

测试实现：[`LocalDatabaseTest.kt`](../android/app/src/androidTest/java/com/dayoudu/app/data/LocalDatabaseTest.kt)。SQL 使用绑定参数传值，不拼接用户输入。原始 SQL 绕过 Repository 的范围、关联和版本校验；本次互通测试不替代页面端业务校验测试。

## 模块保存状态

| 模块 | 表 | 当前状态 |
|---|---|---|
| 计划与提醒 | `plans` | 当前“今天”可保存 |
| 计划调整历史 | `plan_revisions` | 调整计划时间时自动保存 |
| 活动与工作现金 | `activities` | 当前“今天”可保存 |
| 活动计时片段 | `activity_segments` | 开始、暂停、继续、结束或补记时保存 |
| 食物营养表 | `food_versions` | 当前“记录”可保存 |
| 饮食摄入事件 | `intakes` | 当前“记录”可保存 |
| 饮食摄入明细与营养快照 | `intake_items` | 随摄入事件保存 |
| 睡眠记录 | `sleep_logs` | 当前“记录”可保存 |
| 独立记账本 | `ledger_entries` | 当前“记录”可保存 |
| 本机档案 | `local_users` | 当前“我的”可保存 |
| 活动主时间类别 | `time_categories` | 当前活动保存时可自动建立；独立管理入口未接入当前三页 |
| 项目 | `projects` | 存储层可写；活动可选择已存项目，当前三页没有新增项目入口 |
| 活动标签 | `activity_tags` | 当前活动编辑器可保存 |
| 回报模型版本 | `value_model_versions` | 存储层保留，当前页面入口暂停 |
| 回报维度与权重 | `value_dimensions` | 存储层保留，当前页面入口暂停 |
| 活动评价 | `activity_evaluations` | 存储层保留，当前页面入口暂停 |
| 各维度评分 | `dimension_ratings` | 存储层保留，当前页面入口暂停 |

## 字段约定

- “允许 NULL”描述数据库列约束；非空列不等于用户必须手填。ID、版本、状态、时间快照等一般由应用自动维护。
- `TEXT` 金额、营养、重量、权重和评分保存十进制字符串；布尔值保存为 `INTEGER` 0/1。
- `*Millis` 保存 Unix 毫秒；`awakeSeconds` 使用秒，`commuteMinutes` 和 `reminderMinutesBefore` 使用分钟。
- 默认值列只列 SQLite schema 中声明的默认值；Kotlin 构造参数默认值不会自动变成 SQL DEFAULT。
- 当前表之间通过 ID 关联及 Repository 校验维护关系，Room v10 schema 没有声明 SQL 外键约束。
- 更新多数业务记录需携带读取时的 expectedVersion；保存后递增 version。食物和模型版本采用新增版本方式。

## 1. 计划与提醒：`plans`

状态：当前“今天”可保存。

保存规则：标题 1–120 字；结束晚于开始且同一天；提前提醒可空或 0–10080 分钟。ID、基线、版本、创建/修改时间由应用维护。

| 字段 | SQLite 类型 | 允许 NULL | 主键 | SQL 默认值 | 含义 |
|---|---|---|---|---|---|
| `id` | TEXT | 否 | 第1列 | `—` | 记录 ID；通常由应用生成 UUID |
| `title` | TEXT | 否 | — | `—` | 标题 |
| `scheduledStartMillis` | INTEGER | 否 | — | `—` | 计划开始时间，Unix 毫秒 |
| `scheduledEndMillis` | INTEGER | 否 | — | `—` | 计划结束时间，Unix 毫秒 |
| `timezone` | TEXT | 否 | — | `—` | 记录时区 ID，例如 Asia/Shanghai |
| `fixed` | INTEGER | 否 | — | `—` | 是否固定计划：0 否，1 是 |
| `status` | TEXT | 否 | — | `—` | 计划状态：SCHEDULED / RESCHEDULED / COMPLETE / CANCELLED |
| `baselineStartMillis` | INTEGER | 否 | — | `—` | 首次创建时的计划开始基线，Unix 毫秒 |
| `baselineEndMillis` | INTEGER | 否 | — | `—` | 首次创建时的计划结束基线，Unix 毫秒 |
| `version` | INTEGER | 否 | — | `—` | 记录版本号；应用用于更新冲突检查 |
| `createdAtMillis` | INTEGER | 否 | — | `—` | 创建时间，Unix 毫秒 |
| `updatedAtMillis` | INTEGER | 否 | — | `—` | 最近修改时间，Unix 毫秒 |
| `reminderMinutesBefore` | INTEGER | 是 | — | `—` | 提前提醒分钟数；NULL 表示关闭 |
| `reminderSilent` | INTEGER | 否 | — | `0` | 是否静音提醒：0 否，1 是 |
| `deletedAtMillis` | INTEGER | 是 | — | `—` | 软删除时间，Unix 毫秒；NULL 表示未删除 |

## 2. 计划调整历史：`plan_revisions`

状态：调整计划时间时自动保存。

保存规则：记录改期前的时间和版本；调整时间须填写原因。

| 字段 | SQLite 类型 | 允许 NULL | 主键 | SQL 默认值 | 含义 |
|---|---|---|---|---|---|
| `id` | TEXT | 否 | 第1列 | `—` | 记录 ID；通常由应用生成 UUID |
| `planId` | TEXT | 否 | — | `—` | 关联 plans.id |
| `revision` | INTEGER | 否 | — | `—` | 改期前计划版本号 |
| `scheduledStartMillis` | INTEGER | 否 | — | `—` | 改期前原计划开始时间，Unix 毫秒 |
| `scheduledEndMillis` | INTEGER | 否 | — | `—` | 改期前原计划结束时间，Unix 毫秒 |
| `changeReason` | TEXT | 否 | — | `—` | 调整原因 |
| `changedAtMillis` | INTEGER | 否 | — | `—` | 调整发生时间，Unix 毫秒 |

## 3. 活动与工作现金：`activities`

状态：当前“今天”可保存。

保存规则：活动标题和主时间类别必填；支持关联计划、项目及标签。现金记录要求活动已结束且标记为工作；金额非负、至多两位小数，填写金额须指定三字母币种；通勤可空或 0–525600 分钟。该现金字段独立于账本。

| 字段 | SQLite 类型 | 允许 NULL | 主键 | SQL 默认值 | 含义 |
|---|---|---|---|---|---|
| `id` | TEXT | 否 | 第1列 | `—` | 记录 ID；通常由应用生成 UUID |
| `title` | TEXT | 否 | — | `—` | 标题 |
| `categoryName` | TEXT | 否 | — | `—` | 保存时主时间类别名称 |
| `planId` | TEXT | 是 | — | `—` | 关联 plans.id；活动可不关联计划 |
| `sessionStatus` | TEXT | 否 | — | `—` | 计时状态：RUNNING / PAUSED / FINISHED |
| `version` | INTEGER | 否 | — | `—` | 记录版本号；应用用于更新冲突检查 |
| `createdAtMillis` | INTEGER | 否 | — | `—` | 创建时间，Unix 毫秒 |
| `updatedAtMillis` | INTEGER | 否 | — | `—` | 最近修改时间，Unix 毫秒 |
| `isWork` | INTEGER | 否 | — | `0` | 是否工作活动：0 否，1 是 |
| `incomeAmount` | TEXT | 是 | — | `—` | 该活动收入，十进制金额文本 |
| `expenseAmount` | TEXT | 是 | — | `—` | 该活动直接支出，十进制金额文本 |
| `currencyCode` | TEXT | 是 | — | `—` | 三个大写字母币种代码，例如 CNY |
| `commuteMinutes` | INTEGER | 是 | — | `—` | 通勤分钟数 |
| `categoryId` | TEXT | 是 | — | `—` | 关联 time_categories.id |
| `projectId` | TEXT | 是 | — | `—` | 关联 projects.id |
| `projectNameSnapshot` | TEXT | 是 | — | `—` | 保存时项目名称快照 |
| `deletedAtMillis` | INTEGER | 是 | — | `—` | 软删除时间，Unix 毫秒；NULL 表示未删除 |

## 4. 活动计时片段：`activity_segments`

状态：开始、暂停、继续、结束或补记时保存。

保存规则：进行中片段结束可空；结束须晚于开始；Repository 检查活动与睡眠重叠，多段整体纠错尚未实现。

| 字段 | SQLite 类型 | 允许 NULL | 主键 | SQL 默认值 | 含义 |
|---|---|---|---|---|---|
| `id` | TEXT | 否 | 第1列 | `—` | 记录 ID；通常由应用生成 UUID |
| `activityId` | TEXT | 否 | — | `—` | 关联 activities.id |
| `startMillis` | INTEGER | 否 | — | `—` | 实际片段开始，Unix 毫秒 |
| `endMillis` | INTEGER | 是 | — | `—` | 实际片段结束，Unix 毫秒；NULL 表示尚未结束 |

## 5. 食物营养表：`food_versions`

状态：当前“记录”可保存。

保存规则：名称 1–120 字；每 100g 热量必填且非负；状态、来源须为列出的枚举。三大营养素可空，填写时非负；来源说明至多 300 字。修改/停用会新增版本，保留旧版。

| 字段 | SQLite 类型 | 允许 NULL | 主键 | SQL 默认值 | 含义 |
|---|---|---|---|---|---|
| `foodId` | TEXT | 否 | 第1列 | `—` | 食物 ID；同一食物各版本共用 |
| `version` | INTEGER | 否 | 第2列 | `—` | 食物版本号 |
| `name` | TEXT | 否 | — | `—` | 名称 |
| `preparedState` | TEXT | 否 | — | `—` | 食物状态：RAW / COOKED / PACKAGED / UNKNOWN |
| `kcalPer100g` | TEXT | 否 | — | `—` | 每 100g 能量，kcal，十进制文本 |
| `sourceType` | TEXT | 否 | — | `—` | 来源：USER_LABEL（标签）/ USER_ESTIMATE（手工估计） |
| `archived` | INTEGER | 否 | — | `—` | 停用标记：0 启用，1 停用 |
| `brand` | TEXT | 是 | — | `—` | 品牌 |
| `proteinGPer100g` | TEXT | 是 | — | `—` | 每 100g 蛋白质，g，十进制文本 |
| `fatGPer100g` | TEXT | 是 | — | `—` | 每 100g 脂肪，g，十进制文本 |
| `carbGPer100g` | TEXT | 是 | — | `—` | 每 100g 碳水，g，十进制文本 |
| `sourceNote` | TEXT | 是 | — | `—` | 营养来源说明 |

## 6. 饮食摄入事件：`intakes`

状态：当前“记录”可保存。

保存规则：餐次和摄入时刻必填；一次包含 1–30 项食物，备注至多 500 字。

| 字段 | SQLite 类型 | 允许 NULL | 主键 | SQL 默认值 | 含义 |
|---|---|---|---|---|---|
| `id` | TEXT | 否 | 第1列 | `—` | 记录 ID；通常由应用生成 UUID |
| `eatenAtMillis` | INTEGER | 否 | — | `—` | 摄入时刻，Unix 毫秒 |
| `timezone` | TEXT | 否 | — | `—` | 记录时区 ID，例如 Asia/Shanghai |
| `mealType` | TEXT | 否 | — | `—` | 餐次：BREAKFAST / LUNCH / DINNER / SNACK |
| `note` | TEXT | 是 | — | `—` | 备注 |
| `version` | INTEGER | 否 | — | `1` | 记录版本号；应用用于更新冲突检查 |
| `deletedAtMillis` | INTEGER | 是 | — | `—` | 软删除时间，Unix 毫秒；NULL 表示未删除 |

## 7. 饮食摄入明细与营养快照：`intake_items`

状态：随摄入事件保存。

保存规则：已知食物需指定 foodId 与 foodVersion；未知营养可手填 1–120 字名称。食用克数可空，填写须大于 0；NULL 与 0 分开。营养快照由应用复制，旧记录不因食物新版自动重算。

| 字段 | SQLite 类型 | 允许 NULL | 主键 | SQL 默认值 | 含义 |
|---|---|---|---|---|---|
| `id` | TEXT | 否 | 第1列 | `—` | 记录 ID；通常由应用生成 UUID |
| `intakeId` | TEXT | 否 | — | `—` | 关联摄入记录 intakes.id |
| `foodId` | TEXT | 是 | — | `—` | 关联 food_versions.foodId；未知营养食物可为空 |
| `foodVersion` | INTEGER | 是 | — | `—` | 关联食物版本 food_versions.version |
| `displayName` | TEXT | 否 | — | `—` | 保存时食物显示名称快照 |
| `consumedGrams` | TEXT | 是 | — | `—` | 实际食用克数，十进制文本；NULL 表示待补 |
| `kcalPer100gSnapshot` | TEXT | 是 | — | `—` | 所用每 100g 能量快照，kcal |
| `sourceTypeSnapshot` | TEXT | 是 | — | `—` | 所用营养来源快照 |
| `proteinGPer100gSnapshot` | TEXT | 是 | — | `—` | 所用每 100g 蛋白质快照，g |
| `fatGPer100gSnapshot` | TEXT | 是 | — | `—` | 所用每 100g 脂肪快照，g |
| `carbGPer100gSnapshot` | TEXT | 是 | — | `—` | 所用每 100g 碳水快照，g |
| `manualName` | TEXT | 是 | — | `—` | 未选食物版本时手填的名称 |
| `nutritionStatus` | TEXT | 否 | — | `—` | 营养完整状态：COMPLETE / MISSING_AMOUNT / MISSING_FOOD |

## 8. 睡眠记录：`sleep_logs`

状态：当前“记录”可保存。

保存规则：入睡、醒来时刻必填，醒来须晚于入睡；夜间清醒分钟可空或 0–10080，且短于整个时段；与睡眠及实际活动不重叠。按醒来日展示。删除为物理删除。

| 字段 | SQLite 类型 | 允许 NULL | 主键 | SQL 默认值 | 含义 |
|---|---|---|---|---|---|
| `id` | TEXT | 否 | 第1列 | `—` | 记录 ID；通常由应用生成 UUID |
| `onsetMillis` | INTEGER | 否 | — | `—` | 入睡时间，Unix 毫秒 |
| `wakeMillis` | INTEGER | 否 | — | `—` | 醒来时间，Unix 毫秒 |
| `awakeSeconds` | INTEGER | 是 | — | `—` | 已记录夜间清醒秒数；表单填写分钟后转为秒 |
| `timezone` | TEXT | 否 | — | `—` | 记录时区 ID，例如 Asia/Shanghai |
| `version` | INTEGER | 否 | — | `—` | 记录版本号；应用用于更新冲突检查 |

## 9. 独立记账本：`ledger_entries`

状态：当前“记录”可保存。

保存规则：类型、发生时间、来源和金额必填；来源 1–100 字；金额 >0、至多两位小数、精度至多 14 位；账本当前没有币种字段。

| 字段 | SQLite 类型 | 允许 NULL | 主键 | SQL 默认值 | 含义 |
|---|---|---|---|---|---|
| `id` | TEXT | 否 | 第1列 | `—` | 记录 ID；通常由应用生成 UUID |
| `occurredAtMillis` | INTEGER | 否 | — | `—` | 收支发生时刻，Unix 毫秒 |
| `timezone` | TEXT | 否 | — | `—` | 记录时区 ID，例如 Asia/Shanghai |
| `type` | TEXT | 否 | — | `—` | 收支类型：INCOME / EXPENSE |
| `source` | TEXT | 否 | — | `—` | 收入或支出来源 |
| `amount` | TEXT | 否 | — | `—` | 正金额，十进制文本 |
| `version` | INTEGER | 否 | — | `—` | 记录版本号；应用用于更新冲突检查 |
| `deletedAtMillis` | INTEGER | 是 | — | `—` | 软删除时间，Unix 毫秒；NULL 表示未删除 |

## 10. 本机档案：`local_users`

状态：当前“我的”可保存。

保存规则：进入“我的”时自动建立稳定 ID；昵称可空、至多 40 字；不等于登录账户。

| 字段 | SQLite 类型 | 允许 NULL | 主键 | SQL 默认值 | 含义 |
|---|---|---|---|---|---|
| `id` | TEXT | 否 | 第1列 | `—` | 记录 ID；通常由应用生成 UUID |
| `displayName` | TEXT | 是 | — | `—` | 可选昵称 |
| `createdAtMillis` | INTEGER | 否 | — | `—` | 创建时间，Unix 毫秒 |

## 11. 活动主时间类别：`time_categories`

状态：当前活动保存时可自动建立；独立管理入口未接入当前三页。

保存规则：名称非空且至多 50 字，数据库要求名称唯一；停用后不用于新活动。

| 字段 | SQLite 类型 | 允许 NULL | 主键 | SQL 默认值 | 含义 |
|---|---|---|---|---|---|
| `id` | TEXT | 否 | 第1列 | `—` | 记录 ID；通常由应用生成 UUID |
| `name` | TEXT | 否 | — | `—` | 名称 |
| `archived` | INTEGER | 否 | — | `—` | 停用标记：0 启用，1 停用 |

## 12. 项目：`projects`

状态：存储层可写；活动可选择已存项目，当前三页没有新增项目入口。

保存规则：名称非空且至多 80 字，数据库要求名称唯一；支持停用。

| 字段 | SQLite 类型 | 允许 NULL | 主键 | SQL 默认值 | 含义 |
|---|---|---|---|---|---|
| `id` | TEXT | 否 | 第1列 | `—` | 记录 ID；通常由应用生成 UUID |
| `name` | TEXT | 否 | — | `—` | 名称 |
| `archived` | INTEGER | 否 | — | `—` | 停用标记：0 启用，1 停用 |

## 13. 活动标签：`activity_tags`

状态：当前活动编辑器可保存。

保存规则：逗号分隔，去空、去重；一次至多 8 个标签，每个至多 30 字。

| 字段 | SQLite 类型 | 允许 NULL | 主键 | SQL 默认值 | 含义 |
|---|---|---|---|---|---|
| `activityId` | TEXT | 否 | 第1列 | `—` | 关联 activities.id |
| `name` | TEXT | 否 | 第2列 | `—` | 名称 |

## 14. 回报模型版本：`value_model_versions`

状态：存储层保留，当前页面入口暂停。

保存规则：与维度在同一事务保存；至少一个维度，修改模型会新增版本。

| 字段 | SQLite 类型 | 允许 NULL | 主键 | SQL 默认值 | 含义 |
|---|---|---|---|---|---|
| `modelId` | TEXT | 否 | 第1列 | `—` | 回报模型 ID |
| `version` | INTEGER | 否 | 第2列 | `—` | 模型版本号 |
| `confirmedAtMillis` | INTEGER | 否 | — | `—` | 模型确认时间，Unix 毫秒 |

## 15. 回报维度与权重：`value_dimensions`

状态：存储层保留，当前页面入口暂停。

保存规则：维度名称非空、不重复；权重必须为正数。

| 字段 | SQLite 类型 | 允许 NULL | 主键 | SQL 默认值 | 含义 |
|---|---|---|---|---|---|
| `modelId` | TEXT | 否 | 第1列 | `—` | 回报模型 ID |
| `modelVersion` | INTEGER | 否 | 第2列 | `—` | 回报模型版本 |
| `dimensionId` | TEXT | 否 | 第3列 | `—` | 维度 ID |
| `name` | TEXT | 否 | — | `—` | 名称 |
| `weight` | TEXT | 否 | — | `—` | 用户定义权重，正十进制文本 |

## 16. 活动评价：`activity_evaluations`

状态：存储层保留，当前页面入口暂停。

保存规则：评价关联未删除且已结束的活动和模型版本。

| 字段 | SQLite 类型 | 允许 NULL | 主键 | SQL 默认值 | 含义 |
|---|---|---|---|---|---|
| `activityId` | TEXT | 否 | 第1列 | `—` | 关联 activities.id |
| `modelId` | TEXT | 否 | 第2列 | `—` | 回报模型 ID |
| `modelVersion` | INTEGER | 否 | 第3列 | `—` | 回报模型版本 |
| `evaluatedAtMillis` | INTEGER | 否 | — | `—` | 评价时间，Unix 毫秒 |
| `version` | INTEGER | 否 | — | `—` | 记录版本号；应用用于更新冲突检查 |

## 17. 各维度评分：`dimension_ratings`

状态：存储层保留，当前页面入口暂停。

保存规则：评分维度必须与模型一致；值为 0–10，NULL 表示未评分。

| 字段 | SQLite 类型 | 允许 NULL | 主键 | SQL 默认值 | 含义 |
|---|---|---|---|---|---|
| `activityId` | TEXT | 否 | 第1列 | `—` | 关联 activities.id |
| `modelId` | TEXT | 否 | 第2列 | `—` | 回报模型 ID |
| `modelVersion` | INTEGER | 否 | 第3列 | `—` | 回报模型版本 |
| `dimensionId` | TEXT | 否 | 第4列 | `—` | 维度 ID |
| `score` | TEXT | 是 | — | `—` | 评分 0–10，十进制文本；NULL 表示未评分 |

## 验证边界与参考

本轮核对的是实际字段及 SQL 存储互通，没有新增 MySQL / HTTP 后端，没有启用已暂停的回报页面。批量复用的日期范围/星期用于生成独立 plans 记录，尚无重复规则专表；已知热量、完成数、睡眠时长等由保存的记录计算，不是独立存储列；备份 JSON 是导出格式，不另建备份业务表。

完整数据库结构来源：[`10.json`](../android/app/schemas/com.dayoudu.app.data.NutritionDatabase/10.json)。建库与查看方法见[本地数据库说明](../android/本地数据库.md)。

官方参考：[Room DAO](https://developer.android.com/training/data-storage/room/accessing-data)、[SupportSQLiteDatabase](https://developer.android.com/reference/androidx/sqlite/db/SupportSQLiteDatabase)。
