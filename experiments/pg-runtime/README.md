# PostgreSQL 17 ObjectSet / Transactional Outbox 实测 POC

**仅用于架构选型实验。不可部署生产、不可连接真实 ERP、不可针对现有业务数据库运行。**

## 研究目标

1. ObjectSet：验证 PostgreSQL 上多跳质量追溯、去重聚合、分页、workspace/RLS、财务属性隔离和实际 EXPLAIN ANALYZE 查询计划。
2. Action：验证独立于 Function 的受控操作流程、PostgreSQL 事务 Outbox、FOR UPDATE SKIP LOCKED 多 Worker、独立 HTTP 模拟 ERP 的幂等回执、Worker 进程崩溃恢复，以及来源追平/冲突处理。
3. 得到可复现的事实，而不是用 SQLite 毫秒数或脚本存在本身代表 PG 性能验证通过。

## GitHub Actions 验证

工作流路径：.github/workflows/pg-runtime-poc.yml。

此分支 push 或 PR 更新后应启动 GitHub Actions，使用 PostgreSQL 17 Service Container 和 Python 3.12 + psycopg 3。成功后输出的实验记录保存在 experiments/pg-runtime/artifacts/results.json 并上传为 Workflow Artifact。

该工作流以 Matrix 在相同 PostgreSQL 17 镜像上分别运行 20,000 与 100,000 条产品批次规模；workflow_dispatch 将再次运行这两种固定规模。更多规模须调整矩阵并保留独立验收记录。GitHub Actions 成功才代表对应负载实际执行通过。

## 独立实验复现

强烈建议使用一次性的 PostgreSQL 17 容器和名为 ontopoc 的独立临时数据库。配置环境变量：

~~~bash
export POC_PG_DSN="postgresql://postgres:postgres@127.0.0.1:5432/ontopoc"
export POC_LOTS=20000
python -m pip install "psycopg[binary]>=3.2,<4"
python experiments/pg-runtime/run_pg_poc.py
~~~

实验脚本会执行 DROP SCHEMA poc CASCADE，创建仅供实验的两个 PostgreSQL Role，并重建对象数据与 Action 数据表。**严禁指向非 disposable 数据库。**

## 如何解释测试结论

- 查询使用四跳 QualityEvent → MaterialLot → ProductLot → WorkOrder → CustomerOrder，并通过对象 ID 去重防止金额重复计算。
- PostgreSQL 行级安全策略约束工作空间/可见性；只有实验 Finance Role 可读金额列。
- 注意，本实验使用 session GUC 模拟工作空间/权限。若开放 DB 给最终用户自行设置 GUC，就可伪造权限。这不是完整生产鉴权方案。
- Outbox 入队与 ActionAttempt 状态修改位于同一个 PostgreSQL 事务；多个 Worker 通过 SKIP LOCKED 领取，Claim Token 区分过期占用。
- 真实崩溃点通过独立子进程 os._exit 模拟；HTTP Mock ERP 也为单独进程，使用独立持久 SQLite 回执库。
- Mock ERP 保证同一幂等键和完全相同的 Payload 仅成功落账一次。真实 ERP/QMS 若没有同等协议，不能照搬自动重发。
- Mock ERP 的 CONFIRMED 只代表其回执确认，不代表生产系统里的业务状态真正生效。

## 尚未解决的生产决策

- PostgreSQL 与图/搜索引擎同口径大规模、多跳扇出、并发、缓存和容量对比。
- 真实动态多租户 ABAC、字段/对象/关系权限、导出与派生结果安全传播和撤权。
- 实际 ERP/QMS 外部回执协议、真实业务效果核验、多进程 crash matrix 的穷尽覆盖、生产级重试调度与审计重建。
- Transactional Outbox 并不等于跨系统 Exactly Once，也不替代业务补偿和对账。

本 POC 的正确定位是：先签收架构原理与单实例可行性证据，再依据性能和故障表现决定正式运行时技术选型。


## 第三轮：高扇出、实际授权成本、Action 并发竞态

