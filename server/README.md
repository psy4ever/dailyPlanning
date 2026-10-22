# Daily Rhythm server

计划、记账、饮食统一同步后端。实际契约及实现映射见[文档 17](../deliverables/17-三模块云同步实现映射与验收.md)，云端部署见[文档 18](../deliverables/18-云端部署与手机联调操作.md)。

0.3.2 沿用记录卡片同步契约 v2，新增个人云服务注册开关。公网 IP HTTPS 直连部署默认关闭新账户注册，保留已有账户登录，并只通过网关可信转发头识别客户端地址；步骤见[手机直连](../手机直连-启用步骤.md)。历史账目兼容读取，不需要重建 MySQL。字段与验收见[文档 23](../deliverables/23-记录卡片原型实现与数据库迁移.md)。

编译目标 Java 17，生产容器 Java 21；Spring Boot 3.5.16、Flyway 11.20.3、Gradle 8.13。

```powershell
.\gradlew.bat test bootJar --console=plain
```

默认测试使用独立 H2 MySQL 模式；可通过 `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` 运行到专用 MySQL 测试库。测试会创建隔离测试账户，不向生产数据库运行测试。

模拟器集成测试的本机回环服务器：

```powershell
.\gradlew.bat smokeServer --console=plain
```

只监听 127.0.0.1:18080，使用测试资源中的 H2 和明确标记的测试账户；测试资源不会打包进生产 JAR。正式运行需要 `MYSQL_PASSWORD`，首次初始化还需要 `DAILY_USERNAME/DAILY_PASSWORD`；`DAILY_USERNAME` 保留为初始昵称和旧版登录别名，不再作为数字 UID 输入。Flyway V3 将既有账户依次改成八位数字 UID，保留 owner ID、密码哈希、昵称和业务记录。`POST /api/v1/auth/register` 只接收昵称与密码，在数据库事务中发放 UID；`POST /api/v1/auth/login` 接收 `accountName`/密码，也兼容 UID 与旧登录名。同名昵称可用不同密码区分；同名且密码相同则要求使用 UID。当前生产 API 的主机映射为回环地址，由 Caddy 网关提供 `https://43.142.121.249` 公网入口，日常访问不需要 SSH 隧道。

当前用户查询命令与后续公共食物审核方案见[后台管理说明](../后台管理-用户与公共食物审核.md)；管理员网页与审核接口尚未实现。
