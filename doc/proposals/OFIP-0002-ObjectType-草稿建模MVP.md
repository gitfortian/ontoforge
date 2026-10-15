# OFIP-0002：P0-A Native ObjectType 草稿建模的首个可用闭环

**Status:** Discussion

**Discussion:** Pending — 待本提案独立 PR 创建后绑定

**Decision:** Pending — 维护者未签收；文档或 CI 通过不等于 Accepted

**Implementation:** Not Started — 通过评审后以一个功能 PR 完成 S0-MVP-0.2 纵向交付

**Release:** Not Released

**Related:** [OFIP-0001 工程治理（Accepted）](./OFIP-0001-需求提案文档治理与CI证据门禁.md) · [总体架构 V1.3（待产品/技术/安全签收）](../本体产品总体功能与技术架构设计_v1.3.md) · [Native DSL/AST V0.2（合同候选）](../design/核心本体元模型与Native-DSL-AST契约_v0.2.md) · [S0 迭代计划](../implementation/S0-MVP-迭代计划.md)

> **Proposal only**：本文件记录第一个可操作 ObjectType 建模方案；本 PR 不包含应用代码、Flyway 迁移、Schema 修改、UI 原型或 CI 放宽。S0 无鉴权与多空间隔离，本功能仅在 Windows 本机 loopback + 开发数据库使用，不得接入实际工业生产数据。

## Motivation

### 1. 用户到底需要做成什么

工业领域建模人员希望通过 Web 界面定义“生产批次（ProductionBatch）”这样的**对象类型**，包括稳定标识、展示名称、主键及可查询/敏感度明确的属性；点击保存后数据进入 PostgreSQL，刷新浏览器或者重启服务后还能重新打开同一草稿，非法结构则明确阻止写入并指示如何修复。