**研究分支**：research/poc-final-architecture-gates-20261015。该分支是基于 PR #8 的独立后续研究，生产产品代码不应复制该实验中的超级用户数据库连接或租户 Session GUC 伪授权机制。

CI Workflow：.github/workflows/pg-final-gates.yml（PostgreSQL 17，独立 Job 的 100,000×8 与 250,000×24 配置）。

在一次性 PostgreSQL 17 实验实例中运行：

~~~sh
export POC_PG_DSN=postgresql://postgres:postgres@127.0.0.1:5432/ontopoc
export POC_LOTS=100000
export POC_FANOUT=8
python -m pip install "psycopg[binary]>=3.2,<4"
python experiments/pg-runtime/run_pg_poc.py
python experiments/pg-runtime/run_final_gates.py
~~~

注意：**第一条 Python 命令会重新初始化并删除旧的 poc Schema，必须首先运行**；第二条命令假定 Schema 和基础合成对象已经存在，额外生成 fanout_link 和独立 RLS/索引。不可指向业务数据库。

第三轮实际验证：
- FanoutLink 数量精确等于 lots×fanout；Query Role RLS 与逐表授权谓词的逻辑结果一致；Finance 金额列受真实 DB 列权限控制。
- 高扇出聚合、有限分页及 Reader/Workspace/Visibility 组合；5 次热样本 EXPLAIN ANALYZE、p50/p95 和完整查询计划保存至 artifacts/final_gates.json。
- 幂等键并发、同目标不同 Action 的入队竞争、租约过期/盗领、旧 Worker 状态更新 fencing、14 个不同目标的并行派单。
- 通过独立 HTTP MockERP 模拟持久幂等回执、崩溃后再对账、SOURCE_CAUGHT_UP 和 SOURCE_CONFLICT。

**安全与可靠性边界**：本实验仅模拟业务接受回执，并不验证真实 ERP 业务效果；连接以超级用户身份建模并使用 SET ROLE 的 RLS 用例不是生产权限架构；不保证任意高扇出查询的 SLA，也不能据此声称跨 ERP 的 Exactly Once。

阅读 [统一架构 V1.3](../../doc/本体产品总体功能与技术架构设计_v1.3.md) 第6、8、13、14章，了解 POC 证据、可采用的 P0 基线和仍不可冻结的生产能力。

## M8.1 · FK/身份域与 CAS 幂等研究前置验证（2026-10-19）

这一实验单独针对 M8.1 的**设计风险**，不读写 OntoForge 产品数据表、不创建 LinkBinding 生产 API，不向 OFIP-0010 提供维护者投票。入口：[run_m81_fk_cas_preflight.py](run_m81_fk_cas_preflight.py) / [专用 PG17 研究 CI](../../.github/workflows/m8-1-fk-cas-research.yml)。

实验范围：在一次性 `ontopoc` 库建立临时专属 `m81_fk_preflight` schema，真实 `READ ONLY REPEATABLE READ` 中读取两端 PK/FK、正确 OUT/IN、NULL 与空字符串、单根孤儿、ONE_TO_ONE 基数冲突；验证不同对象身份域仍允许基于受控 FK→PK 值匹配产生 LinkFact。再在独立**实验元数据表**运行 UPDATE 保存结果未知后的幂等恢复（同键同 digest 返回原 revision、同键不同 digest 冲突、不同键同 expectedRevision 并发 CAS 只允许一个成功），以及 >10k 物理行的 Join 前拒绝条件。

**独立运行前提（严格安全守卫）**：只允许 loopback `postgresql://postgres:...@127.0.0.1:5432/ontopoc`、显式 `M81_DISPOSABLE_PG17=1`，并要求实验 schema 不存在；运行结束删除**仅自身创建的** schema。不得连接业务库或共享数据库。推荐使用 CI 自带的 PostgreSQL 17 service container。代码即使 PASS，也**不证明生产**：应用身份/授权、M5 双端指纹、精确 ObjectSet v2 AST/HTTP、真正 Java transaction boundary、签名 cursor/JWT、React UX 及 Spring Boot E2E 均 **NOT RUN**。该研究不能替代以后 M8.1 纵向生产 PR 的 L01–L18 验收。
