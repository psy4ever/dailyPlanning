# 日有度｜域名、HTTPS 与公网安全上线

检查日期：2026-10-22。适用环境：腾讯云上海轻量应用服务器，Ubuntu 24.04，公网 IP `43.142.121.249`，Docker Compose 中的 MySQL、API、Caddy。域名**尚未购买**。本文是操作顺序和代码审查结果，不表示已经完成备案、证书签发或公网安全测试。三模块功能验收仍按[部署与手机联调](18-云端部署与手机联调操作.md)第 4 节执行。

## 1. 先购买并备案，再启用公网 HTTPS

1. 在当前服务器所属的腾讯云账号购买一个能用于中国大陆备案的域名。为减少操作环节，可以使用同一腾讯云账号的[域名注册](https://cloud.tencent.com/document/product/242/9595)。先完成账号实名和域名实名，核对域名持有人、续费价、自动续费设置及备案支持的后缀。域名是独立费用；不要购买“单独的付费 SSL 证书”，现有 Caddy 可在条件满足后申请公开可信证书。不要将域名账号密码、证件或 App 密码发到聊天。
2. 因服务器在中国大陆，先到腾讯云 [ICP 备案控制台](https://cloud.tencent.com/document/product/243/39038)按实际服务提交网站/域名与 APP 的适用备案。腾讯云说明，域名解析到境内云资源并对外提供服务前需取得相应备案；[备案云资源要求](https://cloud.tencent.com/document/product/243/18908)包括轻量应用服务器的购买期限等。按控制台审核要求办理，**不要先解析并公开未备案域名**。App 面向中国大陆用户提供互联网信息服务的备案要求另见[工信部通知](https://www.miit.gov.cn/jgsj/xgj/wjfb/art/2023/art_dd783a581c9644a4aee10afa582811db.html)。若备案流程对“仅有 API、尚无网站首页”的服务归类有疑问，以腾讯云备案审核答复为准。
3. 备案通过后，决定唯一 API 子域名，例如 `api.<已购买的域名>`。在[腾讯云 DNS 解析](https://cloud.tencent.com/document/product/302/3446)添加一条 `A` 记录：主机记录 `api`，记录值 `43.142.121.249`，线路默认。不要顺手添加无用的 `@`、`www`、通配符或错误的 `AAAA` 记录。`api.<已购买的域名>`只是格式说明，命令中必须换成真实域名。
4. 先处理第 3 节列出的登录限流、备份等公网前缺口。然后在腾讯云轻量应用服务器的[防火墙](https://cloud.tencent.com/document/product/1207/89060)放行入站 TCP `80`、`443`。保留 SSH `22` 供管理；**不要**放行 `3306`、`18080`、`8080`。服务器上的其他防火墙若已启用，也需只允许对应端口。80 用于证书验证和自动跳转 HTTPS，不承载明文登录。
5. 本机 PowerShell 核对 DNS。以下命令中的域名用真实值替换：

   ```powershell
   $apiHost = 'api.你的真实域名'
   Resolve-DnsName -Name $apiHost -Type A
   ```

   A 记录须是 `43.142.121.249`。如存在 AAAA 记录，须确保它也指向可用的 IPv6 服务；否则删除错误记录。DNS 刚修改时可能需要等待生效。
6. 已登录的服务器终端进入 `~/daily-rhythm`，执行 `nano .env`，在文件末尾增加且只增加一行 `API_DOMAIN=api.你的真实域名`。保存后不要展示或转发整个 `.env`；里面有数据库及 App 初始密码。确认 `compose.https.yaml` 和 `Caddyfile` 已从本机部署包上传到该目录。然后执行：

   ```bash
   cd ~/daily-rhythm
   sudo docker compose -f compose.yaml -f compose.api.yaml -f compose.https.yaml config --quiet
   sudo docker compose -f compose.yaml -f compose.api.yaml -f compose.https.yaml up -d --wait --wait-timeout 240 gateway
   sudo docker compose -f compose.yaml -f compose.api.yaml -f compose.https.yaml ps
   sudo docker compose -f compose.yaml -f compose.api.yaml -f compose.https.yaml logs --tail 40 gateway
   ```

   `Caddyfile` 的 `{$API_DOMAIN}` 站点和 `compose.https.yaml` 的 80/443、证书持久卷已经写好。[Caddy 官方文档](https://caddyserver.com/docs/automatic-https)说明：公开域名解析、80/443 可达、证书数据目录可写时，会自动申请和续期证书并将 HTTP 跳转到 HTTPS。
7. 回到**本机 PowerShell**，验证真实证书和路由，不使用 `-k` 或跳过证书验证：

   ```powershell
   $apiHost = 'api.你的真实域名'
   curl.exe --fail --show-error "https://$apiHost/health"
   curl.exe -i "http://$apiHost/health"
   ```

   第一条应为 `{"status":"ok"}`；第二条应显示跳转到 HTTPS。若失败，先查 DNS、备案状态、80/443 防火墙和 gateway 日志，不修改 App 以绕过 TLS。
8. 验证备份与三个模块的真实云端往返，再按照[Android 构建配置](../android/app/build.gradle.kts)用 `-PcloudApiBaseUrl=https://真实API域名` 构建正式版并签名。发布版登录页不填写网址。当前模拟器安装的是调试签名包；切换为另一签名的发布包可能不能覆盖安装，**先完成云端和独立备份验证，不要直接卸载有数据的 App**。

## 2. 已具备的防护和证据

| 范围 | 当前状态 | 依据 |
|---|---|
| 端口隔离 | 本机从外网仅探测到 22 可连接；80/443、3306、18080 均不可连接。只代表 2026-10-22 的一次 TCP 可达性检查，不等于完整端口扫描。 | `compose.yaml` 未映射 MySQL 端口；`compose.api.yaml` 把 API 映射到 `127.0.0.1:18080`。 |
| 登录和数据隔离 | 密码存 BCrypt 哈希；会话令牌由随机数生成，数据库存令牌哈希；业务 SQL 以认证账户作为 owner 并使用参数绑定。 | `Authentication.java`、`SyncService.java`。代码审查结论，未做渗透测试。 |
| 请求和容器 | Caddy 限制请求体 2 MB，服务端 POST 再检查长度，单条同步约 1 MB 上限；API 容器只读、降权并限制内存。 | `Caddyfile`、`Authentication.java`、`SyncService.java`、`compose.api.yaml`。这些限制不等于抗 DDoS。 |
| 手机令牌 | Android Keystore AES-GCM 加密保存令牌，发布配置只接受 HTTPS。 | `CloudSync.kt`。 |
| 备份 | 有加密、HMAC 校验和隔离恢复脚本。 | `backup.py`、`restore-check.py`。**尚未在这台正式服务器完成恢复演练和异机副本验证**。 |

## 3. 公开 80/443 前要处理的缺口

1. **登录限流的来源识别不正确。** `Authentication.java` 以 `HttpServletRequest.getRemoteAddr()` 为限流键。经 Caddy 反代时，后端看到的通常是代理容器地址；当前 10 次登录、5 次注册/分钟限制可能成为全体用户共享的桶，攻击者可使正常登录被拒绝。Caddy 默认转发 `X-Forwarded-For`，但应用尚未在可信代理边界使用它。上线前实现可信来源的真实客户端 IP 识别，并同时做每账户/UID 与每 IP 的限制；不要直接信任用户随意提供的 `X-Forwarded-For`。[Caddy 转发头说明](https://caddyserver.com/docs/caddyfile/directives/reverse_proxy)、[OWASP 限流检查](https://cheatsheetseries.owasp.org/cheatsheets/REST_Assessment_Cheat_Sheet.html)。
2. **注册端点公开且 UID 可顺序猜测。** 这符合八位数字 UID 的产品需求，但 UID 不是秘密。若仅供自己使用，正式公网版本应关闭自由注册或改为仅受控邀请，保留已有账号登录；否则需要更完善的注册滥用防护。8–16 位密码上限是此前的产品选择；实际使用请选不重复的高随机性 16 位密码，勿复用 SSH/MySQL 密码。
3. **限流只在单个进程内存中，重启可重置；同步接口也没有持续请求速率/总资源配额。** 现有大小限制能挡住过大的单个 POST，不能防止大量小请求、撞库或流量型攻击。公开后要加监控和告警；若将来有更多用户，再评估网关限流和云防护服务。不要把 HTTPS 当成 WAF 或抗 DDoS。
4. **主机和恢复状态未完成验证。** 登录服务器执行安全更新检查，安排维护时段更新 Ubuntu/Docker/固定镜像；先备份并验证恢复，再启用每日定时备份，并把密文副本与密钥分别保存在服务器之外。记录 TLS 证书到期、API/db 健康和备份失败的告警。SSH 仍通过密码登录，建议先配置并验证个人 SSH 密钥，再考虑关闭密码登录，避免远程锁定自己。
5. **上线验收尚未完成。** 用真实手机验证 HTTPS 下注册/登录、计划/记账/饮食写入和另一设备恢复；检查未认证接口返回 401、账户间不能互读、错误响应不含栈或密码。应用商店另需隐私政策、账户删除等合规工作。

结论：当前配置适合 SSH 隧道下个人联调，**尚不应把 80/443 打开后视为已具备完整的公网攻击防护**。先完成备案、限流修正、备份恢复和真机验收，再使用正式 HTTPS。
