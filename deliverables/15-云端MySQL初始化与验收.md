# 云端 MySQL 初始化与验收

日期：2026-10-21。用户已完成本轮云端数据库基础配置与验收：MySQL 8.4.11 容器 `daily-rhythm-db-1` 为 `healthy`，数据库为 `daily_rhythm`，账号为 `daily_rhythm_app@%`；中文及金额提交、读取通过，`committed_ok=1`；重启服务后记录不变，`persisted_ok=1`、`rollback_still_ok=1`。持久卷为 `daily-rhythm-mysql-data`。Android 保留 Room 并已实现三模块同步代码，本地集成测试通过。用户随后启动云端 API，两个容器均为 `healthy`，`/health` 返回 `{"status":"ok"}`；手机端真实云记录读写仍待验收。

## 配置内容

- 使用用户实际下载的镜像 digest：`sha256:0f51e63e537a1f176423fa03ef559aabe6fa4372a53160a44bc7e75da7d15364`。不猜测该镜像的补丁版本，以 `SELECT VERSION()` 为准。
- Compose 项目 `daily-rhythm`，服务 `db`；数据库 `daily_rhythm`，应用账号 `daily_rhythm_app`，仅获得该库的权限。
- 两个独立随机密码保存在服务器 `~/daily-rhythm/.env`，文件以 600 权限创建，目录以 700 权限创建。重复运行生成器保留已有文件；密码不输出到聊天或日志。`.env` 已被仓库忽略。
- 数据目录 `/var/lib/mysql` 挂载到命名卷 `daily-rhythm-mysql-data`。容器重建复用同一卷；持久卷不能替代独立备份。
- 不配置 `ports`，不放行公网 3306。后端加入同一 Compose 网络后使用 `db:3306` 访问。
- 健康检查以应用账号通过 TCP 运行 `SELECT 1`，同时验证账号、密码和数据库可用。容器配置自动重启和日志轮转。

