# 日有度（Daily Rhythm）

Android 优先的私人计划、行为时间、作息与饮食记录应用方案。目标是让计划、执行打卡与回顾形成闭环；个人回报由用户自定义维度和权重，不等同于工资收入。

当前 Android 版为“今天 / 记录 / 我的”三页；洞察与回报权重入口暂停。单日计划只选时分，批量复用才选日期范围；记录页已有独立记账本。见[本轮映射](deliverables/10-日期归属批量计划记账与用户映射.md)。

## 当前交付

- [Android 本地版](android/README.md)：三页离线计划、计时、睡眠、饮食、记账、提醒与备份；需 Android Studio 或 APK 在设备运行。
- [核心功能映射与验收](deliverables/07-核心功能实现映射.md)、[初框架记录](deliverables/06-初框架映射与验收.md)与[开发指令记录](开发指令记录.md)。
- [早期四页交互原型](prototype/index.html)：包含已暂停的洞察与权重方案；数据均为示例，刷新后重置，当前 Android 页面以本轮映射为准。
- [需求与首版设计](deliverables/01-产品需求与首版设计.md)
- [技术架构、开发工具与算法](deliverables/02-技术架构与算法.md)
- [上架与自用完成度分析](deliverables/03-上架与产品价值判断.md)
- [原型—接口—代码映射](deliverables/04-原型接口代码映射.md)
- [HTTP API 与字段契约](deliverables/05-接口字段设计.md)
- [最新页面概念图](deliverables/design/screens-v3-nutrition.png)和[图标概念稿](deliverables/design/app-icon-v1.png)

直接用浏览器打开 `prototype/index.html` 即可查看原型；无需安装依赖或联网。若浏览器限制本地脚本，也可在仓库根目录运行 `python -m http.server 8767`，再访问 `http://127.0.0.1:8767/prototype/index.html`。

## 实现状态

目前已有**需求、架构、API 设计、视觉稿、独立 HTML/CSS/JavaScript 早期演示原型，以及可构建 Debug APK 的 Android 本地版**。当前 Android 入口实现单日计划、批量复用、实际时间、睡眠、饮食、记账、本机档案、提醒和备份；旧回报代码与数据暂留但入口暂停。尚未在真机完成运行验收，也未创建 Spring Boot 服务或 MySQL 数据库。HTTP 接口仍为设计稿，浏览器原型只使用示例数据。

后续优先进行真机边界验证与交互优化，再完成多段计时纠错和重叠拆分。联网同步、饮水、周期、自定义记录及 AI 属于后续阶段。

详细范围和验证记录见 [交付总览](deliverables/README.md)。
