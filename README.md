# OntoForge · 砺元

> **完整产品功能树（V1.1，待产品签收）**：[8 个一级功能域、F01–F29 共 29 个二级模块、216 个三级能力](./doc/product/OntoForge-产品完整功能树-三级-V1.1.md)。产品范围以此为主视角，已开发代码证据仍见下方 **V1.0 AS-IS 实施盘点**；216 项不代表已实施/已验收。  
> **核心验收设计**：[15 项功能合同及 8 条端到端黄金故事](./doc/product/OntoForge-Core功能验收合同与黄金故事-V1.1.md)。缺陷分开追踪：[M7 未填筛选扩大查询 #80](https://github.com/gitfortian/ontoforge/issues/80)、[Native UUID 前后端不一致 #81](https://github.com/gitfortian/ontoforge/issues/81)；[全域正式签收 #82](https://github.com/gitfortian/ontoforge/issues/82)。  
> **125→216 编号迁移**：[旧实施条目全部归档与边界/CI 分类](./doc/product/OntoForge-ASIS到全产品功能树-追溯与分类-V1.1.md)：71 条直接对照、26 条合并细节、16 条未来规划、6 条边界、6 条质量活动。
> **第二轮独立复核**：[V1.1 第二轮正式评审报告](./doc/product/OntoForge-V1.1-第二轮独立产品功能复核-20261019.md)；**216 项当前静态分类修订为 57 已实现·限定、5 局部待验证、154 规划**。原 70/9/137 是第一轮初始标记，并不代表主线代码被撤销。**产品范围目录有条件通过，真实 UAT 未执行，PR #78 仍为 Draft**。  
> **当前功能盘点基线（待正式全域签收，2026-10-19）**：[V1.0 实施视角：12 个交付模块、30 个实现分组、125 个能力／边界条目](./doc/implementation/OntoForge-产品功能说明书-三级功能-V1.0.md)。以主线合并的 M4–M7 为实际代码基准，区分已实现·限定环境、部分实现和未来规划；不是正式发布声明。
> **正式产品评审结论（REVISE）**：[PR #78 的 7 项 P1 / 12 项 P2 与 F01–F29 漏项审查](./doc/implementation/OntoForge-三级功能正式产品评审报告-20261019.md)。现行目录可核对已合并 M4–M7，但未获全产品功能范围签收。  

**Onto**logy + **Forge**：本体在真实工业数据、来源核验和业务闭环中不断锻造。

> **砺**：磨砺、精炼；**元**：根本、元素。面向工业制造的 Enterprise-native Operational Ontology。

面向企业与工业制造的**受治理语义运营本体平台**：Native Object/Link、来源绑定、ObjectSet、确定性决策与受控 Action，通过一致的来源、版本、授权和执行证据为应用与 Agent 提供可信业务上下文。

**[阅读唯一现行总体架构：V1.3](./doc/本体产品总体功能与技术架构设计_v1.3.md)**

**[产品与研发统一术语词汇表（256 个词条）](./doc/砺元本体产品统一术语词汇表.md)**：先理解 ObjectType / LinkType、草稿 / 快照 / 正式发布、Data Binding / ObjectSet / Action 的区别。

- [现行 Native DSL/AST V0.2 技术合同](./doc/design/核心本体元模型与Native-DSL-AST契约_v0.2.md)
- [文档目录、研究证据与历史清理说明](./doc/README.md)
- **[当前已实现功能全景（AS-IS，按 main 代码核验）](./doc/implementation/OntoForge-AS-IS-已实现功能全景-20261017.md)**：对象/属性/关系、草稿/快照、PG 测试表绑定/预览/两跳追溯，包含状态和证据。
- **[当前实际技术架构全景（AS-IS，按 main 代码核验）](./doc/implementation/OntoForge-AS-IS-实际架构全景-20261017.md)**：模块/调用链/Flyway V1–V4/安全边界/运行路径/CI。
- [MVP 历史功能与成熟度总账（F01–F29，含设计候选和历史阶段）](./doc/implementation/OntoForge-MVP-产品功能全景与成熟度矩阵.md)
- [S0-MVP-0.3 本轮交付说明与下一步进度](./doc/implementation/S0-MVP-0.3-交付说明-20261016.md)
- [每轮交付说明模板（新增功能 / 证据 / 风险 / 下一步）](./doc/implementation/交付说明模板.md)
- [S0-MVP-0.3 功能说明与全面 Review 验收矩阵](./doc/implementation/S0-MVP-0.3-功能说明与全面Review验收矩阵.md)
- [PostgreSQL ObjectSet / Action Outbox 研究实验](./experiments/pg-runtime/README.md)

> **2026-10-17 当前 main 更新**：#32 与 #33 已合并；除下述历史 0.2–0.4 建模与本地快照之外，**已具备隔离测试数据的制造质量追溯三步闭环**（可选 `demo_*` 测试表、真实样本预览、PG 绑定及双跳查询）。**不等于生产数据源、通用 ObjectSet、企业登录或 Agent 已完成**。当前完整可验证状态请优先读上面的两份 AS-IS 全景文档；#34 脱敏 CSV 导入与 #35 AS-IS 文档均已合并，#17 B0 **已合并但不再是精简 MVP 的认证研发方向**。下方 S0.2–0.4 章节属于历史实现说明。
>
> **S0-MVP-0.2/0.3/0.4 真实建模代码已合并 main（PR #7 / #11 / #13）**：可定义同包多个 ObjectType 与 LinkType、保存草稿、完整性检查、留存不可变本地历史及比较风险。当前环境证据统一见[产品进度总账](./doc/implementation/OntoForge-MVP-产品功能全景与成熟度矩阵.md)，区分 Windows 隔离自动化与用户既有安装人工验收。**未正式发布；没有身份权限、真实业务数据绑定、ObjectSet 生产查询或 Action**。

## 工程模块化（2026-10-19）

当前真实后端模块为 `core → common`、`configuration`、`platform → dao`、`business → core/platform/dao` 与 `boot` 组装；箭头表示编译依赖，准确关系见 [完整工程模块规划](./doc/implementation/S0-工程模块规划与命名规范.md)。七份历史 Flyway 迁移统一由 DAO 提供，API、模型/绑定格式、安装 UUID 和认证模式保持原合同。前端仍为单一 npm 工程，按 `app / features / shared` 组织。

在仓库根执行 `.\scripts\run-local.ps1`，脚本先构建实际 Reactor，再启动可执行 Boot jar；已有构建可传 `-SkipBuild`。它沿用当前终端的 DB/Profile/OIDC/来源配置。若使用 `mvn -pl ontoforge-boot spring-boot:run`，须先 `mvn -pl ontoforge-boot -am -DskipTests install` 安装内部依赖。下列有日期的 AS-IS 材料保留其历史基线；最新工程归属以模块规划和代码为准。

沿用 main 的 M7 配置约束：非 `mvp-local` 环境须通过 `ONTOFORGE_OBJECTSET_CURSOR_SIGNING_SECRET` 提供至少 32 字符的稳定私有游标签名密钥。

## 当前精简 MVP：无需登录的本机隔离运行（需显式启用）

**开发优先级已调整**：本体定义 → 测试数据绑定 → ObjectSet/多结果查询 → 工业业务追溯。登录、用户和角色不是 MVP 核心能力；未来优先复用 [yak-ops](https://github.com/weifuwan/yak-ops) 的 `HttpSession` 认证，而不是单独开发 OntoForge 身份产品。

#17 的 JWT/OIDC 已经合并 main，**没有直接删除或改写已执行的 Flyway V5**。本 PR 增加单独的 `mvp-local` Spring Profile（**不是默认开启**），把本地建模和追溯从企业 OIDC 的前置要求中释放出来，已有 JWT 分支先作为独立兼容运行方式保留，不当做今后优选方向。

仅在自己的 Windows 11 本机新建 **`ontoforge_test_quality`** PostgreSQL 测试数据库，设置后端环境变量：

```powershell
$env:SPRING_PROFILES_ACTIVE = 'mvp-local'
$env:DB_URL = 'jdbc:postgresql://127.0.0.1:5432/ontoforge_test_quality'
$env:DB_USERNAME = 'postgres'
$env:DB_PASSWORD = [System.Net.NetworkCredential]::new('', (Read-Host 'Local test PostgreSQL password' -AsSecureString)).Password
.\scripts\run-local.ps1
# 第二终端：cd ontoforge-ui; npm ci; npm run dev
# 浏览器访问 http://127.0.0.1:5173/#/model/packages
```

此模式后端只接受 `server.address=127.0.0.1/localhost` 和本机 `ontoforge` / `ontoforge_test_*` 数据库；**不需要 OIDC 配置、Token、管理员预分配模型包**，但没有多用户权限边界。UI 显示“本机隔离 MVP”，可自行创建草稿、编辑/校验/冻结和进入质量追溯。不能使用公网代理、隧道或端口转发，不能接生产数据；需要更严格隔离时请只用 `ontoforge_test_*` 库。

不启用 `mvp-local` 的原有 JWT/OIDC 路径仍 **fail-closed**，必须配置 IdP，不得因本地体验而全局放行。

详细决策、[yak-ops](https://github.com/weifuwan/yak-ops) 复用边界和 ObjectSet 下一步任务见[核心 MVP 优先级与认证复用决策](./doc/implementation/MVP-核心优先与yak-ops认证复用决策-20261017.md)。

## S0-MVP-0.2：Windows 11 本机 PostgreSQL（默认开发方式）

> **历史说明：#17 已合并，旧 B0/JWT 运行方式非当前 MVP 默认路径、未获生产签收**：本分支在原有建模、快照和质量追溯上叠加 OIDC/JWT 与模型包授权。服务端依赖 `OIDC_ISSUER_URI`、`OIDC_JWK_SET_URI`、`OIDC_AUDIENCE`，且模型包 `manufacturing.quality` 需显式授权；不能匿名访问本体或追溯 API。该分支将权限库表放入新增 Flyway **V5**，既有测试来源数据仍由 V4 管理。浏览器接续方式和操作要求见 [B0 实施说明](./doc/implementation/S0-MVP-0.5-B0-可信身份与模型包权限实施说明-20261016.md)。本节下方的旧无 Profile 启动示例是历史记录；现请优先采用上面的显式 `mvp-local` 开发流程；若不用该 Profile，仍须配置可信 IdP 与测试包成员权限；不得连接生产 MES/ERP 或误把 B0 测试 IdP 视为企业已验收。

准备：Java 21、Maven、Node.js 22（至少 22.12）、已安装运行的**本机 PostgreSQL**，不需要 Docker Desktop。前端使用提交的依赖锁文件和 `npm ci`。

1. 在 Windows PowerShell 中确认 PostgreSQL 服务运行，并创建或复用本机数据库 `ontoforge`（默认 `127.0.0.1:5432`，用户 `postgres`）；**已有数据库不要重建**。
2. 在启动后端的同一个 PowerShell 窗口设置 `DB_PASSWORD`，它**没有代码内置的默认值**：

   ```powershell
   $env:DB_URL = 'jdbc:postgresql://127.0.0.1:5432/ontoforge'
   $env:DB_USERNAME = 'postgres'
   $env:DB_PASSWORD = [System.Net.NetworkCredential]::new('', (Read-Host 'PostgreSQL password' -AsSecureString)).Password
   .\scripts\run-local.ps1
   ```

3. 另开 PowerShell 进入 `ontoforge-ui`，运行 `npm ci` 和 `npm run dev`；访问 [http://127.0.0.1:5173](http://127.0.0.1:5173)。
4. 新开 PowerShell，重新设置相同的 DB_* 环境变量，并在仓库根目录运行 `.\scripts\verify-s0.ps1`（`psql` 未加入 PATH 时传 `-PsqlPath`）。脚本只读校验 Flyway V1/V2、数据库 UUID、`DRAFT_AVAILABLE` 与真实 API 是否一致；重启后端后可用 `-ExpectedInstallationId` 及 `-ExpectedDraftId` 验证既有安装和草稿均被保留。

### S0-MVP-0.3：本地 Native 模型快照与结构差异（已合并开发主线）

设计依据：[**Accepted OFIP-0003**](./doc/proposals/OFIP-0003-S0-MVP-0.3-模型快照与发布边界.md)。[实施 PR #11](https://github.com/gitfortian/ontoforge/pull/11) 提供 Flyway V3、真实事务冻结/Revision CAS、规范化 JCS 字节 SHA-256、只读历史、模型字段风险 Diff，以及原建模工作台的冻结确认、历史与版本选择。**该功能已合并 main；这不意味着已正式发布或 Windows 11 本机验收通过。**

真实用户主流程：

1. 从 `http://127.0.0.1:5173/#/model/packages` 创建/打开模型包，完成 ObjectType/Property 并保存 WIP。
2. 点击「检查模型完整性」，确保校验为 **VALID** 且对应当前修订；有本地未保存编辑时不得冻结。
3. 点击「冻结本地只读快照」，在确认弹窗核对 packageId 和 revision；成功跳转到真正的只读快照详情，看到 `snapshotId`、`jcs-rfc8785-v1` Profile 和已核对的 SHA-256。
4. 返回草稿，修改描述或属性配置，再保存、重新校验、冻结；在「历史快照」选择两份来源修订，查看结构差异以及潜在破坏风险。**Diff 不等于下游兼容性证明**。
5. 遇到 `409` 核对草稿修订；遇到 `RESULT_UNKNOWN` 不要生成新键盲目重试，先点击「按原幂等键核对结果」；服务端按 `draftId + idempotencyKey` 保留同一冻结身份。
6. 仅在**独立测试数据库**做 Windows 原生 PG 验收：在 Spring Boot 启动后运行下面的脚本，完成 Exercise 后**真实重启后端（不要重建数据库）**再执行输出的 Recheck：

```powershell
.\scripts\verify-s0.ps1
.\scripts\verify-snapshot.ps1 -Mode Exercise -AllowCreateTestDraft
# 重启后端后，执行 Exercise 返回的真实 EvidencePath（示例请勿直接照抄）
.\scripts\verify-snapshot.ps1 -Mode Recheck -EvidencePath "C:\\Temp\\<actual-evidence>.json"
```

脚本仅访问本机 loopback API，但**会永久写入一个专门验收 Draft 和两份 Snapshot**，因此不得连接生产数据库；不提交 PostgreSQL 密码、真实工业模型内容或凭据到 PR。Linux CI 上执行同一脚本及真实 Chromium 只能证明自动化可运行，**不等于用户 Windows 11 本机验收 PASS**。现阶段依然无认证、授权、审查主体、发行/激活与工业 Action，禁止生产部署。

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

### S0-MVP-0.4：多对象与 LinkType 关系建模（已合并开发主线）

现可在同一模型包建立生产批次、工单、客户订单等多个 `ObjectType`，并定义 `LinkType` 的来源/目标、基数和未来证据要求。完整编辑保存到 PostgreSQL 后可刷新重开、验证关系引用，将所有对象及关系冻结为本地不可变快照，并比较基数与证据要求的变更风险。详见 [0.4 真实功能交付说明](./doc/implementation/S0-MVP-0.4-真实功能实施与验收报告.md)。

在**独立本地测试库**可用 `./scripts/verify-linktype.ps1 -Mode Exercise -AllowCreateTestDraft` 创建可追溯测试数据，实际重启 Spring Boot 后使用输出的 EvidencePath 执行 `-Mode Recheck`。脚本会写入测试模型且不会自动删除；**从未在用户本机执行时须记录 NOT RUN**。定义 LinkType 并不生成真实 LinkFact，也不是正式 SchemaRelease。

### ObjectType 真实黄金故事（使用测试模型，不要连接工厂生产数据）

本轮不再使用 `doc/prototypes/` 中的内存模拟 HTML。直接访问 [本体建模工作台](http://127.0.0.1:5173/#/model/packages)，页面请求真实的 Spring Boot API，数据写入你的 `ontoforge` PostgreSQL：

1. 在「模型包」点击「创建模型包」，输入 `manufacturing.production`。若已经有这个包，请打开已有草稿，**不要清空数据库**。
2. 在对象类型填写 `production-batch` / `ProductionBatch`；可以暂时不设置主键，点击「保存编辑进度」。复制浏览器地址中的 `draftId`，刷新，核实原值仍在。
3. 点「检查模型完整性」，未完成时看到 `INCOMPLETE`；添加 `batch-id` / `batchId`，选择 `STRING`、勾选「必填」，选择它为主键，保存后再校验应为 `VALID`。这**仅是本阶段结构/语义校验**，不代表审核或发布。
4. 修改描述并保存，核实旧校验变为 `NOT_RUN`，数据库草稿 revision 增长；重开刚才 URL，核实确实读取 PostgreSQL。
5. 使用两个浏览器标签页编辑同一 `draftId`；分别保存时旧 revision 应得到 `409 DRAFT_REVISION_CONFLICT`，第二个标签页未保存内容必须保留。断开 API 时不得报告“已保存”，恢复后先核对服务器 revision。
6. 停止并重新启动 Spring Boot，不要重建 PG 数据库。可运行 `.\scripts\verify-s0.ps1 -ExpectedInstallationId <UUID> -ExpectedDraftId <UUID>`，验证安装身份、Flyway V1/V2 和草稿持久性。替换占位 UUID 为 API 返回的实际值。

**自动证据**：CI 采用隔离 PG17、真实 Spring Boot API、React/Vite 与 Chromium，检查上述成功与冲突/网络负例。**Windows 11 本机人工验收尚未执行时，不得声称已通过。** 本节记录 S0-0.2 的历史用例；截至 S0-0.4，已支持 LinkType 编辑，但仍不提供正式发布、身份授权、模型实例、Data Binding 或 Action。

**完整分步骤、psql 安装目录用法、失败排查与重启验收**：[Windows 11 本机 PostgreSQL 启动与验收](./doc/implementation/S0-Windows11-本机PostgreSQL-启动与验收.md)。

根目录 `compose.yaml` 仍保留为**可选 Docker 方式**，使用它时必须自行明确对应的 DB_URL/DB_USERNAME/DB_PASSWORD；其 `ontology` 测试库与 Windows 默认 `ontoforge` 库不是同一环境，不应在本机 5432 上同时启动两个 PostgreSQL 实例。CI 采用独立 PostgreSQL 服务及 CI 专用环境变量。

详细版本范围：[S0 极简 MVP 迭代与验收计划](./doc/implementation/S0-MVP-迭代计划.md)。

**安全范围**：本版本没有认证与权限控制，仅适合隔离的本地开发环境；不在仓库中保存数据库密码，不得直接对外开放服务或接入生产数据。

工程目录、实际模块与长期依赖规范：[完整模块结构规划](./doc/implementation/S0-工程模块规划与命名规范.md)；品牌及兼容性说明：[OntoForge · 砺元命名约定](./doc/implementation/OntoForge-品牌与工程命名约定.md)。
