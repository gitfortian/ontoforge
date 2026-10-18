# OFIP-0014：M12 沙箱受控 Action 预演、幂等执行与结果核验

**Status:** Discussion

**Discussion:** 待创建的 `[DISCUSS]` 提案 PR；需求讨论见 [M12 #51](https://github.com/gitfortian/ontoforge/issues/51)。

**Decision:** Pending（须维护者显式记录决定、日期和 permalink；本提案不提供运行授权）

**Implementation:** Not Started

**Release:** Not Released

**Related:** [OFIP-0001](./OFIP-0001-需求提案文档治理与CI证据门禁.md) · [V1.3 候选](../本体产品总体功能与技术架构设计_v1.3.md) · [Native V0.2 候选](../design/核心本体元模型与Native-DSL-AST契约_v0.2.md) · [执行记录候选 Schema](../design/schema/semantic-execution-record-v0.2.schema.json) · [UNKNOWN 样例](../design/examples/manufacturing-action-unknown-v0.2.json) · [M10 #49](https://github.com/gitfortian/ontoforge/issues/49) · [M11 #50](https://github.com/gitfortian/ontoforge/issues/50)

> Extended MVP：仅在独立模拟业务系统验证真实状态机和副作用，不接生产写接口，不执行工业物理控制。预演、页面确认、模型发布审批与业务 Action 授权是不同 Gate。

## Motivation

领域工程师已经能查询异常对象，但需要在可控环境证明“生成建议 → 人工批准 → 写入模拟系统 → 查询回执 → 核验业务事实”不会重复写入或把未知结果冒充成功。
首条故事限定为对一个用户自定义类型的对象，在模拟系统创建一条“复核任务”。任务指向该对象并记录输入摘要，不修改来源业务对象，不产生生产通知或现场指令。
模拟系统必须真实持久化命令和任务，并能独立查询回执及业务状态；前端定时切换状态或本地返回假成功不算实现。

审阅基线 `6968ea0804f57b2b82f237e37f812bcadb32f343` 的证据：

- [B0AuthorizationService.java](../../ontoforge-boot/src/main/java/io/ontoforge/boot/security/B0AuthorizationService.java) 只有草稿/校验/快照操作权限，不包含 Action 执行或审批权。
- [LocalMvpAuthorizationService.java](../../ontoforge-boot/src/main/java/io/ontoforge/boot/security/LocalMvpAuthorizationService.java) 显式绕过身份检查，只适合本机隔离建模；`local-mvp` 不是可信审批人。
- 当前 Java 控制器没有 Action 运行端点；Native V0.2 已有 Action/Policy/执行记录候选合同及结构负例，不是运行时。
- [test_native_v02.py](../design/tests/test_native_v02.py) 已拒绝缺业务状态证据的成功、待审批派发、DENY 后执行，以及 UNKNOWN 声称结果已确认；这些边界必须保留。

Issue 方向正确，但缺少可信授权前置、预演失效、并发重放、崩溃窗口、远端幂等契约及证据合同取舍。
M10 的正式版本锁和 M11 的真实工作台是前置；M10 模型签收本身不授予本次 Action 权限。
M12 不能成为 M13 只读接口的依赖。
非目标：通用编排引擎、任意脚本/URL/SQL 执行、多步骤跨系统事务、自动补偿、Agent 自动 Action 或生产安全认证。

## Public Interfaces

### 受限 Action 与记录合同

首切片候选 `SandboxActionContract v1` 仅登记一个服务器管理的 `create-review-task` 能力，允许绑定不同自定义 Native 类型。
它包含实际 Action 版本/摘要、锁定的目标 TypeRef、输入字段类型/长度、单目标 EffectScope、前提检查版本、模拟执行器引用、审批要求、幂等及核验要求。
执行器 URL、凭据、权限策略及允许目标由服务端配置；请求体不得覆盖，禁止客户端注册任意网络地址。
该受限合同不等于完整 Native `ActionDefinition`：现有 V0.2 强制 Function/Decision 引用，首切片不因此创建假 Function 或编排器。

候选 API 均属于 `/api/actions/sandbox/v1`，使用严格字段集合：

| 接口 | 输入和结果 |
| --- | --- |
| `POST /previews` | ActionRef、对象引用、typed parameters；服务端读取远端 revision，返回 previewId/digest、完整版本及 EffectScope、失效时间 |
| `POST /executions` | previewId、previewDigest、idempotencyKey；生成 `PROPOSED` 记录，不自动批准或派发 |
| `POST /executions/lookup` | actionRef 与原 idempotencyKey；重新授权后找回 executionId/状态，不要求预演仍在有效期 |
| `POST /executions/{id}/approvals` | decision、expectedStateVersion、approvalIdempotencyKey；审批人对指定版本和预演摘要批准/拒绝，服务端核验独立权限 |
| `GET /executions/{id}` | 返回经授权可见的当前状态、事件、回执及核验依据 |
| `POST /executions/{id}/reconcile` | 请求对账，不允许调用者把状态改成成功，也不重新派发 UNKNOWN |

`SandboxActionExecution v1` 由服务端持久化 executionId、stateVersion、workspace/package、主体/审批人、实际模型 `SCHEMA_RELEASE(releaseId)`/RuntimeVersionLock、Action/策略版本、对象及目标 revision、输入/预演/效果范围摘要、幂等键、状态、尝试、事件、远端回执和结果核验引用。
完整 Binding 引用统一 `{kind,bindingId,revision,digest,sourceId}`，不虚构 Native SemVer。
新记录是明确有版本的受限合同，**不声称符合 Native V0.2 Semantic Execution Record**。
后者强制 SchemaRelease/Function/Rule/Decision/Index 等联合版本；尚未存在的版本不能填占位摘要。
未来扩展须显式提供转换矩阵和新版本/已接受的 Schema 调整，保留旧记录，不静默修改现有 V0.2 Schema。

### 可信身份、审批与前置 Gate

真实执行需已接受且已实施的可信身份适配器，以及独立 `SANDBOX_ACTION_REQUEST` / `SANDBOX_ACTION_APPROVE` / `SANDBOX_ACTION_READ` 授权。
不得从现有包 `EDITOR`、模型发布角色、浏览器参数或模型输出自动推导这些权限。
首切片要求一个独立真实审批主体，禁止请求人自批；仅需支持该固定策略，不顺带建通用权限管理平台。
身份可由受治理的现有 JWT 路径或未来独立评审后的适配器提供，不强制新造 OIDC；yak-ops HttpSession 当前未实施。
`mvp-local` 只能运行标识清楚的无副作用预演；该预演锁定真实 Local Snapshot/Binding，标 `eligibility=NON_EXECUTABLE_LOCAL`，不要求不存在的正式 Release 或 Action grant。
本地预演不得生成可信批准或进入执行记录/队列；所有实际提交、审批、派发请求服务端拒绝。
未完成可信授权或 M10 Gate 时，执行保持不可用，不能用本机占位身份演示“审批通过”。

| 条件/错误 | 语义 |
| --- | --- |
| 无权/会话无效 | 401/403；不暴露目标和执行记录详情 |
| 预演过期/摘要变化 | `PREVIEW_STALE`，重新预演并重新审批 |
| 模型、Binding、Action、策略或远端 revision 变化 | `PRECONDITION_CHANGED`，阻断旧批准派发 |
| 同键不同意图 | `IDEMPOTENCY_CONFLICT`，409，不产生第二条命令 |
| 未完成正式版本/身份 Gate | `ACTION_GATE_CLOSED`，不入执行队列 |
| 网络超时或派发是否生效不明 | `UNKNOWN`，只能核对，不能显示失败后建议直接重试 |

## Proposed Changes

### 1. 用户流程与批准失效

1. 用户在 M11 对象页选择沙箱任务预演，确认模拟标识、单个对象和固定输入范围；本机开发流程到非执行资格预演即止。
2. 可信执行形态下，服务端检查读取权/请求权、锁定 M10-B/OFIP-0005 正式版本、运行时版本锁、Action/策略与远端 revision，生成只读预演；预演不发送业务写命令。
3. 用户提交预演形成 `PROPOSED`；具有独立审批权限的主体查看同一锁定摘要并批准或拒绝。
4. 派发前重新验证审批、权限、有效期、模型/Binding/Action/策略、EffectScope 及目标 revision；任一变化进入 `BLOCKED`。
5. 授权且预检一致后事务性写入队列和事件，由执行器向独立模拟系统派发；UI 只读取服务器事实。
6. 远端接受后核对命令回执；再独立读取实际任务，核验对象、输入摘要及任务状态，满足全部条件才显示 `VERIFIED_SUCCESS`。
7. 响应丢失、重启或结果未确认时用户可查看尝试和对账状态；不提供会重新生成幂等键的“一键重试”。

首切片预演及批准有效期候选为 5 分钟；实施前由维护者确认，并由服务端时间判断。
已有执行意图的 POST 响应丢失时，先按原键 lookup 找回，不因预演过期创建第二条记录；尚未建意图且预演已过期，则需重新预演。
审批按 expectedStateVersion 做 CAS；授权核验后先查询幂等决定，同 approvalIdempotencyKey 同决策返回原决定/当前状态，同键异决策或非重放的旧状态版本拒绝，不覆盖已存在批准。
批准绑定 previewDigest、版本集合、主体/审批人和 EffectScope，不能批准可变的 `latest`。
撤权在批准和派发时重查；已派发的外部事实不能撤回，撤权后内部服务仍以受限系统身份完成对账，结果仅向仍有读取权者展示。
模型、输入或目标变更需要新预演；旧批准保留为不可再用的历史事件，不在原记录上覆盖。

### 2. 状态机

| 当前状态 | 允许前进及事实条件 |
| --- | --- |
| `PROPOSED` | 可信审批通过→`APPROVED`；拒绝→`DENIED`；失效→`BLOCKED` |
| `APPROVED` | 事务内复核通过且写入 outbox→`QUEUED`；版本/权限变化→`BLOCKED` |
| `QUEUED` | 原子认领且派发前复核→`DISPATCHING`；复核失败→`BLOCKED` |
| `DISPATCHING` | 远端接受→`RECEIPT_CONFIRMED`；确定拒绝→`FAILED`；响应不明/租约失效→`UNKNOWN` |
| `UNKNOWN` | 只按原幂等键查询远端命令；确认接受→`RECEIPT_CONFIRMED`，确认拒绝→`FAILED`，其余保持未知 |
| `RECEIPT_CONFIRMED` | 独立业务读取匹配→`VERIFIED_SUCCESS`；不匹配则保留未核验及原因，不能凭 2xx 改成功 |
| `VERIFIED_SUCCESS` / `DENIED` / `FAILED` / `BLOCKED` | 终态保留历史；新意图需要新预演和新记录 |

UNKNOWN 查询不到记录仍不能证明从未执行；首切片不自动重发 UNKNOWN。
核验超时不销毁已确认回执，不把“命令已接收”降成“业务失败”；显示待核验并允许有界对账。
不提供“取消即回滚远端”的承诺；已派发命令的事实必须保留。

### 3. 幂等、崩溃恢复与模拟系统

本地唯一约束为 `(workspaceRef, actionRef, idempotencyKey)`，绑定规范化意图摘要；同键同意图只返回同一执行身份，同键不同意图拒绝。
摘要包含目标身份、输入、完整锁定版本及预演摘要；服务端核验当前读取权后才能返回已有记录。
意图创建与人工批准分属独立事务；每次状态迁移及其事件、适用的 outbox 写入在同一 PostgreSQL 事务提交，以 stateVersion CAS 防止丢更新。
队列认领采用版本条件和租约，重启不能删除记录重新开始。
派发采用至少一次传递思路，不能宣称跨系统 exactly-once；持久幂等由模拟系统自己的命令注册表和唯一约束保证。
模拟系统在独立 loopback 进程和独立事务边界运行，使用隔离测试库/专用 schema 与最小凭据，不共享业务事务伪装远端成功。
同幂等键保存输入摘要、远端命令 ID 与业务任务身份，重启后仍可查询；创建命令与任务在远端一个事务内提交。
它以 `expectedSourceRevision` 原子复核单个目标版本；冲突必须拒绝且无副作用。
故障模式至少包含远端提交后丢响应、接受但业务未达成、明确拒绝、延迟、重复请求及进程重启。
模拟器地址必须配置白名单 loopback；用户输入和源文本不能变更执行器、请求方法或凭据。

### 4. 交付与 UX

首个功能 PR 交付一个自定义类型的一条 Action 全链路：真实双主体、预演/批准 UI、持久队列、独立模拟器、回执与核验，包含故障恢复。
不为将来的 Function/Decision/Connector 建空模块；受限前提和计划可由一个确定性实现承担，版本及摘要是真实构建事实。
实施前完成需求/安全评审及低高保真签收，重点展示 UNKNOWN、待核验、撤权、预演失效和同键冲突。
页面持续标识“模拟系统”，把“已批准”“已接收”“结果已核验”分开显示，不把危险按钮作为未实施能力摆上页面。
沙箱验收不授予外部系统、工厂或工业控制使用许可；后者需要新的适配/安全/运维提案及真实验收。

## Compatibility, Deprecation, and Migration Plan

- 当前草稿、快照、M2/M3 查询及本机无认证建模保持不变，不扩大 LocalMvp 的已有 permitAll 白名单来启用 Action 写操作。
- M10 尚未接受/实施的 Release 合同不能用 Local Snapshot 冒充；正式版本或 Action 授权 Gate 未就绪时实现只能交无副作用预演，M12 不得标完整。
- 新增执行、审批、事件/outbox 及远端模拟注册表采用实施时下一个未占用的前向 Flyway/模拟器迁移；V1–V6 不改，不用清库或 repair。
- 事件追加、状态带版本更新；执行记录包含明确 `recordVersion`，未知旧版本可只读保留或明确拒绝，不能猜测转换成成功。
- 旧 Native V0.2 样例只是结构夹具，不导入为真实审批/历史执行；受限 sandbox 记录升级时保留原内容和转换依据。
- 回滚部署先停止新派发并保留对账能力、幂等注册表和待处理记录；本地回滚不能抹除远端已存在任务。
- 凭据外置，审计只保存必要参数摘要和授权事实；记录读取/导出、保留期限及删除策略在实施前明确，不记录令牌/密码。

## Test Plan

以下均为未来实现要求；本次设计文档不代表真实写动作已经执行。

| 范围 | 正例 | 必须负例 | 验证形式 |
| --- | --- | --- | --- |
| 合同/状态机 | 锁定版本和合法状态转换 | 客户端权限字段、越界 EffectScope、非法跳状态、假审批 | 严格 Schema/类型及服务 HTTP 测试 |
| 授权 | 真实请求人及独立批准人 | 自批、local-mvp、撤权、伪造/过期凭据、跨包读取 | 签名负例及真实数据库授权 |
| 幂等/并发 | 同键同意图一次远端任务 | 同键不同输入、两进程重复认领、并发目标变更 | PG17 双连接、独立模拟器、核对实际任务数量 |
| 恢复/UNKNOWN | 重启后原键对账恢复 | 远端提交后断响应、派发后本地崩溃、查不到回执 | 受控故障注入和持久化重启 |
| 核验 | 接受回执及业务任务匹配 | 2xx 无任务、错误对象/参数、旧 revision、核验不可用 | 独立远端读取及完整事件证据 |
| UI | 自定义对象→预演→审批→回执→核验 | 双击、刷新、旧预演、未知后盲重试入口 | React/Chromium + 真实后端及模拟器 E2E |

实现验收须报告端到端命令、独立 `TEST_DB_*`、故障注入开关、base/head SHA、测试报告与模拟器任务事实。
保留 [Native V0.2 结构负例](../design/tests/test_native_v02.py)，但其 PASS 不算 Action Runtime PASS。
提案 PR 只检查文档与候选结构回归；真实数据库/Action/浏览器/工业现场/生产发布分别记录 `NOT RUN` 或 CI 的 `NOT SELECTED`。

## Rejected Alternatives

1. **本机确认弹窗等于审批授权**：local-mvp 无可信身份，客户端不能签发自己的执行权；真实沙箱写仍需独立服务端 Gate。
2. **直接完整落地 Function/Decision/Policy 平台**：一个受控动作尚无这些系统的真实消费者；采用清楚命名的受限合同，不能伪装 Native V0.2 已实现。
3. **HTTP 超时就换新键重试**：可能重复创建业务资源；UNKNOWN 先按原键对账，首切片不自动重发。
4. **模拟器与本地状态共用一个事务**：无法暴露最重要的“远端提交而本地未知”窗口；使用独立进程/事务并验证崩溃恢复。
5. **回执 ACCEPTED 就显示成功**：只说明接收，业务效果仍需独立读取；未核验状态必须可见。
6. **把沙箱验证视为生产执行资格**：真实系统的权限、幂等、回执、补偿和现场限制不同，必须独立设计与验收。

---

**Decision Gate:** 接受本设计仍不授权执行任何 Action；实现签收、可信身份/执行权、M10 版本 Gate 与模拟系统运行边界必须分别满足。
