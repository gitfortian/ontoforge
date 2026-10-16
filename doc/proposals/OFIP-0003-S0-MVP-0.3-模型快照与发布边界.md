# OFIP-0003：S0-MVP-0.3 Native 模型版本冻结、差异与发布安全边界

**Status:** Discussion  
**Discussion:** [PR #10 — OFIP-0003 首轮产品/领域评审](https://github.com/gitfortian/ontoforge/pull/10)  
**Decision:** Pending — 第一轮 Domain / Scope Gate 产品与技术独立预审，**DG1–DG9 均为推荐意见，尚非维护者签收**  
**Implementation:** Not Started — 仅方案评审；不涉及 SQL / Java / React / Native Schema 的实现更改  
**Release:** Not Released  
**Owner / Reference:** OntoForge 产品评审；[OFIP-0002（Accepted）](./OFIP-0002-ObjectType-草稿建模MVP.md)、[现行架构 V1.3 候选](../本体产品总体功能与技术架构设计_v1.3.md)、[Native DSL/AST V0.2 候选](../design/核心本体元模型与Native-DSL-AST契约_v0.2.md)  
**Background:** S0-MVP-0.2 [PR #7](https://github.com/gitfortian/ontoforge/pull/7) 已合并（真实 Flyway V2 / Native 草稿 Java API / React / PG17+Chromium CI）；[Windows 验收工具 PR #9](https://github.com/gitfortian/ontoforge/pull/9) 已合并。**用户 Windows 11 本机 PostgreSQL 重启复验仍未提供，不得声称已通过或已发布。**

> 本提案特意区分 **Working Draft → Validated Revision → Immutable Local Snapshot** 与未来 **ChangeSet → Trusted Review → Approved Release → Activation**。本轮建议的 S0.3 是**本地研发语义模型快照与版本对照能力**，不是企业可审计的发布/部署系统，更不是可供生产 Agent 或工业 Action 直接消费的 SchemaRelease。

## 1. Motivation

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

## 2. Public Interfaces（候选，不是已签收 API）

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

## 3. Proposed Changes

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

## 4. Compatibility, Deprecation, and Migration Plan

1. Flyway V2 的 `native_package_draft` 和数据不改、不删除；建议 V3 纯新增 Snapshot 表、索引与数据库层不可变保护。若以后真正需要扩展当前草稿 editorSchemaVersion 或多类型/Link，另评审迁移。
2. Native V0.2 Package Schema 不因“快照”增加字段。Snapshot metadata 放**外围受管资源**，不是偷偷塞进 `objectTypes[]` 或 Native Package 根节点，否则 JSON Schema additionalProperties 会拒绝。
3. Snapshot 与未来正式 Release 均允许同包历史并存；单 Draft 的 packageId 唯一约束不能传播到历史表。
4. 对同一 payload 的 canonicalization/formatter 变化不得无声重算旧摘要。必须保留 Profile ID、原始受检 bytes/可稳定重建的 canonical 字节；历史查询按保存时 Profile 解释，未来算法升级另发 Profile。
5. 不支持的旧编译产物、跨包依赖、LinkType/Binding 原型应**显式拒绝**；不能让 S0.2 首对象 UI 把其他类型截断后再冻结。
6. Windows 11 原生 PostgreSQL 是默认本机环境，不能把用户必须安装 Docker 作为产品前置条件。CI PG17 仍独立且不等于用户本机验收。
7. 发布与审批尚无公共消费者，因此不存在从假发布状态“迁移成真”的捷径；0.3 只能交付 Snapshot，与正式 Approval/Release 的语义隔离是前向兼容基础。

## 5. Test Plan（本轮规划验收，不是已运行的结果）

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
| T10 真实浏览器 | Vite → React → Spring Boot → PG17：冻结、查看、Diff、刷新、断网/409、未保存离开 | Mock 的 31/31 不能替代 |
| T11 Windows 实机 | 用户 Windows 11 原生 PG：保存、冻结、后端重启、同一 Snapshot 内容/摘要重读 | Linux CI 绿灯不能替代 |
| T12 安全边界 | 无 Auth 只允许本机回环，未配置授权的审批/发布/激活入口不存在 | 不能把操作者名字从表单当审计主体 |

**实际开发必须只用一个完整、可运行的功能 PR**，包含 PG V3、Java Transaction/Canonicalization/Diff、正式 React UI 和真实 E2E。独立设计 PR 中**不得混入开发代码**。如果经过领域评审确认 0.3 应改成真正可信审批/发布，则必须先签收最低身份/授权合同，扩大范围与验收，不得绕过安全边界。

## 6. Rejected Alternatives

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

**建议下一轮优先顺序**：先让维护者确认 DG1–DG9 中对 Snapshot 而非正式 Release 的分期选择；之后在本 OFIP 下完成 Contract-Gate-2（PG V3 事务与不可变约束、Canonicalization fixture/版本、请求幂等和 Diff 类型），再做精简用户路径原型及真实代码。任何单个 Gate 通过，都不自动代表完整 OFIP Accepted。
