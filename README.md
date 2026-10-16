# OntoForge · 砺元

**Onto**logy + **Forge**：本体在真实工业数据、来源核验和业务闭环中不断锻造。

> **砺**：磨砺、精炼；**元**：根本、元素。面向工业制造的 Enterprise-native Operational Ontology。

面向企业与工业制造的**受治理语义运营本体平台**：Native Object/Link、来源绑定、ObjectSet、确定性决策与受控 Action，通过一致的来源、版本、授权和执行证据为应用与 Agent 提供可信业务上下文。

**[阅读唯一现行总体架构：V1.3](./doc/本体产品总体功能与技术架构设计_v1.3.md)**

- [现行 Native DSL/AST V0.2 技术合同](./doc/design/核心本体元模型与Native-DSL-AST契约_v0.2.md)
- [文档目录、研究证据与历史清理说明](./doc/README.md)
- [PostgreSQL ObjectSet / Action Outbox 研究实验](./experiments/pg-runtime/README.md)

> **S0-MVP-0.2 ObjectType 草稿建模代码已由 [PR #7](https://github.com/gitfortian/ontoforge/pull/7) 合并到 main；Windows 11 原生 PostgreSQL 实机验收仍待完成，版本未发布。**，不是可生产使用的本体产品。用户/权限/工作空间/业务系统连接尚未实现；所有架构选择仍以 V1.3 和阶段 Gate 为准。

## S0-MVP-0.2：Windows 11 本机 PostgreSQL（默认开发方式）

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
4. 新开 PowerShell，重新设置相同的 DB_* 环境变量，并在仓库根目录运行 `.\scripts\verify-s0.ps1`（`psql` 未加入 PATH 时传 `-PsqlPath`）。脚本只读校验 Flyway V1/V2、数据库 UUID、`DRAFT_AVAILABLE` 与真实 API 是否一致；重启后端后可用 `-ExpectedInstallationId` 及 `-ExpectedDraftId` 验证既有安装和草稿均被保留。

### Windows 11 一键实际验收（建议优先执行）

在本机 PostgreSQL、Spring Boot 启动后，可以**直接由脚本执行真实 HTTP 建模黄金故事**，不需要反复手工填页面。脚本只连接 `127.0.0.1 / localhost`，使用一个**随机独立模型包**，不会修改或删除已有模型；由于当前没有删除 API，创建的验收草稿会留在测试数据库中。建议使用独立的开发/测试数据库。

```powershell
# 仅查看数据库和已运行 API（只读）：与现有 Flyway V1/V2/installationId 核验互补
.\scripts\verify-s0.ps1

# 明确同意创建一个可追溯的、唯一命名的验收草稿（会写入当前测试数据库）
.\scripts\verify-objecttype.ps1 -Mode Exercise -AllowCreateTestDraft
```

第一次运行将依次实测：模型包创建、未完成 WIP 保存与重开、INCOMPLETE、补齐主键后 VALID、revision 增长与旧校验失效，以及旧版本 PUT 必须得到 HTTP 409。末尾会打印一条带 `-EvidencePath` 的复验命令，并将草稿 UUID、installationId、revision 和预期描述写入**本机临时 JSON**（不含密码）。

**保留 PostgreSQL 数据库，重启 Spring Boot 后执行刚才输出的 `-Mode Recheck -EvidencePath "..." ` 命令。** 只有复验确认原 draftId、installationId 和描述不变，才能把 Windows 持久化验收记为 PASS。

脚本可在 GitHub Actions 的 Linux PowerShell 7 环境中进行自动回归，但这**不等于用户 Windows 11 本机通过**。Edge 页面、菜单和键盘使用体验仍应做最终人工抽检；正式记录规则见 [S0-MVP-0.2 Windows 验收记录](./doc/implementation/S0-MVP-0.2-Windows-验收.md)。

### ObjectType 真实黄金故事（使用测试模型，不要连接工厂生产数据）

本轮不再使用 `doc/prototypes/` 中的内存模拟 HTML。直接访问 [本体建模工作台](http://127.0.0.1:5173/#/model/packages)，页面请求真实的 Spring Boot API，数据写入你的 `ontoforge` PostgreSQL：

1. 在「模型包」点击「创建模型包」，输入 `manufacturing.production`。若已经有这个包，请打开已有草稿，**不要清空数据库**。
2. 在对象类型填写 `production-batch` / `ProductionBatch`；可以暂时不设置主键，点击「保存编辑进度」。复制浏览器地址中的 `draftId`，刷新，核实原值仍在。
3. 点「检查模型完整性」，未完成时看到 `INCOMPLETE`；添加 `batch-id` / `batchId`，选择 `STRING`、勾选「必填」，选择它为主键，保存后再校验应为 `VALID`。这**仅是本阶段结构/语义校验**，不代表审核或发布。
4. 修改描述并保存，核实旧校验变为 `NOT_RUN`，数据库草稿 revision 增长；重开刚才 URL，核实确实读取 PostgreSQL。
5. 使用两个浏览器标签页编辑同一 `draftId`；分别保存时旧 revision 应得到 `409 DRAFT_REVISION_CONFLICT`，第二个标签页未保存内容必须保留。断开 API 时不得报告“已保存”，恢复后先核对服务器 revision。
6. 停止并重新启动 Spring Boot，不要重建 PG 数据库。可运行 `.\scripts\verify-s0.ps1 -ExpectedInstallationId <UUID> -ExpectedDraftId <UUID>`，验证安装身份、Flyway V1/V2 和草稿持久性。替换占位 UUID 为 API 返回的实际值。

**自动证据**：CI 采用隔离 PG17、真实 Spring Boot API、React/Vite 与 Chromium，检查上述成功与冲突/网络负例。**Windows 11 本机人工验收尚未执行时，不得声称已通过。** 本 MVP 不提供 LinkType 编辑、正式发布、身份授权、模型实例、Data Binding 或 Action。

**完整分步骤、psql 安装目录用法、失败排查与重启验收**：[Windows 11 本机 PostgreSQL 启动与验收](./doc/implementation/S0-Windows11-本机PostgreSQL-启动与验收.md)。

根目录 `compose.yaml` 仍保留为**可选 Docker 方式**，使用它时必须自行明确对应的 DB_URL/DB_USERNAME/DB_PASSWORD；其 `ontology` 测试库与 Windows 默认 `ontoforge` 库不是同一环境，不应在本机 5432 上同时启动两个 PostgreSQL 实例。CI 采用独立 PostgreSQL 服务及 CI 专用环境变量。

详细版本范围：[S0 极简 MVP 迭代与验收计划](./doc/implementation/S0-MVP-迭代计划.md)。

**安全范围**：本版本没有认证与权限控制，仅适合隔离的本地开发环境；不在仓库中保存数据库密码，不得直接对外开放服务或接入生产数据。

工程目录与长期模块命名规范：[S0 模块规划](./doc/implementation/S0-工程模块规划与命名规范.md)；品牌及兼容性说明：[OntoForge · 砺元命名约定](./doc/implementation/OntoForge-品牌与工程命名约定.md)。
