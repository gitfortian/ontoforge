# PostgreSQL 17 ObjectSet / Transactional Outbox 真实运行研究与选型门禁

> 时间：2026-10-15
> 研究定位：原生企业运营本体 **架构风险 POC**，不是生产级架构定稿或正式产品功能。
> 来源：本 PR 中 experiments/pg-runtime 的 **可运行源代码**与 GitHub Actions 实测运行记录；不同于之前仅提供 ZIP、GitHub 不含实验源码的报告。
> 已验证运行：[GitHub Actions Run #38677635013](https://github.com/gitfortian/ontology-system/actions/runs/38677635013)（PostgreSQL 17.11；20k / 100k 两个 Job 都通过）。
> 证据和完整执行计划：上述 CI 的两个 PostgreSQL 实测 Artifacts，分别包含 JSON EXPLAIN ANALYZE 执行计划与定量记录。历史试错的一次 CI 首先暴露 psycopg 参数化 SQL 取模转义问题，已经修复并重测。

## 0. 为什么需要这次实测

已合并的本体架构 V1.2 确立 Native Ontology、Data Binding、ObjectSet、Function、Action 的产品路线。前两轮 SQLite/HTTP POC 支持「逻辑对象集合」「来源事实与编辑分离」「UNKNOWN/幂等对账」的初步设计。但**尚未证明** PostgreSQL 实际可以查询或在其事务中完成并发 Outbox。

本轮要回答两类工程问题：

1. **POC-Query**：PostgreSQL 真实执行 ObjectSet 多跳追溯、对象去重聚合、分页、RLS 和列权限的最小实现是否正确？规模增大时执行计划和时延表现如何？由此能否把 PostgreSQL 作为 P0 对象运行时的**首选基线候选**？
2. **POC-Edit**：PostgreSQL 的事务提交、FOR UPDATE SKIP LOCKED、多 Worker、崩溃前后执行回执能否组成可运行的可靠派单协议？哪些故障和外部接口语义仍需要生产化研究？

## 1. 实验条件及真实自动化验证

- 数据库：GitHub Actions Service Container **PostgreSQL 17.11**（Debian 17.11），不是 SQLite 模拟、也不是口头推断。
- 运行器：GitHub-hosted ubuntu-latest，Python 3.12 + psycopg 3，独立 HTTP MockERP 进程及其持久 SQLite 回执库。
- 数据：使用 SQL generate_series 生成完全合成的工业对象。质量事件→物料批次→产品批次→工单→订单，多跳关系存在额外重复路径。
- 安全：poc_object_reader 与 poc_finance_reader 为不同 PG 角色；表启用强制 RLS，按照 workspace_id 与 visibility 过滤对象、链接；amount_cents 只授权财务角色。
- 工作负载：20,000 与 100,000 ProductLot，每个规模生成同数量 CustomerOrder 及相应工单/关联；单机独立 CI Job。
- 验证：**每个规模 14/14 项测试通过**（5 项 ObjectSet + 9 项 Action/Outbox），合计 **28 次通过记录**，并非 28 个不同测试案例。
- 查询采样：每种 SQL 的 EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) 前有 1 次预热，每个 Job 记录 8 次实际 Execution Time，计算中位数和样本 p95。不同 Job 硬件/缓存条件不保证完全相同。

### 1.1 实际 PostgreSQL 查询结果（单位 ms）

| ProductLot / CustomerOrder | 首次分页 p50 | 首次分页 p95 | 去重聚合 p50 | 去重聚合 p95 | 页查询共享缓存命中 | 聚合共享缓存命中 |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 20,000 / 20,000 | 26.267 | 26.451 | 31.4415 | 31.7067 | 1,816 blocks | 1,964 blocks |
| 100,000 / 100,000 | 89.9845 | 91.5176 | 112.48 | 113.8458 | 11,066 blocks | 11,802 blocks |

两组 EXPLAIN 最终的 Shared Read Blocks 均为 0，代表**记录的这些热缓存样本主要命中内存**。这些是 PostgreSQL 执行器报告的 SQL Execution Time，不包含网络往返、JSON 解析、业务规则计算、IndexBuild、权限服务、用户界面及端到端 Agent 工具开销。

**架构上重要的负面发现**：本次查询即使只加载 25 个对象，也需要先将多跳关联结果聚合/去重，并排序取得第一页。执行计划展示 Hash/Aggregate/Sort/Limit 节点。因此「ObjectSet 延迟执行」不等于「只扫描 25 条记录」，还需要查询重写、物理计划控制与索引/物化选择。

### 1.2 实测安全与查询边界

- workspace A 的对象不能穿越到 B，读取级别 PUBLIC 与 RESTRICTED 的对象数量不同。
- 未拥有财务列 SELECT 权限的 Reader 对订单金额聚合被 PostgreSQL 拒绝；财务角色可以执行。
- 按唯一 CustomerOrder ID 去重，避免同一订单通过多条关联关系被重复计金额。
- Keyset 分页游标纳入 workspace / clearance / generation / schema / filter / last ID；校验不同 workspace 或 generation 无法复用。
- 4 个并发读请求的分页结果一致。

**不应过度理解**：固定 SQL 路径并不是完整的 Native Typed Query AST 编译器；使用 session GUC 驱动 RLS **不能直接用于让不可信终端用户连接 PostgreSQL**。该实验验证 PG 行/列保护机制及基本查询行为，不是生产 ABAC、撤权、查询防侧信道或数据派生传播的终验。

## 2. PostgreSQL Transactional Outbox 实测结果

### 2.1 真实执行的操作闭环

