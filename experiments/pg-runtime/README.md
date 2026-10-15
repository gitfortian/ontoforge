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
