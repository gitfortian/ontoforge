# 原生企业本体 ObjectSet / Action：架构选型收口及冻结提案

> 版本：研究 V0.1 · 2026-10-15
> 状态：**待架构评审签收；不是正式产品部署/容量验收**
> 所依据的顶层基线：已合并《本体产品功能架构与技术架构设计 V1.2》（Native Ontology 权威、OWL 可选互操作、参考 Palantir 企业运营本体的产品抽象）。
> 代码：本研究 PR 的 experiments/pg-runtime（基于 PR #8，另含最后一轮高扇出与故障竞态脚本）。
> 最后一次真实通过的证据：[GitHub Actions Run #38681607499](https://github.com/gitfortian/ontology-system/actions/runs/38681607499)，PostgreSQL 17.11，两组矩阵 **均为 30/30 测试通过**；每组均上传原始 JSON 查询计划 Artifact。
> 追溯：本 PR 为依赖 PR #8 的**堆叠研究 PR**。先合并 #8，再将此 PR 的目标分支调整为 main；在 #8 未合并前，不应错误地认为 PR #9 可单独依赖 main 应用。

## 0. 结论摘要：冻结核心分层，不冻结无限容量

**拟签收的架构基线**：

1. Native Ontology Language 负责业务对象/关系/能力契约；Data Binding 负责源字段与对象身份的映射、来源权威、同步/索引版本。
2. ObjectSet 保持业务层**类型化、Lazy、可组合的 Logical Query AST**；其 Physical Plan 独立编译到具体引擎。不能把 ObjectSet API 退化成进程内对象列表或与某种 SQL/Graph 查询语法直接耦合。
3. PostgreSQL 进入 **P0 默认关系对象索引/元数据/运营编辑/事务 Outbox 存储方案**（可随规模和真实负载演进）；但单实例 PostgreSQL **不是全行业、亿级对象及高扇出所有查询的永久唯一引擎**。
4. 外部 ERP/MES/QMS 是各自业务领域事实的权威来源；本体本地保存可重建对象/链接投影与单独的已核实运营编辑，**不能因为投影存在就把它当成源系统已经改变**。
5. Function/Decision 产生计算或拟议变更；Action 必须独立管理审批、版本签名、对象范围、目标级竞争、派单、外部回执、UNKNOWN、对账与结果核验。推理结论不能直接成为执行业务命令的许可。
6. 查询与执行均应保留对应 Schema、Binding、SourceCheckpoint、Function、Policy 和 Action 版本证据；Semantic Execution Record 是跨 Query/Decision/Action 的长期合同，不强制其全部运行时在 P0 一次开发完毕。

这些原则可冻结进入 Native DSL/AST V0.2 / ADR，但**本 POC 没有证明生产级 Exactly Once、端到端授权、实时权限撤销、可靠外部业务状态或亿级性能**。

## 1. 本轮实际测试了什么

### 1.1 真实环境和工作负载

- PostgreSQL **17.11**（GitHub Actions 临时容器），Python 3.12/psycopg 3，独立 HTTP MockERP 进程和专用 SQLite 持久回执库。
- **基础四跳质量追溯**：QualityEvent → MaterialLot → ProductLot → WorkOrder → CustomerOrder，原 PR #8 的基础查询/编辑回归在两组都运行。
- **本轮高扇出专用关联**：QualityEvent → ProductLot → FanoutLink → CustomerOrder（与基础四跳查询**不是同一 SQL**）。合成高扇出链、受限链接/对象和多路径重复事实，测试先按对象身份去重再做订单金额汇总。
- 两组独立 Runner/Job：
  - **100,000** 产品批次、**800,000** 条新增 FanoutLink（平均 8 条/产品批次）。
  - **250,000** 产品批次、**6,000,000** 条新增 FanoutLink（平均 24 条/产品批次）。
- 本轮每组共 **14 项 PR #8 基础回归 + 16 项最后一轮门禁 = 30/30**。合计 60 次通过执行，**不是 60 种独立测试用例**。
- 每个高扇出性能查询：**1 次预热 + 5 次 EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)**，以下 p50/p95 仅是极小样本的统计，不应把 p95 当成可生产承诺的尾延迟。

