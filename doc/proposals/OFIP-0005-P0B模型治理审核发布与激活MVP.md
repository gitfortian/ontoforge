# OFIP-0005｜P0-B 模型治理：可信审查、正式发行、激活与回退（第一轮产品/领域评审）

**Status:** Discussion

**Decision:** Pending — DG-1 十项产品/领域决策推荐方案已提交审议；**未获得维护者正式 Accepted、未批准修改生产 API/Flyway 或开发安全敏感代码**。

**Discussion:** [PR #16 — P0-B OFIP-0005 第一轮 DG-1 领域评审](https://github.com/gitfortian/ontoforge/pull/16)

**Implementation:** Not Started — 仅领域方案、用户故事和未来验收合同；无真实身份、审核、发行、激活功能代码。

**Release:** Not Released

**Owner / Reference:** OntoForge 产品/架构/安全联合评审；[256 词条统一词汇表](../砺元本体产品统一术语词汇表.md)、[唯一 V1.3 总体架构](../本体产品总体功能与技术架构设计_v1.3.md)、[Native DSL/AST V0.2](../design/核心本体元模型与Native-DSL-AST契约_v0.2.md)、[OFIP-0003 本地模型快照](./OFIP-0003-S0-MVP-0.3-模型快照与发布边界.md)、[OFIP-0004 LinkType 建模](./OFIP-0004-多对象与LinkType关系建模MVP.md)。

> **本轮对普通用户的承诺**：砺元已经能设计并保存对象/关系，也能安全留下“本地模型快照”。P0-B 要让团队能够**提交这次模型修改、看懂差异、由可信身份审核、形成正式模型版本、选择哪一个版本生效；若新版本出问题，能有证据地切回旧版本**。  
> **一个红线**：`Local Snapshot` 不会因本设计而变成已批准发行。当前 S0 没有可信身份/ACL/注册表，因此**不存在真实 ReviewDecision、SchemaRelease 或 Activation**。这份提案只提方案，不伪造产品已经支持的操作。

## Motivation｜为什么必须做 P0-B

### 1. 从“个人能建模”跨到“团队敢正式使用”

现行开发主线（0.2/0.3/0.4 已合并）：模型包里可定义 `ObjectType` / `Property` / `LinkType`，保存安全 WIP，通过服务端 VALID 检查，将一个确定修订冻结为 JCS/SHA-256 本地只读 `SchemaSnapshot`，读取历史、比较结构风险，并支持 revision CAS 与幂等恢复。上述是**研发环境真实基础子功能**，没有可信用户、工作空间 ACL、审查、正式发行、消费者激活或回退。

真实团队的下一道业务问题：质量工程师调整批次→工单关联基数时，生产侧服务是否应立刻采用？谁同意了这个变化？版本依赖是否被锁定？两个月后是否能证明批准的定义就是当时正式发行的那份？如果发布完成但网络超时，该找回哪个结果？如果新版本不合适，是回退激活指针，还是覆盖旧内容？这些答案是 P0-B 的产品价值。

### 2. 主要使用者与用户任务

| 角色（未来可信身份） | 核心任务 | 不能允许什么 |
| --- | --- | --- |
| 模型设计者 Editor | 编辑草稿、留存有效快照、准备 ChangeSet、解释变更理由 | 自行冒充 Reviewer 或改写审查记录 |
| 领域审核者 Reviewer | 查看固定内容、结构差异、业务含义与安全风险，批准/退回/拒绝 | 修改待审内容后继续沿用既有 Approval |
| 发行管理员 Publisher | 核验审核和发布 Gate、创建不可变 SchemaRelease | 对未经批准的普通快照直接点击正式发布 |
| 激活管理员 Activator（MVP 可与 Publisher 共享角色，但操作分开） | 选择可信发行版本作为指定范围 Active Release，安全回退 | 把模型指针回退描述为 ERP 外部操作已撤销 |
| 工作空间管理员 Owner | 管理包归属、最小权限、审核策略和版本可见范围 | 客户端自报管理员、跨租户读取或发布 |
| 应用/Agent 消费者（未来） | 按明确 Release 或 Active 指针读取可用模型 | 读取未审核草稿、未经授权的跨包依赖或过期版本 |

**身份前置判断**：当前没有登录/授权系统。P0-B **必须先建立可信服务端认证与工作空间/模型包权限**，不能用 `reviewerName: "Alice"`、前端角色下拉框或固定 service account 来制造审批证据。自动测试可以在隔离 loopback 环境使用专用测试身份；生产模式必须 Fail Closed。

### 3. 与 F01–F29 全局功能总账的关系

| 能力编号 | S0 已经真正做到 | P0-B 本次要解决的子范围 | 仍然不等于整个模块完成 |
| --- | --- | --- | --- |
| F01 本体工作空间 | 模型包/草稿入口的基础 UI；尚无可信多用户工作空间 | 可信主体、Workspace/Package 归属、最小授权 | 企业组织目录、复杂 RBAC/ABAC |
| F04 版本发布 | 本地快照与字段/关系 Diff 可用 | 可信正式 SchemaRelease、版本读取、激活及回退 | 跨企业环境发布、全消费者迁移 |
| F05 模型包与依赖 | 包内 ObjectType/LinkType 引用与编辑修订 | 正式包版本坐标、依赖锁定、跨包影响治理 | 真正跨包 LinkType 的运行关系不在本轮 |
| F19 语义权限 | 只有字段 Classification 声明、没有实际可信 ACL | 可信发布主体与至少包级角色/操作检查 | P1 查询对象/属性/关系层 ABAC |
| F20 Evidence/Trace/Audit | 0.3 保留快照不可变摘要和本地历史 | 真实审查/审批/发行/激活证据及恢复路径 | 企业级 WORM、外部合规归档 |
| F25 协同建模与审查 | 单草稿 CAS 防旧 revision 覆盖 | 审核队列、退回、意见、职责分离 | 完整协同分支和三方合并 |
| F28 语义质量门禁 | VALID 检查与局部结构风险 | 发布前绑定精确候选的质量/兼容风险判断 | 有实际消费者注册之后的端到端兼容证书 |

### 4. 不新增的能力

本轮 **不**创建真实 `Object` / `LinkFact`、Data Binding、ObjectSet Query Runtime、工业系统 Connector、Function/Agent 或 Action；不引入工厂实时设备控制；不承诺“已发布模型自动同步所有下游数据库或 ERP”；不因为展示“审核通过”就默认批准工业业务操作。

## Public Interfaces｜产品对象与公共合同候选

> **DG-1 只冻结产品语义，具体 REST URL、Java class、SQL/Flyway、JSON Schema 与权限表留到第二轮 TC-2 审查。** 不得把本节候选字段当成已有接口。

### 1. 七个必须分开的模型治理资源

| 资源 | 唯一身份/核心内容（候选） | 写入权威 | 为什么必须独立 |
| --- | --- | --- | --- |
| `Draft` | draftId、packageId、revision、editingContent | Editor 的模型草稿 | 可改、可暂存 WIP；不能代表可信发行 |
| `SchemaSnapshot` | snapshotId、sourceRevision、payloadSha256、canonicalPayload | 现有 0.3 不可变历史服务 | 可核验定义但尚无批准主体与审核资格 |
| `ChangeSet` | changeSetId、baseVersionRef、candidateSnapshotId、candidateDigest、reason、diff | 提交者通过可信 API | 定义“提出改变什么”，不代表同意 |
| `QualityReport` | checkedCandidateDigest、policyVersion、issues、impact、result | 服务端质量门禁 | 防止旧版校验报告被复用到新版 |
| `ReviewDecision` | reviewId、reviewerSubject、decision、comment、digest、time、reviewPolicyRef | 可信 Reviewer 审核 API | 只能批准固定内容；记录退回/拒绝 |
| `SchemaRelease` | releaseId、packageCoordinate、canonicalDigest、lockDigest、approvalRefs、immutablePayload | 发行服务经 Gate 创建 | 可供未来消费者引用的可信正式模型版本 |
| `Activation` | activationId、scope、oldReleaseId、newReleaseId、expectedActiveRef、actor、time | 受控激活服务 | 模型发行不自动成为所有应用正在使用的版本 |

**模型包内版本与运行态必须进一步区分**：`SchemaRelease`、`BindingVersion`、`IndexGeneration`、`FunctionRelease`、`PolicyVersion` 具有不同责任。P0-B 允许模型控制面激活可用版本，不允许替未来 P1/P3 部件静默改状态。

### 2. 产品状态建议（不要写成一个大枚举）

| 对象 | 独立状态 | 说明 |
| --- | --- | --- |
| Draft | WIP / VALIDATED（当前修订） | 编辑后旧 VALID 自动失效 |
| ChangeSet | PREPARED → IN_REVIEW → CHANGES_REQUESTED / REJECTED / APPROVED | 退回需新候选并重新审核；拒绝保留证据 |
| SchemaRelease | PUBLISHING → PUBLISHED（成功）/ FAILED（可恢复） | 创建后不可编辑，失败不得留下假的正式版本 |
| Activation | IDLE → SWITCHING → ACTIVE / FAILED / UNKNOWN（需核对） | 持久化指针事务原子切换，不能出现两个 Active |
| Retirement | ACTIVE → DEPRECATED → RETIRED（另有可逆回退决策） | 退役不清理不可变历史或已有审计 |

**不要采用**单个 `status = PUBLISHED` 同时表示草稿、审批、发行、绑定、读索引和应用当前使用版本。正式发布完成 ≠ 对消费者激活；激活模型 ≠ 源数据投影更新；模型回退 ≠ 外部系统 Action 回滚。

### 3. 所有需要机器保证的关键不变量

1. **可信主体**：Review/Publish/Activate 必须由服务端验证身份和授权产生，任何客户端自报角色均无效；没有企业 IdP 时只允许受隔离测试身份的演示，不允许对外公开。
2. **固定基线**：ChangeSet 明确关联一份不可变的基线和候选，附当前包版本坐标；不能用 UI 编辑态充当审批内容。
3. **内容绑定**：审查决议必须锁定 `candidateDigest + dependencyLockDigest + qualityPolicyVersion + targetPackageCoordinate`；其中任何一项变化使原批准不可被新版本复用。
4. **多维审核**：模型结构 VALID 不等于发布 Quality PASS；主键改变、关系删除/基数变化、证据要求放宽、分类变更、依赖漂移，必须在报告里呈现受限的兼容/安全结论。
5. **一致性**：正式发行写入遵守幂等、唯一包版本坐标、不可变内容和故障恢复；发布失败不能激活半成品，也不能偷偷删掉旧版本。
6. **发布与激活分离**：`PUBLISHED` 只说明发行存在；只有对指定 Scope 显式 `Activation` 后才成为相应消费者的 Active Release。没有绑定/索引/消费者时，不宣称任何真实对象数据已经迁移。
7. **回退有审计**：回退是新的一次对 Active 指针的授权变更（含 old/new、理由、签收），不会覆盖历史、不会逆转 ERP/QMS 既有副作用。
8. **范围隔离**：包/工作空间 Owner、Editor、Reviewer、Publisher 权限服务端执行；同一提交者不能作为唯一审核者满足双人复核策略。
9. **未知结果找回**：发布、激活请求收到超时或连接断开时使用原幂等键/请求标识查询已有结果，不能靠反复点击创建多个发行或激活事件。
10. **当前消费者状态诚实**：P1 尚未注册真实业务消费者时只能报告 `NO_REGISTERED_CONSUMERS` 或 `UNKNOWN_CONSUMER_IMPACT`；不能写“全部下游已兼容”。
11. **可检查证据**：每个对外可信 Release 能追到 Source Snapshot、完整候选内容、依赖锁、质量报告、审查主体、当时权限与激活链。
12. **不破坏历史**：已有 `native_package_draft`、`native_schema_snapshot`、Flyway V1–V3、JCS 摘要、旧版 JSON 字节一律保留，新增功能使用前向迁移和新资源。

### 4. 关键公共字段命名（仅建议）

沿用[统一词汇表](../砺元本体产品统一术语词汇表.md)的中文主词，面向代码建议保留：`packageId`、`draftId`、`revision`、`snapshotId`、`candidateDigest`、`changeSetId`、`reviewDecisionId`、`approvalDigest`、`releaseId`、`version`、`lockDigest`、`activationId`、`activeReleaseRef`、`subjectId`、`scope`。**目前只有 S0 已实现的字段属于真实 API 合同；其他候选均待 TC-2 定义。**

### TC-2｜第二轮：受信身份、资源状态、发布与激活的详细合同（评审候选）

> **阶段状态：Contract Review / Decision Pending。** 本节把 DG-1 目标收敛到可编码、可拒绝、可测试的合同。所有新增 JSON 字段、REST 路径、数据库结构及安全策略均是**推荐方案**，当前不在 `main`、不具备生产能力。既有 S0 API 的实际实现以当前代码为准。DG-1 与 TC-2 应明确记录独立评审决议后，再进入 UX-Low/High；不能因本章通过文档 CI 就自动 Accepted。

#### TC01 · 与现有 0.4 可运行代码的衔接事实

| 已核查真实实现（main） | 精确事实 | 本轮限制 |
| --- | --- | --- |
| `NativeDraftController` | `/api/model/drafts` 的创建、保存、校验、读取；`expectedRevision` 乐观锁。控制器**没有身份与包授权**。 | 必须在边界增加统一受信权限，不允许给新接口加校验而旧草稿接口仍敞开可篡改 |
| `NativeSnapshotController` | `/api/model/drafts/{draftId}/snapshots` 以 revision 和 idempotencyKey 冻结；`/snapshots/{id}`、`/snapshot-diffs` 供本地历史浏览。 | 旧路径及语义要保留；不能被误用为审核/发行/激活 API；跨工作空间读取也必须受保护 |
| `NativeSnapshotService` | 将有效候选规范化为 JCS UTF-8 并校验 SHA-256；保存原规范化字节及 JSONB 镜像，提供不可变历史和结构 Diff。 | 通过摘要校验 ≠ 可信签名、批准或消费者兼容证明；严禁就地修改旧历史 |
| `V2__native_package_drafts.sql` | `package_id` 在本地安装内唯一，单包一个可写 Draft，没有 workspace_id/owner/actor。 | P0-B 不得假设已有多租户隔离；新归属必须有明确迁移/认领过程 |
| `V3__native_schema_snapshot.sql` | 唯一来源修订、幂等键；`canonical_payload BYTEA` 和 `payload_sha256`；UPDATE/DELETE 触发器拒绝。 | 只添加新 Flyway 版本和新表，不改历史 V1–V3 或原 JCS Profile |
| `ontoforge-boot/pom.xml` | 当前 `spring-boot-starter-web/jdbc`，没有已经配置完成的 Spring Security OAuth2 Resource Server。 | 不得说 S0 已有可信 IAM。认证与 Java/React/PG 真实负例必须属于 B0 的完整故事 |

**一项风险必须提前明确**：S0 旧草稿/快照接口本身无鉴权。在 B0 引入身份时，**必须把现有所有读、写、校验、快照、Diff API 纳入同一权限模型**，而不是只守护新审批接口；只有受控 loopback 历史迁移流程才可能有临时例外。

#### TC02 · 身份信任链：生产 OIDC 与本地/CI 测试如何分离

**推荐 A**：Spring Security Resource Server 验证 OIDC/JWT **签名、issuer、audience、有效期、not-before、密钥轮换**；将受信 `(issuer, subject)` 映射为内部 `principalId`，访问 `workspaceId/packageId + operation` 由数据库/授权服务独立检查。企业对接的 IdP 由部署配置指定，砺元不得把用户自报 role 当作权限。

- **前端**：登录会话和 API Token 由已批准的 OIDC 客户端/BFF 方案承载；避免在 localStorage 保存长期 Token；跨站请求与凭证 Cookie 需有 SameSite、CSRF、TLS 明确策略。具体选择须在安全审查记录，不将任何演示 IdP 直接视为可部署 SSO。
- **开发 Windows 11**：使用隔离测试身份服务（例如自部署的 OIDC 测试提供者或可复现的签名 JWT fixture）签发短期、受固定 issuer/audience 约束的测试 Token；仅允许在显式 `dev/test` profile、绑定 loopback、隔离数据库且 CI 禁止生产配置时使用。不得提供用户可任意编辑的 `X-User-Id`、`X-Role` 绕鉴权开关。
- **CI**：两把独立签名密钥或两个隔离测试主体 `editor-A` / `reviewer-B`，覆盖过期签名、错 issuer/audience、撤权、跨 workspace、申请人自审、无 Token；负例应观察到真实 401/403，而不是前端模拟报错。
- **断言**：`subjectId` 不来自请求 JSON；审计只使用服务端认证上下文。无法认证 → 401；已认证但无权限 → 403（不泄露受限包具体信息时可 404），身份服务故障不开放审核/发行/激活。
- **留待安全专项决定**：生产采用纯 Bearer Token 还是 BFF 会话、刷新策略、OIDC Token 类型与 scope 映射、密钥泄露/撤权处置。**不应先写死单一客户 IdP**。

#### TC03 · 包归属与最小操作权限

资源建议至少：`Workspace(workspaceId)`、`PackageAssignment(workspaceId, packageId, owner)`、`WorkspaceMembership(principalId, role)`、`PackageRoleBinding(principalId, packageId, role)`。是否在 B0 采用 1 个 workspace 后端实现可简化，但字段及权限模型不能隐藏域边界。

| 操作 | Viewer | Editor | Reviewer | Publisher | Activator | Owner |
| --- | :---: | :---: | :---: | :---: | :---: | :---: |
| 阅读已授权的包与正式版本 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 读取受限的 Draft/Local Snapshot | 按策略 | ✓ | ✓ | ✓ | 按策略 | ✓ |
| 创建/保存 Draft、结构校验、冻结 Local Snapshot | — | ✓ | — | — | — | 可显式授权 |
| 创建/提交 ChangeSet | — | ✓ | — | — | — | 可显式授权 |
| 审查/退回/拒绝/批准他人 ChangeSet | — | — | ✓ | — | — | 仅具备 Reviewer 身份且满足职责分离 |
| 发行经批准版本 | — | — | — | ✓ | — | 仅具备 Publisher 身份 |
| 激活/回退 Active Release | — | — | — | — | ✓ | 仅具备 Activator 身份 |
| 授予/撤销包权限与成员资格 | — | — | — | — | — | ✓ |

表中 ✓ 是**角色申请资格，不是最终授权**。同一人可持有多角色，但自我批准在默认职责分离策略下必须拒绝；包角色与 workspace 资格应共同满足。Viewer 的内容读取必须区分正式公开版本与受限草稿内容。授权失败不得返回敏感候选摘要、Comment、Diff 或真实包 ID 的存在性。

**旧单安装 packageId 唯一语义**：建议 B0 起保留旧存储的 packageId 及 Draft ID；让管理员在只允许本地迁移的维护流程明确认领到选定 Workspace，不自动把所有历史记录授权给任何新用户。若同名包跨 Workspace 重复，先定义唯一约束的演进策略和兼容路由，**本期不承诺跨租户同名包并存**；若未认领，默认无法经可信 API 访问。

#### TC04 · 稳定资源 ID、基线/候选和不可变引用

| 资源 | 身份建议 | 内容与合法引用 | 写入/不可变原则 |
| --- | --- | --- | --- |
| `ChangeSet` | UUID `changeSetId`、`packageId`、`workspaceId` | `baseReleaseId?`、`candidateSnapshotId`、`candidateDigest`、`targetVersion`、`reason` | 提交审核时固定候选；变更产生新提交/轮次，不在已批准记录上改 JSON |
| `QualityReport` | UUID `reportId` | `candidateDigest`、`lockDigest`、`qualityPolicyVersion`、`checkedAt`、结构风险、来源未知 | 内容哈希锁定；策略或候选变化不得复用旧报告 |
| `ReviewDecision` | UUID `decisionId` | `changeSetId`、`round`、`reviewerPrincipalId`、`decision`、`approvalDigest`、`decidedAt` | 只追加，不允许更新已审查记录；角色撤权之后的新动作要按当前授权 |
| `SchemaRelease` | UUID `releaseId` + `workspaceId/packageId/version` 唯一坐标 | 复制已审核 Snapshot 的**精确规范化字节**、`payloadSha256`、`lockDigest`、审批引用 | 一旦正式完成不可修改；不共享可变 Draft JSONB |
| `ActivationEvent` | UUID `activationId`、`scopeId`、单调顺序 | `previousReleaseId?`、`nextReleaseId`、`expectedActiveRevision`、`actor`、`reason` | 只追加事件，与唯一 Active 指针更新同事务 |

**首次发行**：`baseReleaseId=null` 被显式允许，仅当该 package/scope 没有旧 Active Release 时使用；必须保留“首次发行”的日志语义。**后续发行**：基线可引用某个已发布版本（及其已验证摘要），不得把任意客户端传来的 draftId 当作版本基线。

#### TC05 · 审批摘要、依赖锁和 JCS：本期最重要的一致性边界

**提议分离三个 Digest**（最终命名与二进制规范由 TC-2 决议确定）：

1. `payloadSha256`：沿用旧 `SchemaSnapshot` 的 **JCS RFC8785 canonicalPayload 字节**摘要；任何新发布流程都必须读取并复核这份字节，不能再次基于 JSONB 重建另一个“近似相同的旧内容”。
2. `lockDigest`：未来依赖锁列表 `(packageId, version, digest)` 按明确顺序和字段稳定 JCS 计算；**当前 S0 不支持 imports**，P0-B0/B1 最小路径可将锁定义为 `[]` 且有精确合同，绝不能假装支持跨包动态 latest。跨包实际引用与访问另设未来 Gate。
3. `approvalDigest`：按域分离前缀的固定 JCS 对象计算，例如：
   `{profile:"ontoforge-approval-v1", workspaceId,packageId,targetVersion,sourceSnapshotId,candidateDigest,lockDigest,qualityPolicyVersion,reviewRound,policyId}`。审批签署时必须把当前 `approvalDigest` 与可信 ReviewDecision 关联；任一内容、版本、权限政策/审核政策标识变化后，旧批准不能用于新发行。

**不能照搬**：仅使用 `payloadSha256` 作为审批许可，因为同一模型内容在不同 workspace/包版本/依赖/安全政策下仍可能需要不同批准。Digest 本身不是密码学主体签名；可信 `reviewerPrincipalId`、`authenticatedIssuer`、实际授权事件和服务器时钟才构成受控审批证据。正式保真/签名机制需单独安全评审。

**证据自洽约束**：每条 Release 必须能从 `sourceSnapshotId → candidateDigest → approvalDigest → ReviewDecision → QualityReport → lockDigest` 重新核对；不能只保存空白批准人名和一段自然语言“审核通过”。

#### TC06 · 正式版坐标及 Registry 不可变策略

- `schemaVersion = 0.2.0` 是 Native **文档机器合同版本**；`package.version` 是业务模型包版本，二者不得混淆。实际包候选应以已校验的 **完整 Native payload** 发布，不随手修改原字节填个用户输入版本号。
- 候选中 `version` 的真实来源、命名/递增规则和审批版目标需要在 B1 实现前核对既有 `DraftValidation` 的候选生成；若旧 Snapshot 固定生成 `0.1.0`，不能仅在 Release DB 中写 `1.0.0` 却继续宣称 payload 是 `1.0.0`。**推荐在新发行包外围建立独立可信坐标/manifest，与旧冻结 payload 的实际 version 字段显式分离；如要改变 Native payload 必须产生新候选与新审批**。
- Registry 唯一约束：`(workspaceId,packageId,version)` 至多对应一个不可变 `releaseId` 与 digest；相同坐标、不同摘要 → 409 `VERSION_ALREADY_EXISTS`，不覆盖，不自动升版本。
- P0-B 不允许 `latest` 浮动跨包依赖。合法发布所引用的依赖必须已固定、已批准且获授权；因为 S0 imports 不支持，首期真实发布范围先限制**无导入的完整同包 ObjectType/LinkType**。
- 发布完成但未激活也可通过授权的 `releaseId`、版本坐标只读检索；不同范围有不同 Active 指针，绝不猜“最近的发布版本就是全部消费者当前版本”。

#### TC07 · 状态机、并发前提及不可变事件

```text
Local Snapshot(immutable)
  └─ prepare ChangeSet(PREPARED)
       └─ submit(IN_REVIEW)
            ├─ request_changes(CHANGES_REQUESTED) ──> 新修订/新审批轮次
            ├─ reject(REJECTED, immutable)
            └─ approve(APPROVED, bound approvalDigest)
                 └─ publish(PUBLISHING/RECOVERING → PUBLISHED | FAILED)
                      └─ activate(SWITCHING → ACTIVE | FAILED/UNKNOWN)
                           └─ reactivate(previous approved Release) → 新 ActivationEvent
```

- `ChangeSet` 提交审核后候选快照 ID 与摘要不可更新；“退回修改”须先创建新候选/审核轮次并使老审批失效。
- `REJECTED` 与 `APPROVED` 视为该轮终态、不可覆盖；退回后需新的轮次与明确对比。
- 正式 `SchemaRelease` 内容与审批证据一经提交不可更新删除；准备中/失败态要存储在独立发布尝试记录而非污染已发布不可变资源。
- `ActivationEvent` 的事件写入、单个 scope 的 `activeReleaseId` 及 `activeRevision` CAS 必须置于**一个 PostgreSQL 事务**。`expectedActiveRevision` 与当前不符 → 409；不能出现两个 Active 版本。
- 激活是模型控制面指针变更，不自动触发 Binding、Index、Function 或 Action 部署。回退使用新的 ActivationEvent，必须在同一授权边界检查该旧 Release 是否仍有资格被激活。
- 审批、发行、激活、角色变化应记录服务端事件和时间戳；**授权决议不能只发生在 UI 层**。

#### TC08 · 未来 HTTP 接口形状及错误码（只作为候选 API，不代表路由已存在）

| 未来 REST 路径候选 | 输入必须锁定 | 输出/控制面原则 |
| --- | --- | --- |
| `POST /api/model/changesets` | `sourceSnapshotId`、`baseReleaseId?`、`targetVersion`、reason、idempotencyKey | 201 新建；服务端读取快照验证完整候选，绑定包/主体 |
| `POST /api/model/changesets/{id}/submit` | `expectedRevision`、`expectedCandidateDigest` | 服务端质量 Gate；形成固定审核轮次 |
| `GET /api/model/reviews/inbox` | 授权包范围和游标（不允许客户端伪造 Reviewer ID） | 仅返回当前主体真正有资格审查的待办 |
| `POST /api/model/changesets/{id}/decisions` | 审核轮次/预期 Approval Digest、decision、comment、idempotencyKey | 可信主体/职责分离/Policy 验证，决议只追加 |
| `POST /api/model/releases` | `changeSetId`、预期 `approvalDigest`、`targetVersion`、idempotencyKey | 验证 Gate 后原子创建不可变 Release，超时可按原 key 恢复 |
| `GET /api/model/releases/{releaseId}` | 无客户端自报 role | 按范围授权，返回 immutable payload+决议/摘要 |
| `POST /api/model/activations` | scope、releaseId、expectedActiveRevision、reason、idempotencyKey | 同事务追加激活事件并 CAS Active 指针 |
| `GET /api/model/activations/{id}` / `.../by-key/{key}` | scope 和可信身份 | 原请求核验，不能超时后盲发新 key |

**统一拒绝/恢复合同建议**：

| HTTP | 错误码建议 | 场景 |
| --- | --- | --- |
| 400 | `MALFORMED_REQUEST` | 非法结构、额外字段、非法 UUID / 包版本 |
| 401 | `UNAUTHENTICATED` | 无 Token、签名/issuer/audience/有效期错误 |
| 403（或安全化的 404） | `ACCESS_DENIED` / `NOT_FOUND` | 未授权的包、Review/Publish/Activate；不暴露包存在性 |
| 404 | `RESOURCE_NOT_FOUND` | 有权上下文内资源不存在 |
| 409 | `REVISION_CONFLICT` / `VERSION_ALREADY_EXISTS` / `ACTIVE_REVISION_CONFLICT` | 版本/审核轮次/激活指针旧值，禁止静默覆盖 |
| 412 | `APPROVAL_DIGEST_MISMATCH` / `DEPENDENCY_LOCK_STALE` | 依据变化，旧审批必须失效 |
| 422 | `QUALITY_GATE_FAILED` / `REVIEW_POLICY_NOT_SATISFIED` | VALID 之外的业务安全/职责分离 Gate 不满足 |
| 503 | `AUTHORITY_UNAVAILABLE` / `RESULT_UNKNOWN`（按服务端能否判定区分） | 权威身份/策略暂不可用，或者执行结果无法安全确认 |

调用者重试时 `idempotencyKey` 必须与原业务操作 scope 与请求内容摘要绑定；**相同 key 不同 body** 要拒绝，不能返回已有成功作为新请求成功。对于未知结果优先 by-key 读取当前真实持久化产物。

#### TC09 · 数据库新迁移与旧安装升级（实施方案候选）

- 提议从新 **Flyway V4+** 增加 workspace / subject 映射 / role assignment / change set / review decision / immutable release / activation event / active pointer / operation idempotency / audit evidence；具体拆分只按真实 B0/B1/B2 交付，不提前造空表。
- `V1–V3` 文件及已产生的 `native_schema_snapshot.canonical_payload` / `payload_sha256` / `sourceRevision` 原样保留；禁止删旧记录、重算历史或修改不可变触发器掩盖失败。
- **B0 引入身份对旧 API 的兼容门槛**：定义可信 workspace 认领程序，要求操作员确认唯一旧 packageId 与目标 workspace；默认旧数据拒绝未授权访问，不能“一键给当前用户 Owner”无审计。
- 旧 `/api/model/drafts` 和 `/api/model/snapshots` 的 JSON 返回形状尽量兼容，但由服务端补强访问控制；仍基于既有真实 `DraftFailure` 错误语义，避免给一个错误再造两个互相矛盾的安全错误体系。
- 权限必须在 SQL 查找前或受约束 SQL 里校验，列表/历史/差异和按键回查不可绕过。将来多 Workspace 支持跨包同名时要正式迁移主键与路由，不改掉 S0 已冻结的单包身份历史。
- 不允许客户端使用超级用户 PostgreSQL 账号绕开业务角色检查；隔离测试库的数据库登录与生产系统配置分开。

#### TC10 · 精确兼容风险输出（必须承认未知）

`CompatibilityReport` 不等于现有 `SnapshotDiff`。前者的最小内容建议为：

`candidateDigest`、`baseReleaseId?`、`structuralRisks[]`（对象/属性/LinkType）、`classificationRelaxations[]`、`dependencyRisks[]`、`knownConsumerCount`（如果真实注册）、`consumerImpactStatus`（`UNKNOWN` / `NO_REGISTERED_CONSUMERS` / `VALIDATED_FOR_KNOWN_CONSUMERS`）、`policyDecision`（`ALLOW` / `REVIEW_REQUIRED` / `BLOCK`）、`evaluationProfile`。

首次 MVP 无真实消费者目录时 **禁止`VALIDATED_FOR_KNOWN_CONSUMERS`**；“注册消费者为零”不是“已证明所有消费者安全”。结构 Diff 可用于预警，不能冒充下游数据迁移、BindingVersion、ObjectSet 或 Agent Context 的实际兼容性认证。关键字段风险例如主键/LinkType 删除、基数变化、证据/分类放宽默认需要业务/安全进一步审查。

#### TC11 · 三条可实际操作的未来实现路径（完整且安全）

| 工程 PR | 一个用户看得见的端到端成果 | 真实拒绝路径 | 通过后仍缺 |
| --- | --- | --- | --- |
| **B0 身份 / 资源边界** | 以 editor-A 登录后只能操作获授权的模型包；reviewer-B 可按角色查看审核资源但不能任意改草稿；原 0.4 草稿/快照仍可用 | 无 Token、错签名、跨 workspace/包、伪造 role、旧无认证 API、登出/撤权后一律失败 | 没有正式发布；勿显示虚假“审核通过” |
| **B1 ChangeSet → 审核 → 正式发行** | editor-A 提交绑定 Snapshot 的修改；reviewer-B 看到确切 Diff 并批准；publisher 生成一份可核验、可读取的不可变 SchemaRelease | 自审、旧批准/锁漂移、版本冲突、未知远程结果幂等、未授权发布 | 还没 Active 指针，不对真实工业消费者生效 |
| **B2 激活与安全回退** | activator 将有效 Release 在测试 Scope 激活；从实际浏览器看到当前版；发生失效/竞态时拒绝；审核后再激活旧版 | 旧 expectedActiveRevision 409、未发布 Release、跨 scope、幂等冲突、越权 | 不意味着 P1 绑定/索引/Agent/Action 已安全迁移 |

#### TC12 · 可验收的测试矩阵，防止 POC/Mock 偷换真实状态

| T | 应当实际证明的事实 | 预期验证 |
| --- | --- | --- |
| TC2-01 | 既有无认证 S0 模型 Draft、Snapshot、Diff 与列表在 B0 受控，不泄露跨包数据 | Java API + PG17 + 浏览器 |
| TC2-02 | OIDC 错签名、错 issuer/audience、过期 Token、无 Token → 401；合法无权 → 403/安全化 404 | Security 测试，真实服务端过滤器 |
| TC2-03 | 角色撤销后无法继续提交/审核/发行/激活；token 存在不等于包授权仍存在 | Java/PG + API |
| TC2-04 | 不同可信主体完成提案/审核；同一人自审被拒 | 双身份隔离 Chromium 用户故事 |
| TC2-05 | ChangeSet 关联的快照是完整 3 ObjectTypes+2 LinkTypes 的候选；旧快照摘要不变 | 原 0.4 真实 API + PG |
| TC2-06 | 修改候选/版本坐标/依赖锁/审核政策后，旧审批必定失效 | Java/PG/哈希反例 |
| TC2-07 | 未通过质量门禁的模型即使结构 VALID 也不能正式发行 | API + 质量策略 |
| TC2-08 | 首次发行 `baseReleaseId=null`；第二版必须有明确基线、单坐标不可变 | Java/PG + 唯一索引 |
| TC2-09 | 同 IdempotencyKey 同请求产生同一发行；同 key 不同请求拒绝 | HTTP 重试 / 模拟断线 |
| TC2-10 | SchemaRelease 发行成功但不改变 Active；发布后不可 UPDATE/DELETE | PG 事务 + UI 读取 |
| TC2-11 | 两主体同时激活同 scope，最多一个 CAS 成功，其余明确 409 | Java/PG 并发 |
| TC2-12 | 切回旧版会生成新 ActivationEvent；不删除新发布也不触发 ERP 外部动作 | 真实 API/PG/Chromium |
| TC2-13 | 旧本地 V2/V3 记录、JCS SHA 字节、旧 Draft 修订冲突保护全部保持 | Flyway 升级 + 实时回归 |
| TC2-14 | Linux 实际后端重启后完整恢复发布/审核/激活/权限；Windows 11 本机验收独立 NOT RUN | 双分层运行记录 |
| TC2-15 | 无消费者时明确展示“未知/尚无登记”，不能签发兼容认证 | API/React 产品断言 |
| TC2-16 | 安全日志与审核证据关联服务端主体、受控时间、digest，错误响应不泄露 Token/SQL/机密模型 | 审计负例 |

#### TC13 · TC-2 尚需确认的七项核心决定（推荐 A，但不是已批准）

| 合同决策 | 推荐 A | 明确不采纳的捷径 |
| --- | --- | --- |
| **TC-D1 身份模式** | B0 采用受信 JWT/OIDC 验证，开发用隔离测试 issuer，角色/包授权由服务器评估 | 浏览器自报审核人、生产接口无认证 |
| **TC-D2 旧 S0 接口** | Draft/Snapshot/列表/Diff 统一纳入鉴权与归属；需要审计化旧数据认领 | 只保护新发行接口 |
| **TC-D3 首次与重复发行** | 初版允许显式 `baseReleaseId=null`，版本坐标和 digest 唯一，重试有受限作用域 | 临时从文件名推断版本、同版本覆盖 |
| **TC-D4 内容与审批摘要** | 分离 JCS payloadDigest、依赖 lockDigest、包含上下文的 approvalDigest | SHA 一致就自动视为审核通过 |
| **TC-D5 跨包依赖 MVP** | 首期正式发行仅无导入的完整同包模型，锁为空数组且有合同；扩展需新的 Gate | 假装已支持跨包浮动 latest |
| **TC-D6 并发与未知结果** | 独立审核轮次、幂等发行、Activation CAS + 原请求按 key 回查 | 依赖前端禁用按钮保证唯一 |
| **TC-D7 消费者影响与安全** | 结构风险 + UNKNOWN/NO_REGISTERED_CONSUMERS + 独立 Policy Gate，绝不“100% 兼容” | 没消费者就自动放心发布 |

**第二轮独立 Review 必须对照**：Spring Boot 没有配置 OAuth2 Resource Server、旧草稿路径无可信鉴权、Native V0.2 现有 JSON Schema `version` 字段与快照真实候选生成的关系、同包 0.4 对象/关系的双修订状态、V2/V3 不可变迁移。任何一项未核实，就保持 `Discussion`，不得生成生产代码或标记 `Accepted`。


## Proposed Changes｜用户完整路径与建议的实施切片

### 1. 单个制造黄金故事：把一次关系改动正式应用到选定范围

业务包 `manufacturing.production` 已定义 `ProductionBatch`、`WorkOrder`、`CustomerOrder`，其中“批次归属工单” LinkType 需要从 `MANY_TO_ONE` 调整。建议正常路径如下：

1. **起点**：设计者打开模型包，在既有工作台查看当前有效的发布版本（如果不存在则显示“尚无正式版本”，而不是显示最新 Snapshot 冒充）。
2. **编辑与对比**：对现有模型修改关系基数，保存/校验为 VALID，并留存新的本地只读 Snapshot。选择当前 Active Release 作为变更基线，打开“准备提交审核”。
3. **提交变更**：平台生成 ChangeSet，展示关系变化、受影响对象引用、潜在破坏级别、来源证据要求与消费者未知范围；用户填写业务理由并提交。提交后把这份候选内容锁定，不允许后台静默替换。
4. **独立审核**：一个真实已登录且具有相关包 Review 权限的主体，在审核列表看见固定版本、逐项 Diff、质量报告与内容 Digest；可评论/退回/拒绝/批准，不具备资格者明确 403。
5. **可信发行**：符合 Gate 后由发行人员创建不可变 SchemaRelease，获得 `releaseId`、版本坐标、依赖锁、审批关联与内容摘要；该动作**不改变**已生效版本。
6. **显式激活**：在选定 Workspace/Consumer Scope 中查看旧版→新版、兼容风险、回退入口，确认激活后才更新 Active 指针。若发布目标版本不合法、未授权或指针竞态则拒绝。
7. **验证恢复**：重启后能追溯上述 ChangeSet、ReviewDecision、Release 与 Activation，旧模型版本仍可读；激活失败/超时使用原请求确认究竟是否切换。
8. **业务回退**：若新模型不适宜，选择一个以前已获信任的 Release 发起新 Activation，保留“从新版退回旧版”的单独审计证据；**不**删除新 Release，也**不**声称工厂业务系统已回滚。

**MVP 特殊情况：首次发行**。包从未有 Active Release 时，允许 `baseReleaseRef = null` 的明确首次发行流程，但必须有已验证候选、可信审核和版本唯一性；不伪造既有消费者影响报告。

### 2. 关键异常故事必须提前设计

| 异常 | 用户看到什么 | 系统必须保证什么 |
| --- | --- | --- |
| 草稿校验 VALID 后再次编辑 | “校验已过期，请重新保存与检查” | 不得引用旧 validatedRevision |
| 送审后修改关联基数/Dependency Lock | “旧审批仅针对旧候选，需重新提交” | ReviewDecision 绑定的摘要不可漂移 |
| 设计者自己审批自己的变更 | “不满足当前复核职责分离要求” | 服务端 403/Policy Denied |
| 无可信登录 / IdP 失效 | “当前无法验证身份，不允许审核/发行/激活” | Fail Closed；不接受客户端伪造主体 |
| 包版本坐标冲突 | “此版本号已被占用，请检查已有发行” | 不覆盖旧 Release、恢复原请求 |
| 关系删除 / 主键变化 / 权限放宽 | “潜在破坏 / 安全复核，可能有未知消费者” | 不能只凭 JSON Schema 合法放行 |
| 发布写入成功但响应超时 | “结果待核对，按原请求找回” | 同 idempotencyKey 不重复发行 |
| 激活发生竞态 | “当前有效版本已改变，请刷新确认” | expectedActiveRef CAS，保证单一有效指针 |
| 激活后需退回 | “选择已发布历史版并填写原因” | 记录新 Activation，不删除旧发布 |
| 包依赖目标变动 | “依赖锁过期或不匹配” | 禁止 floating latest 偷换审核内容 |
| 无实际消费者注册 | “当前没有已登记消费者，无法证明兼容” | 不生成虚假的消费者清零证明 |
| 系统重启 / 数据库恢复 | “审核与发行证据可重新读取、校验摘要” | 不可变历史与身份不重写 |

### 3. UI/信息架构建议：沿现有模型包工作台推进，不新造孤岛

```text
本体设计
  └ 模型包详情（延续 S0 已有）
      ├ 模型定义（对象 / 关系 / 保存 / 校验）
      ├ 版本历史（本地快照 / 已发行版本 — 清楚分组）
      ├ 变更与审核（我的变更 / 待我审核 / 审核意见）
      └ 发行与激活（已发行版本 / 当前有效 / 回退记录）
```

优先在当前工作台复用模型列表、Diff、状态与恢复布局。**不可在 S0 用户无登录时显示一个真的“审核通过”成功按钮**。界面要让新人准确区分“保存草稿”“留存本地快照”“提交审核”“创建正式发行”“激活版本”。

### 4. 建议实施切片（都必须有真正可验收的用户动作）

| 迭代 | 范围与真实用户结果 | 前置条件 | 暂不称为 |
| --- | --- | --- | --- |
| P0-B0 可信身份与工作空间 | 两个测试主体经真实服务端认证进入同一隔离工作空间，能区分 Edit、Review、Publish；越权请求真实拒绝 | 企业 OIDC/OAuth2 或隔离测试 IdP，明确非生产环境拒绝策略 | 模拟登录文案或企业全量 ABAC |
| P0-B1 审核与发行纵向闭环 | 一人提交确定候选 ChangeSet，另一人审核固定内容，质量 Gate 通过后生成可只读查询的不可变 SchemaRelease | P0-B0 通过真实授权负例、已有 0.4 快照完整不变 | “只建审核表与发布按钮” |
| P0-B2 激活与安全回退纵向闭环 | 将已发行版本在受控 Scope 中切换、检测竞态、重启后找回状态，再审计地切回已发行旧版 | P0-B1 可信 Release 可引用 | 自动同步工业源/实际 Action |

**范围警惕**：B0 不应演变成完整 IAM 产品；B1 不应为了“代码量”大拆十个没用户价值的小 PR；B2 不能未经 P1 验证就表示所有真实绑定/索引已适配新模型。可根据 TC-2 复审调整切片，但每个实施 PR 都要有用户从入口到成功或拒绝的完整故事。

## Compatibility, Deprecation, and Migration Plan

1. **零破坏现有历史**：保留已合并的 0.2/0.3/0.4 Draft/Snapshot 数据和 JCS 摘要；新治理表必须前向 Flyway Migration，不允许编辑 V1–V3、删除旧草稿或通过 `repair` 隐藏兼容问题。
2. **快照“升级”规则**：旧 Snapshot 可以作为新 ChangeSet 的候选来源，但必须经过新 Gate 并新建独立 ReviewDecision / SchemaRelease；**不得给旧快照直接补一个 approved 标志或改它的摘要**。
3. **版本唯一性**：`packageId + version` 与内容摘要、依赖锁有明确不可变约束；跨包依赖必须指向锁定版本，允许以后专业的跨包能力另行评审。
4. **访问与兼容**：引入身份后，既有 S0 本地 API 需要设计隔离的迁移路径；生产授权默认拒绝，不能用固定管理员绕开鉴权让旧客户端照常无条件修改。
5. **发布失败**：事务失败要保留正确未发布状态、请求幂等记录以及审计上下文；激活依赖已完整提交的 Release。
6. **回退边界**：仅切换模型使用指针，不更改已经形成的 ExternalReceipt、Action 记录、业务实例事实或绑定/index 的物理数据。
7. **已发布兼容**：当前没有真实 P1 生产消费者，结构风险可测，消费者影响明确为“未知/尚无已注册”，不能从空表推断兼容 100%。
8. **数据治理风险**：模型包若含 CONFIDENTIAL/RESTRICTED 声明，即使字段本身没有实例，审核和导出也必须按包授权、来源/标签保护处理。

## Test Plan｜阶段门禁与未来真实验收

> **本提案全部测试均为“将来必须执行”，截至 DG-1 没有 P0-B 真实功能代码，所有下面的测试 = NOT RUN。** 已通过的 S0-JCS/PG/React 测试仅证明已有基础链路。

| 未来编号 | 用户可验证的结果 / 安全不变量 | 实测层级 |
| --- | --- | --- |
| G01 | 两个受信主体分属真实包角色，客户端自填 admin 不能提权 | IdP + Java/API + PG |
| G02 | 跨工作空间或无权包不能读取候选/提交审核/读取发布详情 | Java/API/数据库隔离负例 |
| G03 | 从已冻结合法模型形成 ChangeSet，锁定 base 与 candidate digest | Native V0.2 + Java + PG |
| G04 | 修改候选字段/依赖/Policy 版本后旧审核失效，不能复用批准 | Java/PG/哈希回归 |
| G05 | 不同主体审批，提交者不能独自审批双人复核场景 | 服务端 + Chromium |
| G06 | 驳回/退回留下评论和不可删除的审查证据 | Java/PG + UI |
| G07 | 兼容报告区分结构风险与未知消费者，不给假认证 | 语义规则 + Chromium |
| G08 | 带批准证据/依赖锁的同包版本正式发行，二次同版本不同摘要拒绝 | Java/PG + Registry |
| G09 | 同幂等键响应丢失后找回同一 Release，无重复产物 | HTTP / 故障注入 |
| G10 | 发布成功但不激活，按指定 Release ID 读取仍正确 | Java/PG/Chromium |
| G11 | 激活同一 Scope 的 Active Release CAS，竞态只允许一个成功 | Java/PG 并发测试 |
| G12 | 回退到历史可信 Release，新 Activation 留痕，不改两份历史 | Java/PG + Chromium |
| G13 | 后端真实重启后 ChangeSet、审核、Release、Activation 不丢且摘要可核验 | PG17 + 后端重启 |
| G14 | 在 Windows 11 原生 PG 本机停启重查同一发行/激活记录 | Windows 专用验收（NOT RUN） |
| G15 | 0.3/0.4 老草稿、SchemaSnapshot 及历史摘要仍按旧 API 原义可查 | Java/PG 迁移兼容 |
| G16 | 安全异常（IdP 掉线、ACL 撤权）发行/激活一律 fail-closed | Java/API/浏览器安全负例 |
| G17 | 模型激活不触发外部 ERP、MES、QMS、PLC 或 Action | 系统边界/出站网络审计 |
| G18 | 权限、发布、激活、审查操作的主体、时间、内容摘要可审计重建 | 审计证据 + 恢复测试 |

**独立成果门禁**：

- **DG-1**：本轮产品范围、角色、术语、黄金故事、10 项产品决策明确评审。
- **TC-2**：第二轮确定可信身份来源、领域聚合/不可变记录、角色矩阵、Approval Digest、依赖锁、版本唯一性、故障与 HTTP 合同、数据库前向迁移及兼容风险政策。
- **UX-Low**：第三轮交互路径与异常恢复原型，覆盖“提交→审核→发行→激活→回退”，不能假装真实审批主体。
- **UX-High**：第四轮继承砺元 Studio 视觉和完整专业能力、实际 Chromium 点击/键盘/可访问性审查；仅为设计 Mock。
- **实施阶段**：以上经过正式批准，且有可信身份/授权和数据版本保护后按 B0/B1/B2 交付真实 Java/PG/React/Chromium，不提前制造空模型类或按钮。

## Rejected Alternatives｜不采用哪些看似简单的方案

| 方案 | 为什么不选 |
| --- | --- |
| 把本地快照 `SchemaSnapshot` 直接标成 `SchemaRelease` | 审批、主体、依赖锁、质量报告缺失；会伪造可信级别 |
| 通过前端下拉框输入“审批人”作为可信 Review | 服务端无法证实主体或隔离权限，任何人可伪造批准 |
| 合并 `Review`、`Release`、`Activation` 到一个 status 和按钮 | 发布成功与当前被谁使用属于不同事实；竞态和回退无法安全处理 |
| 只做结构 Diff，声称所有消费者兼容 | 尚无真实下游注册、BindingVersion/IndexGeneration，无法证明消费者影响 |
| 在模型 P0-B 回退时调用 ERP/PLC 恢复业务状态 | 模型版本回退与工业 Action 副作用完全不同；安全风险不可接受 |
| 提前为多租户 IAM、ABAC/跨云发布造一批空微服务 | 没有完整用户故事和真实可信权限边界，增加复杂度无法验收 |
| 改写 Flyway V3 或重算所有历史快照摘要 | 毁坏已合并的真实历史、幂等和兼容性；必须新迁移新资源 |
| 未经产品评审直接实现 P0-B 全量前后端 | 可信身份、批准责任及版本效果尚未明确，代码先行会造成大返工 |

## 第一轮 DG-1｜十项需要作出产品决议的选择

> **这里都是明确推荐，不是已签收结果。除非在 PR 中记录真实 [VOTE] Accepted 与决策理由，本文件始终保持 Discussion。**

| 决策 | A（本轮推荐） | B（备选） | 推荐理由 |
| --- | --- | --- | --- |
| DG1 产品完整范围 | **A：审核→发行→按范围激活→回退**；先走可信身份门槛 | B：仅做发行，不做激活 | A 才能解决“哪个版本正在用” |
| DG2 可信身份 | **A：服务端真实 IdP/OIDC 身份，隔离测试 IdP 供 CI** | B：纯前端用户/角色模拟 | B 不具备审查证据意义 |
| DG3 职责分离 | **A：提交者与最终审核者默认分离，异常按政策显式批准** | B：同一人无限制自审 | 先建立可信审查，避免虚假发布 |
| DG4 正式发行来源 | **A：合法 Snapshot + ChangeSet + QualityReport + ReviewDecision** | B：直接 Snapshot→Release | A 不伪造历史快照为已审核 |
| DG5 发布/激活 | **A：明确两步并保留独立时间与授权** | B：发布自动覆盖 Active | B 无法安全预演和回退 |
| DG6 内容锁定 | **A：候选内容 + 依赖锁 + Policy 版本 + 目标坐标固定摘要** | B：只批准显示名称/版本号 | B 可被“批准后换内容”绕过 |
| DG7 兼容说明 | **A：保守结构风险 + 明确未知消费者，不发全兼容证书** | B：0 消费者即 100% 兼容 | 不把尚未实现的 P1 消费者统计当真实依据 |
| DG8 回退语义 | **A：创建新激活事件指向旧 Release，历史完整保留** | B：覆盖或删除新 Release | A 可证明谁何时为何回退 |
| DG9 UI 入口 | **A：延续一个模型包工作台，分“变更审核/发行激活”** | B：新增独立“本体专业工具”一级菜单 | 保持已冻结 Studio 用户心智 |
| DG10 工程范围 | **A：B0 可信身份、B1 审查发行、B2 激活回退，各 PR 真实闭环** | B：先批量创建空模块/表单占位 | A 让每个 PR 可见且可验收 |

**本轮独立审查建议**：DG1–DG10 推荐 A 可以作为下一轮合同设计输入，但需要特别检查**可信 IdP 在 Windows 11 本机开发环境如何落地**、跨包依赖锁的 MVP 最小可用形态、模型变更影响报告的真实性，以及“正式版不存在任何 P1 消费者时”的激活 UI 应如何解释。不得在缺少可靠主体与授权时生成假 ReviewDecision。

**下一步（需本轮产品方案评审后）**：第二轮 TC-2 完成资源身份、状态转移、错误矩阵、安全检查、审计与兼容策略；第三轮 UX-Low、第四轮 UX-High；正式工程开发只有在合同/UX 得到真实评审决议后才开始。
