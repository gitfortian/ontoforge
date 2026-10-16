# OFIP-0003：S0-MVP-0.3 Native 模型版本冻结、差异与发布安全边界

**Status:** Discussion  
**Discussion:** [PR #10 — OFIP-0003 首轮产品/领域评审](https://github.com/gitfortian/ontoforge/pull/10)  
**Decision:** Pending — 用户已确认按 DG1–DG9 推荐方向继续第二轮；**Contract-Gate-2：TC1–TC12 均为独立审核推荐 A / 待正式签收**，完整 OFIP 尚未获得实施授权  
**Implementation:** Not Started — 仅方案评审；不涉及 SQL / Java / React / Native Schema 的实现更改  
**Release:** Not Released  
**Owner / Reference:** OntoForge 产品评审；[OFIP-0002（Accepted）](./OFIP-0002-ObjectType-草稿建模MVP.md)、[现行架构 V1.3 候选](../本体产品总体功能与技术架构设计_v1.3.md)、[Native DSL/AST V0.2 候选](../design/核心本体元模型与Native-DSL-AST契约_v0.2.md)  
**Background:** S0-MVP-0.2 [PR #7](https://github.com/gitfortian/ontoforge/pull/7) 已合并（真实 Flyway V2 / Native 草稿 Java API / React / PG17+Chromium CI）；[Windows 验收工具 PR #9](https://github.com/gitfortian/ontoforge/pull/9) 已合并。**用户 Windows 11 本机 PostgreSQL 重启复验仍未提供，不得声称已通过或已发布。**

> 本提案特意区分 **Working Draft → Validated Revision → Immutable Local Snapshot** 与未来 **ChangeSet → Trusted Review → Approved Release → Activation**。本轮建议的 S0.3 是**本地研发语义模型快照与版本对照能力**，不是企业可审计的发布/部署系统，更不是可供生产 Agent 或工业 Action 直接消费的 SchemaRelease。

## Motivation

### 用户是谁、要解决什么

工业本体设计者在 S0.2 已能创建 `manufacturing.production` 模型包、配置 `ProductionBatch` 与 `batch-id` 主键、保存 WIP、完整校验，重启后从 PostgreSQL 重新读取。现在他需要：把一个**确定的有效修订**留为只读基线，后续继续修改草稿而不覆盖旧基线，能回答“这个版本有什么、下一版本改了什么、哪个修订产生了它”，以及当旧版本被引用时不被悄悄替换。

0.2 的代码事实：

- `native_package_draft` 有单个可写聚合、`revision`、`editing_document`、`validated_revision`、`validation_report`；**没有快照/Release/Review/Activation 表**。
- `NativeDraftService.validate` 绑定当前 revision 写入 VALID/INVALID/INCOMPLETE 报告；`DraftValidation` 从 WIP 生成符合 Native V0.2 JSON Schema 的候选，候选 `version="0.1.0"` 是默认字段，**不是已授予的版本坐标**。
- 0.2 的 Web 只编辑模型包首个 ObjectType；遇到多个类型或非空 LinkType 时只读，防止全量 PUT 丢失其他声明。
- 0.2 没有可信用户/工作空间/ACL，也没有审查主体；**任何客户端填写“审批人/发布人”的 JSON 均不能成为受信审批证据**。
- V1.3 第 4.2 节定义 Draft → Validate → Review → Approved → ImmutableRelease → Activation；其余控制面消费者/绑定/数据事实尚不存在。

### 本轮产品目标与明确的 NOT GOAL

**S0-MVP-0.3 推荐目标**：从一个真正通过校验的草稿 revision **原子冻结只读 Snapshot**，稳定计算且可复核真实 SHA-256 内容摘要；持久化、重启和按快照 ID 读取；支持同包两个快照之间的结构 Diff；返回最近快照列表并能查看来源 revision；继续编辑草稿不会改变任何已冻结快照。

**明确不做**：没有受信身份的“审核通过”、虚假审批签名、企业正式 Published/Active、可执行的 Binding/Action、跨包/跨工作空间依赖闭包、部署、索引生成、生产访问控制、自动触发 Agent 或从模型快照写回 MES。首期允许前端对快照做**本地人工查看/记录问题**，但不保存“管理员已经批准”的伪记录。

建议产品命名 **“模型快照 / Snapshot”**，不要在 S0.3 把按钮命名为“正式发布”或“审核通过”。未来正式 Release 是更严格的独立工序，可以消费满足新门禁的已冻结 Snapshot，不能将历史 Snapshot 原地改写为已批准 Release。

### 黄金故事

`manufacturing.production` 的当前草稿在 revision=3 已校验 VALID：

1. 设计者在模型包详情看到“已校验 revision 3 / 尚未冻结”，点击 **冻结本地快照**（须有明确后果和只读提示）。
2. 服务端在 PostgreSQL 事务中锁定该草稿，重新检查 expectedRevision=3 和实际 Native V0.2 候选、完整校验，生成只读的 Snapshot #1，返回快照 ID、摘要、来源 revision、时间。旧状态、旧报表不被伪装为有效。
3. 设计者修改业务说明和质量状态的可搜索/必填设置，保存为 revision=4、重新校验后生成 Snapshot #2。
4. 设计者从模型包“历史快照”进入差异页，看到 ObjectType/Property 的新增、移除、类型、主键、必填、可搜索、分类与枚举变更，并查看不可变的旧快照。
5. 重启 Java，继续读取 Snapshot #1，它与产生时**字节规范化后的内容摘要完全一致**。切换快照查看不改变当前草稿；没有对生产消费者的激活操作。

## Public Interfaces

> 本节为候选 API 与领域合同，需后续 Contract-Gate 才能签收。

### 领域身份与状态

| 概念 | 身份与职责 | 不得混同 |
| --- | --- | --- |
| Package coordinate | `packageId`，逻辑业务命名空间 | 用户 ID / PG 主键 / 发布版本 |
| Mutable Draft | `draftId + revision`、`editorSchemaVersion`、WIP JSONB | Native Release、完整候选与快照 |
| Validation result | `draftId + validatedRevision + status + issues` | ReviewDecision、发布许可 |
| Candidate | 服务端从完整 WIP 生成并以 Native V0.2 验证的候选内容 | 可信编译 AST / Signed Release |
| Immutable SchemaSnapshot | `snapshotId`、`packageId`、`sourceDraftId`、`sourceRevision`、`candidateSchemaVersion`、`canonicalizationProfile`、`payloadSha256`、`snapshotPayload`、`createdAt` | `ReleaseDigest` / `package.version` / Approved / Active |
| Future ReviewDecision | 可信服务端身份、被审内容摘要、依赖锁、意见、时间和决策；身份缺失必须拒绝 | 手填 `reviewer="admin"` |
| Future SchemaRelease | `packageId + explicit version`、已冻结锁/兼容报告、Approval、ReleaseDigest；永久不可变 | 本地 Snapshot ID 或草稿 revision |
| Future Activation | 指向特定 Release 的部署/激活指针与生效证据 | 创建 Snapshot 或通过 Schema 校验 |

**明确约束**：快照是**受限开发环境的 schema candidate snapshot**。其 `snapshotSha256` 只是稳定的**内容摘要**，不可将其直接命名为 `ReleaseDigest` 或“已签名”。`version="0.1.0"` 在当前 Native Candidate 中是校验所需的临时候选元数据；必须在显示/API 里注明未授予正式发布版本。

### 接口草案（下一轮 Contract-Gate 才冻结路径/DTO/状态码）

| 任务 | 推荐方式 | 原则 |
| --- | --- | --- |
| 冻结当前已检验草稿 | `POST /api/model/drafts/{draftId}/snapshots` with `expectedRevision` + `idempotencyKey` | 服务端重检、事务内 CAS、失败不产生半成品、409 明确原因 |
| 模型包快照列表 | `GET /api/model/packages/{packageId}/snapshots` | 只返回本地 SchemaSnapshot；分页稳定、只读 |
| 快照详情 | `GET /api/model/snapshots/{snapshotId}` | 只读、明确 unapproved/unreleased/unactivated；校验摘要 |
| 两快照比较 | `GET /api/model/snapshot-diffs?from={id}&to={id}` | 限相同 packageId，结构化 Diff，不盲用文本 Diff |
| API 负面合同 | 400/404/409/422/503，具体错误码另评审 | 不暴露内部 SQL；超时结果未知允许按幂等键查询 |
| 此阶段不开放 | `/approve`、`/publish`、`/activate`、`/rollback` | 任何伪发布或单机 Fake Approver 均不属于 S0.3 |

推荐错误码：`DRAFT_REVISION_CONFLICT`、`VALIDATION_NOT_CURRENT`、`CANDIDATE_INVALID`、`SNAPSHOT_ALREADY_EXISTS`（如请求幂等语义不能安全返回原结果时）、`PACKAGE_MISMATCH`、`SNAPSHOT_NOT_FOUND`、`UNSUPPORTED_DEFINITION`、`PERSISTENCE_UNAVAILABLE`。**返回 HTTP 200 的 VALID 不等于发布。**

## Proposed Changes

### 3.1 S0.3 子集与安全前提

- 保留 0.2 自身“一包一个当前可写草稿”的模型；新增**同一 packageId 多个只读历史快照**。原 `package_id UNIQUE` 只约束 Draft 表，不约束 Snapshot 或未来 Release 多版本表。
- 本期只支持**恰好 1 个完整 ObjectType 及其 Property**，这是对 S0.2 实际前端能力的明确约束，**在 Java 服务端再次验证**；0 个或 2 个及以上均不能偷偷截断后冻结。LinkType/import/bindings/functions/policies/actions 等数组必须为空。若服务端检测未知资产，**拒绝冻结**，而不是静默丢弃。
- 应先跑 S0.2 Windows 实机验收再宣布任何 0.3 本机可用；并不阻止现在规划 0.3 的领域/合同。
- UI 必须显示**本地开发 / 未审核 / 未发布 / 不供 Agent 使用**，点击快照只读，不出现可操作的发布或激活假按钮。

### 3.2 原子冻结：由真实服务端负责

1. 客户端只传 draftId、expectedRevision、幂等键，不能传任意“已审 Native Package”或审批人、发布时间。
2. PostgreSQL 事务读取并锁定 Draft；`draft.revision==expectedRevision`，最新已保存 revision 的 ValidationResult 必须 VALID；**事务内重新生成候选并重复权威 Native Schema+语义校验**，避免用历史 Valid 的状态代替当前定义。
3. 只对**规范化后完整候选内容**执行版本化 canonical JSON 规则（推荐评审 RFC 8785/JCS Profile，非简单 Jackson `toString()`、`Map` 迭代顺序或伪固定 hash），计算真正 SHA-256；同时保存 Profile ID、生成器/合同版本。
4. 一个事务插入只读 Snapshot 内容、摘要及 source revision/创建时间。以 `(draftId,sourceRevision)` 唯一索引配合幂等键；相同快照重复提交必须安全返回既有结果，不产生两份逻辑历史或覆盖已有快照。
5. 回执丢失时，调用方按 source revision/幂等键查询结果；请求未知绝不提示“冻结失败可盲目重试”。
6. 已冻结内容**不依赖后续草稿再读取**；新草稿保存、旧验证失效、差异渲染均不能更新历史 Snapshot 的 payload/摘要。

**并发例外必须拒绝**：用户点击冻结时其他标签页更新了 Draft，即使此前显示 VALID，只有事务内 revision 一致才成功；若 VALID 被并发覆盖、候选变为无效、摘要 Profile 不可用、数据库失败，都不生成有“成功”状态的半成品。

### 3.3 真正不可变，而非“前端按钮 disabled”

建议新增 `native_schema_snapshot`（仅为设计，待 Contract-Gate 确认 Flyway V3）：

- `snapshot_id UUID PK`、`package_id`、`source_draft_id UUID FK`、`source_revision BIGINT`、`candidate_schema_version`、`canonicalization_profile`、`snapshot_document JSONB`、`payload_sha256 CHAR(64)`、`created_at TIMESTAMPTZ`；
- `UNIQUE(source_draft_id,source_revision)` 实现修订级幂等。无需修改 V2 草稿身份或主键；
- 无 UPDATE/DELETE 业务端点；建议数据库约束/触发器拒绝业务角色修改/删除快照，应用启动身份权限最小化，避免开发中误改历史，自动化测试要验证；
- 返回详情时摘要必须按**相同 Canonicalization Profile** 校验；日志记录 snapshotId/sourceRevision/digest 前缀，不记录敏感模型全文；
- 本地 PG 超级用户仍可改数据库：这只能提供“产品受控不可变”，**不是防内部管理员篡改的独立审计或签名系统**。可信签名、可验证审计链由未来安全 Gate 定义。

### 3.4 版本差异的语义和风险表达

Diff 键必须按现有 Type-scoped Property 身份：`(packageId,objectType.id,property.id)`，不是从数组下标比较。每条变更包含 `changeKind` / `propertyPath` / `oldValue` / `newValue` / `riskClassification` / `reason`。

| 变更 | 示例 | 0.3 显示建议 |
| --- | --- | --- |
| 新增对象类型或属性 | `ProductionBatch.qualityStatus` | ADDITIVE（仅结构层面，仍未判断下游） |
| 删除/重命名 Type/Property ID | `batch-id` 改 `lot-id` | POTENTIALLY_BREAKING |
| 改主键、值类型 | STRING→INTEGER / 更换 primaryKeyRef | POTENTIALLY_BREAKING |
| required false→true | `qualityStatus` 必填 | POTENTIALLY_BREAKING |
| searchable true→false | 无法再按此字段搜索 | POTENTIALLY_BREAKING |
| classification 变化 | INTERNAL→PUBLIC/RESTRICTED | POLICY_REVIEW_REQUIRED（绝非自动扩大授权） |
| ENUM 增删值 | 移除 COMPLETED / 新增 HELD | POTENTIALLY_BREAKING / REVIEW_REQUIRED |
| 显示名/描述更新 | 修改说明文字 | DOC_ONLY（不声称所有消费者兼容） |

**不要生成“兼容性已通过/可无痛升级”结论**：真实下游依赖、绑定、SourceAuthority、授权、索引、Function/Action 尚不存在，0.3 只能提示**结构风险**和“未来发布必须另行正式兼容评估”。没有消费者证据时不能推断向后兼容。

### 3.5 用户旅程和界面信息架构（本轮不做高保真）

建议复用现有 Native 建模工作台和品牌视觉，不另建“治理/发布中心”或独立专业工具菜单。模型包详情有：

- “当前草稿”摘要：revision、校验结果、最后修改时间。
- “冻结快照”：仅当没有本地未保存内容且服务端同 revision VALID 才出现可点击；明确“此操作只生成本地只读基线，非正式发布”。
- “历史快照”：按时间/顺序查看 snapshotId、摘要（可复制）、来源 revision 与未发布标签。
- “比较”：选择同包两份快照，按 Type/Property 对照差异及风险；默认聚焦有风险的改动。
- “只读详情”：不让用户编辑历史快照；可以「回到当前草稿」继续工作。

本轮不要求一次做七个页面：**模型包 + 草稿工作台 + 快照历史/差异抽屉**即可验证黄金故事。UI 设计合同和可点击原型须在领域/合同签收后单独设计；不得抄低保真演示页面充当生产 UI。

### 3.6 与未来可信审查、发布和激活衔接

建议后续独立 OFIP 完成最小可信主体识别、受控角色/空间、审查记录（谁对哪些完全一致的内容与依赖做了什么判断），且需考虑**审核与提交人是否允许同一主体**。任何 `ReviewDecision` 必须绑定 Digest+SourceSnapshot+依赖 Lock 与主体证据，修改内容、依赖、Policy/Action 或资格撤销应按规则使审查失效。

未来正式发布另建 `SchemaRelease(packageId,releaseVersion,releaseDigest,reviewDecision,dependencyLock,...)` 与 `Activation`：**不可原地把已有 Snapshot status 改为 PUBLISHED**；应通过新 Release 事务验证并生成新的可审计资产。发布失败不得出现 Active 指针。回滚只能切换到经允许的历史 Release 或新建修复 Release，不修改旧不可变证据。Schema Release 不自动激活 Binding、对象索引或 Action。


### 3.7 第二轮 Contract-Gate-2：权威版本化技术合同审查（TC1–TC12，建议 A / 待正式签收）

**本轮性质**：依据用户确认推荐方向继续技术评审，**不冒充维护者的 GitHub `[VOTE] Accepted`**。本节给出可以直接指导下一阶段 API/PG/Canonicalizer/前端研发的**候选合同**，不是已运行的实现；保留 `Status: Discussion / Implementation: Not Started`，任何草稿快照、V3 表、Java 编译器现在都**尚不存在**。

#### TC1 · 冻结资格与证据的单一权威

**A（推荐）**：服务端从 PostgreSQL 当前 Draft 派生，不接收客户端上传的完整 Native Package / 已审核状态。冻结事务需同时满足：

1. URL 的 `draftId` 存在；请求 `expectedRevision` 等于 DB 当前 `revision` 且大于零。
2. `validated_revision = revision`、`validation_report.status = "VALID"`、报告绑定的 `validatedRevision` 一致；以上只属于前置状态校验。
3. 在同一个数据库事务内，重新调用经过受审的 `DraftValidation.validate` 和权威 `NativePackageSchema.validate` 构建完整 Candidate；**重算成功才允许冻结**。重算与历史校验矛盾须拒绝，不得用旧 `VALID` 硬闯。
4. **S0.3 支持子集恰好一个 ObjectType**、该类型可有多个 Property，`linkTypes` 为空。服务端还必须断言原始 WIP 编辑结构没有任何未知资产/未支持数据，并断言最终 Candidate 的 `imports/linkTypes/bindings/linkBindings/objectSets/functions/rules/decisionStrategies/policies/actions` 都为空；不能依赖 Candidate 生成器“默认补空数组”就断定不存在被丢弃的定义。
5. 只有真实服务端读取的 `editing_document` 可以冻结；浏览器刚填写但尚未保存的数据禁止冻结，UI 明确要求先保存、再校验。

失败规则：revision 不一致 `409 DRAFT_REVISION_CONFLICT`；校验状态旧/未通过 `409 VALIDATION_NOT_CURRENT` 或 `422 CANDIDATE_INVALID`（区分已过期与完整性错误）；含不支持资产 `422 UNSUPPORTED_DEFINITION` 并提供稳定 JSON Pointer 路径。不能“修剪字段以让校验通过”。

#### TC2 · PostgreSQL Flyway V3 表与快照身份

**A（推荐）：纯新增迁移 V3，0.2 的 V1/V2 保持原样，绝不重建或复制旧 draft。**

建议表 `native_schema_snapshot`（精确数据类型/DDL 留最终签收）：

| 列 / 约束 | 建议 | 不变量 |
| --- | --- | --- |
| `snapshot_id UUID PRIMARY KEY` | 服务端生成 | 永不复用；不是 releaseId |
| `package_id TEXT NOT NULL` | 冻结时从 Draft 取值 | 对应同一个真实包，不由客户端自由填写 |
| `source_draft_id UUID NOT NULL REFERENCES native_package_draft(draft_id) ON DELETE RESTRICT` | 源编辑聚合 | 防止无来源历史快照 |
| `source_revision BIGINT NOT NULL CHECK (> 0)` | 实际冻结修订号 | 不是 releaseVersion |
| `idempotency_key UUID NOT NULL` | 请求幂等身份 | 同键不同语义拒绝 |
| `candidate_schema_version TEXT NOT NULL` | 例如 `0.2.0` | 不是 editorSchemaVersion |
| `canonicalization_profile TEXT NOT NULL` | 例如 `jcs-rfc8785-v1` | 不得靠将来程序默认值解释历史 |
| `canonical_payload BYTEA NOT NULL` | 冻结时 JCS 的确定性 UTF-8 原始字节 | **此列是哈希和还原的权威字节** |
| `snapshot_document JSONB NOT NULL` | 由相同字节解码的查询副本 | 仅供查询/展示；不是重新摘要的权威字节 |
| `payload_sha256 CHAR(64) NOT NULL` | canonical bytes 的小写 hex | 内容摘要 ≠ ReleaseDigest |
| `created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()` | 服务端时间 | 不作为内容摘要输入 |

**唯一约束**：`UNIQUE(source_draft_id,source_revision)`、`UNIQUE(source_draft_id,idempotency_key)`。建议稳定分页索引 `(package_id,created_at DESC,snapshot_id DESC)`。**不要 `UNIQUE(package_id,payload_sha256)`**：在不同 sourceRevision 再次冻结内容相同的草稿，其历史身份仍不同，摘要相同是正常情况。

为真正防业务错误覆盖，**禁止任何业务 UPDATE/DELETE 快照**：服务不暴露更新/删除 API；V3 建立拒绝 UPDATE/DELETE 的触发器或受限数据库权限（建议两者互补）；回归以应用连接执行直接 SQL 写入验证失败。注意本机 PostgreSQL 的数据库超级用户最终可禁用触发器/修改记录，不能宣称具备合规防篡改审计或密码学签名能力。

#### TC3 · 规范化与 SHA-256：不允许隐式 Jackson 近似

**A（推荐）**：Profile 固定 `jcs-rfc8785-v1`，严格遵守 [RFC 8785](https://www.rfc-editor.org/rfc/rfc8785.html) 的 JCS；计算 `lowercaseHex(SHA-256(canonical UTF-8 bytes))`。摘要输入是**完整已校验 Native Candidate**（包括其约束要求的 `schemaVersion/packageId/version` 与各空数组），不包含 draftId、创建时刻、审核状态、sourceRevision 或 idempotencyKey；外围身份/出处字段独立存储。

1. **对象键按 UTF-16 code unit 无区域排序**，嵌套对象也排序；数组元素顺序不重排。不能按 UTF-8 codepoint、Java `HashMap` 遍历或本地化排序规则替代。
2. JSON 字符串保留原始 Unicode 语义，符合 JCS 的转义规则；不做暗中的 NFC/NFD Unicode 正规化。未配对 surrogate 必须拒绝；不得“替换成问号继续生成摘要”。
3. RFC 8785 数字使用 ECMAScript/IEEE-754 double 的明确序列化规则。当前受限快照子集的 Native 候选主要是字符串、布尔值与数组，**不得因此宣称普通任意 JSON 数字可直接哈希**；未来扩充更多数值字段前必须有独立 golden fixtures，超出可无损表达的整数/精确十进制应改用受审字符串 Profile 或拒绝。
4. 要求候选生成器产生受控对象、拒绝重复 JSON key/非法 Unicode；但**已有 WIP 是 JSONB，原始输入可能已被解析归一**，不能声称能从旧 JSONB 反推原输入是否包含重复 key。冻结只保证对**服务端已验证的 Candidate** 计算可重现摘要，不伪称复原原始 HTTP 原文。
5. 保存 `canonical_payload BYTEA` + `canonicalization_profile`；读详情时对**存储的相同字节**重新 SHA-256 并核对元数据，然后解析并检查其对应 JSONB 镜像。任何不匹配返回有明确代码的服务端完整性故障，不可以静默返回“已验证”。
6. 升级算法必须新增 Profile 标识和解释器；**绝不按新 Profile 重算覆盖已有快照哈希**。内容相同的不同 revision 可以有相同摘要但不同 snapshotId。

**阻断测试**：引用 RFC 8785 官方 fixtures 及 Java/Python/JavaScript 跨语言字节一致性，覆盖嵌套属性顺序、UTF-16 排序、emoji、转义、负零/大指数/小数、重复键拒绝、非法 surrogate、Profile 未知与 SHA 不匹配。若本轮依赖库未通过这些 fixtures，则**不可声称实现了 JCS**；应另议限定 Profile，而不是为了赶版本篡改声称的算法。

#### TC4 · 冻结事务、并发、幂等与 UNKNOWN 恢复

**A（推荐）**：Spring `@Transactional`、PostgreSQL **`SELECT ... FOR UPDATE`** 行级锁以当前 Draft 作为并发序列化权威：

1. **先查幂等索引** `(draftId,idempotencyKey)`：如已存在且保存的 `sourceRevision` 等于请求 revision，返回同一 Snapshot（HTTP 200），即使 Draft 此刻又被修改，也能安全查询先前成功回执；同键搭配不同 revision 返回 409 `IDEMPOTENCY_KEY_REUSED`。
2. 不存在该键：锁 Draft；校验 expectedRevision、当前 VALID 报告、受支持的完整内容；重建 Candidate → JCS → SHA-256 → 在**同一事务**插入独立快照。一旦任一步失败，事务整体回滚，未产生快照。
3. 同 revision 不同 key 的并发请求，只有第一个产生快照；另一个必须返回 `409 SNAPSHOT_REVISION_ALREADY_FROZEN` 并可按 sourceRevision 找回历史；不允许多建。
4. 数据库唯一性约束是最终裁判；事务冲突（包括两个请求同时查不到键）必须在事务边界按唯一键正确分类，不能将 `DataIntegrityViolationException` 当作 500 或捕获后不回滚。重试读现有记录不得使用已失败/已回滚的事务继续查询。
5. HTTP 超时、连接中断意味着**结果未知**，客户端必须保留 request key：先调用只读按键查询，再决定是否重试**相同 key + 同 revision**；不能生成新 key 无限重试。
6. 测试必须覆盖真正并行 Worker、锁等待/超时、事务回滚后的零半成品、服务端 commit 成功但 HTTP 响应丢失，不能用单线程假装竞态消失。

这一设计不允许通过前端先调用 `validate` 后立刻另一个未加锁的 `INSERT` 充当事务保证。

#### TC5 · REST 资源合同与响应矩阵

接口路径仍属候选，但推荐 0.3 固定如下最小集合：

| 方法 / 资源 | 请求或响应要点 | 成功与边界 |
| --- | --- | --- |
| `POST /api/model/drafts/{draftId}/snapshots` | body 只有 `expectedRevision: positive long`、`idempotencyKey: UUID`；不允许未知字段 | **201 新建**，`Location` 指向只读详情；**200 相同键重放**；409/422 失败 |
| `GET /api/model/drafts/{draftId}/snapshots/by-key/{idempotencyKey}` | 只读查询请求键；验证 UUID | 200 同一 Snapshot，404 不存在；支持结果未知恢复 |
| `GET /api/model/drafts/{draftId}/snapshots/by-revision/{revision}` | 已冻结修订的归属查询 | 200/404；用于不同 key 争抢时找回已有历史 |
| `GET /api/model/packages/{packageId}/snapshots?pageSize=&cursor=` | 默认 20，上限 50；createdAt DESC, snapshotId DESC 的稳定 keyset cursor | `items[]` 和 `nextCursor`，无历史为空数组 |
| `GET /api/model/snapshots/{snapshotId}` | immutable payload、sourceRevision、profile、digest、createdAt，明确未审核/未发布 | 200 / 404 / 完整性异常，不可修改 |
| `GET /api/model/snapshot-diffs?from={uuid}&to={uuid}` | 同包，两个快照都经过完整性核验 | 200（可为空的变更数组）；包不符 422 |
| 不存在的 `/approve`、`/publish`、`/activate` | S0.3 禁止提供（包括隐藏入口） | 404/未注册，不能用“成功”Mock |

**统一失败响应** `{code, message, issues?}`（issues 带 JSON Pointer），不泄露 SQL、密钥或整个模型正文。建议明确：
- `400 MALFORMED_REQUEST`：请求 JSON/UUID/游标格式错误；
- `404 DRAFT_NOT_FOUND/SNAPSHOT_NOT_FOUND`：资源或幂等查询不存在；
- `409 DRAFT_REVISION_CONFLICT/VALIDATION_NOT_CURRENT/IDEMPOTENCY_KEY_REUSED/SNAPSHOT_REVISION_ALREADY_FROZEN`：前提或并发冲突；
- `422 CANDIDATE_INVALID/UNSUPPORTED_DEFINITION/PACKAGE_MISMATCH`：模型/比较的领域约束不符；
- `500 SNAPSHOT_INTEGRITY_FAILURE`：存储摘要与权威字节不一致、算法 Profile 无法识别等内部数据完整性错误，须明确告警并拒绝出具有效快照；
- `503 PERSISTENCE_UNAVAILABLE`：数据库不可用；
- 网络失败：浏览器显示 `RESULT_UNKNOWN` **仅为客户端本地分类**，不虚构这是后端返回的 HTTP 状态。

详情响应只读，明确 `approvalStatus: NOT_AVAILABLE`、`publicationStatus: NOT_PUBLISHED`、`activationStatus: NOT_ACTIVE` 属于**固定安全事实**；更安全的替代方案是只显示一条统一 `lifecycle: LOCAL_SNAPSHOT_ONLY`，不提前设计伪正式审批状态机。**推荐后者**。

#### TC6 · 结构 Diff 合同：身份、排序、风险而不是字符比较

比较必须从经过完整性验证的**快照权威内容**构造，不读取最新 Draft。内容语义路径与 Identity 使用：

- `ObjectType` 键 `objectType.id`；`Property` 键 `(objectType.id,property.id)`。`apiName`、`label` 是属性，不是物理对象身份。
- ID 改名在没有显式 Rename Map 的情况下输出 **REMOVED + ADDED**，附潜在破坏风险；禁止猜测两个同名业务标签“就是同一实体”。
- 响应每条字段 `{entityKind, objectTypeId, propertyId?, path, changeKind, before?, after?, riskLevel, reasonCode}`；`changeKind` 是 `ADDED/REMOVED/MODIFIED`，`riskLevel` 建议 `DOC_ONLY/ADDITIVE/POTENTIALLY_BREAKING/POLICY_REVIEW_REQUIRED`。
- 按 `objectTypeId`、`propertyId`、path 形成**跨页稳定顺序**，不能按 JSON 数组索引对齐。S0.3 只需 bounded 同包 Diff（约束由 0.2 DTO 上限推导），无需图布局/自动兼容迁移。
- 新增**必填属性**不能自动标 `ADDITIVE`：它可能影响已有实例的数据完整性，建议 `POTENTIALLY_BREAKING`；新增可选字段仅标“结构性新增”。
- 主键、值类型、API 名变化、字段删除、required false→true、searchable true→false 标 `POTENTIALLY_BREAKING`。
- classification 双向变化均为 `POLICY_REVIEW_REQUIRED`，不允许说提高/降低等级就自动放权；ENUM 删除或值变化需风险标注，纯顺序变化需说明是否业务有序，默认为集合语义（顺序不影响枚举值集合比较）。
- 有旧内容但不再有消费者/Binding 运行信息，**一律不输出“兼容性检查通过/可直接升级”**。风险标签仅做审查提示，不是真实 Dependency Lock/CompatibilityReport。

`fromSnapshotId=toSnapshotId` 推荐返回 200 空变更并注明 `sameSnapshot=true`；跨包返回 422 `PACKAGE_MISMATCH`；任一 digest 不一致返回 500 且**不渲染 Diff**。

#### TC7 · 当前草稿、快照与未来 Release 的版本分离

- **当前编辑**：`draftId+revision`，任意保存使先前 ValidationReport 失效；
- **Native 候选**：`schemaVersion=0.2.0`，`version=0.1.0` 只为了满足既有候选格式，不是包的正式发行号；
- **S0.3 Snapshot**：`snapshotId + sourceRevision + canonicalizationProfile + payloadSha256`；可以同一内容出现在两个不同修订中；
- **未来 SchemaRelease**：明确的 ReleaseVersion+SignedReview/DependencyLock+ReleaseDigest，**绝不通过修改旧 Snapshot 的状态实现**；Release/Activation 尚无服务端实体或 API。
- 数据中的 `createdAt` 表示“本地快照被创建”，不能改写成“已发布于”。

如果将来 `NativeCandidate.version` 需要由用户指定或由 Registry 授予，必须形成新的 OFIP/Version Profile，确保旧 Profile 的快照仍可读取和校验。

#### TC8 · 数据访问与部署安全边界

1. S0.3 继续限定 `127.0.0.1` loopback 开发环境；默认 PostgreSQL 原生安装场景允许当前开发用户，但不把它冒充为安全隔离、多租户或企业授权。
2. 所有快照写入依赖服务端逻辑 + SQL 层拒绝更新/删除；**独立数据库应用角色**与最小权限是推荐补强，但涉及 Windows 旧配置迁移，必须在本轮合同明确是否要求、如何无损升级。即使超级用户可以绕过约束，也不能省去正常业务更新拒绝的测试。
3. 不能直接用请求体传 `approvedBy`、`releaseVersion`、`active=true` 等字段；Unknown Properties 必须拒绝。
4. 暂不建立“本机管理员”假身份，不开放模型快照供远程 Agent/生产系统消费；如果范围变化，先补可信身份与授权合同、审计与发布 Gate。

#### TC9 · 错误与恢复 UX 的技术约束

- 模型包“当前草稿”与“历史快照”为**不同资源**；历史快照只读。回到草稿后显示其最新 revision/validation，不从快照覆盖草稿。
- `freeze` 的按钮同时受本地 `dirty=false`、服务端 `VALID validatedRevision=currentRevision`、`busy=false` 限制；点击之后请求期间禁止再次点击，保留固定 request key。
- 响应失败时区分：明确 409（刷新核对当前草稿）、422（展示校验问题）、`RESULT_UNKNOWN`（显示“查询冻结结果”并保留 request key），而不是统一“请重试”。
- 列表稳定分页、空状态、loading、只读详情、版本比较差异都沿用现有 OntoForge 视觉体系；不新增独立的“专业工具”菜单，不把高保真 HTML 原型直接复制成业务页。
- 不能把 Snapshot 标“发布成功”；建议副标题固定说明“本机模型历史版本，不含审批/发布/激活”。

#### TC10 · 跨语言、数据库与真正不可变的验收证据

- **Java unit**：JCS golden fixture、哈希一致性、Profile 升级拒绝、非法 surrogate/数字拒绝、Object/Property 结构 Diff 与风险；
- **真实 PG17**：Flyway V1/V2/V3，历史安装 UUID 与旧 WIP 不变，事务锁/rollback/并行 Worker 幂等、应用更新删除触发器拒绝、同 revision 不同 key 冲突；
- **真实 HTTP**：201/200 幂等重放/409/422/404/500 完整性失败/503 数据库断连，不得只用 MockMvc/Mock Repository；
- **真实 Chromium + Vite + Spring Boot + PG17**：两轮冻结/只读历史/结构 Diff/刷新重开、提交响应丢失查询、旧 revision 409、浏览器 Back 未保存警告；
- **Windows 11 原生 PostgreSQL 实机**：脚本生成独立测试 package/draft/snapshots，真正停止并重启 Spring Boot 后按原 ID 读取摘要与内容；**脚本执行结果由用户本机记录，CI PASS 不等于 Windows PASS**；
- CI Required Quality Scope 必须覆盖 `Flyway V3`、版本治理业务 API 的新 JUnit、浏览器 E2E 与文档；未执行则不能写 PASS。

#### TC11 · 一次性 PR 交付边界

设计签收后正式功能 PR 必须至少同时提供：

1. Flyway V3 **仅新增不可变快照数据表和安全约束**；
2. Java transactional Snapshot API、真正 JCS canonicalizer/SHA-256、结构差异服务；
3. React 原生建模工作台集成快照入口、历史/只读详情与 Diff；
4. 真实 PG/HTTP/Chromium 正反例及与 Windows 验收脚本衔接；
5. 同 PR 的用户黄金故事与故障恢复记录。

**不得把仅有数据库表、单个 Digest 工具函数或空页面分别叫成“已完成 0.3”**。也不能为完成 0.3 把 S0.4 审核/身份/发布/Agent 隐含实现进来。

#### TC12 · 合同尚需验证的风险与决策保持开放

- RFC 8785/JCS Java 实现与 Jackson/PG JSONB 的互操作以**跨语言 byte fixture** 为准；未找到合格实现之前不得“选一个库直接上”。官方规范：[RFC 8785 §3](https://www.rfc-editor.org/rfc/rfc8785.html#section-3)。
- V2 当前数据库账号默认可使用 `postgres` 超级用户；因此 S0.3 的不可变只能宣称**普通产品操作/正常 SQL 触发器的约束**，不是安全审计不可篡改。若要宣称更强，必须单独设计受限 DB 角色、审计和备份。
- S0.3 限定单 ObjectType 是**用户交互安全子集**，不能推论本体领域模型从此一包仅支持一类型；未来多类型/Link 冻结必须版本化扩展 Contract Gate，保持历史可读。
- 查询摘要失败时如何保留现场、后台告警与是否要提供原始 canonical 字节下载必须在实施评审内明确，避免错误“自动修复”覆写历史。默认**拒绝出具已校验结果**，不导出未经许可的全文。

**第二轮初步审核：TC1–TC12 的 A 方案原则成立，可作为 Contract-Gate-2 签收候选；目前未运行任何 0.3 实现或测试。核心 P0 锁是 TC1、TC3、TC4、TC6、TC8；保留决策 Pending 直到维护者评审。**



### 3.8 第三轮 UX-Gate-Low：模型快照冻结、历史及差异（独立 Mock 功能评审通过；维护者正式签收待定）

> **[可点击低保真原型](../prototypes/ofip-0003-snapshot-lowfi.html)** · [真实 Chromium 回归源码](../prototypes/tests/test_ofip0003_snapshot_lowfi.py) · [独立浏览器测试工作流](../../.github/workflows/ofip-0003-lowfi-browser.yml)。
> 
> 原型是**完全离线、仅浏览器内存的任务演练**：不读取 Java/PostgreSQL，不创建真正的 Native Candidate，不做 RFC 8785/JCS，不计算 SHA-256，不包含认证、审核/发布/激活。界面内的 Snapshot ID、digest、revision 与 API 状态码均为**模拟交互，不是生产执行证据**；刷新页面所有模拟数据清空。特别注明模拟指纹“**非 SHA-256**”。绝不能拿原型截图宣称运行时功能已完成。

#### 3.8.1 用户故事与信息架构（L1–L6）

| 故事 | 当前问题 | 低保真建议与主要操作 | 返回 / 失败路径 |
| --- | --- | --- | --- |
| L1 模型包列表与空态 | 初次使用不知从哪开始 | 现有导航「模型包与快照」，空态主操作「创建示例模型包」；产品正式版仍是真实包列表 | 空列表说明 + 进入草稿工作台，不新增独立版本管理菜单 |
| L2 当前草稿 | VALID 与已发布容易混淆 | 草稿编辑为主，先「保存编辑进度」→「检查模型完整性」；revision / 未保存 / 受检状态有清晰反馈 | WIP / INCOMPLETE 不阻碍继续编辑；冻结必须禁用 |
| L3 冻结确定修订 | 用户不知道冻结的是哪个版本 | 只在服务器**当前** `validatedRevision = revision`、VALID、无未保存编辑时可以冻结；按钮明示「冻结为本地快照」 | 返回快照只读历史；409、422、UNKNOWN 都有核对路径，不自动跳成成功 |
| L4 历史快照 | 旧模型可能被覆盖、无法追溯 | 在模型包内独立「历史快照」列表，展示 snapshotId、来源 revision、算法 Profile/指纹和“未发布” | 查看只读详情，回到历史或当前草稿；不允许从快照直接覆盖当前草稿 |
| L5 双快照差异 | 看不出新旧对象定义的风险 | 默认比较同模型包最近两份，后续可选任意两份；按 Type/Property 结构路径显示前后值与风险 | 返回历史；没有历史 / 只有一份时禁用比较，不伪造兼容通过 |
| L6 恢复与校验 | 回执丢失 / 另一标签页修改导致不知下一步 | 409 显示本地/服务器修订及内容并要求确认；UNKNOWN 保存幂等键并首先核对；未保存离开确认 | 保留本地输入；任何放弃编辑都需显式确认 |

**页面结构保持为**：模型包/草稿工作台 + 快照历史 + 只读详情 + 差异视图。右侧校验/冻结与异常恢复面板附属于草稿工作台，不拆成四个孤立菜单。正式 React 应复用 0.2 已存在的白侧栏、面包屑、表单/卡片、主按钮与错误提示体系；当前低保真灰阶展示**不代表高保真最终视觉**。

#### 3.8.2 操作条件与状态反馈合同（UX1–UX13）

| 编号 | 场景 | 期望可观察行为 |
| --- | --- | --- |
| UX1 | 新建模型包→WIP 保存 | 模型不完整也能保存，revision 增长、VALID 不会提前出现 |
| UX2 | 校验不完整模型 | INCOMPLETE 问题可点击定位到字段，冻结不可用 |
| UX3 | 修改刚刚校验的字段 | 立即标记“本地编辑未保存”，旧校验不视为当前；保存后 NOT_RUN，重新校验前不冻结 |
| UX4 | 冻结本地 Snapshot | 只允许已保存且 VALID 的当前修订；成功产生不可编辑的只读历史，文案绝不写 Published |
| UX5 | 两份快照→比较 | 原始快照不变，显示 description 的 DOC_ONLY 和 searchable true→false 的 POTENTIALLY_BREAKING 等 |
| UX6 | 相同幂等键重放 | 返回已存在快照，列表数量不增加 |
| UX7 | 冻结已提交但网络回执丢失 | 状态 RESULT_UNKNOWN，不伪报成功；保留原 key → 按 key 取回原快照 |
| UX8 | 另一标签页修改 revision | 409，冻结零新历史；核对服务器后显示双方信息；用户确认才可丢弃本地内容 |
| UX9 | 包含当前编辑器不支持的资产 | 拒绝冻结，说明 UNSUPPORTED_DEFINITION，不做自动裁剪 |
| UX10 | 带未保存编辑返回列表 | 用户拒绝离开则保持字段值；同意后才回列表 |
| UX11 | 冻结后继续修改当前草稿 | 原快照内容保持只读、不随当前 draft 新 revision 改变 |
| UX12 | 390px 窄屏 | 可访问冻结、历史与差异主入口；无页面级横向溢出 |
| UX13 | 原型刷新 | 清空模拟数据并提醒仅内存，防止将 Mock 误认成 PostgreSQL 持久化 |

**按钮启用和冲突真实语义**：
- 仅前端看到 VALID 不足以实施冻结：正式产品按 TC1/TC4 由服务端重新校验并锁定修订；UX Gate 对“按钮应如何引导”评审，**不构成事务已实现**。
- 响应未知时 UI 不新造第二个 key；先按原 key 查询历史结果，查不到后才安全重试原 key，同一 revision 另一个 key 被拒绝。
- Snapshot 的“只读”是展示交互合同；正式业务不可变需要数据库级 UPDATE/DELETE 拒绝和 hash 回读核验。
- Diff 只能描述**结构潜在风险**，没有真实 Binding/消费者/Policy 证据时，不显示“兼容已验证”。

#### 3.8.3 低保真审核的覆盖边界与真实产品遗留要求

**本轮 UX 原型验证包括**：点击与表单输入、缺少主键的 INCOMPLETE、有效草稿冻结、历史详情只读、对照风险标签、错误码/幂等恢复路径、丢弃确认、移动端布局、模拟刷新语义。

**实现 PR 必须新增而原型无法证明**：GitHub CI 的真实 PG17 行锁和 Flyway V3、不重复的数据库快照、RFC 8785 官方跨语言 fixture、SHA-256 双重验证、服务端单 ObjectType fail-closed、两 Worker 竞态、浏览器导航/刷新后的真实 URL/可寻址 Snapshot、真实断网/HTTP 500/503、正式 Windows 11 PostgreSQL 重启后重开与摘要验证、生产准入的身份授权（本期严格后置）。

**独立 UX-Gate-Low 评审结果（2026-10-16）**：[真实 Chromium 13/13 PASS](https://github.com/gitfortian/ontoforge/actions/runs/38861950361)、[13 张可复查实际浏览器截图](https://github.com/gitfortian/ontoforge/actions/runs/38861950361/artifacts/12029154636)，并已保留 OFIP-0002 的既有原型回归。第一轮 12/13 的失败为“快照详情缺少明确中文「非 SHA-256」声明”，修复文本后重新验证 13/13，并没有放宽断言或跳过测试。测试只覆盖 Mock 交互，**0.3 Flyway V3/Java Snapshot/React/真实 PG/Windows 全部 NOT RUN**。

**审核意见**：用户任务线（WIP→VALID→冻结→历史→Diff→继续编辑）及 UX1–UX13 的异常恢复路径，建议低保真独立评审 **PASS（Mock 设计范围）**。低保真可以作为高保真/正式 React 交互设计的输入；本次没有可信摘要、后端冻结、事务或审核发布证据。**维护者对 Gate 和整体 OFIP 的正式签收仍待明确记录**；后续高保真设计与业务功能开发不能仅凭 Chromium 绿色自动授权。


### 3.9 第四轮 UX-Gate-High：OntoForge 风格的快照版本治理交互（独立评审）

**可点击产物**：[高保真原型](../prototypes/ofip-0003-snapshot-highfi.html) / [20 条高保真 Chromium 测试](../prototypes/tests/test_ofip0003_snapshot_highfi.py) / [高低保真联合 CI](../../.github/workflows/ofip-0003-lowfi-browser.yml)。

> 本原型仍是浏览器内存模拟，**不是正式 React / Spring Boot / PostgreSQL**。刷新会清空；模拟指纹标注“非 SHA-256”，不是 ReleaseDigest、可信审批或发布记录。0.3 Flyway V3、Java Snapshot/JCS/Diff、PG 事务与 Windows 实机 **NOT RUN**。

#### 3.9.1 统一信息架构

基于现有 OntoForge 主导航、模型包、白色卡片、蓝灰工具配色、表单和反馈层级改进，不为 Snapshot 新增孤立的发布中心、专业工具菜单或独立发布向导；**原型的 CSS 不能直接替换生产组件**。

| 界面 | 展示及主动作 | 返回 / 保护 |
| --- | --- | --- |
| 模型包 / 空态 | 当前模型包、进入工作台、历史入口 | 仍以当前 Draft 为主要任务对象 |
| 草稿工作台 | 主区编辑、侧区 revision/VALID/冻结/恢复 | dirty 禁止校验/冻结，离开询问 |
| 冻结前确认弹窗 | 显示 packageId、修订号、VALID、“本地只读非发布” | 取消/ESC 没有副作用；确认后仍由服务器重新检查 |
| 历史列表 | snapshotId、sourceRevision、只读及未发布标识 | 查看只读详情，允许自选 From/To |
| 只读 Snapshot | 内容、来源与摘要证据（真实系统需验证） | 无编辑/审批/发布/激活按钮，能回草稿 |
| 语义 Diff | 按 Type/Property 的前后值、风险标签 | 同包/不同 ID；不宣称已验证下游兼容 |

#### 3.9.2 H1–H12 交互验收合同

| H | 检验重点 | 通过条件 |
| --- | --- | --- |
| H1 | 空态创建/进入草稿 | 入口清楚，无伪造数据 |
| H2 | INCOMPLETE | 字段错误可跳转，冻结不可用 |
| H3 | VALID 后修改 | dirty 立即让旧校验失效，保存后 NOT_RUN |
| H4 | 冻结确认 | 说明修订、结果和只读含义；取消/ESC 零快照 |
| H5 | 冻结后只读 | 详情不可编辑，历史永远不等同发布 |
| H6 | 自选两份历史 | From/To 对应真实选择；相同 ID 禁止比较 |
| H7 | 风险差异 | description DOC_ONLY、searchable 降级潜在破坏性、classification 需策略复核 |
| H8 | 并发修订 409 | 无新快照；核对两边内容、确认后才放弃本地修改 |
| H9 | 网络结果 UNKNOWN | 保留原幂等键，先查结果，不因重试生成第二份 |
| H10 | Unsupported Definition | 明确 422 拒绝多类型/Link/Binding，不能静默裁剪 |
| H11 | 窄屏与键盘 | 390px 无横向溢出、弹窗及历史可达、ESC 可取消；小字号文本对比度 ≥4.5:1 |
| H12 | 证据真实性 | 显示 Mock、非 SHA-256、未审核/发布/激活，没有假 ReleaseDigest 或“审批通过”按钮 |

#### 3.9.3 高保真与正式 React 的实现差异

- 现有页面按真实 draftId、revision、validatedRevision 读写；冻结资格以**服务端复查**为准，按钮禁用只是辅助。
- 弹窗打开/关闭需要合理焦点处理；真正提交中锁定二次操作。HTTP 409、422、500、503 和回执 UNKNOWN 必须有分级恢复路径。
- 正式历史/差异 URL 可直达并经刷新重新读取 PostgreSQL；原型基于内存，**没有持久化证据**。
- Diff 按语义对象 ID 而非数组位置绑定，不猜测重命名；跨包、相同 ID 与摘要损坏一律拒绝并解释。
- 正式文件应使用项目现有 React 组件与设计 Token，**不能把高保真 HTML 直接拷贝进工作台**。

#### 3.9.4 独立评审和测试边界

[**真实 Chromium #38864360663**](https://github.com/gitfortian/ontoforge/actions/runs/38864360663)：**33/33 PASS**，其中低保真 13 条、高保真 20 条。[实际浏览器截图](https://github.com/gitfortian/ontoforge/actions/runs/38864360663/artifacts/12029783956)。高保真新增覆盖确认取消/ESC、三份快照中自选两份、同一快照比较拒绝、390px 弹窗、真实摘要/发布能力防误导；既有 13 条故事亦在高保真复测。 独立 UI 审核发现浅灰蓝色辅助文本对比度不足，已修复高保真样式并新增浏览器 computed-style 检验（≥4.5:1）；回归扩大至 33/33。

**受托独立 UX-Gate-High 结论：建议 PASS（仅设计模拟范围）**。工作任务连贯、视觉系统匹配、异常恢复可理解，未发现阻断该阶段设计的 P0/P1；但维护者正式 Gate / 整体 OFIP Accepted 仍未记录。**生产 API/PG/Java/Windows 全部 NOT RUN**。后续仍应先完成正式设计签收，才可用一个完整 PR 交付真实实现。

## Compatibility, Deprecation, and Migration Plan

1. Flyway V2 的 `native_package_draft` 和数据不改、不删除；建议 V3 纯新增 Snapshot 表、索引与数据库层不可变保护。若以后真正需要扩展当前草稿 editorSchemaVersion 或多类型/Link，另评审迁移。
2. Native V0.2 Package Schema 不因“快照”增加字段。Snapshot metadata 放**外围受管资源**，不是偷偷塞进 `objectTypes[]` 或 Native Package 根节点，否则 JSON Schema additionalProperties 会拒绝。
3. Snapshot 与未来正式 Release 均允许同包历史并存；单 Draft 的 packageId 唯一约束不能传播到历史表。
4. 对同一 payload 的 canonicalization/formatter 变化不得无声重算旧摘要。必须保留 Profile ID、原始受检 bytes/可稳定重建的 canonical 字节；历史查询按保存时 Profile 解释，未来算法升级另发 Profile。
5. 不支持的旧编译产物、跨包依赖、LinkType/Binding 原型应**显式拒绝**；不能让 S0.2 首对象 UI 把其他类型截断后再冻结。
6. Windows 11 原生 PostgreSQL 是默认本机环境，不能把用户必须安装 Docker 作为产品前置条件。CI PG17 仍独立且不等于用户本机验收。
7. 发布与审批尚无公共消费者，因此不存在从假发布状态“迁移成真”的捷径；0.3 只能交付 Snapshot，与正式 Approval/Release 的语义隔离是前向兼容基础。

## Test Plan

> 以下为计划验收范围；相关 0.3 API 和 E2E 尚未开发或运行。

| Gate / 情景 | 必须有的可复验证据 | 失败时不能误报 |
| --- | --- | --- |
| T1 成功冻结 | PG17 Draft=VALID revision N → Snapshot 创建/按 ID 读取 | JSON Schema 通过不等于 Approved |
| T2 冻结并发 | 另标签页在冻结前修改 revision；期望 409、无插入 | 不能冻结错误修订 |
| T3 未完成/无效 | INCOMPLETE/INVALID/NOT_RUN 均拒绝并报告问题 | 不能只看客户端 badge |
| T4 幂等与结果未知 | 同幂等键重试、服务端已落库但客户端超时 → 查询同一结果 | 不制造重复版本 |
| T5 内容不可变 | 冻结后修改当前草稿、重启 Java、反复读取与 SHA 校验一致 | 历史记录不能“跟着最新变” |
| T6 拒绝篡改 | UPDATE/DELETE 数据库业务角色/服务 API 被拒绝 | 不能仅依赖禁用按钮 |
| T7 Digest 可重现 | 多语言 canonicalization golden fixtures、字段顺序/数字/Unicode、Profile 改变/无效拒绝 | 不能用未定义 JSON 序列顺序冒充稳定哈希 |
| T8 Diff 领域作用域 | 多类型同名 Property.id / 字段 ID 改变 / required / classification / ENUM | 不能误报包级重名或把降级授权称安全 |
| T9 不支持资产 | 0 个或多个 ObjectType、LinkType、imports、Binding 等非空均明确拒绝 S0.3 冻结 | 不允许暗中丢字段 |
| T10 真实浏览器 | Vite → React → Spring Boot → PG17：冻结、查看、Diff、刷新、断网/409、未保存离开 | Mock 浏览器绿色结果不能替代 |
| T11 Windows 实机 | 用户 Windows 11 原生 PG：保存、冻结、后端重启、同一 Snapshot 内容/摘要重读 | Linux CI 绿灯不能替代 |
| T12 安全边界 | 无 Auth 只允许本机回环，未配置授权的审批/发布/激活入口不存在 | 不能把操作者名字从表单当审计主体 |

**实际开发必须只用一个完整、可运行的功能 PR**，包含 PG V3、Java Transaction/Canonicalization/Diff、正式 React UI 和真实 E2E。独立设计 PR 中**不得混入开发代码**。如果经过领域评审确认 0.3 应改成真正可信审批/发布，则必须先签收最低身份/授权合同，扩大范围与验收，不得绕过安全边界。

## Rejected Alternatives

1. **“0.3 一步交付审批 + 正式版本 + 激活”**：当前没有 IAM/工作空间/依赖锁；审核人可被客户端任意填入，缺少权限审核与可信凭据。拒绝伪正式发布。
2. **把 `VALID` 直接改成 `PUBLISHED`**：完整性校验不是业务审批或兼容评估；会误导 Agent/消费者。拒绝。
3. **只复制原草稿 JSONB、不固定摘要与算法**：无法重建历史快照、也难识别更改；拒绝“看似不可变”的演示级存储。
4. **把 UI 上的锁图标当成不可变保护**：服务端/DB 更新仍可覆盖内容；拒绝。
5. **把版本号写在原 Draft 记录上**：单可写聚合无法持有历史独立版本，覆盖旧数据；拒绝。
6. **现在设计和开发完整权限/组织/角色/OIDC/签名平台**：为 S0.3 的本地模型快照需求过度建设；后续需要最小可信主体与审批时独立 OFIP。
7. **同时开发 LinkType/Binding/Action/ObjectSet**：会遮蔽当前“模型快照正确性”关键风险，且未经过各自安全/版本评审；拒绝。

---

## 第一轮独立审核：DG1–DG9 待决策清单（推荐 A，尚未签收）

| 决策 | A：建议 | B：替代方向及代价 | 审核风险 |
| --- | --- | --- | --- |
| **DG1 0.3 产品边界** | **A：本地不可变 Snapshot + 历史读取 + Diff**，审查/正式 Release/Activation 后置 | B：0.3 含审批和正式发布，先补可信身份与锁/审核，多轮评审与更大 PR | **P0：无认证不可伪造 Review** |
| **DG2 审核语义** | A：本轮只人工查看快照，不产生“审核通过”记录 | B：存可手填的审核人/状态；不可信，建议拒绝 | **P0：身份信任** |
| **DG3 冻结前提** | A：服务端在事务中检查 expectedRevision+当前 VALID 并重算 Native 候选 | B：仅凭前端 VALID 角标冻结；竞态 | **P0：错误内容冻结** |
| **DG4 摘要合同** | A：评审 RFC8785/JCS Profile + 版本固定、真实 SHA-256、跨语言 fixture | B：JSON.stringify / Jackson 序列直接 hash，格式不稳定 | **P0：不可复现** |
| **DG5 数据所有权** | A：Snapshot 独立表，禁止业务 UPDATE/DELETE，当前 Draft 不变 | B：复用 Draft 表的多个状态；混合可写和不可变身份 | **P1：历史可能被覆盖** |
| **DG6 版本号身份** | A：snapshotId/digest 与未来 package.version/releaseDigest 完全分离 | B：将占位 version 0.1.0 当正式发行号 | **P1：假发布** |
| **DG7 Diff 规则** | A：Type-scoped 结构化 Diff + 风险提示，不声称实际下游兼容 | B：只做全文 JSON Diff 或无依赖证据给“兼容”章 | **P1：误导升级安全** |
| **DG8 可冻结子集** | A：首个 ObjectType/Property 且未知资产非空拒绝，零丢失 | B：隐式过滤其他 Type/Link/Binding | **P0：破坏性数据截断** |
| **DG9 实施 Gate** | A：领域签收 → Contract（API/PG/Canonicalization）→ 小原型/UX → 一完整功能 PR → Linux CI+Windows 验收 | B：直接写发布页/后端，再发现身份和摘要冲突 | **P1：重做成本** |

**独立预审意见**：V1.3 总体方向与 DG1–DG9 的 A 路线一致；但 **DG1 的安全边界、DG4 的规范化摘要和 DG8 的无损子集是阻断条件**。本提案可以进入正式产品讨论，**不能在当前 PR 中标为 Accepted**。

**第二轮推进记录（2026-10-16）**：用户确认沿 DG1–DG9 推荐路线继续，现已完成 TC1–TC12 技术合同审查候选，涉及 V3 字节存储/JCS、API 幂等恢复、事务与兼容差异风险，**尚未取得正式技术签收或实施授权**。下一步建议维护者 review TC1–TC12，明确批准/调整后进入 UX-Gate-Low；任何单个 Gate 通过都不自动代表完整 OFIP Accepted。
