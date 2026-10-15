# POC-Query / POC-Edit：架构风险验证报告 V0.1

> 日期：2026-10-15
> 状态：**已实际运行的可抛弃研究性 POC**；不代表本体产品内核实现、生产环境性能或安全认证。
> 技术条件：Python 3.13.5、标准库 SQLite（内存）、MockERP 进程内模拟，单进程，无真实 ERP/MES/QMS、PostgreSQL/搜索/图引擎和网络故障实验。
> 与架构关系：遵循已合并 V1.2 中 Native Ontology + ObjectSet + Data Binding + 受控 Action 的方向，不修改 V1.2/原 DSL/AST。

## 0. 验证结论

**这轮已经实际验证的原则**：ObjectSet 可以表示为不可变、延迟执行的类型化查询；对象/关系权限在查询内部生效；聚合先按对象身份去重；分页游标绑定主体、权限、查询和索引代际；Action 的预览不应发送副作用；审批绑定 Function/Policy/Schema/Binding 的版本；远端超时要保留 UNKNOWN 并用相同幂等键对账；经核实的运营编辑与源事实分开保存，冲突明确暴露。

**尚无证据支持的结论**：哪种生产物理存储最优、千万级对象/多跳图查询性能、分布式 exactly-once 写回、真实网络故障恢复、多实例并发一致性、企业级 ABAC 和端到端数据治理授权。不可用单机 SQLite 的毫秒数取代生产 ADR。

本次完整源代码、测试与脚本以对应评审对话的 `ontology_runtime_pocs_20261015.zip` 作为**独立实验附件**提供（此 PR 仅保存结论与方法，实验代码尚未纳入仓库；因此本 PR 的 CI 不会自动执行这批测试）。

## 1. POC-Query：验证对象集合及查询架构

**模型与数据**：合成 ProductLot、MaterialLot、CustomerOrder、QualityEvent；其中已经可执行的查询关系是 `ProductLot --affectsOrder--> CustomerOrder`，不是任意深度图遍历。每个产品批次关联两个订单，部分批次和关系标记 restricted；额外有质量事件及物料批次表，用于扩展研究样本。另模拟两个 workspace、source completeness、schema release、binding version、index generation。

**可运行查询**：`ObjectSet().filter_quality('HELD').traverse('affectsOrder')` 再执行分页或计数/金额聚合。ObjectSet 的构建阶段不预载大量对象；SQL 执行时包含 workspace、lot、link 和 order 标签过滤；金额聚合需额外 finance 权限；不同路径到达同一订单时按订单对象 ID 去重后计数或求和。

**已运行的测试结果**：**9/9 通过**。覆盖对象和边权限、跨工作空间、金额访问/聚合、游标签名/范围/索引代际失效、来源 PARTIAL 标记、非法查询输入、关联去重以及合成工业数据结构。

### 1.1 定量实测

单进程内存 SQLite / Python 3.13.5；首次执行预热后重复 30 次，记录毫秒级 median 与 p95；无磁盘与网络基准，无 PostgreSQL/图数据库对比。

| 产品批次 | 客户订单 | 关系条数 | 首次分页 median / p95 (ms) | 去重 Count median / p95 (ms) |
| ---: | ---: | ---: | ---: | ---: |
| 1,000 | 2,000 | 2,000 | 0.266 / 0.357 | 0.267 / 0.385 |
| 5,000 | 10,000 | 10,000 | 1.576 / 1.871 | 1.658 / 1.905 |
| 20,000 | 40,000 | 40,000 | 7.542 / 8.799 | 8.062 / 9.540 |
| 50,000 | 100,000 | 100,000 | 21.451 / 22.759 | 23.940 / 25.003 |

**对架构有价值的发现**：最初由 SQLite 自行选择的联表起点出现明显低效；固定从已筛选的批次索引进入 Link 和订单索引后，性能显著改善。这是受控 POC 的实验技巧（通过 SQLite `CROSS JOIN` 固定连接顺序），不能替代真实 Query Planner。**因此可以冻结「逻辑查询与物理执行计划分层」，不能据此冻结「SQLite/关系型引擎是最终最优存储」。**

## 2. POC-Edit：来源事实、编辑意图与外部写回

