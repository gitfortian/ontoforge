# POC-Query / POC-Edit 第二轮验证报告

> 日期：2026-10-15；研究性原型，不是产品工程代码。  
> 关联：V1.2 架构基线、研究 PR #6 与第一轮 POC PR #7。  
> **原始源码、自动化测试、PostgreSQL 脚本、日志、完整报告保存在本次对话的 ontology_poc_round2_20261015.zip；ZIP 暂未直接进入 GitHub。**  
> **最重要的限制：当前执行环境没有 PostgreSQL，也无法下载安装；PostgreSQL 运行脚本尚未实测。**

## 1. 本轮到底为了影响哪些决策？

**POC-Query**：验证 ObjectSet 是否能以独立于存储的类型化查询表达，完成多跳制造质量追溯、跨表关联、对象级去重聚合、分页与安全过滤；并为之后选 PostgreSQL、图、搜索或混合方案明确可运行的对照条件。

**POC-Edit**：验证来源事实、运营编辑、对象当前状态与外部业务系统回执可以分离，Action 在审批、函数和政策版本变化、HTTP 断开、远程已接受但本地崩溃、进程恢复、重复提交及源事实冲突下有明确且可恢复的状态。

## 2. 已实际执行的验证结果

| 实验 | 条件 | 结果 |
| --- | --- | --- |
| 四跳 ObjectSet 语义与查询权限 | Python 3.13.5 + SQLite 3.46.1 内存库 | **7/7 测试通过** |
| 受控编辑与远端回执 | 两份持久 SQLite DB + 独立 HTTP ERP 进程 + Worker 崩溃/重启 | **17/17 测试通过** |
| PostgreSQL ObjectSet | PG17 DDL、合成数据、RLS、EXPLAIN、PowerShell 脚本 | **未执行** |
| PostgreSQL Outbox | 候选 DDL、SKIP LOCKED Claim 片段 | **未执行** |

### SQLite 四跳查询基准

路径为 QualityEvent → MaterialLot → ProductLot → WorkOrder → CustomerOrder，含两张 Link 表、重复路径、workspace 与可见性过滤。先按订单唯一 ID 去重，再聚合金额。三次预热、十次重复的本轮内存 SQLite 实测如下（单位 ms）：

| 批次/订单规模 | 授权后的受影响订单 | 首次分页 median / p95 | 去重聚合 median / p95 |
| ---: | ---: | ---: | ---: |
| 5,000 / 5,000 | 753 | 1.238 / 1.373 | 1.265 / 1.324 |
| 20,000 / 20,000 | 3,011 | 5.320 / 5.567 | 5.265 / 5.332 |
| 50,000 / 50,000 | 7,529 | 15.493 / 15.814 | 15.322 / 18.021 |

以上数据**不代表 PostgreSQL 或生产环境性能**，无网络、磁盘、真实业务负载、独立权限服务和多用户并发。

### 独立 HTTP/Outbox 故障实验

- Proposal 与 Outbox 排队使用本地原子事务；禁止自我审批、拒绝相同幂等键的不同 payload。
- Worker 进程在领取任务后、真正调用 ERP 前退出：持久 Lease 到期后恢复，同一 Key 重试。
- ERP 已在独立数据库提交回执但断开 HTTP：本地 UNKNOWN；查询回执后 VERIFIED。
- Worker HTTP 发送成功却在写本地回执前退出：再次领取前对账，ERP 仍只有一条已提交记录。
- 并发领取只允许一个 Worker 获取；Function/Policy/Schema 变更可阻断派单。
- 已验证运营编辑遇来源追平显示 SOURCE_CAUGHT_UP；若来源新版本与编辑冲突显示 SOURCE_CONFLICT/UNKNOWN，不无条件覆盖源。

**严格前提**：模拟 ERP 自行实现了同 Key 幂等和回执查询。真实 ERP 如果不保证同样合同，不能直接照搬自动补发。模拟 ERP CONFIRMED 只意味着其持久账本确认，不代表真实业务状态核验。当前 Outbox 使用 SQLite，不证明 PostgreSQL Exactly Once、多节点公平性、可靠调度或真实外部事务一致性。

## 3. 可以进入 Native V0.2 / ADR 的架构不变量

1. ObjectSet 是独立、可组合、延迟执行的 Typed Query AST；逻辑查询与物理执行计划分层。
2. 多跳关联的金额等指标必须先按业务对象唯一身份去重，关系路径数是另一种指标。
3. 工作空间/对象/属性/边的授权需要在查询执行和聚合中间结果中执行；现有 SQLite 仅模拟过滤，生产 ABAC/RLS 待验证。
4. SourceFact、EditIntent/Overlay、EffectiveObject 与 ExternalReceipt 必须保持独立生命周期和权威边界。
5. Action 执行必须有自己独立的审批、版本锁定、执行状态、幂等键和外部核验；Function 逻辑成立不代表 Action 被授权。
6. 已派单结果未知时保留 UNKNOWN；来源冲突时拒绝以编辑覆盖权威源新事实。

## 4. 尚不可冻结的重大选型

| 决策 | 未完成证据 | 后续门禁 |
| --- | --- | --- |
| PostgreSQL 是否足够支撑 Object Runtime | 当前只有 SQLite，PG 没有 EXPLAIN 实测 | 同样数据在 PG17 多跳查询、大小规模、并发/RLS、p95/p99 计划和索引代价 |
| 是否增加图/搜索引擎 | 没有统一查询与授权负载的候选对比 | 先测出 PostgreSQL 实际瓶颈，再有根据地比较 |
| PostgreSQL Outbox 生产锁/租约 | SQLite 只支持该模式的协议可行性 | 真实 PG SKIP LOCKED、多 Worker、随机 Kill、重启恢复 |
| 真实 ERP/QMS 幂等与业务核验 | 模拟回执不能当外部事实 | 外部沙箱真实回执/重复提交/对账合同 |
| 权限与决策重建 | 缺生产鉴权服务、导出传播、完整 Semantic Execution Record | 跨索引/查询/Action/MCP 权限回归，版本化决策审计 |

## 5. Windows 11 本机复现

源码 ZIP 中包含以下脚本。

- 不需要数据库：在 experiments/pg-objectset 运行 python test_query_local.py -v，在 experiments/outbox-http 运行 python test_outbox.py -v。
- Docker Desktop：在 experiments/pg-objectset 运行 .\run.ps1 -Lots 20000。
- 已安装 PostgreSQL：先创建**可丢弃的专用数据库** ontology_poc，再运行 .\run_existing_pg.ps1 -Database ontology_poc -Lots 20000。
- 按 20k、50k、200k 逐步扩大数据集，保存 PostgreSQL 的 EXPLAIN ANALYZE JSON、实际耗时、机器参数、索引和权限开销。**未执行的 PostgreSQL SQL 不得标记为通过。**

## 6. 对已合并 V1.2 的影响

Palantir 对标、Native Ontology 核心、OWL 可选互操作的产品路线保持不变。推荐把 Query AST/PhysicalPlan 分离、ObjectSet 对象去重、SourceFact/EditOverlay/ExternalReceipt、Action UNKNOWN 与 Function/Policy 版本校验列为 Native V0.2 的强制工程不变量。

**本轮可以冻结原理层，不可冻结 PostgreSQL vs 搜索/图的物理存储选型，也不可宣布 Action Runtime 已具备生产级 Exactly Once。**