### 1.2 已实测的 PostgreSQL 指标（ms）

| 负载 | RLS 后聚合 p50 / 样本 p95 | 显式过滤后聚合 p50 / 样本 p95 | RLS 首次分页 p50 / 样本 p95 |
| --- | --- | --- | --- |
| **10 万批次 / 80 万 Link** | **324.141 / 347.486** | **267.242 / 268.124** | **251.833 / 253.278** |
| **25 万批次 / 600 万 Link** | **1200.040 / 1224.011** | **1049.333 / 1076.658** | **1040.214 / 1065.413** |

10 万负载的 RLS 聚合查询最后一轮为共享缓存命中约 211,238 个 Blocks、共享读取 0，临时写块 0。25 万负载的聚合查询共享缓存命中约 1,231,008 Blocks、共享读取约 57,742 Blocks，并出现约 5,030 个临时写块；其首次分页也出现临时写入。

**解释与边界**：

- **第一**：PostgreSQL 能真正执行我们指定的跨关系筛选、去重、分页、金额聚合及角色/RLS 控制，证明**P0 实现可行性**，不是“只是 SQL 脚本写出来了”。
- **第二**：即使第一页只返回 25 个订单，在高扇出下数据库仍需做大范围关联、去重及排序。因此 **Lazy ObjectSet** 只保证应用层不提前加载集合，不自动等于低物理扫描成本。应设计 COST_BUDGET、TIMEOUT、INDEX_AVAILABILITY、查询计划监控、可选择的物化/预计算。
- **第三**：显式过滤与 RLS 结果被自动校验相等；它们采用不同执行角色和优化路径，**不能把两种耗时差解释成“PG RLS 的纯额外开销”**。本轮可确认的是复杂授权查询有实际执行成本；真正的 RLS 成本分解和生产级 ABAC 压力测试仍待做。
- **第四**：10 万和 25 万并非同一 Runner、同样硬件状态、相同扇出；且后者出现磁盘缓存/临时盘流量。不能用两组时间直接拟合线性扩展曲线或给出部署容量 SLA。
- **第五**：链接关系是人为生成的相对均匀分布，不含真实工厂热点、超级节点、时序窗口过滤和流式索引负载。尚未对其他 SQL/搜索/图引擎做同口径基准。

## 2. 授权校验：正确性与查询代价必须同时设计

本轮在 PostgreSQL 的真正非管理员 Reader Role 中执行 RLS：
- 工作空间隔离：同一 SQL 在 A 与 B 的范围不同，权限敏感数据不跨工作空间流出。
- 对象及边的 visibility=PUBLIC/RESTRICTED 在多跳查询每一层执行；高权限查询范围扩大，低权限不能看见受限对象/链接。
- Finance Role 与普通 Reader Role 通过真正 PostgreSQL 列权限隔离订单 amount_cents；未授予该列的用户执行聚合应失败，不能以“前端隐藏金额”替代。
- 对全部对象/Link 明确编写等价的 workspace/visibility 谓词，和 RLS 结果作总数及金额一致性校验。
- 不同数据库角色、多并发请求相互隔离。

**未完成 / 不允许签收生产授权**：

- 本 POC 的工作空间和 clearance 由一个可信连接以 Session GUC 注入，数据库连接使用可切换角色的实验账户；如果给不可信用户直接开放连接/修改 GUC，则权限可能被伪造。这是实验边界，**不是生产认证/租户隔离方案**。
- 还缺真正 ABAC/Policy Engine 的身份声明校验、可复用连接池中的上下文清除、实时撤权、敏感数据推导/聚合旁路、小群体计数侧信道、权限传播到 Agent/Export/Vector Cache。
- 未来对 ObjectSet 的集合运算、跨源 Join、Function、导出须统一策略，不能只在分页结果返回前最后做一次过滤。

## 3. 多 Worker / 外部写回：本轮真正修复与验证的事故风险

### 3.1 本次新增的生产风险发现

