# M8.2 · T4 正式 React 工作台与真实 Chromium 交付说明

**日期：2026-10-20；唯一 [Draft PR #103](https://github.com/gitfortian/ontoforge/pull/103)。** 此报告记录本次真实前端纵向研发，不是静态 HTML 原型签收，也不是 T5/M8.2 终审或生产发布。

## 1. 对产品功能的可见增量

生产 React 复用现有 [ObjectSet 主工作台](../../ontoforge-ui/src/features/analysis/objectsets/ObjectSetWorkspace.tsx)，而非额外创建第二个专业工具导航或复制 [UX-High HTML](../prototypes/m8-2-bridge-table-highfi.html)。[LinkTraversalWorkspace](../../ontoforge-ui/src/features/analysis/objectsets/LinkTraversalWorkspace.tsx) 现根据已冻结 LinkType.cardinality，在同一个关系工作台将旧 M8.1 `FOREIGN_KEY` 与新 M8.2 `BRIDGE_TABLE` 严格分流；原 M8.1 的目标单 FK 配置不受影响。

| 用户操作 | 正式 React 的真实操作结果 | 约束 |
| --- | --- | --- |
| 打开工作台 / 选冻结类型 | 通过现有 ObjectSet 路径选择有权访问的 package、Local Snapshot、已冻结 LinkType；M:N LinkType 出现在列表，仍展示双端 M5 Binding 修订与摘要 | 不放开任意来源表或第三 ObjectBinding |
| 选定 M:N 桥表与双列 | 从当前服务端允许列表中选定 `public` Bridge 表、SOURCE FK、TARGET FK；列独立选择且不可相同；`expectedSchemaFingerprints` 来源于最新服务端目录 | 仅受控同源，不允许手写 SQL / 任意 schema / Composite FK |
| 执行 FULL | 浏览器调用真实 `POST /api/runtime/link-bindings/validate`，展示 TOTAL、matched、uniquePair、duplicate Pair、三种 SQL NULL 与三种孤儿，`VALID/VALID_WITH_ORPHANS` 区分 | 失败不显示伪造 0 关联；无 FULL 不能保存 |
| 创建/更新版本 | 发送 `local-link-binding-v2` + 两个冻结 M5 refs + 三个指纹 + `expectedRevision` 与 `idempotencyKey`，调用真实 POST/PUT | 复用 T2 当前 Head CAS/Operation，不因重试创建第二个修订 |
| UNKNOWN/超时恢复 | 网络读取失败时保留相同 operation key，并呈现“使用原操作键重试保存”；出错结果不覆盖当前有效 Revision | 重试原 body，同 key，不误认为已完成 |
| 重开已保存 | 从服务器 GET 实际 LinkBinding Revision，恢复 Bridge 表/双 FK/已存 FULL 质量；不会凭浏览器草稿推断已保存 | 仍由服务器复核第三表当前 SELECT 与结构指纹 |
| ObjectSet OUT/IN | 原 `local-query-v2` UI 发起真实 `POST /api/runtime/object-sets/traverse`；切换方向清除原输出端条件，避免静默放大查询 | 不暴露多跳/任意 SQL；保存前不可查询 |
| COUNT/SUM/PAGE | 复用 M8.1 原页面的排序/投影/有界筛选、唯一对象聚合和签名 cursor 翻页入口；显示 COMPLETE / PARTIAL、KNOWN_SUBSET、相关孤儿 | Bridge 逻辑关系与输出对象均去重，不把 PARTIAL 当完整全量 |
| 三方证据 | 展示 SOURCE、TARGET、BRIDGE 的真实 `sourceReadRefs` 表、schemaFingerprint、读引用；当前 Pair duplicate 计数取自查询结果 | Bridge 为第三事实来源，不是第三 ObjectBinding |
| 375px / 键盘导航 | 桥表双 FK 组合在小屏改为单列；表单支持原生 label、select、focus visible，页面采用 Yak UI token 而非静态原型 CSS | UX 原型仍按已签收 H01–H12 作为完整 T5 回归对照 |

相关样式在 [link-traverse.css](../../ontoforge-ui/src/features/analysis/objectsets/link-traverse.css)，路由、原有 ObjectSet 及 B0 授权机制不改变。

## 2. 用户实际可跑的故事与测试数据

[真实浏览器用例](../../ontoforge-ui/e2e/test_real_m8_bridge_react.py) 用现有 B0 JWT fixture（非伪造前端身份）创建 Native 不可变快照、真正的 M5 双端 Binding，再由 **浏览器**选择新 `cell-quality-bridge`（MANY_TO_MANY）。[隔离来源 fixture](../../scripts/m8-onehop-e2e-source.sql) 为真实 PG17 `m8_cell_event_bridge(cell_code,event_code)` 注入 10 条物理事实，含四条 matched、一条重复 Pair、三类 SQL NULL 和三类 orphan，并为独立只读 SourcePG 角色授予 SELECT。

全流程真实断言：
- 前端选择 `m8_cell_event_bridge` / `cell_code` / `event_code`，FULL 展示 10 行、`VALID_WITH_ORPHANS` 和全部账本名称。
- 用浏览器仅中断**第一次保存 POST 的网络传输**（不 mock HTTP 成功），React 展示 `NETWORK_UNAVAILABLE`，保存原 36 位 UUID，用户在页面点击 **相同操作键重试**，真实服务器创建一条 Bridge Revision #1，无重复 Head。
- 从真实 Spring REST GET 核查 `mappingMode=BRIDGE_TABLE`、三表来源/双列、已保存质量 duplicate=1。
- OUT `CELL-01`：两条唯一对象、仅相关 orphan 触发 PARTIAL、三份 ReadRef；COUNT_DISTINCT=2、SUM=10+20=30，仍显示 PARTIAL。
- IN `QE-11`：输出原 SOURCE `CELL-01`，根相关孤儿使 PARTIAL，LinkFact SOURCE/TARGET 方向不反转。
- 刷新当前目录后，点击“重开已保存”从服务器读取并恢复 Bridge 双列与质量；将浏览器缩至 375px，检查真实 React 选表/双列/验收结果可见及未出现严重横向溢出。
- 同一个真实产品回归套件还复跑了 **旧 M8.1 单 FK UI**，确保新 M:N 下拉没有覆盖原默认 LinkType / 操作入口。

实际测试命令是现有 [Required Quality 的真实浏览器工作流](../../.github/workflows/native-draft-e2e.yml) 中 `python -m unittest discover -s ontoforge-ui/e2e -p 'test_*.py' -v`，不是单独跑已经签收的静态 UX HTML。

## 3. 可追溯 CI 证据

**应用代码验收提交 `246c16a6de871b5868e859f0a2ba94c053a068ab`**：

- [OntoForge Required Quality run #39369010013](https://github.com/gitfortian/ontoforge/actions/runs/39369010013)：**completed/success**，包含真实 PostgreSQL 17 + Spring Boot HTTP + 真实 React Chromium 黄金故事。
- 在该 run 对应的真实 PG17 + React Chromium Job 中，日志明确输出 `test_bridge_full_save_unknown_recovery_out_in_sum_and_reopen_375px ... ok`，同批浏览器发现执行 `Ran 39 tests` / `OK`。允许日志里出现“Package already belongs to another workspace”作为测试 **故意验证跨空间接管必须拒绝** 的数据库负例，不应误判为产线数据库故障。
- [S0 MVP foundation #39369009986](https://github.com/gitfortian/ontoforge/actions/runs/39369009986) 的 Java/PG17/Flyway + frontend build 通过；[M8 R2 Candidate Contract #39369010008](https://github.com/gitfortian/ontoforge/actions/runs/39369010008)、Docs #39369009941、UX-Low #39369009997、UX-High #39369010230 及旧原型 #39369009972 的状态以最新同 head 实际检查为准。

**注意**：本报告提交后形成新 docs-only Head，需要再次确认其 workflow 状态；不得沿用上述历史 Head 编造新 Head 的绿色结论。

## 4. 剩余边界与 T5 强制出口

- **本轮 T4 为可用 UI + 实际 PG17/HTTP/Chromium 验收，不是整套 H01–H12/B01–B18/C01–C12 的正式终审**。真实世界里仍需对每个关键负例逐项标注 coverage/未覆盖/风险，不应以一个 happy-path 黄金故事覆盖所有验收矩阵。
- 要补 **T5 深度独立 Review**：权限丢失/恢复、在途请求竞态、真实 CAS 并发/UNKNOWN 跨浏览器恢复、三表 schema DDL 漂移、无权限结果非泄漏、页签重入、真实 375px 截图与键盘焦点、数据高扇出、缓存/多 Worker 跨进程行为与 SQL source account 安全；测试区分模拟原型、真实 PG17/React 与 Windows 11 原生 UAT。
- CI 浏览器测试采用 Linux PostgreSQL 17；Windows 11 + 本机 PostgreSQL 实测 Issue #102 仍独立未核实，不能仅凭 Windows PowerShell parser PASS 冒充真实运行。
- 生产 PR #103 在全部 T5 和独立维护者放行前**始终 Draft、NOT MERGED、NOT RELEASED**。M8.3 二跳、Action、跨 PostgreSQL 与任意 SQL 不在本次 OFIP-0010 授权范围内。

**全局里程碑**：T1 V11/DAO ✔；T2 Bridge FULL/API/CAS ✔；T3 ObjectSet OUT/IN/来源证据 ✔；**T4 正式 React 及真实浏览器纵向故事 ✔**；T5 全矩阵独立终审 ⏳。