**内部事实**：`SourceFact(workspace, lot, status, sourceRevision)` 与 `EditOverlay` 分离；`ActionAttempt` 绑定 modelRelease、bindingVersion、functionDigest、policyVersion、来源 revision、提交人/审批人以及动作参数与幂等键。

**模拟真实业务流程**：
1. `preview(HOLD)` 完全只读、无远端调用；
2. `propose` 生成可复用的 ActionAttempt，幂等键绑定提交人和请求内容；
3. 独立审批（提议人不得自批）；
4. `dispatch` 前复核 SourceRevision 和模型/映射/Function/Policy 版本，变更即拒绝继续；
5. `MockERP.send` 可能返回 ACCEPTED、在接受后超时，或接受前超时；
6. 本地超时保留 UNKNOWN，不通过新 key 盲重试；
7. `reconcile` 通过原幂等键查询远端状态，只有 CONFIRMED 才建立已核实运营编辑；
8. 源状态后来追平时显示 SOURCE_CAUGHT_UP；若与编辑矛盾，显示显式 CONFLICT。

**已运行的测试结果**：**13/13 通过**。含严格只读预演、拒绝自批/执行越权、重试幂等键、正常编辑→来源追平、两种超时、外部失败、执行前来源冲突、函数/政策版本漂移、事后源事实冲突、工作空间隔离及拒绝乱序来源 revision。

**重要边界**：`MockERP` 是进程内模拟，不证明真实 ERP 支持幂等查询或确定性核验；本 POC 有持久动作状态但**没有真正的事务 Outbox Worker、Lease、多实例乐观锁或重启恢复系统**。进程在写入 DISPATCHING 与远端发送之间崩溃，需要下一轮单独验证派单/幂等恢复，不能声称已实现 exactly-once。

## 3. 可冻结的八项架构不变量

1. Native ObjectType、LinkType 与 Object/LinkFact 实例分层。
2. ObjectSet 是可组合、类型化、Lazy 的 Logical Query AST，不等于内存数组。
3. 过滤/遍历/聚合和结果缓存都必须执行授权；只有前端隐藏字段不够。
4. ObjectSet 基于**对象身份**去重；边的多条路径不能默认重复计金额。
5. 查询成功、事实完整、数据新鲜度是不同维度；PARTIAL/UNKNOWN 不得被误当完整业务判断。
6. Decision/Function 与 Action 分离；Action 审批与 Function/Policy/Schema/Binding 的有效版本绑定。
7. 外部 ACCEPTED、UNKNOWN、VERIFIED SUCCESS 分离，失败必须支持对账而不是自动重试。
8. SourceFact、OperationEdit、EffectiveObject 与 ExternalReceipt 有不同权威/状态和可追溯关系。

## 4. 不可冻结的技术决策和下一轮 POC

| 待定问题 | 本次证据缺口 | 下一轮真实 POC 要求 |
|---|---|---|
| PostgreSQL vs 图/搜索/混合存储 | 只有 SQLite 内存的一跳 JOIN | 同一工业数据集、多跳扇出、千万级对象候选、p95/p99、并发、IO、更新吞吐、权限代价、成本 |
| 分布式 Query / 游标语义 | 未运行多节点、源撤权或缓存 | 源快照、index generation、权限与查询游标跨节点、撤权后即时拒绝 |
| Outbox/一致性/重启 | 远端只是模拟、无事务 Outbox | PostgreSQL + 真实 HTTP Mock ERP + Worker，崩溃点矩阵、延迟回执、乱序及补偿 |
| 决策完整重建 | 只记录最低版本/来源事实 | Native Semantic Execution Record 绑定事实、Rule/Function/DecisionStrategy/Policy 版本、批准人与执行结果 |
| 工业生产行动安全 | 未运行 MES/QMS 沙箱或真实策略 | 真实业务系统沙箱、回执语义、审批、权限撤销、审计不可篡改 |

## 5. 实施建议

**继续按 V1.2 推进，但不要提前锁定物理存储。** 将已验证的 Query AST、权限、事实/编辑层次、UNKNOWN 对账等核心概念写入下一轮 Native DSL/AST V0.2 / ADR。随后优先执行「PostgreSQL 多跳 Query POC」和「真实 HTTP + Transactional Outbox Edit POC」两项升级实验，再签收对象运行时和外部动作一致性的实现选型。

完整研究代码请以本轮实验附件复现；PR 只保存决策证据与明确的实验边界。