ActionAttempt(PROPOSED→APPROVED→QUEUED) 与 ActionOutbox 在**同一个 PostgreSQL 事务**内产生。外部提交不发生在数据库事务中。多个 Worker 使用 FOR UPDATE SKIP LOCKED 竞争领取，登记 Lease、Claim Token 与领取次数，再经独立 HTTP ERP 模拟服务提交带幂等键的 HOLD 命令。

验证过以下情形：

- 提议者不能自行批准 Action，未经审批的动作无法进入 Queue。
- 8 个 Worker 并发竞争 4 个可领取动作，每个动作只被一个 Worker 领取。
- Worker 领取后、发出 HTTP 前以 os._exit() 模拟实际进程崩溃；Lease 过期后新 Worker 接管。
- 独立 HTTP ERP **已经保存确认回执后**，Worker 在保存本地状态前崩溃；新 Worker 先查远端回执，避免再次落账。
- 外部系统暂时不可达时 Action 保留 UNKNOWN，不把异常映射为业务失败，也不创建假成功编辑。
- Function/Policy/Schema/Binding 版本或 SourceRevision 在批准后发生变化时，Queue/Dispatch 阶段拒绝继续。
- 真实模拟业务回执 VERIFIED 后才记录 EditOverlay；后续来源事实追平展示 SOURCE_CAUGHT_UP，来源反向变化展示 SOURCE_CONFLICT。

### 2.2 对一致性架构的影响

可作为下一步正式 ADR 的**设计依据**：

1. SourceFact、OperationalEdit、EffectiveObject 与 ExternalReceipt 各自拥有权威范围、版本和状态。
2. Function/Rule/Decision 形成判断或修改意图，但不可直接代替 Action 的独立授权和派单。
3. ActionAttempt/Outbox 与事件日志应以同数据库事务提交；物理运行时通过多 Worker Claim/Lease/Token 管理。
4. 远端超时、接收方已提交但发送方未知，是一个独立 UNKNOWN 状态；对账优先于自动重试。
5. 生产重发之前必须确认 ERP/QMS 有稳定的幂等键、相同 Payload 冲突拒绝和可查询回执的合同。

**非常重要**：本轮使用的是独立进程运行的 MockERP，而远端持久账本仍是 SQLite；它保证研究性同 Key 去重，不证明实际工业系统有相同接口或业务生效语义，也不构成跨系统 Exactly Once 保证。

## 3. 新获得的选型证据与不能冻结的部分

| 决策 | 目前可以说的结论 | 不能做的推断 |
|---|---|---|
| ObjectSet 的物理查询能力 | PostgreSQL 17 能正确运行已定义的四跳关系、过滤、去重、分页和聚合，20k/100k 两组实测可重复 | PostgreSQL 必然优于图数据库或搜索引擎 |
| P0 存储路线 | **推荐 PostgreSQL 作为 P0 基础候选**：Schema Registry/Binding/事务编辑及首版对象关系投影可集中验证 | 不经索引/物化/更新吞吐测试就承诺亿级运行能力 |
| 查询内 RLS/列权限 | PG 已执行真实 RLS 和列授权负例 | 已完成完整生产授权、字段派生传播、撤权和旁路防护 |
| Action Outbox | PG 事务出队、多 Worker 与失联/崩溃对账协议跑通 | 无需校验外部系统幂等、可随意重试、达到 Exactly Once |
| Runtime 一致性 | 事实/编辑/回执/状态追平与冲突机制证明可实现 | 已完成多地域系统、动态时态事实和真实外部事务一致性 |
| 产品总架构 | Palantir 风格 Native Ontology 仍成立 | 本轮足以冻结所有后续 Function/Decision/Action 元模型 |

**推荐先把 PostgreSQL 作为初期对象/关系读模型与运营编辑存储的 P0 默认验证基线，保留 ObjectSet 与物理执行引擎之间的编译/适配边界。** 是否要增加搜索/图/时序引擎，取决于未来实际查询模式、规模、性能和成本证据，而不是早期盲目引入。

## 4. 必须继续验证的高风险门禁（优先级从高到低）

- **G1 Query Planner**：1m/10m 对象、多源 Link 扇出、5–8 跳、带时态条件与多维过滤；冷缓存和多读写并发、可控 p95/p99、索引占用和刷新延迟。
- **G2 Auth**：真正服务主体和字段/对象/Link/数据派生的策略组合、租户上下文不可伪造、连接池复用、权限撤销后游标/缓存失效。
- **G3 Outbox**：多个 Worker 在源版本变更与 Lease 到期竞态时，对远端回执、Claim Token 和副作用重放做完整竞态覆盖；不可使用永久不可回收的 UNKNOWN。
- **G4 External Effects**：接入真实 ERP/MES/QMS 沙箱及适配器，验证幂等合同、Command Receipt 与业务状态确认是两层不同的证据。
- **G5 Semantic Execution Record**：绑定已生效模型、数据快照、Rule/Function、DecisionStrategy、Policy、ActionDefinition、调用者、审批者、回执和结果对账；完整决策可追溯不等同物理世界严格可重放。

## 5. 评审建议

**建议同意**：原生本体产品方向不变，PostgreSQL 进入 P0 可实施基线候选；将 ObjectSet 逻辑查询与执行策略独立、行动编辑层、幂等/回执对账及 UNKNOWN 作为接下来 DSL/AST V0.2 与 ADR 的强制原则。

**暂不签收**：PostgreSQL 作为任意规模下的唯一存储、生产强一致写回、跨系统 Exactly Once 和工业 Agent 自动执行权限。

本 PR 包含代码和自动化工作流，下一位评审可以直接复现以上可证明结论，而不需要依赖聊天附件。