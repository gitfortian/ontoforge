# OFIP-0002：Native 模型包与 ObjectType 领域模型、草稿生命周期及首个 MVP

**Status:** Accepted

**Discussion:** [PR #6 — OFIP-0002 评审](https://github.com/gitfortian/ontoforge/pull/6)

**Decision:** **[VOTE] Accepted — 2026-10-15（[实施授权记录](https://github.com/gitfortian/ontoforge/pull/6#issuecomment-6193022402)）**。已通过 **Domain-Gate-1：DG1–DG9 全部 A**（[记录](https://github.com/gitfortian/ontoforge/pull/6#issuecomment-6191075093)）；**Contract-Gate-2：TC1–TC10 全部 A 已通过**（[记录](https://github.com/gitfortian/ontoforge/pull/6#issuecomment-6191277696)）。**UX-Gate-Low / UX-Gate-High：受托独立审核通过**（[实际验收结论](https://github.com/gitfortian/ontoforge/pull/6#issuecomment-6192752743)）；**实施获准，真实纵向功能在 [PR #7](https://github.com/gitfortian/ontoforge/pull/7) 中实施及验收**

**Implementation:** In Progress — [PR #7](https://github.com/gitfortian/ontoforge/pull/7) 包含真实 Flyway V2、Spring Boot、React 和 PG17+Chromium 证据，仍待 PR CI、合并和 Windows 实机验收；设计签收不等于完成

**Release:** Not Released

**Review gate:** Domain-Gate-1 **Passed**；Contract-Gate-2 **Passed (design only)**；UX-Gate-Low **Passed (delegated independent review)**；UX-Gate-High **Passed (delegated independent review; mock UI only)**；OFIP-0002 已获 `[VOTE] Accepted`（[决议](https://github.com/gitfortian/ontoforge/pull/6#issuecomment-6193022402)），业务运行与用户本机验收仍待独立完成

**Related:** [OFIP-0001 治理规范](./OFIP-0001-需求提案文档治理与CI证据门禁.md) · [总体架构 V1.3 候选](../本体产品总体功能与技术架构设计_v1.3.md) · [Native V0.2 结构合同候选](../design/核心本体元模型与Native-DSL-AST契约_v0.2.md) · [S0 迭代计划](../implementation/S0-MVP-迭代计划.md)

> **重要事实**：经核对当前 main，Spring Boot 仅有真实的系统状态 API，Flyway V1 只建平台安装记录；React 的本体建模入口仍为“规划中”。Native V0.2 的 JSON Schema 与 Python 正反例是真实存在的**候选合同与实验验证**，不是 Java 服务或生产编译器。S0 无认证/授权/工作空间隔离；不得作为企业正式服务开放或加载真实工业数据。
>
> **已通过的两轮设计约束**：DG1–DG9 与 TC1–TC10 均明确签收为阶段设计决策。Package 1:N ObjectType、1:N LinkType；Save WIP 与 Validate 分离；草稿编辑 DTO / Native Candidate 独立；按包 revision 防覆盖；完整模型校验不等于发布。**机器合同代码的实际修订须在后续受审功能/合同 PR 中实现，并以测试证明**，不能将设计签收等同实现。低/高保真受托独立评审已通过，完整 OFIP 已取得实施授权；Native 机器合同实际修订、前后端纵向功能交付仍是独立事项。

## Motivation

### 1. 用户、问题与可观察结果

工业领域专家或本体建模人员需要描述“生产批次（ProductionBatch）”的业务概念，再逐渐建立“物料批次（MaterialLot）”“工单（WorkOrder）”“质量事件（QualityEvent）”等类型和它们之间的关联。理想工作流是：创建/选择模型包 → 增加对象类型 → 定义属性与主键 → 保存编辑进度 → 校验语义 → 逐步扩展其他对象与关系 → 将来审核和发布。实际数据库中的生产批次或 MES 表行**不是** ObjectType 定义本身。

首个可验收 MVP 要求：本机打开真实的对象类型列表，创建 `manufacturing.production` 包下的 `ProductionBatch`，添加 `batchId` 等属性，保存草稿，返回列表、刷新、重开、修改后再次保存；未填完时也不能丢失合法的编辑进度；明确指出尚未达到完整模型校验要求；重复 ID、非法类型及并发覆盖不得被当成成功。

**领域模型与技术合同两轮已签收，低/高保真交互现已通过受托独立审查**；本 OFIP 已获准进入独立功能 PR 的工程实施阶段，尚未提交 Spring Boot/React 业务代码或数据库迁移。下一阶段须严格按照真实 Native/PG/API/浏览器验收合同完整交付，不允许仅凭 Mock 演示宣布产品可用。

### 2. 两个发现的核心设计冲突

**冲突 A：Package 不是 ObjectType 的一对一包装。** Native V0.2 Package Schema 的 `objectTypes`、`linkTypes` 等字段原本就是数组，允许多项；原 OFIP-0002 临时“每个 Package 草稿只能有一个 ObjectType”的建议如固化到领域模型/数据库唯一键，会阻碍建立 LinkType 和同一领域包内的多类型关联。

**冲突 B：编辑草稿不等于有效 Native Package。** 现有 V0.2 Schema 要求 ObjectType 必须有 `primaryKeyRef` 和至少一个 Property；如果“保存草稿”必须先满足完整 Schema，则不能自然保存只填好名称、尚未选主键的用户工作进度。长期需要区分“可持久化的编辑内容”与“可通过完整结构/语义校验的模型候选”，而不能向 Schema 偷塞半成品并声称校验通过。

**发现 C：现有 Property ID 作用域仍需治理。** 现有 Python `semantic_validate_package` 的 `CROSS_TYPE_PROPERTY_ID_COLLISION` 要求同一个包中多个 ObjectType 的 Property ID 全局不重复；ObjectType/Property 的声明 JSON Schema 本身只约束 ID 字面格式，不表达跨 ObjectType 唯一性。长期概念的 Property 归属通常以所属 ObjectType 为界；这一点需要显式定夺并经合同修订/测试，而不是前端偷偷改规则。

### 3. 本轮设计非目标

- 不接入 MES/QMS/ERP，不定义 ObjectInstance/LinkFact 运行时，不执行 Data Binding、ObjectSet、Function、Policy、Action 或 Agent。
- 不冻结完整多用户 Workspace、组织域、RBAC/ABAC、跨包 Imports、工业标准 Profile、Ontology Compiler/Registry。
- 不在本轮实现或假装已经支持审核、审批、不可变发布、激活、版本回滚与真实跨系统行动。
- 不以组件、数据库表数、UI 大屏、通用微服务或图谱可视化作为第一批交付目标。
- 不把既有 V1.3 整体架构从“待签收”自动提升成已签收。

## Public Interfaces

**Domain-Gate-1 已通过**。下述核心领域语言为下一轮提供约束；新增 Contract-Gate-2 讨论 HTTP DTO、数据库、校验、引用作用域和错误码，但在本轮评审通过前均不是已签收接口。现有 V0.2 机器合同**没有在本 PR 修改**。

### 1. 核心领域概念和基数

| 概念 | 领域定义与身份 | 关系/不变量 | 本轮职责 |
| --- | --- | --- | --- |
| Ontology Package | 定义集合与将来交付/版本化的边界；`packageId` 如 `manufacturing.production` | 1 个 Package 持有 0..N 个 ObjectType、0..N 个 LinkType；最终 Package 可包含其他 V0.2 声明 | 组织、依赖边界、未来发布坐标 |
| ObjectType | 一种业务对象的**类型定义**，如 `production-batch` / `ProductionBatch` | 属于一个 Package；有 0..N 编辑期 Property（候选正式合同要求 >=1）；最多一个 primaryKeyRef | 类型的字段与标识规则 |
| Property | ObjectType 所拥有的属性声明；如 `batch-id` / `batchId` | 属于且只能属于一个 ObjectType；类型、必填、可搜索、分类与 ENUM 值由合同治理 | 声明属性约束，而非真实值 |
| LinkType | 两个 ObjectType 之间**可能存在关系**的声明 | 属于一个 Package；有 sourceTypeRef、targetTypeRef、cardinality、evidenceRequired；现行 V0.2 源/目标是简短 Type ID 引用 | 关系语义，不是 LinkFact |
| PackageDraft | 一个正在编辑的 Package 的工作副本 | 独立 draftId、revision、编辑数据、保存时间、校验报告；不等于已发布包 | 不完整工作保存与冲突保护 |
| CandidatePackage | 从草稿生成的完整 Native Package 候选 | 必须通过 V0.2 JSON Schema + 语义/引用/唯一性验证才能标“已通过本阶段校验” | 为未来 Review/Release 提供输入 |
| SchemaRelease | 将来版本化、审核/审批后不可变的模型声明 | 绑定 Package 坐标、不可变内容摘要和审核证据；S0 不存在 | 不得被草稿保存替代 |
| ObjectInstance / LinkFact | 将来由来源/映射/投影提供的**业务实例和关系事实** | 不能因为 ObjectType/LinkType 已定义而自动生成 | P1 之后另行建模 |

**推荐的逻辑关系**（这里不是物理 SQL 结构）：

~~~mermaid
classDiagram
direction TB
class OntologyPackage {
  packageId
  candidateVersion
  imports
}
class PackageDraft {
  draftId
  revision
  editingContent
  validationState
}
class ObjectType {
  id
  apiName
  label
  primaryKeyRef
}
class Property {
  id
  apiName
  valueType
  required
  searchable
  classification
}
class LinkType {
  id
  apiName
  sourceTypeRef
  targetTypeRef
  cardinality
}
class CandidatePackage {
  schemaVersion
  packageId
  version
}
OntologyPackage "1" *-- "0..*" ObjectType : declares
OntologyPackage "1" *-- "0..*" LinkType : declares
ObjectType "1" *-- "0..*" Property : owns
PackageDraft "1" --> "1" OntologyPackage : edits
PackageDraft "1" --> "0..1" CandidatePackage : validates into
LinkType --> ObjectType : source and target refs
~~~

以上草稿 0..N Property 是为了允许持久化 WIP；**校验形成 CandidatePackage 时仍须满足 V0.2 当前 ObjectType `properties.minItems=1` 与 `primaryKeyRef` 必填要求**。这个图不表示一行或一张 SQL 表。

### 2. 身份和名称规则必须区分

| 标识 | 作用域 | 不得混淆的对象 |
| --- | --- | --- |
| `packageId` | 包的稳定逻辑坐标（如 manufacturing.production） | PostgreSQL 主键、Tenant、已发布版本 |
| `ObjectType.id` | 所属 Package 内稳定定义 ID（如 production-batch） | apiName、真实生产批次主键 |
| `ObjectType.apiName` | 同一 Package 公开可读/可编程名称（如 ProductionBatch） | objectType.id；重命名会影响消费者 |
| `Property.id` | **待决策**：推荐概念上归属 ObjectType，复合标识为 Package/ObjectType/Property | 现有 V0.2 Python 检查实际要求包内 Property ID 全局不重复 |
| `Property.apiName` | 所属 ObjectType 中的可编程属性名称（如 batchId） | Property.id、ObjectInstance 的字段值 |
| `LinkType.id` | 所属 Package 内的关系类型定义 ID | 特定实例之间的 LinkFact ID |
| `draftId` | 草稿工作副本数据库 ID（建议 UUID） | packageId、ObjectType.id |
| `revision` | 编辑中的乐观并发计数（成功更新后递增） | schemaVersion、候选版本、ReleaseDigest |
| `schemaVersion` | Native JSON 契约版本，目前 V0.2 候选为 `0.2.0` | 用户编辑 revision |
| `Package.version` | 候选或正式语义版本（现行 V0.2 字段，例如 `0.1.0`） | draft revision、Schema 结构版本 |
| `ReleaseDigest` | 将来真实规范化后不可变发布身份 | 示例 JSON 的伪 digest 或仅通过结构校验 |

**建议**：即使 S0 只创建首个 ObjectType，服务端未来的聚合语义也应是 **PackageDraft 编辑一个 Package 的类型集合**，不能把 `package_id UNIQUE` 解释为“永远只有一份 Package 版本”；如需要“同一 packageId 只有一个当前可写草稿”，这是 **草稿生命周期临时唯一性策略**，不能禁止未来历史发布版本并存。

### 3. ObjectType 与 Property 的专业约束

- **稳定标识与展示信息分离**：`id` 是机器 ID；`apiName` 是消费接口名称；`label` 是展示名；`description` 为语义定义。不能把中文 label 翻译成可靠的跨系统身份，也不能默认随意变更稳定 ID。
- **主键引用为 Property ID**：`primaryKeyRef` 必须指向当前 ObjectType 自己拥有的属性；现有 V0.2 语义测试要求对应属性必填且 `valueType` 为 STRING 或 INTEGER。主键是未来实例身份的规则，**不是**当前存在的真实实例 ID。
- **Property 值类型**：沿用当前 V0.2 STRING / INTEGER / DECIMAL / BOOLEAN / DATETIME / ENUM；ENUM 必须有非空、去重的 enumValues，非 ENUM 不允许携带 enumValues。字段 `required`、`searchable`、`classification` 的数据质量/授权含义须分别解释，不因 required=true 就推断 MES 实际已有值。
- **分类不等于实际访问许可**：PUBLIC/INTERNAL/CONFIDENTIAL/RESTRICTED 是语义分类，不代表已经实现字段级授权；S0 不存在认证主体，禁止“仅在 UI 隐藏”冒充权限控制。
- **唯一性作用域待裁决**：长期推荐 `(packageId, objectType.id, property.id)` 作为 Property 逻辑身份；但 V0.2 的 `CROSS_TYPE_PROPERTY_ID_COLLISION` 负例与之不完全兼容。**在修改既有结构/校验合同时，先决定保留包级限制还是修订候选 Native 规范和相关正反例；本轮不改代码。**
- 正式发布后对主键、数据分类、可搜索和字段类型的修改，应走兼容性评估；**编辑尚未发布的草稿**可以按 revision 更新，不假装已经产生破坏下游消费者的发布变更。

### 4. LinkType 与真实关系事实

- LinkType 的 `sourceTypeRef`、`targetTypeRef` 引用 ObjectType **定义**，不是 ObjectInstance ID；`cardinality` 是关联约束声明，不等于 SQL 物理外键。
- **同一 Package 内引用**可以在完整候选模型校验时解析。跨包引用必须考虑 Imports、明确版本、锁摘要及歧义处理；现有 V0.2 的简短类型 ID 不能被随意当作跨包全局唯一键，需后续合同评审明确限定/扩展。
- `evidenceRequired` 描述未来产生真实 LinkFact 时是否需要来源证据；**只保存 LinkType 不得生成任何虚假关联事实**。
- 首版用户故事不开放 LinkType 编辑器，但领域模型、候选 Package 结构和下阶段页面流程必须留出“为已有类型添加关系”的路径；不要求现在创建无用 Link API。

### Contract-Gate-2：已通过的技术合同设计（TC1–TC10 全部 A；尚未编码）

**设计口径**：这里给出 S0-MVP-0.2 的服务端/数据库/结构化编辑合同，供技术、产品、安全审议；本 PR **不新增 JSON Schema、Flyway、Controller 或 TypeScript**。应用前仍需低保真、高保真及其实际验收。本节决策编号为 TC1–TC10，**不是已经确认的设计结论**。

#### TC1：分离 DraftEditingContent 与可校验 Native Package

- **DraftEditingContent 是编辑器专用的持久化文档**，版本字段为 `editorSchemaVersion: "0.1.0"`。它是对包内声明的**有边界、可恢复的结构化输入**，不是 Native Schema V0.2；可以保留部分输入、空属性集合、未选主键、无效格式字符串等，同时要求所有字段长度、元素数、内容类型和嵌套深度受限。
- **Native Package V0.2 是完整候选输出**，`schemaVersion: "0.2.0"`，`packageId`、`version` 由服务端构造，其 `objectTypes` 等必需数组齐全；只有 Draft → Candidate 转换成功才运行既有 JSON Schema Draft 2020-12 及语义检查，不能将未完成的 WIP 放进完整 Package Schema。
- Draft Envelope 与编辑文档分离，`packageId` 为必填合法坐标，其他如 `draftId`、`revision`、`createdAt`、`updatedAt`、`validation` 由服务端维护，不能由客户端伪造；`packageId` 草稿建立后首期不可变。
- 编辑文档顶层首版为 `editorSchemaVersion`、`objectTypes`、`linkTypes`；后者在首版新建页面为空，未来若读到不支持的 LinkType/多类型资料，应**保留完整内容或只读阻止修改**，不能编辑一个对象便抹去其他声明。
- **WIP 允许缺少完成条件，但不允许任意 JSON**：服务端拒绝多余未知根键、嵌入 Binding/Action/Token 等未来字段、非预期值类型、超限字符串/深度/数组、恶意对象结构。用户输入的非法业务 ID/ENUM 值可以保存为有限长度字符串并显示校验问题，但不能因“允许草稿”无限制放宽输入边界。
- 仅当候选转换完整时，`primaryKeyRef` 必须引用自身存在的 Property（其 required=true、valueType=STRING/INTEGER）；结构与语义不通过，生成明确报告但不产生有效候选；绝不发布或激活。

**提议的完整 WIP 编辑文档实例（仍未符合 Native V0.2 Package Schema）**：

~~~json
{
  "editorSchemaVersion": "0.1.0",
  "objectTypes": [{
    "id": "production-batch",
    "apiName": "ProductionBatch",
    "label": "生产批次",
    "description": "生产执行中的批次类型定义",
    "primaryKeyRef": null,
    "properties": [{
      "id": "batch-id",
      "apiName": "batchId",
      "valueType": "STRING",
      "required": true,
      "searchable": true,
      "classification": "INTERNAL"
    }]
  }],
  "linkTypes": []
}
~~~

这里 `primaryKeyRef: null` 只在**编辑 DTO** 中合法，转换 Native 完整候选前须由用户选定真实属性 ID。首版 `linkTypes` 作为受控空集合保留，不提供编辑 API，不接受伪造可执行 Action/Binding。WIP 缺字段和业务规则错误不应通过截断/修正用户输入来隐式“修好”。

#### TC2：引用与标识唯一性的实际合同修订范围

| 层次 | 推荐唯一性/引用语义 | 代码现实及处理 |
| --- | --- | --- |
| Package | 包坐标 `packageId` 稳定且一个本地安装只存在一份当前可写草稿；可发布版本未来另存 | 不等于“Package 永远只允许一个 ObjectType/一个历史版本” |
| ObjectType | `id`、`apiName` 在**同一 Package** 中分别唯一（大小写敏感的确定性比较） | 首版 UI 最多引导创建第一个，但领域及 WIP 数组可以承载多个 |
| Property | `id`、`apiName` 在**同一 ObjectType** 中分别唯一 | DG3 已选 Type-scoped；当前 `semantic_validate_package` 仍有 `CROSS_TYPE_PROPERTY_ID_COLLISION` 包级拒绝，必须在正式合同修订时清除/替换并新增反例 |
| 主键 | `ObjectType.primaryKeyRef` 按本 ObjectType 的 `Property.id` 解析 | 不允许用同名 Property 的另一 ObjectType 满足 PK |
| Binding 字段映射 | `Binding.objectTypeRef + FieldMapping.propertyRef` 组合解析 | 现行 V0.2 的 `objectTypeRef` 提供上下文；没有此上下文不得查询全局 Property ID |
| Action 变更字段 | `ActionEffect.targetTypeRef + propertyRefs[]` 组合解析 | 现行 V0.2 的 `targetTypeRef` 提供上下文；不改变其授权定义和运行时 |
| LinkType 端点 | 仅引用明确的 ObjectType 定义；首版限定包内，跨包需 Import/版本锁 | 没有 resolved imports 时不可猜同名端点；LinkType 不能生成 LinkFact |

**版本策略待裁决**：Native V0.2 尚是**未正式签收的候选合同**。建议本次先通过 OFIP 明确语义勘误：可在后续受审合同 PR 中同步修订 V0.2 语义说明、Python 正反例与 Java 验证，但不改已对外发布的生产合同；若发现其他没有 ObjectType 上下文的消费方或已存在正式发布使用者，则必须升级 schema/profile 版本并提供迁移/拒绝矩阵，**不能无声明地继续沿用 0.2.0 冒充完全兼容**。无论选哪条路，本提案 PR **不直接修改原合同代码**。

#### TC3：Draft 及 Validation 两个独立状态轴

| 轴 | 候选状态 | 实际含义 |
| --- | --- | --- |
| 编辑资源生命周期 | `DRAFT` | 本轮只可保存编辑工作副本，不存在 REVIEWED / PUBLISHED / ACTIVE |
| 当前 revision 的校验 | `NOT_RUN` / `INCOMPLETE` / `INVALID` / `VALID` | 是某个 revision 的结构与语义校验快照，不是草稿生命周期状态 |

- 只有完整、有效的 CandidatePackage 校验通过才是 `VALID`；缺少必需 Property/PK 等完成条件 → `INCOMPLETE`；输入了存在但违反格式/唯一性/类型/跨引用等约束的数据 → `INVALID`。同时有多类错误时优先 `INVALID`，但 issues 应完整列举两类。
- `validation.validatedRevision` 精确绑定受检 `revision`，`VALID` 不代表已持久化为不可变 Release；当用户 PUT 成功后 revision+1，在**同一数据库事务中**将当前校验重置为 `NOT_RUN`、`validatedRevision=null`，不得复用上一 revision 的绿色结果。
- `POST /validate` 的校验失败是**正常业务结果（HTTP 200，status=INCOMPLETE/INVALID + issues）**，不是 500 或请求语法错误；并发/资源不存在仍分别是 409/404。
- 字段级问题以稳定 `code` 和 JSON Pointer 风格 `path` 描述（例如 `/objectTypes/0/primaryKeyRef`），不返回 SQL、StackTrace 或生产数据。

#### TC4：HTTP API 资源与命令（仅候选）

**资源前缀**：`/api/model/drafts`。首版不提供强认证/工作空间身份，但所有请求仅用于 loopback 开发，需有安全的 DTO 边界、大小限制及明确超时恢复。

| HTTP | 路径 | 请求 | 成功与状态 |
| --- | --- | --- | --- |
| `GET` | `/api/model/drafts` | `pageSize`（1..50，默认 20）、`cursor` 可选；排序按 `updatedAt DESC, draftId DESC`，游标必须基于稳定排序键 | 200 `{items:[summary],nextCursor:null|string}`，空列表真实 `[]` |
| `POST` | `/api/model/drafts` | `{ "packageId":"manufacturing.production" }`（创建空 WIP） | 201 `DraftDetail`，`Location` 包含 draftId，`revision=1` |
| `GET` | `/api/model/drafts/{draftId}` | 无 | 200 `DraftDetail`，含编辑文档及当前有效 revision 校验摘要 |
| `PUT` | `/api/model/drafts/{draftId}` | `{ "expectedRevision":1, "editingContent": { ... } }` | 200 `DraftDetail`，数据库条件写入原子递增 revision，旧校验失效 |
| `POST` | `/api/model/drafts/{draftId}/validate` | `{ "expectedRevision":2 }` | 200 `ValidationReport`，`VALID/INCOMPLETE/INVALID` + issues，报告绑定 revision |

**建议最小响应字段**：

~~~json
{
  "draftId": "00000000-0000-4000-8000-000000000001",
  "packageId": "manufacturing.production",
  "revision": 2,
  "status": "DRAFT",
  "editingContent": {
    "editorSchemaVersion": "0.1.0",
    "objectTypes": [],
    "linkTypes": []
  },
  "validation": {
    "status": "NOT_RUN",
    "validatedRevision": null,
    "issues": []
  },
  "createdAt": "2026-10-15T00:00:00Z",
  "updatedAt": "2026-10-15T00:00:00Z"
}
~~~

上面的 UUID/时间/空状态是**协议示例，不是系统已存记录**。保存/校验时间戳必须由服务器生成；客户端传来的 `draftId`、`packageId`、`revision`（除 expectedRevision）和 `validation` 字段不得被 PUT 直接覆盖。

**范围和未来多类型保护**：`PUT` 是 Package Draft 的**全编辑文档替换**而非把第一个 ObjectType 单独覆盖；所有更新都依赖包级 revision。若将来当前编辑器打开自己不支持的多个类型/非空 LinkType，首版 UI 必须进入只读/明确阻止破坏性保存，或者完整无损 round-trip，不可悄悄把数组截断为一个 ObjectType。首期不提供“编辑单独 LinkType”或“发布模型”端点。

#### TC5：错误码与前端恢复策略

| 错误场景 | HTTP | code / 正确行为 |
| --- | --- | --- |
| 非法 JSON、越界请求体、错误 UUID/请求基本格式 | 400 / 413 | `MALFORMED_REQUEST` / `PAYLOAD_TOO_LARGE`；服务端不修改任何状态 |
| 编辑 DTO 结构不安全（未知字段、错误基础类型、危险未来声明、越界数组） | 422 | `EDITOR_DOCUMENT_INVALID`，`issues[{path,code,message}]`，不能存任意 JSON |
| 相同 `packageId` 重复创建当前 Draft | 409 | `PACKAGE_DRAFT_EXISTS`，返回安全定位信息，引导打开已有草稿；不自动 upsert |
| `expectedRevision` 过期 | 409 | `DRAFT_REVISION_CONFLICT`，保留用户未提交内容；提示读取最新版本，不能强覆盖 |
| draftId 不存在 | 404 | `DRAFT_NOT_FOUND` |
| Native 候选尚不完整或含语义错误 | **200（Validate）** | `INCOMPLETE/INVALID` + 稳定 issues；与非法 HTTP 请求区分 |
| PostgreSQL/服务端不可用或请求超时 | 503/5xx/网络异常 | `PERSISTENCE_UNAVAILABLE`（能确定原因时），**不显示已保存**；必要时 GET 检查结果、再重试 |

- API 错误对象建议 `{ "code":"...", "message":"...", "issues":[...] }`；只给安全、业务可理解的信息。对具有确定影响的数据库唯一约束/乐观锁异常映射为 409，别把并发转换成泛用 500。
- 前端保留尚未确认写入的本地编辑数据。请求超时或网络切断属于**未知结果**，不是必定未写入；用户恢复时通过 GET 对比 draftId/revision/内容确认，不应盲目重复 POST。
- 为防止重复点击创建造成重复包，POST 成功前禁用重复提交，服务端以 packageId 唯一约束阻止重复记录；重试同一 packageId 时返回 409 和定位结果，而非悄悄覆盖。
- 编辑保存使用显式 expectedRevision，不采用“最后写入者获胜”。

#### TC6：PostgreSQL 持久化、事务与 Flyway 方案候选

**建议一张真实 Package Draft 聚合表，不为 P0-B 预建空 Review/Release 表**：

~~~sql
-- V2 候选结构片段：仅供评审，尚未成为真实 Flyway 脚本
CREATE TABLE native_package_draft (
  draft_id UUID PRIMARY KEY,
  package_id TEXT NOT NULL UNIQUE,
  editor_schema_version TEXT NOT NULL,
  editing_document JSONB NOT NULL CHECK (jsonb_typeof(editing_document) = 'object'),
  revision BIGINT NOT NULL CHECK (revision >= 1),
  validated_revision BIGINT NULL CHECK (validated_revision IS NULL OR validated_revision >= 1),
  validation_report JSONB NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK (validated_revision IS NULL OR validated_revision <= revision)
);
~~~

- `package_id UNIQUE` 意味着**当前一份可编辑草稿**，不是只允许一个 ObjectType，也不代表将来发布版本表不可多版本并存。
- 数据库保存的是受控的 `DraftEditingContent`，**不是直接存一个不符合 Native V0.2 Schema 却标记为完整的包**；到验证时构造 Native Package 候选。数据格式通过 `editor_schema_version` 前向演进；DraftId、schemaVersion、候选 Package Version、ReleaseDigest 不同。
- `PUT` 推荐原子 SQL 条件更新 `WHERE draft_id=:id AND revision=:expected RETURNING ...`，更新 `revision+1`、编辑文档、updated_at，并清除 `validated_revision`/validation_report；检查影响行数，区分 404 与 409。禁止先无锁 SELECT 再无条件 UPDATE 的竞态写法。
- `POST /validate` 校验当前 revision 对应的快照，写入报告时使用 `WHERE revision=:expected` 原子条件；若在校验计算期间编辑版本已改变，**拒绝 409**，不能把旧校验结论贴到新草稿。
- 不修改 Flyway V1、不删 `platform_installation`，升级 V1→V2 和重启后 Installation ID 保持稳定；所有测试用 CI 的隔离 PG17，不运行会清空用户 Windows 本地库的实验脚本。
- 运行时与上行输入上限建议首期：请求编辑文档 **≤256 KiB**、对象类型 **≤50**、单类型属性 **≤100**、Package 总属性 **≤1000**；这些是**技术合同候选**，不能靠前端隐藏、缺少服务端检查。未来根据产品需求和数据库性能有依据地调整。
- 绝不为了方便当前 MVP 就引入伪 `workspace_id`、`created_by`、假的权限/审计数据；现有服务仍需 loopback，正式可联机版本必须先经过独立身份/授权设计。

#### TC7：Draft → Candidate 的构建算法与版本语义

1. 服务端读取 `draftId`、packageId、revision 和受控 `editingDocument`。
2. 先检验编辑文档合同及资源上限，规范化**只是确定性映射**，不能偷偷补写主键、改名、重排用户显示顺序或删未知业务内容。
3. 若 WIP 缺完整发布定义所必需的 ID、主键、Property 属性，返回 `INCOMPLETE` 报告；若存在重复定义/错误类型/非法 ENUM 等，返回 `INVALID`；两类并存优先显示 `INVALID` 且保留全部 issues。
4. 仅当编辑输入满足完成条件时组装 `schemaVersion=0.2.0`、固定候选 `packageId`、`version="0.1.0"`（S0 单包候选默认值，**不是已发布版本**）及 V0.2 必需的空资产数组，`objectTypes` 映射当前完整定义。
5. 使用已审定的 V0.2 机器结构合同及经 DG3/TC2 决议正式调整的语义校验；**本 MVP 的业务完成条件额外要求至少一个完整 ObjectType，即使当前 Native V0.2 顶层 schema 暂允许 objectTypes=[]，也不能将空包标记为业务就绪的 VALID**。如果该结构版本与规则存在冲突，不得把某个“只跑通的 Java 自写规则”冒充既有合同通过。
6. `VALID` 报告必须给出 `validatedRevision`，供 UI 解释为何编辑后旧结果失效；保留精确问题路径，异常不会反向把 WIP 当成已发布。
7. 候选包版本与未来发布采用分离路线：S0 不计算可信 ReleaseDigest、不生成审核或不可变 Release、不自动激活；未来多版本 Registry 必须另行经过 OFIP/安全验收。

#### TC8：实际交付 PR 与 CI 新门槛

- 将来真正的功能 PR **一次交付** Spring Boot POST/LIST/GET/PUT/VALIDATE、V2 前向迁移、React 创建/保存 WIP/重开/验证/失败恢复和一个真实可操作页面；不要把空 Core/Registry/Controller 先拆成多个半成品 PR。
- `Required Quality Gate` 现有 Backend 检查仅确认 S0 两项 `FoundationTest` 数据库行为；实现 PR 必须增加**至少一套 NativeDraftIT 或等价 PostgreSQL + HTTP 端到端测试**，真实验证 WIP、校验、409 并发、重启及 Flyway V2。扩展 CI 报告与最低覆盖门禁，而不是让旧基础测试代替本体新能力。
- 浏览器 Playwright 或等价自动化需覆盖生产构建接后端的黄金故事；如果未运行，要在 PR 标记 `NOT RUN`，不能用 `npm run build` 替代产品签收。
- 跨类型 Property 同名测试必须证明合同层不歧义：A.batch-id 与 B.batch-id 不冲突，但 A 自身两个 batch-id 拒绝；ActionEffect.targetTypeRef/Binding.objectTypeRef 必须明确引用作用域（首期不执行 Action）。
- S0 的生产安全/多租户/工业真实系统测试仍 `NOT RUN`，不得据此宣称可开放部署。

#### TC9：本轮主要冲突处置（与 DG1–DG9 一致）

| 历史/当前候选冲突 | 技术合同推荐结论 | 必须明确的兼容措施 |
| --- | --- | --- |
| 原始 OFIP-0002 一包一类型 | Package 领域、Draft 编辑聚合支持 0..N ObjectType | 本轮 UI 可只创建首个；存储/API 不能建永久一对一限制 |
| 原始 OFIP-0002 只有完整草稿才能保存 | WIP 编辑结构化保存；Validate 后产出完整 Native 候选 | 双合同分离，`editorSchemaVersion` 与 `schemaVersion` 独立 |
| V0.2 Python `CROSS_TYPE_PROPERTY_ID_COLLISION` | 采用 DG3 已决定的 Type-scoped Property ID | 合同说明 + 双端真实回归 + 上下文引用消歧；是否升版本见 TC2 |
| 全量编辑可能覆盖尚未展示的多类型/关系 | 包级 revision 全量替换，但客户端必须无损 round-trip 或只读保护 | 不因为 MVP UI 只展示一个就截断对象/Link 数组 |
| 草稿校验正常错误 vs HTTP 错误 | 验证业务失败 → 200 + INCOMPLETE/INVALID；API/DTO 错误仍 400/422 | 稳定错误分类、不可混淆“请求失败”和“模型尚未准备好” |

## Proposed Changes

### 1. 双层草稿：Save Progress 与 Validate Model 分离（首轮推荐）

**建议的长期状态语义**：

~~~mermaid
flowchart TD
  A[Draft Editing / WIP] -->|Save Progress| A
  A -->|Run Validation| B{Complete + Valid?}
  B -->|No| C[Validation Issues / Incomplete]
  C --> A
  B -->|Yes| D[Valid Candidate Snapshot]
  D -->|Edit Again| A
  D -. future P0-B .-> E[Review]
  E -. future P0-B .-> F[Approved]
  F -. future P0-B .-> G[Immutable Release]
  G -. future .-> H[Activation]
~~~

**保存进度（Save Progress）**：本机持久化编辑器可表达的合法、受边界控制的草稿，即使尚未选主键或尚未完成属性配置，也能重新打开。要求 `draftId/revision`、确定性错误信息与数据写入成功证据；尚未完全满足 V0.2 Package Schema 的 WIP 应保存为有明确类型/版本的 **DraftEditingContent**，**不得被标记为完整 Native Package 或发布 Ready**。

**校验模型（Validate Model）**：在编辑内容达到完整结构时将其组合为 `CandidatePackage`，执行 JSON Schema Draft 2020-12 与全部适用语义校验；明确 `VALID` / `INCOMPLETE` / `INVALID`、字段级问题和受测 revision。校验是**当前快照的结论**，草稿 revision 改变后不得沿用旧的 Valid/Approved 状态。

**Review / Release / Activation**：独立的未来版本化治理状态，需安全与版本锁约束。Validation PASS 只代表这一轮定义约束可通过，**不是审批、发布、可供 Agent 使用或获得工业 Action 权限**。

**特别标注待技术合同评审**：如何在 JSONB 中表达未完成编辑、WIP 最小字段的校验规则、与现有 V0.2 候选 Schema 怎样转换及如何防止无界任意 JSON，是下一轮必须交付的结构合同，不在本轮通过一句“JSONB 容易存”就算解决。

### 2. 编辑与并发的一致性边界

- 同一个 packageId 的**当前可写草稿**采用独立 draftId 和非负递增 revision。多标签页场景必须是“显式版本冲突并保留未提交编辑”，绝不无条件覆盖。
- 草稿保存、完整性验证、审议通过和不可变发布各有独立证据。**保存请求成功≠完整校验通过**；校验通过≠将来 Review Approved。
- 编辑对象 ID/删除当前主键/新增 Link 等操作可能使原 Validation Report 失效；服务端应关联“被校验的 revision”防止陈旧结果被当作当前结果。
- 如果保存请求超时，不能直接宣称已失败或已成功；应按 draftId 读取最新 revision 或服务端确认结果后，引导用户继续操作。
- 包中的其他类型不应因为编辑单个 ObjectType 而被客户端整个覆盖，API 合同审查必须明确定义乐观锁粒度及提交范围；首期可以包级 revision 串行化，但不能把 ObjectType 当作包的唯一永久内容。

### 3. 首版 MVP 功能能力边界（不固化为架构限制）

| 能力 | 领域模型 | 第一功能 PR | 后续 |
| --- | --- | --- | --- |
| Package 组织多个 ObjectType | **1:N，从第一天成立** | 用户可从空包创建首个类型；底层不做“一包仅一类型”的领域断言 | 逐步增加第 N 个类型 |
| 编辑 ObjectType 和 Property | 完整、真实 | 创建、编辑、保存 WIP、重开、删除草稿中属性、校验结果 | 更丰富字段/批量变更 |
| 主键与类型规则 | 完整校验不变量 | 结构/语义负例、提示和修复 | 兼容影响分析 |
| LinkType | 模型包级声明、两端引用、证据语义 | 不开放编辑器，不生成空功能路由 | 与第二个 ObjectType 的联动故事 |
| Package Candidate | 完整 Native V0.2 结构 | 一个首个 ObjectType 的完整结构化候选校验，其他资产声明为空 | 多类型、Link、Imports 等 |
| 版本治理 | 明确不同身份与状态 | draftId、revision、受检 revision；不提供发布 | Review/不可变 Release/Activation |
| UI 交互/视觉 | 以用户任务推进 | 后续低保真和高保真**签收之后**才实现 | 高级专业工具 |
| Auth/Workspace | 真实产品必须有 | 仅隔离本机开发，无假用户/假审批 | 单独安全/租户 OFIP 后落地 |

首个可操作验收故事仍然是：

> 创建 `manufacturing.production` 下的 `ProductionBatch` → 输入类型标识和属性 → 暂存尚未完整配置的 WIP → 返回列表、刷新、重开 → 完成 batch-id（STRING / required）、primaryKeyRef 和其余属性 → 运行完整校验，看到明确 VALID → 保存后重新打开、再次编辑并使旧校验结论失效 → 再次校验 → 比较 revision 和 PG 持久化事实。

**不把先创建一个 ObjectType 误认为以后一个包只能有一个**。产品是否在首版允许“一包继续添加第二个类型”要在第二轮交互设计基于真实操作复杂度决定，不应作为底层永久独占约束。

### 4. 评审 Gate 与交付步骤

| Gate | 要回答的问题 | 本次输出与验收 |
| --- | --- | --- |
| **Domain-Gate-1（已通过）** | Package/Type/Property/Link 的拥有者、基数、身份、保存与校验、候选与发布如何分离？ | [DG1–DG9 全部 A 已签收](https://github.com/gitfortian/ontoforge/pull/6#issuecomment-6191075093) |
| **Contract-Gate-2（已通过）** | Native V0.2 引用作用域的正式修订方案；Typed WIP、ValidationResult、API、事务与迁移合同 | [TC1–TC10 全部 A 的明确记录](https://github.com/gitfortian/ontoforge/pull/6#issuecomment-6191277696)；仍无新运行时代码 |
| **UX-Gate-Low（当前待审）** | 用户从模型包进入类型，编辑属性、保存 WIP、校验、冲突/网络恢复的操作顺序是否清楚？ | [可点击低保真原型](../prototypes/ofip-0002-objecttype-lowfi.html) + 本 OFIP 的 UX 评审矩阵；评审通过后才进入高保真 |
| UX-Gate-High（随后） | 页面是否符合 OntoForge 视觉体系、字段反馈、空态、错误、可访问性？ | 高保真原型 + 签收截图/状态矩阵；避免复刻未确认的 Demo |
| Implementation Gate | 一次 PR 是否真正交付完整的前后端用户链路？ | Flyway 前向迁移、API、真实 PG、React、字段校验与浏览器自动化/手工回归 |
| Acceptance Gate | Windows 11 本机用户能否完成黄金故事、重启与异常恢复？ | 用户实际操作与签收；CI 成功不替代本机验证 |

每个阶段能一轮通过就一轮通过；发现真实问题才返工，不以文档数量或流程时长为质量目标。**只有所有设计 Gate 签收后才开始功能 PR**；这不等于 P0-A 的所有未来功能都要一次性实现。

---


### UX-Gate-Low：可点击低保真方案（当前待评审）

> **独立可查看文件**：[OFIP-0002 ObjectType 可点击低保真原型](../prototypes/ofip-0002-objecttype-lowfi.html)。在 GitHub 打开文件后可使用 Raw/Download 将 HTML 保存到本机，并通过浏览器打开。此文件位于 \`doc/prototypes\`，不是 \`ontoforge-ui\` 的生产页面；所有新增、保存、校验、409、网络断开均为**当前浏览器页面内存模拟**，**刷新页面即丢失数据**，不能作为 Flyway/API/真实重启验收证据。可参考 [PR #6](https://github.com/gitfortian/ontoforge/pull/6) 中的版本记录。

#### 首版页面地图与交互责任

~~~mermaid
flowchart TD
  A["本体设计 / 模型包列表"] --> B{"是否已有模型包？"}
  B -->|否| C["输入 packageId · 创建空 DRAFT"]
  B -->|是| D["打开模型包详情"]
  C --> D
  D --> E["新建或继续编辑 ObjectType"]
  E --> F["对象标识、名称、属性、主键"]
  F --> G["保存编辑进度（WIP）"]
  G --> D
  D --> E
  E --> H{"当前 revision 已保存？"}
  H -->|否| G
  H -->|是| I["完整校验"]
  I --> J{"VALID / INCOMPLETE / INVALID"}
  J -->|未通过| E
  J -->|通过| K["完成当前候选结构检查"]
  E --> L["模拟网络失败 / revision 冲突"]
  L --> M["保留本地输入 + 明确恢复选项"]
  M --> E
~~~

- **导航**：本体设计下只给两个真实操作入口「模型包」「对象类型」；关系类型保留为不可用的后续信息，不让新用户先处理 LinkType/Binding/发布配置。对象类型列表提供按包返回的真实去向。
- **首次建模**：没有模型包时使用单一「创建模型包」行动；输入 \`packageId\` 后创建空 DRAFT，再进入类型编辑。先建包再建类型是**模型关系与后续多类型扩展要求**，不是制造多页迷宫。
- **编辑界面**：类型标识/展示名/定义、属性配置和主键在同一业务任务里；字段增删不要求额外弹窗。主键应**从当前类型已存在的 Property 选择**，不让用户手输 ref。
- **两种不等价的操作**：「保存编辑进度」只确认 WIP 写入（未来真实服务端才有证据）；「检查模型完整性」验证特定 revision，缺主键/缺属性应显示 INCOMPLETE，格式/重复约束问题为 INVALID；VALID 不叫“可发布”。
- **继续/返回**：保存后仍允许继续编辑，也能回模型包详情、对象类型列表，再重开同一个草稿。未保存编辑离开应二次确认。
- **失败恢复**：模拟另一标签页更新后，当前编辑保存必须呈现 409 且**保留用户输入**；可明确放弃本地编辑并加载最新，但绝不静默覆盖。网络未知结果显示“不确定是否写入”，引导恢复并核对 revision，不能展示已保存成功。
- **未支持包的保护**：如果服务端草稿里已有多个对象类型或 LinkType，而当前编辑器只能展示首个，必须禁止破坏性全量 PUT 或确保无损 round-trip；原型提供相应防覆盖文案，但真实多类型 API 和跨包行为仍待后续开发。
- **模拟与真实的边界**：低保真脚本只维护内存模拟状态，没有 localStorage、PostgreSQL、HTTP 或身份授权；评审的是入口、路径、状态/恢复表达，不是持久化实现。高保真签收以后才开始业务代码。

#### 推荐低保真评审顺序（UX1–UX9）

| 条目 | 原型操作 | 要验证的体验与问题 |
| --- | --- | --- |
| **UX1 空状态/任务入口** | 打开「模型包」，从空页面创建 \`manufacturing.production\` | 是否清晰知道第一步，不需要先配置复杂专业概念 |
| **UX2 包 → 对象类型** | 新包详情点击「新建首个对象类型」 | 是否理解 Package 包含多个类型，而不是“一包固定一个类型” |
| **UX3 WIP 暂存** | 只填 \`id=production-batch\` 和 \`apiName=ProductionBatch\`，保存，返回包详情后重开 | 未完成内容是否可以保留；明确 **不是** V0.2 完整 Package |
| **UX4 反例校验** | 对未选 PK 的草稿点「检查模型完整性」 | INCOMPLETE 原因可理解，不能误以为保存失败 |
| **UX5 完整属性配置** | 增加 \`batch-id\` STRING+必填，选择 PK；再加任意字段，保存并校验 | VALID 是否清楚、字段类型/分类/ENUM 决策负担是否合理 |
| **UX6 旧校验失效** | VALID 后修改描述并再次保存 | 校验状态必须重新变为 NOT_RUN，显示准确 revision |
| **UX7 未保存离开** | 修改字段、点返回模型包 | 弹出离开确认，允许继续编辑；绝不悄悄“已保存” |
| **UX8 并发冲突** | 在编辑页点「模拟另一标签页保存」再提交 | 必须提醒 409，保留输入，提供有风险说明的载入最新动作 |
| **UX9 网络未知** | 点「模拟断网」再保存，恢复网络并重试 | 不能把未知结果报告成成功；应提供核对/重试路径 |

**当前低保真原型的已知限制**：为了验证信息架构，它采用灰阶线框而非 OntoForge 最终视觉；错误目前主要在右侧问题列表并附 JSON Pointer，尚未做到每一行输入旁的就地错误、键盘焦点恢复和实际浏览器 History。这些必须纳入本轮评审意见，在 **UX-Gate-High** 中做完整视觉/状态/可访问性验收，不能因为目前可点击就认为最终交互已定稿。

**阶段签收规则**：本轮请审 UX1–UX9，包括导航、操作数量、完成路径、校验与失败恢复；不满意的直接在 [PR #6](https://github.com/gitfortian/ontoforge/pull/6) 指出，修改原型后复审。UX-Gate-Low 通过才能进入高保真原型。高保真通过、完整 OFIP Accepted 后才启动前后端功能 PR。**DG1–DG9、TC1–TC10 已签收不代表 UX1–UX9 已签收**。

### UX-Gate-Low：独立浏览器实测与评审结论（维护者签收仍待确认）

**独立复核结论：建议通过低保真交互方向，允许进入高保真设计；当前不代表维护者最终 UX 签收，也不代表完整 OFIP Accepted。**

为了避免只靠浏览器脚本语法正确就宣布可用，本轮新增真实 Chromium 自动化：
- 回归脚本：[doc/prototypes/tests/test_objecttype_lowfi_browser.py](../prototypes/tests/test_objecttype_lowfi_browser.py)，覆盖真实点击、输入、弹窗、版本状态、失败恢复与窄屏。
- 工作流：[.github/workflows/ofip-0002-lowfi-browser.yml](../../.github/workflows/ofip-0002-lowfi-browser.yml)，仅用于原型评审的浏览器验证，不测试正式 React API。
- **第一轮失败证据**：[浏览器 Run #38733853021](https://github.com/gitfortian/ontoforge/actions/runs/38733853021) — 10 项中 8 PASS、1 FAIL、1 ERROR；确认 **UX9：网络模拟导致未保存表单内容丢失**，另一个 ERROR 是测试的菜单定位符过严。
- **修复与第二轮通过证据**：[浏览器 Run #38734086157](https://github.com/gitfortian/ontoforge/actions/runs/38734086157) — **10/10 PASS**；[10 张浏览器截图](https://github.com/gitfortian/ontoforge/actions/runs/38734086157/artifacts/11971064943)。已修复断网触发页面重绘前未同步 editor state 的问题；导航测试改用稳定 DOM id，没有隐藏失败或降低断言。
- 本轮最终提交的 [Required Quality Run #38734086165](https://github.com/gitfortian/ontoforge/actions/runs/38734086165) 已通过；同一提交的独立 Native V0.2/文档工作流也通过。当前工作流修改导致自动范围选择器全选相关专项，具体范围以 Actions 记录为准。

**UX1–UX9 审核矩阵（独立测试结果与产品审核区分）**

| 评审项 | Chromium 复核 | 设计判断 |
| --- | --- | --- |
| UX1 空态创建 | PASS | 单一“创建模型包”入口清楚；模型包命名提示需在高保真给出可理解的业务说明 |
| UX2 包进入对象类型 | PASS | “包 → 类型”符合 1:N 领域关系，首版不开放 LinkType 编辑器合理 |
| UX3 WIP 保存/重开 | PASS（同一页面模拟内存） | WIP 保存与完整校验清晰分离；真实刷新/重启持久化要留给功能验收 |
| UX4 不完整/错误校验 | PASS | 状态正确；字段错误仍主要在右侧 JSON Pointer 列表，高保真须就地定位 |
| UX5 类型/属性/PK | PASS | 主键可随新属性 ID 动态选择；可保留首版字段配置，但需降低高级字段首次认知负担 |
| UX6 编辑后旧校验失效 | PASS | revision 与 NOT_RUN 语义正确，真实后端必须原子保证 |
| UX7 未保存离开 | PASS | 取消离开仍保留编辑；高保真需覆盖浏览器后退、刷新和跨路由拦截 |
| UX8 两标签页冲突 | PASS | 409 后仍保留输入；高保真要提供“查看最新版 + 对比本地修改”而非仅舍弃本地 |
| UX9 断网及恢复 | **第一轮 FAIL → 修复后 PASS** | 必须明确“保存结果未知”和“核对服务端 revision”；避免盲目重复提交 |

**高保真必须落实的改进，不作为本轮拒绝低保真方向的理由：**

1. **表单错误定位**：右侧问题列表须可点击跳转对应 Property 行、聚焦输入，并就地显示错误原因/修复提示。不能要求用户读 JSON Pointer。
2. **操作层级**：将“保存编辑进度”作为主要操作，“检查模型完整性”在已保存的当前 revision 上分步推进；完成后给“返回模型包 / 继续编辑”明确分叉，不能堆多个等权按钮。
3. **冲突恢复**：409 除提示加载最新版外，必须展示**服务端 revision、本地 revision、是否舍弃输入**；推荐保留本地内容供用户复制/对比，禁止自动强制覆盖。
4. **异常与浏览器导航**：网络超时后展示“保存结果未知 → 查询最新草稿 → 决定重试”；真实系统的 URL/draftId、Back/Refresh、加载中、空态、断网以及键盘导航需一并进入高保真状态矩阵。
5. **视觉范围**：当前灰阶样式只是交互线框；不能照搬成正式 OntoForge 页面。高保真必须遵循现有应用组件、排版密度和整体设计语言。

**本轮未验证/不应冒充通过**：Windows 本机实际 PostgreSQL/Flyway V2、Spring Boot Draft CRUD、Native V0.2 新 Property 作用域、真实 React 页面、刷新后持久化、生产权限/多租户/工业 Action。原型内存模拟就是原型，不存在“真实保存成功”。

**状态边界**：助手作为独立审查者认为 UX1–UX9 在低保真阶段**可进入高保真优化**；仍请维护者在 PR #6 对交互方向明确签收或给出修改意见，随后才进入 UX-Gate-High。保持 `Status: Discussion` / `Implementation: Not Started`，不因 10/10 绿灯改为完整 Accepted。

### UX-Gate-High：高保真原型与前端交付合同（受托独立审查通过；整体 OFIP 待批准）

#### 受托独立功能评审记录 · 2026-10-15

- **结论**：**UX-Gate-Low = PASS；UX-Gate-High = PASS（仅设计原型交互）**。用户明确委托助手替代逐条点选审核；此处是独立审查结论，不是维护者正式的 `[VOTE] Accepted`，不得擅自开放业务代码实施。
- **实际检查**：[Chromium #38739196976](https://github.com/gitfortian/ontoforge/actions/runs/38739196976) **31/31 PASS**：低保真 10、原高保真 14、专项独立审查 7；[31 张实际浏览器截图](https://github.com/gitfortian/ontoforge/actions/runs/38739196976/artifacts/11974794680)。还实际查看了桌面编辑/字段错误、409 冲突和 390px 窄屏截图，确认与现行 OntoForge S0 品牌布局风格一致。
- **基座回归**：[Required Quality #38739196880](https://github.com/gitfortian/ontoforge/actions/runs/38739196880) PASS；[Architecture Docs Gates #38739196933](https://github.com/gitfortian/ontoforge/actions/runs/38739196933) PASS。既有 PG17/S0 FoundationTest 及 React build 通过**不代表**新的 Native Draft V2/Java API 已测试。

| 审核中发现的问题 | 分级 | 修复及证据 |
| --- | --- | --- |
| 编辑旧 VALID 的草稿时，当前本地表单仍显示“校验通过” | P1 误导状态 | 输入修改立即显示“本地编辑待保存”，隐藏旧问题/标记并禁用完整校验；保存新 revision 后旧报告失效，专项 Chromium PASS |
| 主键下拉框包含非必填或非 STRING/INTEGER 字段 | P1 建模语义 | PK 选择器只提供本类型 required=true 的 STRING/INTEGER 属性，调整字段使其失效时清除选项，专项 Chromium PASS |
| 非 ENUM 字段可以输入枚举值，修改类型后留下隐形数据 | P2 字段准确性 | 非 ENUM 禁用枚举配置，切回普通类型后清空枚举值；实测提交的 Mock 编辑结构无残留 |
| 动态 Property 字段 label 无对应 HTML for | P2 可访问性 | ID/API 名/显示名/值类型/分类/枚举均绑定真实输入，实测点击 label 聚焦正确控件 |
| 高保真辅助说明文字对比度过低 | P2 可读性 | 实际浏览器截图复核后调整灰蓝文字，并新增正文对比度 WCAG AA >=4.5:1 的 Chromium 自动回归 |
| 既有断网重绘丢编辑内容、未保存时校验按钮没有立即禁用 | 已修 P1 回归 | 原低保真和高保真既有回归继续运行 PASS，不允许后续改版回退 |

**非本轮原型 Gate 的遗留实现阻断（必须在业务功能 PR 验证）**：正式可寻址 draftId 路由与浏览器 Back/Refresh；真实持久化保存/重启重开；服务端 Schema/语义及 Type-scoped Property 引用；乐观并发原子写入；500/网络未知结果查询与恢复；安全/认证/工作空间/发布。它们没有因设计原型通过而成为“已完成”。



> **可操作文件**：[OFIP-0002 高保真原型](../prototypes/ofip-0002-objecttype-highfi.html)。GitHub 中使用 **Raw/Download** 保存到 Windows 本机后，可直接用 Edge/Chrome 打开。该文件独立于 \`ontoforge-ui\`，**不是产品代码**；保存、校验、并发、离线、页面状态仅使用浏览器内存模拟，刷新会清空原型数据，**没有 API/PG/真实鉴权**。
>
> **风格依据**：已对照当前 main 中实际 React \`ontoforge-ui/src/main.tsx\` 和 \`ontoforge-ui/src/style.css\` 的白色侧栏、深蓝品牌标识（#173657）、浅灰蓝背景（#f6f8fb）、蓝色任务高亮与卡片边框/阴影；不是把独立工业产品重新设计成一套与 OntoForge 不同的界面，也不修改当前已运行的 S0 页面。

#### 一、页面信息架构

| 页面 | 主要任务 | 默认突出行动 | 成功/失败返回路径 |
| --- | --- | --- | --- |
| 模型包列表 | 确认进入哪个业务定义包 | 空态「新建第一个模型包」，非空态「创建模型包」 | 进入包详情，重复 ID 引导打开已有包 |
| 模型包详情 | 了解当前包内对象定义和校验状况 | 「新建首个对象类型」/「继续编辑」 | 返回包列表；未来多类型/Link 在产品信息架构中有位置 |
| 对象类型列表 | 从对象名称定位所属模型包 | 「进入包」而非立即要求配置技术字段 | 返回包详情继续建模 |
| 对象类型编辑 | 定义 ID、名称、属性、主键 | **底部主操作仅「保存编辑进度」**；右侧按条件提供「检查模型完整性」 | 保存确认后可返回模型包/继续编辑 |
| 版本与校验信息面板 | 解释 DRAFT、revision、VALID/INCOMPLETE/INVALID | 点击具体错误→自动滚动定位字段、聚焦并展示就地提示 | 修复后保存新 revision 再校验 |
| 异常恢复 | 解决 409 和未知保存结果 | 「核对服务器 revision」「确认放弃本地编辑并载入最新」（高风险操作） | 保留输入、不静默覆盖；回到当前编辑任务 |

**首版不开发正式 Relation/Link 编辑、审核、发布、真实生产系统绑定页面**。原型中的禁用导航用于解释后续能力，不等于后端已支持。

#### 二、视觉与组件约束（为未来 React 交付服务）

- **品牌与导航**：实际 OntoForge S0 的品牌名、白色侧栏和深蓝品牌底色；不擅自重命名功能模块，不套用第三方 UI 套件。左侧导航保留「模型包」「对象类型」，顶层只提示本体设计领域。
- **版式**：顶部面包屑/当前评审环境，页面标题+辅助解释，卡片容纳业务配置，桌面采用「编辑主区 + 右侧状态/校验」布局；窄屏将右侧区域移到下方，侧栏改横向入口。主布局在 1365px 桌面和 390px 窄屏用例验证。
- **表单**：字段名、ID、API 名称、值类型、分类和 Required/Searchable 保持与 Native V0.2 提案合同一致；统一明确 focus、disabled、error、empty、unsaved 的视觉语义。高保真 HTML 代码仅说明表现，后续 React 应复用正式样式令牌与真实表单组件，不直接复制/嵌入原型 JS。
- **层级**：保存进度为一次主任务的唯一主要提交动作，校验是右侧下一步；编辑器尚有未保存输入时，校验按钮必须**立即禁用**并解释原因，防止用户把未提交的本地内容当作服务端已校验。
- **状态真实性**：\`DRAFT\`/revision 与 \`VALID/INCOMPLETE/INVALID\` 两条状态轴永不混为一谈，VALID 不命名为“已发布”。网络请求失败不能显示已保存成功。

#### 三、九类关键交互及新增高保真验收点

| 编号 | 用户动作 | 高保真合同 |
| --- | --- | --- |
| H1 | 初次访问及从空态创建包 | 无假模型、单一高优先级入口、提示合理的 packageId 格式 |
| H2 | 模型包详情进入 ObjectType | 不让用户在第一次定义类型前选择 LinkType/数据映射 |
| H3 | 部分输入后保存并重开 | 未填主键也可保存 WIP、返回列表后继续编辑；只模拟同页状态 |
| H4 | 完整校验得到问题列表 | 列表项可点击，定位对应输入，\`aria-invalid=true\`，字段旁显示可读中文原因 |
| H5 | 定义属性和主键 | 输入 Property ID 后主键候选实时更新；ENUM/数据分类可读，不默认伪造权限 |
| H6 | 编辑已 VALID 类型 | 输入后校验入口立即不可用；保存 revision+1 后旧结论清除为 NOT_RUN |
| H7 | 未保存离开或键盘操作 | 当前原型确认弹窗有效；真实 React 需支持地址栏刷新、Back、前进与可访问性焦点恢复 |
| H8 | 模拟并发冲突 | 本地与服务器 revision/描述**并排显示**，保留本地输入；丢弃本地内容必须再次确认 |
| H9 | 断网与恢复 | 未提交输入不丢，提示保存结果未知；允许恢复网络后**先核对服务器 revision**再重试 |

**未支持的真正产品状态**（需在实施 PR 提供专业处理，不用演示成功替代）：数据库读取 Loading、空列表、后台 API 500、真实网络重连、列表分页游标、跨页面 URL/Draft ID、浏览器 Back/Refresh、权限限制/禁用、正式发布后的只读状态、复杂多类型模型包安全降级。高保真当前的设计表达不构成这些状态已经在产品中实现。

#### 四、独立高保真测试、证据和验收阈值

- [高保真真实浏览器回归源码](../prototypes/tests/test_objecttype_highfi_browser.py)：**14 项 Chromium**，沿用低保真的 10 项实际输入/点击测试，并增加 4 项高保真专项检查（字段错误定位、重复 Property 精确定位、保存/校验按钮实时可用状态、冲突双方内容展示）。
- [低保真浏览器回归源码](../prototypes/tests/test_objecttype_lowfi_browser.py)：**10 项 Chromium** 保留为不可回退的 UX1–UX9 基线。新高保真改动不能破坏旧版的已验证交互方向。
- [原型浏览器 CI 工作流](../../.github/workflows/ofip-0002-lowfi-browser.yml) 在原型改动时使用真实 Chromium 执行三套测试、捕获截图；所有输出属于**Mock 页面 UX 自动化**，不能证明 Spring Boot API、PostgreSQL Draft V2、生产权限或真实业务验收。
- 本阶段现有证据：31 项实际 Chromium 测试全部 PASS、GitHub Required Quality Gate 与架构合同工作流真实执行成功；存在失败需修复后重新提交完整 head SHA，并在 PR 中区分 PASS / NOT SELECTED / NOT RUN。
- 设计评审结论需单独由维护者确认。**视觉可用性（页面对齐、密度、标签、文案）和用户故事有效性不能由 Playwright 绿色直接代替**。高/低保真受托独立审查已形成明确 PASS 结论；OFIP-0002 仍保持 Discussion/Implementation Not Started，直到维护者正式批准完整实施。
- Windows 11 本地原生 PostgreSQL、Java Draft API、Flyway V2、真实刷新后重开、安全/身份、工业语义计算均待实施 PR 实际验收。

**受托高保真评审结论：PASS（静态 Mock 交互范围）**。[详见独立审查评论及 31/31 Chromium 证据](https://github.com/gitfortian/ontoforge/pull/6#issuecomment-6192752743)。全部 H1–H9 的主路径均已通过真实点击与异常恢复审核，视觉与对比度问题也已整改。**下一步不再要求用户逐一手工操作原型**，仅保留维护者对整个 OFIP 的正式批准；业务实现、数据库持久化和 Windows 实测不能以原型验收代替。

### Domain-Gate-1：已签收的领域决策（DG1–DG9 全部 A）

| 决策 | 推荐 A | 备选 B / 风险 |
| --- | --- | --- |
| **DG1 Package 基数** | Package 1:N ObjectType、1:N LinkType；首版 UI 先做第一个类型 | 一包永久一类型：未来关系建模需要返工 |
| **DG2 草稿保存** | Save WIP 与 Validate 完整 Native Package 分离，WIP 可以未选主键 | 只能完整后保存：长表单编辑易丢失进度 |
| **DG3 Property 身份** | 逻辑上以 ObjectType 为作用域；下轮必须显式处理 V0.2 的跨类型 ID 约束与兼容 | 延续 Package 全局唯一，实施最省改动但模型语义较僵硬 |
| **DG4 主键规则** | PK 引用自身 Property.id，完整候选要求 required=true 且 STRING/INTEGER | 用展示名、任意复合字段或物理数据库自增键充当主键 |
| **DG5 编辑/发布身份** | draftId + revision + schemaVersion + package.version + future ReleaseDigest 完全分离 | 合并版本概念导致并发/审核语义混乱 |
| **DG6 LinkType 定位** | 领域先定义端点、基数与来源证据；首版无 Link 编辑器，不生成 LinkFact | 要么不考虑关系，要么首版把关系实例也做进去 |
| **DG7 验证状态** | WIP / INCOMPLETE / INVALID / VALID 分离；未来 REVIEWED/PUBLISHED 另设 Gate | 校验绿灯直接标“发布” |
| **DG8 MVP 范围** | 一个功能 PR 做首个 ObjectType 的真实创建、WIP 保存、重开、编辑、完整校验及失败恢复；未来多类型不受底层硬约束 | 首版把多类型/Link/Release 全部开发，会明显膨胀 |
| **DG9 研发顺序** | 本轮领域签收 → 下轮技术合同 → 低保真 → 高保真 → 完整前后端 PR → Windows 实测 | 直接开发或机械地为了轮次生成多份重复文档 |

**签收记录**：[PR #6 评审评论](https://github.com/gitfortian/ontoforge/pull/6#issuecomment-6191075093)。DG1–DG9 按推荐 A 作为领域约束，但 **OFIP-0002 仍是 Discussion；Contract-Gate-2、交互/视觉、完整设计和实施状态继续独立审议**。

### Contract-Gate-2：已签收技术决策（TC1–TC10 全部 A；仅设计）

| 议题 | 推荐 A：建议通过 | B / 代价或风险 |
| --- | --- | --- |
| **TC1 Draft 文档** | `DraftEditingContent(editorSchemaVersion=0.1.0)` 与 Native V0.2 Candidate 严格分离；WIP 可缺 PK 但有服务端安全边界 | 把未完成内容硬写成 Native Package，会稀释现行 Schema |
| **TC2 Property ID** | 使用 Type-scoped 复合引用；同步修订当前未签收 Native 语义及正反例，对缺上下文消费者必须升版本/补迁移 | 直接删除包级 ID 校验会造成引用歧义 |
| **TC3 验证状态** | DRAFT 资源生命周期与 NOT_RUN/INCOMPLETE/INVALID/VALID 校验轴独立，校验绑定 revision，修改即失效 | 将 WIP/VALID/PUBLISHED 混为一谈 |
| **TC4 API** | 列表、创建空草稿、读取、完整编辑文档 PUT、按 revision 校验五个端点；暂不提供发布 API | 过早做复杂 Registry，或只建接口不形成业务闭环 |
| **TC5 错误/恢复** | 请求错误/数据契约错误/冲突/校验不通过分离；VALIDATE 业务不通过返回 200+issues，网络未知不假报保存成功 | 全部用 500/422 或客户端自动覆盖，导致用户无法恢复 |
| **TC6 PG/Flyway** | V2 单表保存 typed WIP JSONB、revision、校验快照；条件写入且 V1 安装 ID 不变 | 每个 Property 一张表或改写历史 V1，增加迁移与竞态风险 |
| **TC7 Candidate 构造** | 服务器从同 revision WIP 转换出完整 V0.2 Candidate；额外至少一 ObjectType 完整，候选版本不代表 Release | 允许空包“VALID”或把校验自动当作发布 |
| **TC8 CI/验收** | 增加实际 Java/PG 集成、真实 API 正反例、浏览器黄金故事和用户 Windows 实测；升级现有只检查 FoundationTest 的证据门 | 旧 S0 CI 绿灯代替 ObjectType 测试，形成假完成 |
| **TC9 兼容性** | 本 PR 不碰 Native Schema；下个受审合同 PR 明确旧 Property 唯一性变化、上下文规则、版本/迁移矩阵 | 仍用 0.2.0 名称暗改语义，不可追溯 |
| **TC10 交付纪律** | TC1–TC9 单独评审通过后再做低保真→高保真→一次性纵向交付，不在技术合同评审 PR 开发 | 先堆 Controller/SQL/前端，再让原型追着代码跑 |

**技术合同签收记录**：[PR #6 评审评论](https://github.com/gitfortian/ontoforge/pull/6#issuecomment-6191277696)。TC1–TC10 采用推荐 A；现有 Native V0.2 机器合同尚未修改，下一阶段 UX-Gate-Low 待实际评审。完整 OFIP 暂不改为 `Status: Accepted`，不得提前编写业务功能代码。


## Compatibility, Deprecation, and Migration Plan

1. **本提案不修改 V0.2 Schema、任何代码或 Flyway V1**；领域决议确认后下一轮才能提出机器合同变更。尤其 Property ID 作用域与 Draft WIP 结构须先确定，再修改 Python 正反例。
2. **已存在 V0.2 实验夹具继续为历史契约证据**。若推荐 Type-scoped Property ID 获准，须在 Contract-Gate-2 明确采用“修订仍未正式生产签收的 V0.2 候选合同”还是“版本化 Native V0.3”，避免同名版本的规则暗变；兼容与拒绝矩阵必须保留。
3. **Package 基数不做“一包一类型”数据库限制**。如持久化中为了第一版选用一行 JSONB 聚合，也应允许内部 0..N ObjectType/LinkType，并明确 WIP 编辑合同与 CandidatePackage 区别。不能先建错误模型，再寄望以后自动迁移。
4. **编辑版本 vs 发布版本**：draft revision 由成功写入递增；候选包语义版本和未来 ReleaseDigest 不能被 revision 顶替。未来多个已发布版本并存，不能被一个唯一 `packageId` 的草稿行所阻断。
5. **主键和业务身份兼容**：正式发布后 ObjectType/Property ID、PK、classifications、enum 值变化需要影响分析；本轮草稿未发布，可按 revision 编辑，不触发虚假的下游数据迁移。
6. **S0 安全界限**：此前没有工作空间、用户、授权机制；只允许 127.0.0.1 开发环境操作模拟定义，不连接真实工厂源、不公开 API、不把本地编辑当作企业安全能力。

## Test Plan

**本轮 Contract-Gate-2 设计 PR 允许报告的真实检查**：现有文档链接、OFIP 六章及状态测试、Native V0.2 原有结构正反例（以实际 Actions 结果为准）。由于原 V0.2 的跨类型 Property ID 限制尚未修订，**旧合同测试绿色也不能证明本轮 TC2 新语义已实现**；不得声明已经跑过数据库 V2、Java API、浏览器或工作空间安全验收。

**后续设计/开发必须制定的可执行验收**：

| 领域合同 | 成功实例 | 必须覆盖的拒绝/状态边界 |
| --- | --- | --- |
| 多类型语义 | Package 逻辑上包含 2 个 ObjectType，某 LinkType 连接它们 | 跨包无锁引用、歧义端点、孤立 LinkType 被拒；LinkType 不生成 LinkFact |
| 草稿与候选 | WIP 无 PK 能保存并重开；完成 PK 后候选包可通过 V0.2 结构与语义检查 | WIP 不能声称 VALID / PUBLISHED；改变 revision 后旧 Validate 失效 |
| 标识规则 | 重复 Property ID 在约定作用域内被正确判断；apiName/ID 各自唯一性明确 | 同一类型重复 ID/Name、跨类型 ID 冲突按最终获批准的合同稳定处理 |
| 主键约束 | batch-id STRING、required=true、primaryKeyRef 指向自身 | 主键缺失、非必填、DECIMAL/DATETIME 作为 PK、引用其他 ObjectType 属性 |
| ENUM/分类 | ENUM 非空去重，分类明确，搜索属性可识别 | ENUM 缺值、重复值、非法分类、未知 valueType、未授权语义不能冒充权限 |
| PG 持久化/并发 | V1 → V2 前向迁移不变安装 ID；WIP→编辑→重启→重开，revision 正确递增 | 过期 revision 409、部分写入回滚、停库/未知响应不伪造已保存 |
| UI/用户故事 | 浏览器按故事创建、保存、重开、完成校验、再编辑使旧结果失效 | 空状态、未保存离开、网络失败、冲突保留输入，不能靠演示数据 |
| CI 与安全 | `Required Quality Gate` 按实际修改选中真实 PG/前端/合同，并有 JUnit/浏览器证据 | 只跑 S0 FoundationTest、仅 TS 构建、仅 JSON Schema 检查不等于 P0 用户体验通过 |

**重要限制**：现有 S0 PostgreSQL CI 的 FoundationTest 目前只有安装状态和 Flyway V1 两项正向测试；未来实现 PR 不能仅凭它们通过就声称 ObjectType API/PG 写入已被覆盖。必须加入本体领域负例和可重复浏览器路径，并让 CI 实际执行。

## Rejected Alternatives

1. **将一包一类型定义为永久领域约束**：拒绝；Native Package 原本是定义集合，未来 LinkType 需要跨 ObjectType 关系语义。
2. **只要按“保存”就声称模型完整有效**：拒绝；WIP 保存与 Native V0.2 完整 Schema/语义校验必须分层。
3. **为了存 WIP 而直接修改 V0.2 完整结构必填字段、允许其不再有效但仍叫 NativePackage**：拒绝；应设计独立 DraftEditingContent 和转换/校验。
4. **直接在代码里取消现有 CROSS_TYPE_PROPERTY_ID_COLLISION 规则**：拒绝；先明确 Property 作用域并正式修订合同/兼容矩阵。
5. **靠 LinkType 定义自动填充 ObjectInstance/LinkFact**：拒绝；类型声明不是来源事实。
6. **以 draftId、package.version 或伪 digest 互相替代**：拒绝；编辑、语义版本、发布身份不可互换。
7. **一轮直接写 SQL/API/页面、后续再评审用户故事**：拒绝；先冻结领域和合同，再做低保真/高保真，最后一个可验收功能 PR。
8. **为未来 Workspace/审批/图引擎创建无消费方的空服务/通用模块**：拒绝；保留领域边界但不提前堆工程结构。
