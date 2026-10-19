# 日有度（Daily Rhythm）

Android 优先的私人计划、行为时间、作息与饮食记录应用方案。目标是让计划、执行打卡与回顾形成闭环；个人回报由用户自定义维度和权重，不等同于工资收入。

## 当前交付

- [可离线运行的四页交互原型](prototype/index.html)：今天、记录、洞察、我的；可演示食物营养表、三餐及多次加餐、已知热量、时间条和权重计算。数据均为示例，刷新后重置。
- [需求与首版设计](deliverables/01-产品需求与首版设计.md)
- [技术架构、开发工具与算法](deliverables/02-技术架构与算法.md)
- [上架与自用完成度分析](deliverables/03-上架与产品价值判断.md)
- [原型—接口—代码映射](deliverables/04-原型接口代码映射.md)
- [HTTP API 与字段契约](deliverables/05-接口字段设计.md)
- [最新页面概念图](deliverables/design/screens-v3-nutrition.png)和[图标概念稿](deliverables/design/app-icon-v1.png)

直接用浏览器打开 `prototype/index.html` 即可查看原型；无需安装依赖或联网。若浏览器限制本地脚本，也可在仓库根目录运行 `python -m http.server 8767`，再访问 `http://127.0.0.1:8767/prototype/index.html`。

## 实现状态

目前已有**需求、架构、API 设计、视觉稿及独立 HTML/CSS/JavaScript 演示原型**。尚未创建 Android Studio 工程、Spring Boot 服务或 MySQL 数据库；文档中的模块路径和接口均为待实现方案，原型不是可安装 APK，也不保存真实生活数据。

建议开发顺序：Android 本地计划/实际时间记录与打卡 → 手工食物营养表及三餐/加餐 → 离线统计和自定义权重 → 数据导出备份 → 可选服务端同步 → 饮水、周期、自定义记录和 AI。核心模块能独立使用后，再决定是否开发上架能力。

详细范围和验证记录见 [交付总览](deliverables/README.md)。
