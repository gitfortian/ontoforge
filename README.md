# OntoForge · 砺元

**Onto**logy + **Forge**：本体在真实工业数据、来源核验和业务闭环中不断锻造。

> **砺**：磨砺、精炼；**元**：根本、元素。面向工业制造的 Enterprise-native Operational Ontology。

面向企业与工业制造的**受治理语义运营本体平台**：Native Object/Link、来源绑定、ObjectSet、确定性决策与受控 Action，通过一致的来源、版本、授权和执行证据为应用与 Agent 提供可信业务上下文。

**[阅读唯一现行总体架构：V1.3](./doc/本体产品总体功能与技术架构设计_v1.3.md)**

- [现行 Native DSL/AST V0.2 技术合同](./doc/design/核心本体元模型与Native-DSL-AST契约_v0.2.md)
- [文档目录、研究证据与历史清理说明](./doc/README.md)
- [PostgreSQL ObjectSet / Action Outbox 研究实验](./experiments/pg-runtime/README.md)

> **现阶段为 S0-MVP-0.1 本地工程验证**，不是可生产使用的本体产品。用户/权限/工作空间/业务系统连接尚未实现；所有架构选择仍以 V1.3 和阶段 Gate 为准。

## S0-MVP-0.1：Windows 11 本机 PostgreSQL（默认开发方式）

准备：Java 21、Maven、Node.js 22、已安装运行的**本机 PostgreSQL**，不需要 Docker Desktop。

1. 在 Windows PowerShell 中确认 PostgreSQL 服务运行，并创建或复用本机数据库 `ontoforge`（默认 `127.0.0.1:5432`，用户 `postgres`）；**已有数据库不要重建**。
2. 在启动后端的同一个 PowerShell 窗口设置 `DB_PASSWORD`，它**没有代码内置的默认值**：

   ```powershell
   $env:DB_URL = 'jdbc:postgresql://127.0.0.1:5432/ontoforge'
   $env:DB_USERNAME = 'postgres'
   $env:DB_PASSWORD = [System.Net.NetworkCredential]::new('', (Read-Host 'PostgreSQL password' -AsSecureString)).Password
   mvn -pl ontoforge-boot spring-boot:run
   ```

3. 另开 PowerShell 进入 `ontoforge-ui`，运行 `npm install` 和 `npm run dev`；访问 [http://127.0.0.1:5173](http://127.0.0.1:5173)。
4. 新开 PowerShell，重新设置相同的 DB_* 环境变量，并在仓库根目录运行 `.\scripts\verify-s0.ps1`（`psql` 未加入 PATH 时传 `-PsqlPath`）。脚本只读校验 Flyway V1、数据库 UUID 与真实 API 是否一致；重启后端后可通过 `-ExpectedInstallationId` 验证持久化。

**完整分步骤、psql 安装目录用法、失败排查与重启验收**：[Windows 11 本机 PostgreSQL 启动与验收](./doc/implementation/S0-Windows11-本机PostgreSQL-启动与验收.md)。

根目录 `compose.yaml` 仍保留为**可选 Docker 方式**，使用它时必须自行明确对应的 DB_URL/DB_USERNAME/DB_PASSWORD；其 `ontology` 测试库与 Windows 默认 `ontoforge` 库不是同一环境，不应在本机 5432 上同时启动两个 PostgreSQL 实例。CI 采用独立 PostgreSQL 服务及 CI 专用环境变量。

详细版本范围：[S0 极简 MVP 迭代与验收计划](./doc/implementation/S0-MVP-迭代计划.md)。

**安全范围**：本版本没有认证与权限控制，仅适合隔离的本地开发环境；不在仓库中保存数据库密码，不得直接对外开放服务或接入生产数据。

工程目录与长期模块命名规范：[S0 模块规划](./doc/implementation/S0-工程模块规划与命名规范.md)；品牌及兼容性说明：[OntoForge · 砺元命名约定](./doc/implementation/OntoForge-品牌与工程命名约定.md)。
