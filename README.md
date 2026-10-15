# OntoForge · 砺元

**Onto**logy + **Forge**：本体在真实工业数据、来源核验和业务闭环中不断锻造。

> **砺**：磨砺、精炼；**元**：根本、元素。面向工业制造的 Enterprise-native Operational Ontology。

面向企业与工业制造的**受治理语义运营本体平台**：Native Object/Link、来源绑定、ObjectSet、确定性决策与受控 Action，通过一致的来源、版本、授权和执行证据为应用与 Agent 提供可信业务上下文。

**[阅读唯一现行总体架构：V1.3](./doc/本体产品总体功能与技术架构设计_v1.3.md)**

- [现行 Native DSL/AST V0.2 技术合同](./doc/design/核心本体元模型与Native-DSL-AST契约_v0.2.md)
- [文档目录、研究证据与历史清理说明](./doc/README.md)
- [PostgreSQL ObjectSet / Action Outbox 研究实验](./experiments/pg-runtime/README.md)

> **现阶段为 S0-MVP-0.1 本地工程验证**，不是可生产使用的本体产品。用户/权限/工作空间/业务系统连接尚未实现；所有架构选择仍以 V1.3 和阶段 Gate 为准。

## S0-MVP-0.1：最小启动路径（本地开发）

准备：Java 21、Maven、Node.js 22、Docker Desktop。

1. 在仓库根目录执行：`docker compose up -d`，启动只绑定本地端口的 PostgreSQL 17。
2. 另开终端执行：`mvn -pl ontoforge-boot spring-boot:run`，启动 Spring Boot 与 Flyway 迁移。
3. 再开终端，在 `ontoforge-ui` 执行 `npm install` 和 `npm run dev`，浏览器访问 [http://127.0.0.1:5173](http://127.0.0.1:5173)。
4. 页面应实时显示 API 与 PostgreSQL 连接成功，真实状态接口为 `http://127.0.0.1:8080/api/system/status`；停止后端后刷新会显示连接错误，而不是伪造数据。

详细阶段说明：[S0 极简 MVP 迭代与验收计划](./doc/implementation/S0-MVP-迭代计划.md)。

**安全范围**：此版本没有认证与权限控制，仅适合隔离的本地开发环境；数据库密码为开发演示固定值，不得直接对外开放服务或接入生产数据。

工程目录与长期模块命名规范：[S0 模块规划](./doc/implementation/S0-工程模块规划与命名规范.md)；品牌及兼容性说明：[OntoForge · 砺元命名约定](./doc/implementation/OntoForge-品牌与工程命名约定.md)。
