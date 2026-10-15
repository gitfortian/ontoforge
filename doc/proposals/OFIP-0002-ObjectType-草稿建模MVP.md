# OFIP-0002：Native 模型包与 ObjectType 领域模型、草稿生命周期及首个 MVP

**Status:** Discussion

**Discussion:** [PR #6 — OFIP-0002 评审](https://github.com/gitfortian/ontoforge/pull/6)

**Decision:** Domain-Gate-1：**DG1–DG9 全部 A 已通过**（[PR #6 维护者决定记录](https://github.com/gitfortian/ontoforge/pull/6#issuecomment-6191075093)）；Contract-Gate-2 / 原型及完整 OFIP **Pending**，不授权开发

**Implementation:** Not Started — 领域评审 → 技术合同评审 → 低保真原型 → 高保真签收 → 前后端纵向交付 → 真实验收

**Release:** Not Released

**Review gate:** Domain-Gate-1 **Passed**；Contract-Gate-2 **Discussion / Pending**（本轮提出 API、WIP 校验、PG 存储与兼容性候选，尚未冻结，未到原型或代码阶段）

**Related:** [OFIP-0001 治理规范](./OFIP-0001-需求提案文档治理与CI证据门禁.md) · [总体架构 V1.3 候选](../本体产品总体功能与技术架构设计_v1.3.md) · [Native V0.2 结构合同候选](../design/核心本体元模型与Native-DSL-AST契约_v0.2.md) · [S0 迭代计划](../implementation/S0-MVP-迭代计划.md)

> **重要事实**：经核对当前 main，Spring Boot 仅有真实的系统状态 API，Flyway V1 只建平台安装记录；React 的本体建模入口仍为“规划中”。Native V0.2 的 JSON Schema 与 Python 正反例是真实存在的**候选合同与实验验证**，不是 Java 服务或生产编译器。S0 无认证/授权/工作空间隔离；不得作为企业正式服务开放或加载真实工业数据。
>
> **领域决策结果与后续技术评审**：DG1–DG9 已按推荐 A 方案通过，否定原先“一包一类型、只有完整才能保存”。**Package 1:N ObjectType、1:N LinkType；Save WIP 与 Validate 分离**成为领域设计约束。下文 Contract-Gate-2 提出具体技术合同，仍待单独评审；现有 Native V0.2 源码及产品实现本 PR 不改。

## Motivation

### 1. 用户、问题与可观察结果

工业领域专家或本体建模人员需要描述“生产批次（ProductionBatch）”的业务概念，再逐渐建立“物料批次（MaterialLot）”“工单（WorkOrder）”“质量事件（QualityEvent）”等类型和它们之间的关联。理想工作流是：创建/选择模型包 → 增加对象类型 → 定义属性与主键 → 保存编辑进度 → 校验语义 → 逐步扩展其他对象与关系 → 将来审核和发布。实际数据库中的生产批次或 MES 表行**不是** ObjectType 定义本身。

首个可验收 MVP 要求：本机打开真实的对象类型列表，创建 \`manufacturing.production\` 包下的 \`ProductionBatch\`，添加 \`batchId\` 等属性，保存草稿，返回列表、刷新、重开、修改后再次保存；未填完时也不能丢失合法的编辑进度；明确指出尚未达到完整模型校验要求；重复 ID、非法类型及并发覆盖不得被当成成功。

**本轮为领域评审**，不输出页面原型、不设计 CSS、不提交应用代码。目标是先确定下述概念的归属、生命周期与不变量，避免开发后再拆“一包一类型”的数据库/API。

### 2. 两个发现的核心设计冲突

**冲突 A：Package 不是 ObjectType 的一对一包装。** Native V0.2 Package Schema 的 \`objectTypes\`、\`linkTypes\` 等字段原本就是数组，允许多项；原 OFIP-0002 临时“每个 Package 草稿只能有一个 ObjectType”的建议如固化到领域模型/数据库唯一键，会阻碍建立 LinkType 和同一领域包内的多类型关联。

**冲突 B：编辑草稿不等于有效 Native Package。** 现有 V0.2 Schema 要求 ObjectType 必须有 \`primaryKeyRef\` 和至少一个 Property；如果“保存草稿”必须先满足完整 Schema，则不能自然保存只填好名称、尚未选主键的用户工作进度。长期需要区分“可持久化的编辑内容”与“可通过完整结构/语义校验的模型候选”，而不能向 Schema 偷塞半成品并声称校验通过。

**发现 C：现有 Property ID 作用域仍需治理。** 现有 Python \`semantic_validate_package\` 的 \`CROSS_TYPE_PROPERTY_ID_COLLISION\` 要求同一个包中多个 ObjectType 的 Property ID 全局不重复；ObjectType/Property 的声明 JSON Schema 本身只约束 ID 字面格式，不表达跨 ObjectType 唯一性。长期概念的 Property 归属通常以所属 ObjectType 为界；这一点需要显式定夺并经合同修订/测试，而不是前端偷偷改规则。

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
| Ontology Package | 定义集合与将来交付/版本化的边界；\`packageId\` 如 \`manufacturing.production\` | 1 个 Package 持有 0..N 个 ObjectType、0..N 个 LinkType；最终 Package 可包含其他 V0.2 声明 | 组织、依赖边界、未来发布坐标 |
| ObjectType | 一种业务对象的**类型定义**，如 \`production-batch\` / \`ProductionBatch\` | 属于一个 Package；有 0..N 编辑期 Property（候选正式合同要求 >=1）；最多一个 primaryKeyRef | 类型的字段与标识规则 |
| Property | ObjectType 所拥有的属性声明；如 \`batch-id\` / \`batchId\` | 属于且只能属于一个 ObjectType；类型、必填、可搜索、分类与 ENUM 值由合同治理 | 声明属性约束，而非真实值 |
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

以上草稿 0..N Property 是为了允许持久化 WIP；**校验形成 CandidatePackage 时仍须满足 V0.2 当前 ObjectType \`properties.minItems=1\` 与 \`primaryKeyRef\` 必填要求**。这个图不表示一行或一张 SQL 表。

### 2. 身份和名称规则必须区分

| 标识 | 作用域 | 不得混淆的对象 |
| --- | --- | --- |
| \`packageId\` | 包的稳定逻辑坐标（如 manufacturing.production） | PostgreSQL 主键、Tenant、已发布版本 |
| \`ObjectType.id\` | 所属 Package 内稳定定义 ID（如 production-batch） | apiName、真实生产批次主键 |
| \`ObjectType.apiName\` | 同一 Package 公开可读/可编程名称（如 ProductionBatch） | objectType.id；重命名会影响消费者 |
| \`Property.id\` | **待决策**：推荐概念上归属 ObjectType，复合标识为 Package/ObjectType/Property | 现有 V0.2 Python 检查实际要求包内 Property ID 全局不重复 |
| \`Property.apiName\` | 所属 ObjectType 中的可编程属性名称（如 batchId） | Property.id、ObjectInstance 的字段值 |
| \`LinkType.id\` | 所属 Package 内的关系类型定义 ID | 特定实例之间的 LinkFact ID |
| \`draftId\` | 草稿工作副本数据库 ID（建议 UUID） | packageId、ObjectType.id |
| \`revision\` | 编辑中的乐观并发计数（成功更新后递增） | schemaVersion、候选版本、ReleaseDigest |
| \`schemaVersion\` | Native JSON 契约版本，目前 V0.2 候选为 \`0.2.0\` | 用户编辑 revision |
| \`Package.version\` | 候选或正式语义版本（现行 V0.2 字段，例如 \`0.1.0\`） | draft revision、Schema 结构版本 |
| \`ReleaseDigest\` | 将来真实规范化后不可变发布身份 | 示例 JSON 的伪 digest 或仅通过结构校验 |

**建议**：即使 S0 只创建首个 ObjectType，服务端未来的聚合语义也应是 **PackageDraft 编辑一个 Package 的类型集合**，不能把 \`package_id UNIQUE\` 解释为“永远只有一份 Package 版本”；如需要“同一 packageId 只有一个当前可写草稿”，这是 **草稿生命周期临时唯一性策略**，不能禁止未来历史发布版本并存。

### 3. ObjectType 与 Property 的专业约束

- **稳定标识与展示信息分离**：\`id\` 是机器 ID；\`apiName\` 是消费接口名称；\`label\` 是展示名；\`description\` 为语义定义。不能把中文 label 翻译成可靠的跨系统身份，也不能默认随意变更稳定 ID。
- **主键引用为 Property ID**：\`primaryKeyRef\` 必须指向当前 ObjectType 自己拥有的属性；现有 V0.2 语义测试要求对应属性必填且 \`valueType\` 为 STRING 或 INTEGER。主键是未来实例身份的规则，**不是**当前存在的真实实例 ID。
- **Property 值类型**：沿用当前 V0.2 STRING / INTEGER / DECIMAL / BOOLEAN / DATETIME / ENUM；ENUM 必须有非空、去重的 enumValues，非 ENUM 不允许携带 enumValues。字段 \`required\`、\`searchable\`、\`classification\` 的数据质量/授权含义须分别解释，不因 required=true 就推断 MES 实际已有值。
- **分类不等于实际访问许可**：PUBLIC/INTERNAL/CONFIDENTIAL/RESTRICTED 是语义分类，不代表已经实现字段级授权；S0 不存在认证主体，禁止“仅在 UI 隐藏”冒充权限控制。
- **唯一性作用域待裁决**：长期推荐 \`(packageId, objectType.id, property.id)\` 作为 Property 逻辑身份；但 V0.2 的 \`CROSS_TYPE_PROPERTY_ID_COLLISION\` 负例与之不完全兼容。**在修改既有结构/校验合同时，先决定保留包级限制还是修订候选 Native 规范和相关正反例；本轮不改代码。**
- 正式发布后对主键、数据分类、可搜索和字段类型的修改，应走兼容性评估；**编辑尚未发布的草稿**可以按 revision 更新，不假装已经产生破坏下游消费者的发布变更。

### 4. LinkType 与真实关系事实

- LinkType 的 \`sourceTypeRef\`、\`targetTypeRef\` 引用 ObjectType **定义**，不是 ObjectInstance ID；\`cardinality\` 是关联约束声明，不等于 SQL 物理外键。
- **同一 Package 内引用**可以在完整候选模型校验时解析。跨包引用必须考虑 Imports、明确版本、锁摘要及歧义处理；现有 V0.2 的简短类型 ID 不能被随意当作跨包全局唯一键，需后续合同评审明确限定/扩展。
- \`evidenceRequired\` 描述未来产生真实 LinkFact 时是否需要来源证据；**只保存 LinkType 不得生成任何虚假关联事实**。
- 首版用户故事不开放 LinkType 编辑器，但领域模型、候选 Package 结构和下阶段页面流程必须留出“为已有类型添加关系”的路径；不要求现在创建无用 Link API。

### Contract-Gate-2：技术合同候选（Domain-Gate-1 通过后新增；尚未签收）

**设计口径**：这里给出 S0-MVP-0.2 的服务端/数据库/结构化编辑合同，供技术、产品、安全审议；本 PR **不新增 JSON Schema、Flyway、Controller 或 TypeScript**。应用前仍需低保真、高保真及其实际验收。本节决策编号为 TC1–TC10，**不是已经确认的设计结论**。

#### TC1：分离 DraftEditingContent 与可校验 Native Package

- **DraftEditingContent 是编辑器专用的持久化文档**，版本字段为 \`editorSchemaVersion: "0.1.0"\`。它是对包内声明的**有边界、可恢复的结构化输入**，不是 Native Schema V0.2；可以保留部分输入、空属性集合、未选主键、无效格式字符串等，同时要求所有字段长度、元素数、内容类型和嵌套深度受限。
- **Native Package V0.2 是完整候选输出**，\`schemaVersion: "0.2.0"\`，\`packageId\`、\`version\` 由服务端构造，其 \`objectTypes\` 等必需数组齐全；只有 Draft → Candidate 转换成功才运行既有 JSON Schema Draft 2020-12 及语义检查，不能将未完成的 WIP 放进完整 Package Schema。
- Draft Envelope 与编辑文档分离，\`packageId\` 为必填合法坐标，其他如 \`draftId\`、\`revision\`、\`createdAt\`、\`updatedAt\`、\`validation\` 由服务端维护，不能由客户端伪造；\`packageId\` 草稿建立后首期不可变。
- 编辑文档顶层首版为 \`editorSchemaVersion\`、\`objectTypes\`、\`linkTypes\`；后者在首版新建页面为空，未来若读到不支持的 LinkType/多类型资料，应**保留完整内容或只读阻止修改**，不能编辑一个对象便抹去其他声明。
- **WIP 允许缺少完成条件，但不允许任意 JSON**：服务端拒绝多余未知根键、嵌入 Binding/Action/Token 等未来字段、非预期值类型、超限字符串/深度/数组、恶意对象结构。用户输入的非法业务 ID/ENUM 值可以保存为有限长度字符串并显示校验问题，但不能因“允许草稿”无限制放宽输入边界。
- 仅当候选转换完整时，\`primaryKeyRef\` 必须引用自身存在的 Property（其 required=true、valueType=STRING/INTEGER）；结构与语义不通过，生成明确报告但不产生有效候选；绝不发布或激活。

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

这里 \`primaryKeyRef: null\` 只在**编辑 DTO** 中合法，转换 Native 完整候选前须由用户选定真实属性 ID。首版 \`linkTypes\` 作为受控空集合保留，不提供编辑 API，不接受伪造可执行 Action/Binding。WIP 缺字段和业务规则错误不应通过截断/修正用户输入来隐式“修好”。

#### TC2：引用与标识唯一性的实际合同修订范围

| 层次 | 推荐唯一性/引用语义 | 代码现实及处理 |
| --- | --- | --- |
| Package | 包坐标 \`packageId\` 稳定且一个本地安装只存在一份当前可写草稿；可发布版本未来另存 | 不等于“Package 永远只允许一个 ObjectType/一个历史版本” |
| ObjectType | \`id\`、\`apiName\` 在**同一 Package** 中分别唯一（大小写敏感的确定性比较） | 首版 UI 最多引导创建第一个，但领域及 WIP 数组可以承载多个 |
| Property | \`id\`、\`apiName\` 在**同一 ObjectType** 中分别唯一 | DG3 已选 Type-scoped；当前 \`semantic_validate_package\` 仍有 \`CROSS_TYPE_PROPERTY_ID_COLLISION\` 包级拒绝，必须在正式合同修订时清除/替换并新增反例 |
| 主键 | \`ObjectType.primaryKeyRef\` 按本 ObjectType 的 \`Property.id\` 解析 | 不允许用同名 Property 的另一 ObjectType 满足 PK |
| Binding 字段映射 | \`Binding.objectTypeRef + FieldMapping.propertyRef\` 组合解析 | 现行 V0.2 的 \`objectTypeRef\` 提供上下文；没有此上下文不得查询全局 Property ID |
| Action 变更字段 | \`ActionEffect.targetTypeRef + propertyRefs[]\` 组合解析 | 现行 V0.2 的 \`targetTypeRef\` 提供上下文；不改变其授权定义和运行时 |
| LinkType 端点 | 仅引用明确的 ObjectType 定义；首版限定包内，跨包需 Import/版本锁 | 没有 resolved imports 时不可猜同名端点；LinkType 不能生成 LinkFact |

**版本策略待裁决**：Native V0.2 尚是**未正式签收的候选合同**。建议本次先通过 OFIP 明确语义勘误：可在后续受审合同 PR 中同步修订 V0.2 语义说明、Python 正反例与 Java 验证，但不改已对外发布的生产合同；若发现其他没有 ObjectType 上下文的消费方或已存在正式发布使用者，则必须升级 schema/profile 版本并提供迁移/拒绝矩阵，**不能无声明地继续沿用 0.2.0 冒充完全兼容**。无论选哪条路，本提案 PR **不直接修改原合同代码**。

#### TC3：Draft 及 Validation 两个独立状态轴

| 轴 | 候选状态 | 实际含义 |
| --- | --- | --- |
| 编辑资源生命周期 | \`DRAFT\` | 本轮只可保存编辑工作副本，不存在 REVIEWED / PUBLISHED / ACTIVE |
| 当前 revision 的校验 | \`NOT_RUN\` / \`INCOMPLETE\` / \`INVALID\` / \`VALID\` | 是某个 revision 的结构与语义校验快照，不是草稿生命周期状态 |

- 只有完整、有效的 CandidatePackage 校验通过才是 \`VALID\`；缺少必需 Property/PK 等完成条件 → \`INCOMPLETE\`；输入了存在但违反格式/唯一性/类型/跨引用等约束的数据 → \`INVALID\`。同时有多类错误时优先 \`INVALID\`，但 issues 应完整列举两类。
- \`validation.validatedRevision\` 精确绑定受检 \`revision\`，\`VALID\` 不代表已持久化为不可变 Release；当用户 PUT 成功后 revision+1，在**同一数据库事务中**将当前校验重置为 \`NOT_RUN\`、\`validatedRevision=null\`，不得复用上一 revision 的绿色结果。
- \`POST /validate\` 的校验失败是**正常业务结果（HTTP 200，status=INCOMPLETE/INVALID + issues）**，不是 500 或请求语法错误；并发/资源不存在仍分别是 409/404。
- 字段级问题以稳定 \`code\` 和 JSON Pointer 风格 \`path\` 描述（例如 \`/objectTypes/0/primaryKeyRef\`），不返回 SQL、StackTrace 或生产数据。

#### TC4：HTTP API 资源与命令（仅候选）

**资源前缀**：\`/api/model/drafts\`。首版不提供强认证/工作空间身份，但所有请求仅用于 loopback 开发，需有安全的 DTO 边界、大小限制及明确超时恢复。

| HTTP | 路径 | 请求 | 成功与状态 |
| --- | --- | --- | --- |
| \`GET\` | \`/api/model/drafts\` | \`pageSize\`（1..50，默认 20）、\`cursor\` 可选；排序按 \`updatedAt DESC, draftId DESC\`，游标必须基于稳定排序键 | 200 \`{items:[summary],nextCursor:null|string}\`，空列表真实 \`[]\` |
| \`POST\` | \`/api/model/drafts\` | \`{ "packageId":"manufacturing.production" }\`（创建空 WIP） | 201 \`DraftDetail\`，\`Location\` 包含 draftId，\`revision=1\` |
| \`GET\` | \`/api/model/drafts/{draftId}\` | 无 | 200 \`DraftDetail\`，含编辑文档及当前有效 revision 校验摘要 |
| \`PUT\` | \`/api/model/drafts/{draftId}\` | \`{ "expectedRevision":1, "editingContent": { ... } }\` | 200 \`DraftDetail\`，数据库条件写入原子递增 revision，旧校验失效 |
| \`POST\` | \`/api/model/drafts/{draftId}/validate\` | \`{ "expectedRevision":2 }\` | 200 \`ValidationReport\`，\`VALID/INCOMPLETE/INVALID\` + issues，报告绑定 revision |

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

上面的 UUID/时间/空状态是**协议示例，不是系统已存记录**。保存/校验时间戳必须由服务器生成；客户端传来的 \`draftId\`、\`packageId\`、\`revision\`（除 expectedRevision）和 \`validation\` 字段不得被 PUT 直接覆盖。

**范围和未来多类型保护**：\`PUT\` 是 Package Draft 的**全编辑文档替换**而非把第一个 ObjectType 单独覆盖；所有更新都依赖包级 revision。若将来当前编辑器打开自己不支持的多个类型/非空 LinkType，首版 UI 必须进入只读/明确阻止破坏性保存，或者完整无损 round-trip，不可悄悄把数组截断为一个 ObjectType。首期不提供“编辑单独 LinkType”或“发布模型”端点。

#### TC5：错误码与前端恢复策略

| 错误场景 | HTTP | code / 正确行为 |
| --- | --- | --- |
| 非法 JSON、越界请求体、错误 UUID/请求基本格式 | 400 / 413 | \`MALFORMED_REQUEST\` / \`PAYLOAD_TOO_LARGE\`；服务端不修改任何状态 |
| 编辑 DTO 结构不安全（未知字段、错误基础类型、危险未来声明、越界数组） | 422 | \`EDITOR_DOCUMENT_INVALID\`，\`issues[{path,code,message}]\`，不能存任意 JSON |
| 相同 \`packageId\` 重复创建当前 Draft | 409 | \`PACKAGE_DRAFT_EXISTS\`，返回安全定位信息，引导打开已有草稿；不自动 upsert |
| \`expectedRevision\` 过期 | 409 | \`DRAFT_REVISION_CONFLICT\`，保留用户未提交内容；提示读取最新版本，不能强覆盖 |
| draftId 不存在 | 404 | \`DRAFT_NOT_FOUND\` |
| Native 候选尚不完整或含语义错误 | **200（Validate）** | \`INCOMPLETE/INVALID\` + 稳定 issues；与非法 HTTP 请求区分 |
| PostgreSQL/服务端不可用或请求超时 | 503/5xx/网络异常 | \`PERSISTENCE_UNAVAILABLE\`（能确定原因时），**不显示已保存**；必要时 GET 检查结果、再重试 |

- API 错误对象建议 \`{ "code":"...", "message":"...", "issues":[...] }\`；只给安全、业务可理解的信息。对具有确定影响的数据库唯一约束/乐观锁异常映射为 409，别把并发转换成泛用 500。
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

- \`package_id UNIQUE\` 意味着**当前一份可编辑草稿**，不是只允许一个 ObjectType，也不代表将来发布版本表不可多版本并存。
- 数据库保存的是受控的 \`DraftEditingContent\`，**不是直接存一个不符合 Native V0.2 Schema 却标记为完整的包**；到验证时构造 Native Package 候选。数据格式通过 \`editor_schema_version\` 前向演进；DraftId、schemaVersion、候选 Package Version、ReleaseDigest 不同。
- \`PUT\` 推荐原子 SQL 条件更新 \`WHERE draft_id=:id AND revision=:expected RETURNING ...\`，更新 \`revision+1\`、编辑文档、updated_at，并清除 \`validated_revision\`/validation_report；检查影响行数，区分 404 与 409。禁止先无锁 SELECT 再无条件 UPDATE 的竞态写法。
- \`POST /validate\` 校验当前 revision 对应的快照，写入报告时使用 \`WHERE revision=:expected\` 原子条件；若在校验计算期间编辑版本已改变，**拒绝 409**，不能把旧校验结论贴到新草稿。
- 不修改 Flyway V1、不删 \`platform_installation\`，升级 V1→V2 和重启后 Installation ID 保持稳定；所有测试用 CI 的隔离 PG17，不运行会清空用户 Windows 本地库的实验脚本。
- 运行时与上行输入上限建议首期：请求编辑文档 **≤256 KiB**、对象类型 **≤50**、单类型属性 **≤100**、Package 总属性 **≤1000**；这些是**技术合同候选**，不能靠前端隐藏、缺少服务端检查。未来根据产品需求和数据库性能有依据地调整。
- 绝不为了方便当前 MVP 就引入伪 \`workspace_id\`、\`created_by\`、假的权限/审计数据；现有服务仍需 loopback，正式可联机版本必须先经过独立身份/授权设计。

#### TC7：Draft → Candidate 的构建算法与版本语义

1. 服务端读取 \`draftId\`、packageId、revision 和受控 \`editingDocument\`。
2. 先检验编辑文档合同及资源上限，规范化**只是确定性映射**，不能偷偷补写主键、改名、重排用户显示顺序或删未知业务内容。
3. 若 WIP 缺完整发布定义所必需的 ID、主键、Property 属性，返回 \`INCOMPLETE\` 报告；若存在重复定义/错误类型/非法 ENUM 等，返回 \`INVALID\`；两类并存优先显示 \`INVALID\` 且保留全部 issues。
4. 仅当编辑输入满足完成条件时组装 \`schemaVersion=0.2.0\`、固定候选 \`packageId\`、\`version="0.1.0"\`（S0 单包候选默认值，**不是已发布版本**）及 V0.2 必需的空资产数组，\`objectTypes\` 映射当前完整定义。
5. 使用已审定的 V0.2 机器结构合同及经 DG3/TC2 决议正式调整的语义校验；如果该结构版本与规则存在冲突，不得把某个“只跑通的 Java 自写规则”冒充既有合同通过。
6. \`VALID\` 报告必须给出 \`validatedRevision\`，供 UI 解释为何编辑后旧结果失效；保留精确问题路径，异常不会反向把 WIP 当成已发布。
7. 候选包版本与未来发布采用分离路线：S0 不计算可信 ReleaseDigest、不生成审核或不可变 Release、不自动激活；未来多版本 Registry 必须另行经过 OFIP/安全验收。

#### TC8：实际交付 PR 与 CI 新门槛

- 将来真正的功能 PR **一次交付** Spring Boot POST/LIST/GET/PUT/VALIDATE、V2 前向迁移、React 创建/保存 WIP/重开/验证/失败恢复和一个真实可操作页面；不要把空 Core/Registry/Controller 先拆成多个半成品 PR。
- \`Required Quality Gate\` 现有 Backend 检查仅确认 S0 两项 \`FoundationTest\` 数据库行为；实现 PR 必须增加**至少一套 NativeDraftIT 或等价 PostgreSQL + HTTP 端到端测试**，真实验证 WIP、校验、409 并发、重启及 Flyway V2。扩展 CI 报告与最低覆盖门禁，而不是让旧基础测试代替本体新能力。
- 浏览器 Playwright 或等价自动化需覆盖生产构建接后端的黄金故事；如果未运行，要在 PR 标记 \`NOT RUN\`，不能用 \`npm run build\` 替代产品签收。
- 跨类型 Property 同名测试必须证明合同层不歧义：A.batch-id 与 B.batch-id 不冲突，但 A 自身两个 batch-id 拒绝；ActionEffect.targetTypeRef/Binding.objectTypeRef 必须明确引用作用域（首期不执行 Action）。
- S0 的生产安全/多租户/工业真实系统测试仍 \`NOT RUN\`，不得据此宣称可开放部署。

#### TC9：本轮主要冲突处置（与 DG1–DG9 一致）

| 历史/当前候选冲突 | 技术合同推荐结论 | 必须明确的兼容措施 |
| --- | --- | --- |
| 原始 OFIP-0002 一包一类型 | Package 领域、Draft 编辑聚合支持 0..N ObjectType | 本轮 UI 可只创建首个；存储/API 不能建永久一对一限制 |
| 原始 OFIP-0002 只有完整草稿才能保存 | WIP 编辑结构化保存；Validate 后产出完整 Native 候选 | 双合同分离，\`editorSchemaVersion\` 与 \`schemaVersion\` 独立 |
| V0.2 Python \`CROSS_TYPE_PROPERTY_ID_COLLISION\` | 采用 DG3 已决定的 Type-scoped Property ID | 合同说明 + 双端真实回归 + 上下文引用消歧；是否升版本见 TC2 |
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

**保存进度（Save Progress）**：本机持久化编辑器可表达的合法、受边界控制的草稿，即使尚未选主键或尚未完成属性配置，也能重新打开。要求 \`draftId/revision\`、确定性错误信息与数据写入成功证据；尚未完全满足 V0.2 Package Schema 的 WIP 应保存为有明确类型/版本的 **DraftEditingContent**，**不得被标记为完整 Native Package 或发布 Ready**。

**校验模型（Validate Model）**：在编辑内容达到完整结构时将其组合为 \`CandidatePackage\`，执行 JSON Schema Draft 2020-12 与全部适用语义校验；明确 \`VALID\` / \`INCOMPLETE\` / \`INVALID\`、字段级问题和受测 revision。校验是**当前快照的结论**，草稿 revision 改变后不得沿用旧的 Valid/Approved 状态。

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

> 创建 \`manufacturing.production\` 下的 \`ProductionBatch\` → 输入类型标识和属性 → 暂存尚未完整配置的 WIP → 返回列表、刷新、重开 → 完成 batch-id（STRING / required）、primaryKeyRef 和其余属性 → 运行完整校验，看到明确 VALID → 保存后重新打开、再次编辑并使旧校验结论失效 → 再次校验 → 比较 revision 和 PG 持久化事实。

**不把先创建一个 ObjectType 误认为以后一个包只能有一个**。产品是否在首版允许“一包继续添加第二个类型”要在第二轮交互设计基于真实操作复杂度决定，不应作为底层永久独占约束。

### 4. 评审 Gate 与交付步骤

| Gate | 要回答的问题 | 本次输出与验收 |
| --- | --- | --- |
| **Domain-Gate-1（当前）** | Package/Type/Property/Link 的拥有者、基数、身份、保存与校验、候选与发布如何分离？ | 本 OFIP；维护者逐项决定 DG1–DG9，并标注保留意见 |
| Contract-Gate-2（下一轮） | Native V0.2 约束冲突怎么处理？DraftEditingContent/ValidationResult/错误码/API/事务/迁移如何精确定义？ | 在同一 OFIP 更新公共合同与兼容迁移章节，形成可测试不变量；仍不写产品代码 |
| UX-Gate-Low（随后） | 用户从包进入类型、保存未完成草稿、校验、冲突恢复的顺序是否自然？ | 低保真流程与可交互原型；评审通过才进下一 Gate |
| UX-Gate-High（随后） | 页面是否符合 OntoForge 视觉体系、字段反馈、空态、错误、可访问性？ | 高保真原型 + 签收截图/状态矩阵；避免复刻未确认的 Demo |
| Implementation Gate | 一次 PR 是否真正交付完整的前后端用户链路？ | Flyway 前向迁移、API、真实 PG、React、字段校验与浏览器自动化/手工回归 |
| Acceptance Gate | Windows 11 本机用户能否完成黄金故事、重启与异常恢复？ | 用户实际操作与签收；CI 成功不替代本机验证 |

每个阶段能一轮通过就一轮通过；发现真实问题才返工，不以文档数量或流程时长为质量目标。**只有所有设计 Gate 签收后才开始功能 PR**；这不等于 P0-A 的所有未来功能都要一次性实现。

## Compatibility, Deprecation, and Migration Plan

1. **本提案不修改 V0.2 Schema、任何代码或 Flyway V1**；领域决议确认后下一轮才能提出机器合同变更。尤其 Property ID 作用域与 Draft WIP 结构须先确定，再修改 Python 正反例。
2. **已存在 V0.2 实验夹具继续为历史契约证据**。若推荐 Type-scoped Property ID 获准，须在 Contract-Gate-2 明确采用“修订仍未正式生产签收的 V0.2 候选合同”还是“版本化 Native V0.3”，避免同名版本的规则暗变；兼容与拒绝矩阵必须保留。
3. **Package 基数不做“一包一类型”数据库限制**。如持久化中为了第一版选用一行 JSONB 聚合，也应允许内部 0..N ObjectType/LinkType，并明确 WIP 编辑合同与 CandidatePackage 区别。不能先建错误模型，再寄望以后自动迁移。
4. **编辑版本 vs 发布版本**：draft revision 由成功写入递增；候选包语义版本和未来 ReleaseDigest 不能被 revision 顶替。未来多个已发布版本并存，不能被一个唯一 \`packageId\` 的草稿行所阻断。
5. **主键和业务身份兼容**：正式发布后 ObjectType/Property ID、PK、classifications、enum 值变化需要影响分析；本轮草稿未发布，可按 revision 编辑，不触发虚假的下游数据迁移。
6. **S0 安全界限**：此前没有工作空间、用户、授权机制；只允许 127.0.0.1 开发环境操作模拟定义，不连接真实工厂源、不公开 API、不把本地编辑当作企业安全能力。

## Test Plan

**本轮设计评审 PR 允许报告的真实检查**：现有文档链接、OFIP 六章及状态测试、Native V0.2 原有结构正反例（以实际 Actions 结果为准）。不能声明已经跑过数据库 V2、Java API、浏览器或工作空间安全验收。

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
| CI 与安全 | \`Required Quality Gate\` 按实际修改选中真实 PG/前端/合同，并有 JUnit/浏览器证据 | 只跑 S0 FoundationTest、仅 TS 构建、仅 JSON Schema 检查不等于 P0 用户体验通过 |

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

---

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