1. **相同 Idempotency Key 并发撞入**：请求完成第一次读取后可能都发现没有记录；如果只依赖 UNIQUE，后来的请求会得到数据库错误而不是稳定返回同一 Attempt。现用 ON CONFLICT DO NOTHING + 读取胜出版本，验证 12 并发请求得到同一个 Attempt，不重复产生 PROPOSED 事件。
2. **不同 Key 操作同一业务目标**：不同幂等键不能阻止同时对 LOT-001 执行两个不同 Action。新增按 workspace+lot 的事务级 advisory lock、已在途 Attempt 检查；在入 Outbox 之前拒绝第二个冲突目标操作，并验证两路并行 Queue 只有一路成功。真实产品应由动作 effectScope 决定冲突粒度，不能永久硬编码到整批次。
3. **租约过期时旧 Worker 继续派单**：只比对 ClaimToken 不够，还必须比对有效 Lease；过期/被盗领的旧 token 不能开始新的外部派单。
4. **旧 Worker 迟到错误覆盖新 Worker**：Worker A 失去租约后，网络错误晚到，可能把 Worker B 正在执行的状态改为 UNKNOWN。新增状态更新上的数据库行锁 + 当前 ClaimToken fencing；模拟两名 Worker 接力，迟到错误被拒绝。
5. **远端成功但本地收到迟到失败**：VERIFIED 是已核实回执状态，迟到的网络异常不得改成 UNKNOWN；条件更新会忽略已终结成功记录。
6. **远端已确认而发送方崩溃**：继承 PR #8 的独立子进程异常退出用例；新 Worker 先用原 Key 查询远端持久回执，再决定是否重发；模拟 ERP 最终只保存一条业务命令。

### 3.2 本次真正运行的典型断言

- 12 并发相同幂等键、不同请求主体复用冲突、不同 Action 同一目标竞争；
- 旧租约主动过期、被新 Worker 接管、旧 token 再派单拒绝、迟到 UNKNOWN 变更拒绝；
- 14 个不同目标由多个 Worker 抢占，全部能完成 14 次确认且没有同 Key 重复落账；
- Function/Policy/Schema/Binding 与 SourceRevision 漂移在 Action 入队/派单阶段拒绝；
- HTTP 不可达时保留 UNKNOWN；未知状态不自动变成失败，也**不能在尚无端到端协议证明前自动盲目重试**；
- 来源数据追平/来源新版本冲突被分别识别，SourceFact 不能被 Operational Edit 无条件覆盖。

### 3.3 故障实验不能被过度宣传

- 远端是一个**独立 HTTP MockERP**，其持久回执用 SQLite 实现并自行承诺相同 Idempotency Key + Payload 的去重与回执查询。真实 ERP/WMS/MES 的接口可能没有这两个能力。
- ClaimToken fencing 在本地状态更新和发送**之前**生效，**不能跨 HTTP 边界实现原子的分布式事务**。旧 Worker 已发出请求之后才失去租约，远端仍可能执行；靠业务幂等、最终回执、对账和必要的人工补偿。
- 已核实的是**模拟端“接收方持久确认”**，不是现实的物料冻结已经完成。外部 Receipt 与业务 Outcome Verification 必须分离。
- UNKNOWN 不能永久卡住：必须建设后续专门的对账任务、超时策略、人工复核/补偿流程。这是生产 Action 的**未签收项**，不能因为本轮用例通过就跳过。

## 4. 正式架构冻结建议（带条件）

