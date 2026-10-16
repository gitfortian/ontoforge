# S0-MVP-0.3 · Native 模型快照功能说明、全面 Review 与验收矩阵

> **历史记录**：本文件保留该轮交付/评审基线，文中的 Draft、待合并、未验收等不覆盖后续决定。当前功能和环境状态统一见[产品进度总账](./OntoForge-MVP-产品功能全景与成熟度矩阵.md)。

**文档性质**：功能实现审计 + 产品功能描述 + 发布前验收标准。  
**审查对象**：[PR #11（真实功能实现）](https://github.com/gitfortian/ontoforge/pull/11)；决策来源：[OFIP-0003（Accepted）](../proposals/OFIP-0003-S0-MVP-0.3-模型快照与发布边界.md)。  
**审核方式**：逐项核对 Flyway V3 / Spring Boot Java / React / 真实 PG17 JUnit / Chromium / Windows 脚本。**本文不把 PR Draft、CI 运行中、Mock 测试通过或候选代码存在误称为功能已发布。**  
**当前交付状态**：PR #11 仍在审核验收，**Not Merged / Not Released**；用户 Windows 11 本机 PostgreSQL 的实际重启复验 **NOT RUN**。所有 PASS 必须以具体最终 Head 对应的 CI 运行和实际日志为依据。

## 1. 产品定位与版本边界

**一句话功能**：用户可以把通过 Native V0.2 校验的某个 ObjectType 草稿修订，原子冻结为**只读本地 SchemaSnapshot**，在旧版不被改变的前提下继续编辑新草稿；随后按快照 ID 查看、核验真实 JCS/SHA-256、对比两个快照的结构变更和潜在风险。

**不是**正式治理发布：S0.3 未实现可信用户身份、组织/工作空间、审批和分权、可信签名、SchemaRelease/ReleaseDigest、Activation、生产 ObjectSet、Data Binding 运行、MES 写回或工业 Agent Action。UI/API 均不得提供“审核通过 / 已发布 / 已激活”的可操作伪能力。Snapshot 响应生命周期固定为 **`LOCAL_SNAPSHOT_ONLY`**，其 SHA-256 是内容摘要，不是安全签名或发布许可。

版本身份严格区分：

| 身份 | 归属对象 | 含义 |
| --- | --- | --- |
| `draftId` | 可编辑草稿 | 在当前 packageId 下唯一的编辑容器 |
| `revision` | Draft | 每次成功保存的乐观锁修订，保存后旧 VALID 报告失效 |
| `validatedRevision` | Draft ValidationReport | 完整性校验绑定的具体修订，只能反映校验事实 |
| `schemaVersion=0.2.0` | Native Candidate | 当前候选 Schema 格式版本 |
| Candidate `version=0.1.0` | Native Package | 构造候选时的内部占位值，**不是正式发行版本** |
| `snapshotId` + `sourceRevision` | SchemaSnapshot | 某一确定修订被冻结为独立只读历史 |
| `canonicalizationProfile` + `payloadSha256` | SchemaSnapshot | 可解释规范化字节与内容指纹，不是 ReleaseDigest |
| 未来 `releaseVersion` / `ReleaseDigest` | 审批后的正式 SchemaRelease | **本期没有该资源** |

## 2. 面向用户的真实使用流程

### 2.1 首份快照黄金故事

1. 在本地 `http://127.0.0.1:5173/#/model/packages` 创建模型包（例如 `manufacturing.production`），进入当前 ObjectType 草稿。
2. 录入对象类型（如 `production-batch` / `ProductionBatch`）、属性（如 `batch-id` / `batchId`，STRING + required）和主键，点击 **保存编辑进度**。WIP 保存允许尚不完整的数据，但服务器增加 revision；禁止把 WIP 当作已审核 Native Package。
3. 点击 **检查模型完整性**，必须是**当前相同 revision 的 VALID**；INCOMPLETE、INVALID、NOT_RUN 或本地尚未保存时都不可冻结。
4. 点击 **冻结本地只读快照**，在确认弹窗核对 packageId、revision、VALID 与“不是审批/发布/激活”，服务端在数据库事务内重新检验并冻结。成功时跳转到只读快照详情，展示不可变 ID、sourceRevision、Profile、真正的 SHA-256 与 Native Candidate 内容。
5. 回到当前草稿，修改说明 / 属性 searchable 等，保存使旧校验失效；重新校验后再冻结第二份快照。
6. 模型包历史页面选择 **From / To** 两个同包快照，对照 Type/Property 结构风险。Description 可标 DOC_ONLY，searchable true→false 可标 POTENTIALLY_BREAKING；**不得把结构 Diff 声称为消费者兼容认证**。
7. 历史读取使用独立 snapshotId。修改 Draft 不得更新任一旧快照。刷新页面从真实 PG 重读，不能依赖内存 Mock。后端重启持久化仍需 Windows 用户实测。

### 2.2 异常时正确的下一步

| 情况 | 用户看到什么 | 不能做什么 | 可恢复路径 |
| --- | --- | --- | --- |
| 本地字段有未保存更改 | dirty/编辑待保存；冻结按钮不可用 | 不冻结旧校验结果 | 保存 → 校验当前 revision |
| 未校验或不完整 | NOT_RUN/INCOMPLETE/INVALID、问题列表 | 不直接转为 Snapshot | 修改字段、保存、校验 |
| 另一个编辑者已保存 | `409 DRAFT_REVISION_CONFLICT`，当前冻结**不得成功** | 不静默覆盖本地草稿 | 查看服务器当前 revision，显式决定如何处理本地编辑 |
| 同键同修订重复冻结 | HTTP 200，返回同一个 snapshotId | 不产生第二份快照 | 打开现有详情 |
| 不同键再次冻结同修订 | `409 SNAPSHOT_REVISION_ALREADY_FROZEN` | 不生成另一条相同来源修订 | 从历史或 by-revision 查询既有快照 |
| 冻结后回执丢失 / 网络断开 | 客户端 `RESULT_UNKNOWN`，原幂等键与 revision 保留 | **不能**直接生成新的 key 重试 | 按原 key 查询；没有结果才沿用**同一 key**重试 |
| LinkType/多对象等不支持定义 | `422 UNSUPPORTED_DEFINITION` | 不裁剪、丢弃不支持的源对象 | 回到支持的编辑范围；多类型/Link 属于后续产品阶段 |
| 旧快照内容摘要、Profile 或 JSONB 镜像不一致 | `500 SNAPSHOT_INTEGRITY_FAILURE` | 不显示“已验证”旧内容，不自动重算覆盖历史摘要 | 保留现有 DB/日志，交给研发定位 |
| PG 不可用/HTTP 503 | 明确数据库不可用，或网络未知 | 不声称保存/冻结已完成 | 恢复 PG 后按同一 draft/key 核对真实结果 |
| 本地未保存编辑试图离开 | 确认提醒 | 不默认放弃本地内容 | 取消留在当前编辑，确认才离开 |

## 3. 实现层功能审查（代码对应）

| 领域模块 | 实际文件 | 已提交代码的行为 | 必须补齐 / 验证的风险 |
| --- | --- | --- | --- |
| PostgreSQL V3 | `ontoforge-boot/src/main/resources/db/migration/V3__native_schema_snapshot.sql` | 追加独立快照表；sourceDraftId/revision 与 draft 外键；双唯一约束；正长度、256KiB、版本/Profile/小写摘要约束；更新删除触发器拒绝 | 升级时 V1/V2 数据、installationId 不变；超级用户仍可禁用触发器，**不可称合规防篡改** |
| Java Freeze | `NativeSnapshotService.freeze` | 按 key 查重 → `SELECT ... FOR UPDATE` 锁 Draft → expectedRevision/VALID 检查 → 重生 Candidate → Native schema 校验 → JCS bytes + digest → 单事务插入 | 不同 key 同 revision 的并发 409、同 key 并发只一份、数据库故障回滚；真实 HTTP/PG 负例不能只写模拟 |
| Content Digest | `NativeSnapshotService.canonicalize/sha256/verify` | RFC8785/JCS Profile，BYTEA canonical bytes 为权威；查询 SHA、规范化重现、JSONB 镜像与 Native Contract 一致性检查 | 必须有 RFC 官方 fixture、Unicode/UTF-16、数字格式、算法升级与坏 Profile 的拒绝证据；只证明 hash 不等于防恶意签名 |
| REST | `NativeSnapshotController` | 创建、by-key、by-revision、按包分页、详情、同包结构 Diff；冻结请求拒绝未知字段和冒牌 approvedBy | 400/404/409/422/500/503；网络丢失、分页游标 malformed、不同资源串用 |
| Structural Diff | `NativeSnapshotService.diff` | ObjectType.id 与 (objectType.id,property.id) 身份对齐；分类风险；同包比较；只读历史，独立于当前 Draft | 属性 ID 改名不猜隐式迁移；required false→true、主键、值类型、searchable true→false、classification 与枚举变化需正反例 |
| 正式 React | `ontoforge-ui/src/NativeModelWorkspace.tsx` / `NativeSnapshotWorkspace.tsx` / `model.css` | 在现有侧栏/工作台完成确认冻结、只读详情、历史、分页、选 From/To 与风险标记 | 当前路线由服务端校验，不因按钮禁用就安全；需测试未保存离开、原 key 持久化、跨分页对照与窄屏/错误展示 |
| Windows 验收脚本 | `scripts/verify-s0.ps1` / `verify-snapshot.ps1` | 本机 PG V1/V2/V3 安装检查；随机测试包 + 双快照 + 差异 + SHA 回读；重启后 Recheck | Exercise 会**永久写入独立测试草稿/快照**，必须显式 `-AllowCreateTestDraft`；Windows PowerShell 5.1 语法与实际 native PG 执行分开记录 |

### 3.1 REST / HTTP 端点速查

| Method | 资源 | 成功/失败契约 |
| --- | --- | --- |
| POST | `/api/model/drafts/{draftId}/snapshots` | 新建 201 + Location；同键同 revision 200；409 / 422 / 400 等 |
| GET | `/api/model/drafts/{draftId}/snapshots/by-key/{idempotencyKey}` | 网络 UNKNOWN 时查找已提交结果；200/404 |
| GET | `/api/model/drafts/{draftId}/snapshots/by-revision/{revision}` | 查询该草稿修订已冻结的唯一快照；200/404 |
| GET | `/api/model/packages/{packageId}/snapshots?pageSize=&cursor=` | createdAt DESC + snapshotId DESC keyset 分页；1–50/页 |
| GET | `/api/model/snapshots/{snapshotId}` | 只读/完整性复验，200/404/500 |
| GET | `/api/model/snapshot-diffs?from={UUID}&to={UUID}` | 同包、按字段身份 Diff，200/422/500 |
| 不存在 | `/approve`、`/publish`、`/activate` | **本期不实现，也不提供假成功** |

**后端安全约束**：冻结请求只接受 `expectedRevision` + `idempotencyKey`，不接受客户端传来的 `approvedBy`、`releaseVersion` 或已审 Candidate。S0.3 仅 loopback 开发环境；当前缺失认证、工作空间与分权，禁止公网或生产连接。

### 3.2 结构差异风险规则

| 变化 | 标识 | 限制 |
| --- | --- | --- |
| ObjectType/Property ID 删除 | POTENTIALLY_BREAKING | 无 RenameMap 时不能猜测是重命名 |
| 新增非必填属性 | ADDITIVE | **不代表**生产消费者已兼容 |
| 新增必填属性、主键更换、valueType、apiName 变化 | POTENTIALLY_BREAKING | 需未来正式 Dependency/Compatibility Gate |
| searchable true→false | POTENTIALLY_BREAKING | 搜索消费者可能受影响 |
| classification 任意变化 | POLICY_REVIEW_REQUIRED | 不可自动放宽或缩窄安全决策 |
| enumValues 成员变化 | POTENTIALLY_BREAKING | 顺序变化按集合语义，需受审测试 |
| label / description 改动 | DOC_ONLY | 不代表正式审阅 |
| 同一 snapshotId | 无变化，sameSnapshot=true | 不是正式兼容认证 |

## 4. 自动测试与审核矩阵（OFIP-0003 T1–T12）

**标识含义**：
- **CODED**：发现候选实现或测试代码，但尚不能用此状态替代真实 CI PASS。
- **PASS**：有当前 PR 最终 Head 的真实运行证据。
- **PARTIAL**：测试覆盖一部分，但合同边界/真实异常仍不完整。
- **NOT RUN**：尚无对应真实环境验收。
- **NOT IMPLEMENTED**：本期明确不做或尚无实现。

| 合同 | 代码 / 测试覆盖及独立核对重点 | 当前审查状态 |
| --- | --- | --- |
| T1 成功冻结 | HTTP 201/UUID、源 revision、PG V3 insert、Native Candidate 与 digest；`NativeSnapshotTest` / `test_real_native_snapshot.py` | **PASS（Linux PG17/HTTP/Chromium）** |
| T2 冻结并发 | 两 Worker 同 key 仅一份；**补充不同 key 同 revision 409** 的真实 PG 负例 | **PASS（真实 PG17 并行 Worker）** |
| T3 无效 / 校验过期 | INCOMPLETE/INVALID/NOT_RUN/旧 revision、无未保存绕过 | PARTIAL：真实 INVALID/INCOMPLETE 负例可继续扩展 |
| T4 幂等 / UNKNOWN | 同键重复、不同键争抢、已提交却断开 UI 回执、sessionStorage 恢复同 key | **PASS（真实 HTTP/Chromium）** |
| T5 不可变/持久化 | 两份快照、修改 Draft 后旧快照不变、PG 回读/摘要；**Windows 真重启 NOT RUN** | PARTIAL |
| T6 拒绝篡改 | 普通 SQL UPDATE/DELETE 触发器拒绝；旧摘要一旦失配必须 fail closed | PARTIAL：恶意关闭触发器场景不属于本地可信审计 |
| T7 JCS 跨语言 | Java JCS ordering/数字 fixtures、UTF-16；摘要是真 SHA-256 | PARTIAL：全面官方 fixture 与跨语言一致性尚待独立扩充 |
| T8 Diff Type-scoped | 旧/新对象与 Property 的风险；同包比较/跨包拒绝 | PARTIAL：更多字段风险与 enum 集合语义负例仍可完善 |
| T9 不支持资产 | 2 个 ObjectType / Link/未知定义明确拒绝，不得隐式裁剪 | PARTIAL：多类型已有 HTTP 负例，其他未支持定义需覆盖 |
| T10 真实浏览器 | 两快照 + 刷新只读 + Diff + RESULT_UNKNOWN / retry + 历史跨页 | **PASS（Chromium 10/10，含 22 快照跨页）** |
| T11 Windows 实机 | Native PostgreSQL17、PowerShell 5.1、真正停止并重启 Java 后同 Snapshot/digest 回读 | **NOT RUN（由 Windows 操作者签收）** |
| T12 安全边界 | 无审批/发布/激活端点，拒绝 fake approvedBy，请求只限 localhost 开发配置 | PARTIAL；**认证与生产安全 NOT IMPLEMENTED** |

**独立 Review 新增缺陷与补测**（截至本次审查）：

1. **P1 / 测试可信性**：真实 Chromium E2E 中一条关于“结构 Diff 不会假称兼容已通过”的断言被 `if False` 禁用。修正为检查实际渲染出的兼容性限制提示，测试不再空跑。
2. **P1 / 历史交互**：快照历史前端使用 keyset 翻页，但进入下一页后替换上一页列表，导致早期版本不再是 From/To 候选，无法真正跨页比较。修复为**累积已加载历史、去重、切换页面路由重新从第一页读取**；补真实 PostgreSQL **22 份快照**跨页 Chromium 用户故事。
3. **P1 / 并发覆盖**：已有两 Worker **同 key** 测试，但缺少**不同 key 同 revision 争抢**，新增真实 PG 并行负例，验证仅一份成功，另一份稳定 409。
4. **P2 / REST 约束与页码**：新增真实 PG HTTP keyset `pageSize=1` 两页不重复、顺序正确和 `approvedBy` 非法字段 400。
5. **仍待拓展**：客户端会话跨浏览器（而非同 tab 刷新）恢复、完整官方 RFC 8785 交叉 fixture、服务端损坏镜像与摘要的真实 500 负例、分页时并发插入引发的列表快照一致性、Windows 本机实测。不得对这些写 PASS。

## 5. 交付门禁与发布前检查

- **设计 Gate**：OFIP-0003 已 Accepted；S0.3 仍只允本地只读 Snapshot/结构 Diff，不允许未经身份授权添加正式审批/发布/激活。
- **代码 Gate**：一个 PR 完成交付（PR #11）。需要 Flyway V3、Java SchemaSnapshot、正式 React 入口、正反例、详细运行手册与 CI 全绿；不能把“源码有类/通过 TypeScript Build”写成 E2E 交付完成。
- **受托 Code Review**：本矩阵关注安全边界、幂等和原子性、回滚/不可变、类型作用域 Diff、前端未保存/分页/未知结果；发现任何 P0/P1 必须在同 PR 修复、回归。
- **真实 CI Gate**：当前最终 Head 的 Windows PS 5.1 解析、PG17 Flyway/JUnit、Native V0.2、React Build、真实 Vite → React → Java → PG Chromium、Required Quality **全部实际完成且 success** 才能写“Ready for review”。历史成功不能冒充新 Head 成功。
- **Windows 本机 Gate**：开发/测试数据库下运行 `.\\scripts\\verify-s0.ps1`、`.\\scripts\\verify-snapshot.ps1 -Mode Exercise -AllowCreateTestDraft`；**真实停止/重启 Spring Boot**，保持原生 PG 数据不动；依据本机 JSON EvidencePath 执行 `-Mode Recheck`，并人工检查 Edge/Chrome 编辑冻结/只读与错误反馈。未提供证据保持 NOT RUN。
- **Release Gate**：当前没有可信身份/审批/生产隔离，本期不得宣称企业 Release Ready、Published、Active，也不允许对外开放工业 Action。

**验收记录模板**：

| 证据 | 完成状态 | 实际运行 URL / 环境 / Commit |
| --- | --- | --- |
| GitHub PR #11 真实 PG JUnit | **PASS · 14/14** | [Required Quality #38870260796](https://github.com/gitfortian/ontoforge/actions/runs/38870260796) |
| GitHub PR #11 真实 Chromium | **PASS · 10/10** | [Real Browser E2E #38870260830](https://github.com/gitfortian/ontoforge/actions/runs/38870260830) |
| GitHub Required Quality | **PASS** | [Required Quality #38870260796](https://github.com/gitfortian/ontoforge/actions/runs/38870260796) |
| Windows PowerShell 5.1 语法 | **PASS** | [E2E #38870260830](https://github.com/gitfortian/ontoforge/actions/runs/38870260830)；语法解析 ≠ Windows 本机 PG 验收 |
| 用户 Windows 11 Native PG + 真重启 | NOT RUN | 本机操作者提供脱敏 EvidencePath 与 PASS 结果 |
| 生产身份/权限、Release/Activation | NOT IMPLEMENTED | 本期不允许用模拟数据填 PASS |

**说明**：本审查文档将“代码存在”“自动测试完成”和“用户真正验收”作为三种独立证据；随 PR #11 最新 head 更新 CI 结论，避免只看过去的 Mock 33/33 就宣布快照功能可用。

## 6. 本次审查的已运行证据（代码版本与限制）

**已验证的功能代码 SHA**：`569b1d8ffcd02ac03540735152c16e62fc98236d`

- [Required Quality #38870260796](https://github.com/gitfortian/ontoforge/actions/runs/38870260796)：success。Java + 真实 PG17/Flyway：NativeSnapshotTest **9/9**、NativeDraftTest **3/3**、FoundationTest **2/2**，总计 **14/14 PASS**，零失败、零跳过；React/TypeScript、Native V0.2、Docs、PG 研究 POC 及聚合质量门禁通过。
- [真实 Java/PG + Vite/React/Chromium #38870260830](https://github.com/gitfortian/ontoforge/actions/runs/38870260830)：**10/10 PASS**，含新增的 22 个真实快照跨分页比较、冻结回执未知恢复、双版本只读 Diff、刷新与 SHA；Windows PowerShell 5.1 **语法检查 PASS**。Linux 隔离 PG 上运行两阶段 `Exercise → Recheck` PASS；此处**没有真实重启用户 Windows 11 上的 Spring Boot**。
- [E2E Chromium 截图与日志](https://github.com/gitfortian/ontoforge/actions/runs/38870260830/artifacts/12033042347)；[Architecture Docs #38870260599](https://github.com/gitfortian/ontoforge/actions/runs/38870260599) success；[低/高保真 UX #38870261285](https://github.com/gitfortian/ontoforge/actions/runs/38870261285) success。Mock UX 合格与真正运行时功能合格仍是独立证据。

**留存风险**：T3/T5/T6/T7/T8/T9/T12 的极端分支仍有部分未穷尽的测试，特别是跨语言全量 JCS 官方 fixtures、持久化损坏后的 500 真实故障注入、不同字段风险组合、更多 Unsupported 资产以及可信身份/权限隔离。Windows 原生 PG 真正停止/重启 Spring Boot 与浏览器人工验收 **NOT RUN**。这不是企业生产发布的准入证明。

> 本节证据只针对所示功能代码 SHA。本文件的后续文档提交可能形成新的 PR Head，最终合并前仍需检查**最新 Head** 的 CI；不能因旧 Head success 推定新提交的所有门禁完成.