初始化变量的行为与持久卷用法见 [MySQL 官方镜像说明](https://hub.docker.com/_/mysql)。启动等待参数见 [Compose up 官方文档](https://docs.docker.com/reference/cli/docker/compose/up/)。初始化变量仅在空数据目录首次启动时生效，修改 `.env` 不会自动修改已有数据库密码；不要删除已有凭据文件重新生成。

## 上传并准备配置

以下上传方式使用仓库本地实际文件，无需 Git 提交或推送。已有支持粘贴的 SSH 窗口，也可以把 Python 脚本和 Compose 内容作为 heredoc 在服务器写入相同路径。

在另一个【本机 PowerShell】窗口执行（替换服务器 IP）：

```powershell
scp C:\code\dailyPlanning\deploy\compose.yaml C:\code\dailyPlanning\deploy\prepare-mysql-env.py ubuntu@<服务器公网IP>:~/
```

回到【云服务器 SSH 终端】执行：

```bash
python3 ~/prepare-mysql-env.py
cp -n ~/compose.yaml ~/daily-rhythm/compose.yaml
cd ~/daily-rhythm
sudo docker compose config --quiet
```

`cp -n` 保留已有部署文件；若需要更新已有配置，应先比较差异。生成脚本不需要 sudo。`config --quiet` 成功时没有输出；不要直接分享 `docker compose config` 输出，它可能包含密码。

本轮用户已通过 SCP 上传文件，并反馈 `Created .env; credentials were not displayed.`。`cp -n` 返回关于未来行为变化的 warning，不代表本次复制失败；后续 Compose 已启动成功。服务器执行前应确认当前目录和已有文件；已有 `.env` 继续保留。

## 启动和连接验收

在【云服务器 SSH 终端】的 `~/daily-rhythm` 目录执行：

```bash
sudo docker compose config --quiet && sudo docker compose up -d --pull never --wait --wait-timeout 240
sudo docker compose ps
```

使用 `--pull never` 运行已经下载的固定镜像。首次初始化可能需要数分钟，等待服务 healthy；若报错，先检查错误，不反复重新生成密码或删除数据卷。

当服务 healthy 后执行（只显示版本、当前数据库和认证账号，不显示密码）：

```bash
sudo docker compose exec -T db sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql --protocol=TCP -h 127.0.0.1 -u "$MYSQL_USER" "$MYSQL_DATABASE" --execute="SELECT VERSION(), DATABASE(), CURRENT_USER();"'
```

预期数据库为 `daily_rhythm`，认证账号为 `daily_rhythm_app@%`，版本为镜像实际 MySQL 8.4 补丁版本。

启动失败时，在当前目录收集：

```bash
sudo docker compose ps -a
sudo docker compose logs --tail 60 db
```

当前配置没有设置将随机 root 密码打印到日志的选项。分享报错前仍应遮盖可能出现的个人信息。

## SQL 读写与事务验收（已完成，命令留作复现）

测试 SQL 已保存到 [`verify-mysql-write.sql`](../deploy/verify-mysql-write.sql)。只创建 `_deploy_verify_20261021` 验收表，不写业务模块。建表没有 `IF NOT EXISTS`，如已有同名表会停止执行，先核对之前的验收结果，不覆盖已有表。

在【云服务器 SSH 终端】的 `~/daily-rhythm` 目录执行以下整块命令，包括末尾独立一行 `SQL`：

```bash
sudo docker compose exec -T db sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql --default-character-set=utf8mb4 --protocol=TCP -h 127.0.0.1 -u "$MYSQL_USER" "$MYSQL_DATABASE"' <<'SQL'
SELECT VERSION() AS mysql_version, DATABASE() AS database_name, CURRENT_USER() AS account;
CREATE TABLE _deploy_verify_20261021 (
    id INT PRIMARY KEY,
    title VARCHAR(100) NOT NULL,
    amount DECIMAL(10, 2) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
START TRANSACTION;
INSERT INTO _deploy_verify_20261021 VALUES (1, '云端存储测试', 12.34);
COMMIT;
START TRANSACTION;
INSERT INTO _deploy_verify_20261021 VALUES (2, '这条应该回滚', 99.00);
ROLLBACK;
SELECT id, title, amount FROM _deploy_verify_20261021 ORDER BY id;
SELECT COUNT(*) = 1 AS committed_ok FROM _deploy_verify_20261021
WHERE id = 1 AND title = '云端存储测试' AND amount = 12.34;
SELECT COUNT(*) = 0 AS rollback_ok FROM _deploy_verify_20261021 WHERE id = 2;
SQL
```

预期 `database_name` 为 `daily_rhythm`，`account` 为 `daily_rhythm_app@%`；仅一行记录 `1 / 云端存储测试 / 12.34`，`committed_ok` 和 `rollback_ok` 均为 1。事务语义见 [MySQL 8.4 事务说明](https://dev.mysql.com/doc/refman/8.4/en/commit.html)。

本轮用户实际执行后反馈：`VERSION()=8.4.11`、`DATABASE()=daily_rhythm`、`CURRENT_USER()=daily_rhythm_app@%`；查询只显示 `1 / 云端存储测试 / 12.34`，`committed_ok=1`。这些输出确认中文和小数金额提交后读取成功。首次粘贴缺少最后的 `rollback_ok` 输出，随后已通过重启后的 `rollback_still_ok=1` 补齐回滚证据。当前无需重跑建表和插入步骤。

## 重启后持久化（已通过）与清理（待执行）

在同一云服务器目录执行重启并等待健康检查：

```bash
sudo docker compose restart db && sudo docker compose up -d --pull never --wait --wait-timeout 240 db
```

随后执行 [`verify-mysql-read.sql`](../deploy/verify-mysql-read.sql) 对应查询：

```bash
sudo docker compose exec -T db sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql --default-character-set=utf8mb4 --protocol=TCP -h 127.0.0.1 -u "$MYSQL_USER" "$MYSQL_DATABASE"' <<'SQL'
SELECT id, title, amount FROM _deploy_verify_20261021 ORDER BY id;
SELECT COUNT(*) = 1 AS persisted_ok FROM _deploy_verify_20261021
WHERE id = 1 AND title = '云端存储测试' AND amount = 12.34;
SELECT COUNT(*) = 0 AS rollback_still_ok FROM _deploy_verify_20261021 WHERE id = 2;
SQL
```

预期记录不变，两个检查值均为 1。用户实际反馈重启后容器恢复 `Healthy`，查询仍为 `1 / 云端存储测试 / 12.34`，`persisted_ok=1`、`rollback_still_ok=1`，本项通过。这验证了服务重启后保留数据，不等同于独立备份恢复或整机灾难恢复验收。重启命令见 [Docker 官方说明](https://docs.docker.com/reference/cli/docker/compose/restart/)。

记录并确认结果后，仅清理本次验收表：

```bash
sudo docker compose exec -T db sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql --protocol=TCP -h 127.0.0.1 -u "$MYSQL_USER" "$MYSQL_DATABASE" --execute="DROP TABLE _deploy_verify_20261021;"'
```

## 后续边界

数据库基础设施已可供后端接入。用户已明确本轮同时覆盖计划、记账、饮食三个模块，范围见[三模块接入与验收](16-三模块云端接入范围与验收.md)：共同账户与认证、三个模块的业务迁移和 API、Android 同步队列、受限联调及 HTTPS，然后验证三个模块均可从手机写入云端并恢复到新测试设备。正式保存重要个人数据前，还需完成独立备份和恢复演练。

此时不直接把本地 17 张 Room 表复制成云端业务表；云端业务表还需要账户归属、迁移和同步规则。

不要运行 `docker compose down -v` 或删除 `daily-rhythm-mysql-data`，它们会删除数据库持久卷。当前后端、迁移、鉴权及 Android 同步代码已完成本地集成测试，实际实现见[文档 17](17-三模块云同步实现映射与验收.md)，下一步执行[云端部署](18-云端部署与手机联调操作.md)。本文件中的步骤只验收数据库基础设施。