现有 \`main\` 经代码核对只有 \`/api/system/status\` 及 React 的“规划中”导航；数据库 Flyway V1 只有 \`platform_installation\`，**没有业务模型持久化**。现有 Native V0.2 JSON Schema 与 Python 测试是设计/校验候选，**不是 Java 运行时的模型 API 和正式编译器**。

### 2. 黄金故事：从空平台创建第一个对象类型

1. 用户在“本体设计 → 对象类型”看到真实空状态和明确的“新建对象类型”入口；不展示假模型。
2. 系统引导**选择已有模型包草稿或创建新包**。首次创建时用户输入 \`manufacturing.production\`（packageId），本轮只有一个本地可编辑 Draft；界面简要解释“模型包 = 类型定义的交付单元”，不强迫用户配置 imports、AST 或发布摘要。
3. 输入对象类型：\`id=production-batch\`、\`apiName=ProductionBatch\`、\`label=生产批次\`。
4. 添加属性：\`batch-id\` / \`batchId\` / STRING / required=true / searchable=true / INTERNAL；再添加 \`work-order-id\` / \`workOrderId\` / STRING、\`produced-at\` / \`producedAt\` / DATETIME、\`quality-status\` / \`qualityStatus\` / ENUM（枚举值例：PLANNED、RUNNING、COMPLETED）。必须对每项属性设置 required/searchable/classification，UI 可以提供明确的初始默认值。
5. 选择 \`batch-id\` 为主键；提交“保存草稿”。
6. 后端对**同一版本 Native 结构合同 + 本轮局部语义**校验，原子保存至本机 PostgreSQL；返回 \`draftId\` 和 \`revision\`，前端仅在服务端确认后显示成功。
7. 返回真实草稿列表，刷新浏览器，再点开此草稿，看到原来的四个属性、主键和记录版本；修改描述/属性并再次保存，重新打开变化仍存在。
8. 停止后端、网络失败或发生编辑版本冲突时，不展示假成功；保留本地尚未提交的内容并给出恢复动作。

**本轮最少成功结果**：在浏览器“创建 → 保存 → 列表找到 → 重新打开 → 修改 → 保存 → 重新打开”的完整链路，真实 PostgreSQL 持久化可复验。

### 3. 本轮明确不做

- ObjectInstance/LinkFact、真实 MES/ERP/QMS Data Binding、ObjectSet、RDF/OWL 映射、AI 生成、Action、工业设备写入。
- LinkType 编辑器、批量导入、跨包 imports、多对象类型协同编辑、复杂 Registry、行业模板商店。
- Draft → Review → Approved → ImmutableRelease → Activation（后续 P0-A/P0-B 单独交付）。不在 UI 中假装已“发布”。
- 登录、用户、团队、角色、权限、Workspace、多租户，不创建假的 \`createdBy\` 或授权 API。仅可信**隔离本机开发**。
- 完整编译器、Digest/签名、AST 发布证明及跨包 Lock。结构合法不能被描述成“完整发布验证通过”。

## Public Interfaces

### 1. 契约与身份：不发明第二套元模型

使用当前 [Native Package V0.2 JSON Schema](../design/schema/ontology-package-v0.2.schema.json) 的字段作为持久化权威：

- \`schemaVersion = 0.2.0\` 指**Native 结构合同版本**。
- \`packageId\` 满足现行 schema（例 \`manufacturing.production\`），一个本机安装内至多一个**可编辑的本地草稿**。它是包的**业务坐标**，不是 PostgreSQL UUID，不等于 Workspace。
- \`version = 0.1.0\` 是本轮新草稿默认**候选包版本**，区别于独立的 \`revision\` 编辑并发版本、结构合同版本以及未来不可变 \`ReleaseDigest\`。S0-MVP-0.2 不允许客户端改 \`version\`；后续发布语义需新设计评审。
- \`draftId\` 是服务端生成的 UUID，唯一定位已保存草稿，和 \`ObjectType.id\`、\`packageId\` 均不是同一个 ID。
- \`ObjectType\` 当前字段：\`id\`、\`apiName\`、可选 \`label\`/\`description\`、\`primaryKeyRef\` 和 \`properties\`。
- \`Property\` 当前字段：\`id\`、\`apiName\`、\`valueType\`（STRING、INTEGER、DECIMAL、BOOLEAN、DATETIME、ENUM）、\`required\`、\`searchable\`、\`classification\`（PUBLIC/INTERNAL/CONFIDENTIAL/RESTRICTED）以及 ENUM 时的 \`enumValues\`；\`label\` 可选。
- \`primaryKeyRef\` 必须指向当前类型内存在的属性 ID，该属性 \`required=true\` 且 \`valueType\` 为 STRING 或 INTEGER，不能把字段名/API 名误用作主键引用。
- 本轮保存的完整包 JSON 含 \`imports=[]\`、单个 \`objectTypes=[...]\` 以及 \`linkTypes/bindings/linkBindings/objectSets/functions/rules/decisionStrategies/policies/actions\` **所有必需的空数组**；该对象被明确标记为 DRAFT，绝不是可发布 Release/AST。
- V0.2 现有结构允许跨包同名类型；本轮不擅自要求全局唯一。当前单草稿内拒绝重复 Property ID 和重复 API 名（采用区分大小写的确定性比较；若要改变大小写折叠策略须复审）。未来扩展到多 ObjectType 的包时继续遵循现有测试的跨类型 Property ID 冲突限制。

**边界决定**：实现 API 可以接受**只含本轮 ObjectType 输入的窄 DTO**；由后端构造符合现行 V0.2 的完整 Package。**禁止前端自行声明任意未支持的 Link/Binding/Action 字段并误以为已被实现。** 当前 JSON Schema 不修改；如果开发发现必须修改，要在独立设计评审中说明影响，不能静默改 V0.2。

### 2. Proposed HTTP API（仅设计，尚未实现）

统一本地开发 URI 前缀 \`/api/model/drafts\`，请求响应均为 JSON，字段含义在验收时固定：

| 方法 | 资源 | 含义 | 典型响应 |
| --- | --- | --- | --- |
| \`GET\` | \`/api/model/drafts\` | 列出真实已存草稿摘要；首次使用返回空数组 | 200 + DraftSummary[] |
| \`POST\` | \`/api/model/drafts\` | 新建一个包草稿及其首个 ObjectType | 201 + DraftDetail，Location 指向新资源 |
| \`GET\` | \`/api/model/drafts/{draftId}\` | 读取同一个草稿、包 JSON 和最新 revision | 200 + DraftDetail |
| \`PUT\` | \`/api/model/drafts/{draftId}\` | 全量替换本轮支持的一个 ObjectType，**必须显式携带 expectedRevision** | 200 + revision 自增的 DraftDetail |

创建请求只包含 \`packageId\`、\`objectType\`；更新请求只包含 \`expectedRevision\`、\`objectType\`。后端唯一控制 \`schemaVersion\`、初始 \`version\` 和其余空数组的构造。响应 \`DraftDetail\` 至少包含 \`draftId\`、\`status=DRAFT\`、\`revision\`、\`createdAt\`、\`updatedAt\`、完整 \`package\`。列表至少返回 \`draftId\`、\`packageId\`、\`version\`、\`objectType.id/apiName/label\`、\`revision\`、\`updatedAt\`。字段返回数据库事实，不用前端演示数据。

**状态码及稳定错误语义**：

| 情况 | HTTP | 稳定 code 和客户端行为 |
| --- | --- | --- |
| JSON 语法不合法/必需请求字段缺失 | 400 | \`MALFORMED_REQUEST\`；错误可定位输入 |
| Native 结构/主键/枚举/引用/重复 ID 等校验未通过 | 422 | \`MODEL_VALIDATION_FAILED\`，返回 \`details=[{path,code,message}]\` 指向字段；不写入 |
| 同一 packageId 已有草稿，重复创建 | 409 | \`PACKAGE_DRAFT_EXISTS\`，不覆盖；提示“打开已有草稿” |
| expectedRevision 与数据库不一致 | 409 | \`DRAFT_REVISION_CONFLICT\`，不覆盖；提示载入最新版本 |
| draftId 不存在或 URL 不合法 | 404 / 400 | \`DRAFT_NOT_FOUND\` / \`MALFORMED_REQUEST\`；不可假装重新创建成功 |
| PostgreSQL 不可用/写入结果未知 | 5xx / 网络失败 | \`PERSISTENCE_UNAVAILABLE\`（可明确判断时）；前端**不得显示已保存**，保留编辑缓存并提供重试/列表核实 |

后端错误不得回传 SQL、连接串、密码、StackTrace 或其他内部敏感数据；合法枚举/限制由服务器统一执行，不能仅依赖 UI 控件。请求体大小、字符串长度和列表输出设定保守上限，非法负载在触发重型处理前拒绝；拒绝必须返回稳定错误信息，不无限制吞入任意 JSON。

### 3. 编辑并发合同

- **只用一个数字 revision**（创建时 1，每次成功修改 +1）作为乐观并发版本，不借用 \`package.version\`；PUT 提供 \`expectedRevision\`。
- 数据库使用原子条件写入（示意：\`UPDATE ... SET revision=revision+1 ... WHERE draft_id=? AND revision=? RETURNING ...\`），保证过期编辑不会静默覆盖。
- 失败与网络超时后客户端不直接假设失败或成功；刷新列表或读取草稿的真实 revision 后，用户自行决定重新提交。
- 初始草稿建立时的 \`packageId\` 本轮不可修改；包坐标迁移与跨包移动留给后续显式兼容设计。
- 所有草稿在本版本只有 \`DRAFT\`；不存在变相的 REVIEWED/APPROVED/PUBLISHED 字段，也不提供伪“发布”按钮。

## Proposed Changes

### 1. 产品交互：最少判断、完整返回路径

推荐第一个页面身份为“本体设计 / 对象类型”：

- **列表**：标题“对象类型”，清楚显示对象名、所在模型包、属性数、状态“草稿”、上次修改时间；空状态仅出现“新建对象类型”。列表的记录来自真实 API；失败态给重试，不用静态计数。
- **新建**：第一步在当前页面选择已有包或输入新包坐标；由于本轮每包仅有一个 ObjectType，已经有草稿时直接打开它（不让“新建同包第二个对象”产生暗中冲突）。下一步编辑对象标识与属性；主键从已添加且合法的属性中选择，避免手输错误引用。
- **属性编辑**：简单可读的属性表格，添加/编辑/删除（均在尚未保存的草稿编辑缓存中）；真实 required、searchable、classification、ENUM 值，不用“未来高级设置”占位。删除当前主键时要求重新选主键。
- **保存**：提交之前进行本地 UX 快速校验以减少往返，但**必须经过服务端校验并真实写库**；失败根据 path 在相关属性行就地显示错误，不清空整个表单。保存按钮在等待请求时禁用，禁止双击创建重复请求。
- **完成路径**：保存成功后提供“返回对象类型列表”和“继续编辑”两个真实去向，刷新页面和浏览器 Back/前进后仍打开同一 draftId。
- **未保存离开**：有编辑时显示明确确认，允许继续编辑或放弃本地未提交变化；离开没有持久化的编辑不会假装已自动保存。
- **错误恢复**：断网/500保留输入与重试；409 编辑冲突给“读取最新版本”入口，不自动合并或覆盖他人修改。系统依然没有用户账户，不能凭两个浏览器窗口假定已实现多人治理。

**本轮“保存草稿”的含义是“未发布但结构满足最小创建规则”**：暂不持久化完全缺主键的无效临时表单；若未来要支持未完成模型的自动保存，需定义分级验证与 InvalidDraft 生命周期，而不是悄悄放宽本轮硬约束。

### 2. 服务器与数据库：一个可解释的纵向切片

- Spring Boot 3.5 / Java 21 模块化单体内部创建最小 \`ModelDraft\` Domain/Service/Repository/Controller；在真正出现复用者前，**不单独生成一堆空的 Core/DAO/Registry SPI Maven 模块**。控制器仅处理 HTTP/DTO，业务校验集中在可单测的服务，不让前端/UI 决定真值。
- Flyway 以新文件 \`V2__native_model_drafts.sql\` 前向迁移，**绝不修改已合并的 V1**。建议单表 \`native_model_draft\`：\`draft_id UUID PK\`、\`package_id TEXT UNIQUE NOT NULL\`、\`revision BIGINT NOT NULL CHECK(revision>=1)\`、\`package_document JSONB NOT NULL\`、\`created_at/updated_at TIMESTAMPTZ NOT NULL\`。本轮不引入虚假的 user/workspace/approver 列。
- 数据库必须保证 packageId 和 JSON 内 packageId 一致：在应用写入时检查，必要时用适度 SQL CHECK/索引强约束防止漂移；不为未来复杂版本库预建十几张空表。
- 创建与更新在一个数据库事务内完成结构+语义校验、并发比较与写入；失败不留下半成品；API 只返回成功事务真正产生的结果。
- 共享 V0.2 JSON Schema 与测试作为合同基线。Java 路径需有能证明与此合同相符的验证方式（成熟 JSON Schema 2020-12 validator 或逐约束可执行等价测试），**不能以仅通过 Python Schema CI 就宣称 Java API 已验证**。
- 现有 Python \`semantic_validate_package\` 的主键及重复引用正反例要迁移为同等 Java/PostgreSQL API 行为测试；至少包含：主键不存在/不是必填/错误类型、Property ID 重复、ENUM 值缺失/重复、非法字段 classification/apiName/id、伪造非空 Binding/Action、同包重复创建、revision 过期及数据库故障。
- 单一草稿 JSONB 是 S0 实施最小存储单元；它不代表未来 Registry 的发布模型或高并发局部增量编辑设计。正式模型版本/不可变摘要/跨包依赖仍留在后续 OFIP。

### 3. 路线：一个实现 PR，而不是“分十几个技术 PR”

**下一功能 PR 交付内容必须完整**：Flyway V2、真实 POST/GET/PUT/LIST API、Property/PK/ENUM 服务端校验、React 本体设计/对象类型 CRUD 中的创建/保存/重开/编辑、两种失败恢复、PG17 集成与浏览器黄金故事自动化/可重复手工验收。

验收完成后才进入后续评审的 \`P0-A\` 多对象包/LinkType/依赖及 \`P0-B\` 模型审核/不可变发布。当前 S0 身份/认证缺失是一个**明确的安全阻断**，不得借 UI 模型保存宣称企业团队协作或生产可用。

### 4. 权威和状态

| 信息 | 当前拥有者 | 严格区分 |
| --- | --- | --- |
| ObjectType/Property 结构和候选包 | 现行 Native V0.2 Schema + 本轮服务端校验 | 声明，不是业务对象实例 |
| DraftId 与 Revision | 本地模型草稿 PostgreSQL | 草稿迭代，不是语义 Release |
| packageId + version | Native Package 文档内坐标与候选语义版本 | 不是 Database PK、Review Approval 或 AST Digest |
| 设计是否可发布 | **本轮不实现**；未来 Compiler/Review/Registry | 结构合法 ≠ 发布批准 |
| 数据是否真实完整/可查询 | **本轮不实现**；未来 Binding/Runtime/ObjectSet | 保存 ObjectType ≠ 导入 MES 真实批次 |
| 用户/Workspace/权限 | **本轮不实现**；隔离 loopback 开发模式 | UI 暂时可编辑 ≠ 已做鉴权 |

## Compatibility, Deprecation, and Migration Plan

1. **数据库**：从 V1 执行独立 V2 前向迁移；已有 \`platform_installation\` 和 Installation ID 保持不变。不得 DROP/ALTER 历史 V1 或清理已有本地库。重复重启 Flyway 不能损坏草稿数据。
2. **Native 合同**：不重新定义 ObjectType/Property，不改变 V0.2 JSON Schema/测试；写出的包必须满足 top-level 完整 required 字段。V0.1 OWL-first 夹具继续用于拒绝/迁移测试，不能把历史 EntityType 自动改成 Native Draft。
3. **包版本**：草稿初始化候选 \`0.1.0\`，不声明已经发布；正式版本选择、兼容报告、锁摘要、审核、激活会在后续 OFIP 评审；数据迁移/批量导入不在本轮。
4. **并发与失败**：\`expectedRevision\` 保护已存内容；同包重复 POST 返回 409 并引导打开已有草稿，不能用静默 upsert 隐藏冲突。
5. **未来演进**：初版仅单 ObjectType 包草稿为交互/请求限制；完整 V0.2 Package JSON 持久化使未来在评审后可扩展多类型、Link 等声明，但必须另行建立完整 Draft/Release 和兼容策略。
6. **安全边界**：没有认证和 Workspace 隔离时，此功能**只允许本机开发**；服务器/Vite 默认 loopback，不接生产数据库、不通过远程反向代理开放 API，不伪造组织 ID。未来上生产前先通过身份、权限、隔离和审计门禁。

## Test Plan

> 本章节定义**未来实现 PR 必须实际通过的测试**，不是本提案 PR 已执行的测试。Proposal-only PR 只应运行文档链接/OFIP 结构及现有 Native V0.2 合同回归，不能声称模型 API 或浏览器通过。

| 验收层 | 必须成功 | 必须拒绝或恢复 |
| --- | --- | --- |
| Contract | 写入后从 PostgreSQL 读取的 package 能被现行 V0.2 JSON Schema 验证；\`schemaVersion=0.2.0\`，非本轮资产数组完整为空 | 不允许伪造 Binding/Link/Action 或提交 V0.1；未知字段与非法枚举类型拒绝 |
| Java 服务端 | ObjectType 属性创建、更新和主键引用语义一致；GET 与写入结果相同 | PK 不存在/非 required/非 STRING 或 INTEGER、重复 ID、重复 apiName、非法 id/apiName、ENUM 缺值/重值、非法分类、缺 required 等产生 422 + 精确 path |
| PostgreSQL / Flyway | 基于干净 PG17 执行 V1→V2；在带 V1 数据的数据库前向升级；创建→读回→更新→重启→读回 JSON 等价，Installation ID 未改变 | duplicate packageId 409、expectedRevision 旧值 409 且无覆盖、异常事务无部分数据、停库失败可恢复 |
| API 合同 | GET 列表为空是 200 []；POST 201 Location+draftId/revision 1，GET 同草稿，PUT revision 2，GET 重开仍为 revision 2 | 400/404/409/422/5xx 不返回伪成功，错误信息不含数据库密码或内部 SQL |
| Web 用户故事 | Windows 11 Chrome/Edge：创建 manufacturing.production + ProductionBatch → 保存 → 列表 → 刷新 → 重开 → 编辑 → 重开，页面展示真实 Property/PK | 未保存离开确认、主键错误就地反馈、网络失败保留输入、两个标签页并发冲突不能静默覆盖 |
| 安全与现有基座 | 默认只监听本机回环地址，页面明确“本地开发、无鉴权” | 不加载真实生产数据、不公开服务，不把未来用户/授权角色当已实现；不改动 V1 Installation ID |
| CI 证据 | \`Required Quality Gate\` 成功且选中 Backend+Frontend+Docs（若影响 Schema 还选 Native）；有实际 PG/JUnit 报告与编译/浏览器记录 | 仅 Java/TS 编译或仅 Schema 静态测试不得称为用户可用验收；失败/未执行明确记录 |

**尤其注意现有 CI 门禁演进**：当前 \`Required Quality Gate\` 后端 JUnit 保留已有两项 S0 测试证据，但**尚不会自动判定新的 ObjectType 功能是否真的有测试**。后续功能 PR 必须新增 ObjectType 集成测试及明确的报告校验、浏览器证据，不能用旧 S0 测试凑 PASS。真实 Playwright 环境若因技术原因不具备，需要在 PR 上报告为 NOT RUN 并阻断“完成交付”签收，不能伪装已通过。

### 独立提案 PR 的允许验证范围

- 现有 \`doc/tests\` 的 OFIP 六章、决策仍 Pending、链接完整检查。
- 现有 \`doc/design/tests/test_native_v02.py\` 的结构样例回归。
- 不修改产品代码、Flyway、GitHub Ruleset、CI 选择器、V1.3 或 Native Schema，不把方案评审当作运行能力实现。

## Rejected Alternatives

1. **先做漂亮的模型工作台，后端接假数据**：拒绝。用户看不到真实保存/重开/冲突事实，与一个 PR 一个有形交付相悖。
2. **直接让 React 写数据库 JSON 或把任意 package 文本原样写入 JSONB**：拒绝。服务器必须封闭未实现的 Link/Binding/Action 范围并对身份/主键/类型负责。
3. **每个 Property 一张表 + 先搭完整 Registry/Compiler/权限引擎**：拒绝。S0 没有真实消费方，会膨胀。先以单包 JSONB 草稿完成纵向闭环，后续根据使用与版本要求演进。
4. **把 schemaVersion、候选 package.version、编辑 revision、不可变 ReleaseDigest 混成一个版本**：拒绝。会造成并发覆盖和“草稿已发布”的错误语义。
5. **对相同 packageId 做自动 upsert 或对版本冲突覆盖旧内容**：拒绝。静默丢失修改，必须明确 409 和恢复选择。
6. **保存不完整/非法模型为正式草稿并告诉用户校验通过**：拒绝当前阶段。若引入 WIP/未完成草稿必须先设计另一层校验与状态；当前 Save 仅存满足最小结构合同的未发布 Draft。
7. **因为是本地单人应用便伪造用户/Workspace/权限**：拒绝。只在 loopback、开发数据中使用，并明确未来安全前置门禁。

---

### 待维护者审议的决策

- **D1：产品范围** — 首个可交付 MVP 采用“真实 ObjectType → Property → 选主键 → 保存 → 返回列表 → 重开 → 再编辑”的纵向故事；不引入发布/Link/Binding，是否认可？
- **D2：持久化聚合** — 暂以一个可编辑单 ObjectType 的 Native Package V0.2 草稿（完整 JSONB）作为存储聚合，\`packageId\` 唯一、\`draftId\` UUID 与 \`revision\` 分离，是否认可？
- **D3：结构合同** — S0 创建时 \`schemaVersion=0.2.0\`、候选包 \`version=0.1.0\`、其他声明数组均空；使用现有 V0.2 字段和主键规则，是否认可？
- **D4：并发和失败** — POST/GET/LIST/PUT、expectedRevision 乐观并发、稳定 400/404/409/422 与保留输入的恢复路径，是否认可？
- **D5：交付及安全** — 验收必须包含真实 PG17/Flyway、API 负例和浏览器黄金故事；本机无认证仅 loopback，后续权限/多空间另审，是否认可？
- **D6：实施节奏** — 提案显式 Accepted 后，**一个功能 PR** 完成前端 + 后端 + Flyway + 正反例 + 用户可复验路径，再考虑 LinkType/审核发布，是否认可？

**本文件保持 Discussion / Decision Pending；不能因为当前设计 PR 合并或 CI 成功而改为 Accepted。**