| ADR / 能力 | 建议状态 | 能冻结什么 | 明确保留的扩展和未决 |
| --- | --- | --- | --- |
| A. Native Ontology 权威 | **保持 V1.2** | ObjectType/LinkType/Function/Action/Policy 原生概念；OWL 作为标准适配 | Native V0.2 具体 JSON Schema、迁移合同另行评审 |
| B. ObjectSet Language | **原则可冻结** | 类型化逻辑 AST、Lazy 组合、Auth Pushdown、Identity 去重、解释/预算 | 查询优化器、缓存、物理引擎、物化策略不能固定死 |
| C. P0 基础存储 | **选 PostgreSQL 作为初期默认候选** | Registry、Binding 元数据、关系对象投影、编辑/Outbox 事务可共用基础设施 | 不保证大规模高扇出 SLA；允许后续搜索/图/时序适配 |
| D. 数据权威与同步 | **原则可冻结** | SourceFact、IndexSnapshot/Watermark、BindingVersion、EditOverlay、ExternalReceipt 分开权威 | 增量 CDC、双向写回冲突、漂移、跨源身份还须专项实测 |
| E. Action 执行 | **协议可冻结，生产不可签收** | 审批/函数政策版本锁定、事务 Outbox、Claim+Lease+Token、幂等、UNKNOWN+回执对账 | 真实外部幂等合同、租约竞态全覆盖、撤权、手工恢复、批量操作 |
| F. Permission / Policy | **基础强制，生产未签收** | 对象/Link/属性中间计算和聚合必须受授权 | 生产级不可伪造主体、ABAC、Derived Data Label、撤权/审计 |
| G. Decision & Evidence | **作为产品总方针** | 语义事实→确定性 Rule/Function→DecisionStrategy→Policy→Action→可重建证据 | 本 POC 尚未实现完整 Semantic Execution Record / Replay |
| H. P0 初始工程 | **不另起图/搜索集群** | 原生 API 与查询引擎接口保留可替换性，先构建小而正确的运行闭环 | 达到明确可测性能/使用场景门槛后再引入新物理引擎 |

**应当特别避免的错误结论**：不能因为 600 万关系在单机 PostgreSQL 中有约 1 秒查询，就认定产品达到任何正式业务 SLA；也不能因为有约 1 秒查询，就直接断言必须改成图数据库。首先需要知道**用户需要的 SLA、对象规模分布、过滤选择率、缓存命中、成本和授权策略**，再决定是否改变物理实现。

## 5. 后续真正的工程签收门禁

1. **P0 Native DSL/AST V0.2**：对 ObjectType、Property、LinkType、BindingRef、TypedQueryAST、QueryResult Evidence、ActionEffectScope、FunctionReleaseDigest、SourceAuthority/Completeness/FactStatus 建立 JSON Schema、迁移矩阵与负例；避免一步到位做完所有引擎。
2. **P0 Query 运行时**：PostgreSQL + 物化/索引策略；定义产品预期负载及用户体验 p95/p99，增加有选择性/无选择性过滤、超级节点、热点订单、冷缓存、持续写入、50/100 并发及索引构建/回滚测试。先测实际瓶颈再比较专业图/搜索方案。
3. **P0 Auth Gate**：非超级用户应用主体、受控事务范围的租户上下文、连接池复用、跨角色切换、撤权、聚合侧信道、派生结果与导出/Agent 边界；真正的 AuthorizationContext 不能是用户可写字符串。
4. **P1 Binding / Fact Projection**：ERP/MES/QMS Schema 漂移、来源时点不一致、Identity 冲突、索引 Watermark 与回滚、SourceAuthority 失效、UNKNOWN 与 PARTIAL 返回。
5. **P2 Action 真实沙箱**：使用目标 ERP/QMS/MES 沙箱（不是本 POC Mock），验证同 Key 幂等、回执状态、业务结果核验、前后时点及失败补偿。没有支持幂等/可查询回执的系统，必须设计保守的非自动重试与人工恢复策略。
6. **P2 决策审计**：模型版本、SourceSnapshot/Watermark、Rule/Function、DecisionStrategy、Policy、审批主体、操作意图、外部指令、回执、业务 Outcome、对账，形成 Semantic Execution Record。可审计/可解释不等于能严格重演物理外部动作。

## 6. 最终产品架构选择（待批准）

**一句话：先以 PostgreSQL 建设原生运营本体的“可重建对象/关系投影 + 类型化 ObjectSet Query + 受治理 Action Outbox”，同时把权威业务事实、决策计算和外部行动回执明确分开；任何物理存储和执行器的能力上限，都必须通过同口径实测证明后才扩展。**

本轮只证明当前样本、当前权限和异常机制能运行并具有明确的执行代价。正式研发和产品 SLA、工业级授权与远端业务安全仍需后续工程验收。本文件是**可供签收的架构研究提案**，不对外宣称生产系统已经完成。
