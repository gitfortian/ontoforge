# M8.2 · T3 BridgeTable ObjectSet 一跳查询、三方来源与安全游标

> **2026-10-20 · 生产代码已在唯一 Draft PR #103 实施，验收以每个最新 Head 的 GitHub CI 实际 completed/success 为准**。  
> 批准来源：[OFIP-0010 M8.2-only 维护者受托决议](https://github.com/gitfortian/ontoforge/pull/103#issuecomment-6281584798)。  
> 本文说明 M8.2 T3 实际代码边界，**不代表 T4 正式 React 和 T5 全链路验收已完成**，PR #103 不得因此改 Ready 或合并。

## 1. 用户可见的纵向业务故事

**对象设备 Equipment ↔ 检验批次 InspectionLot，多对多 Bridge 双向一跳：**

1. 用户此前完成 M5 的 Equipment 与 InspectionLot 双端 ObjectBinding 并冻结不可变 Snapshot；T2 从受控 BridgeTable 表中的 `equipment_code, lot_code` 两列完成三表 FULL、创建 / 更新 v2 LinkBinding Revision，保存来源版本与质量证据。
2. 用户从 ObjectSet 选择设备 `EQ-001`，指定保存的 **Bridge Revision #2**、`direction=OUT`。执行器按固定 `local-query-v2` AST 读取设备、检验批次和桥表，物理重复 Pair 只产生一条逻辑 LinkFact，对输出对象去重；只关联本根对象的孤儿会将 `quality.completeness` 设为 PARTIAL、`answerScope=KNOWN_SUBSET`。
3. 反向以 `InspectionLot LOT-11` 为根、`direction=IN`，返回关联 Equipment，但 **LinkFact.source 仍是 Equipment，target 仍是 InspectionLot**，不能反转业务定义。
4. COUNT_DISTINCT / SUM 按**唯一输出 ObjectRef**聚合，SUM 的数值重复 LinkFact 不重复累计。若当前根存在孤儿，`quality` 坚持 KNOWN_SUBSET，调用端不得把小计展示为全量。
5. 查询证据包含两个原始端点的冻结 M5 版本及唯一 LinkBinding Revision，共 **2 OBJECT + 1 LINK**；同时必须存在 **SOURCE/TARGET/BRIDGE 三个 sourceReadRefs、三个当前 schemaFingerprint**。Bridge 不会被转换成第三 ObjectType/Binding。
6. PAGE 限额 1–50，服务端结果冻结 TTL 至多 300s，签名 cursor 只引用服务端保存的受控数据。旧 cursor 翻页、证据展开时重新核验 actor、package、Snapshot、Link 修订、两个 M5 Binding 和**第三 Bridge 当前 SELECT 权限及 schemaFingerprint**；撤权则撤销冻结结果，重新授权也不得复活原 cursor。

## 2. 本轮真实代码改动

| 模块 | 实际改动 | 兼容性保护 |
| --- | --- | --- |
| [LinkTraverseService.java](../../ontoforge-business/src/main/java/io/ontoforge/business/query/LinkTraverseService.java) | 在读取 LinkBinding 时按存储 Revision 的 FK/BRIDGE 状态选择受控分支，**共用原 local-query-v2 AST、会话签名、预算、冻结与 ObjectSet 页面契约**；Bridge 单只读 PostgreSQL 事务内锁定并读三张表，按 Pair + ObjectRef 去重及根局部孤儿计算，三角色 ReadRef；分页和 evidence 持续重核第三来源 | M8.1 单列 FK 路径、原 v2 合同和 M7 v1 不改；禁止跨 sourceId、多跳、客户端任意 SQL |
| [M8TraverseSchema.java](../../ontoforge-core/src/main/java/io/ontoforge/core/compiler/M8TraverseSchema.java) | 增加生产 classpath 上的独立 [Bridge SUCCESS 结果 JSON Schema](../../ontoforge-core/src/main/resources/m8/local-result-v2-bridge.candidate.schema.json)，保留 FK 原 SUCCESS Schema 不放宽 | 不允许把 Bridge 三源响应硬塞进 M8.1 双源 Schema；失败仍是既有 v2 的最小无泄漏 envelope |
| [QueryEvidenceTest.java](../../ontoforge-business/src/test/java/io/ontoforge/business/query/QueryEvidenceTest.java) | 原旧 FK Frozen 反射测试明确以 `bridgeTable=null, bridgeFingerprint=null` 继续覆盖旧证据失效行为；新构造函数显式注入 Bridge adapter | 不删除 M8.1 质量负例；旧 actor/current authorization 语义保留 |
| [LinkBindingFullIntegrationTest.java](../../ontoforge-boot/src/test/java/io/ontoforge/boot/LinkBindingFullIntegrationTest.java) | 在已成功验证的真实 Bridge T2 全流程之后增加 T3 **真实 PostgreSQL 17 + Spring HTTP** 纵向回归：OUT/IN、三个来源版本证据、LINK 定义方向、重复 Pair 去重、SUM/COUNT_DISTINCT、签名分页与伪造 cursor、总物理行/Join 上限、第三来源撤权和 re-grant 仍 410 | 直接复用实际的真实 M5 Binding、Snapshot 与 T2 的 CAS 保存，不用静态 UX Fixture 伪造 ObjectSet 响应 |

## 3. 本轮真实生产数据与预期结果

起始 Bridge FULL 的 9 条物理行：matched=3、uniquePair=2、duplicatePairRowCount=1，六类 NULL/orphan 各一行。T3 真实查询测试在 **保存不可变 Revision #2 后** 再加入两条 `EQ-001 → LOT-12`：因此本次读取得到的去重结果不变，**本次来源实际重复物理 Pair 行变为 2**，不能错误复用历史 `FULL.duplicatePairRowCount=1`。

| 情景 | 可证伪验收 |
| --- | --- |
| OUT `EQ-001` PAGE size=1 | 两页 1+1 个唯一目标 ObjectRef，3 个 SourceReadRefs，linkMappingMode=BRIDGE_TABLE，Bridge 当前物理 duplicate=2；一条孤儿导致 PARTIAL/KNOWN_SUBSET |
| OUT `EQ-002` | 只命中 LOT-12，**尽管全表存在其它孤儿**，本根仍 COMPLETE |
| IN `LOT-11` | 仅唯一 Equipment 输出，原 LinkFact 源/目标身份不反转；根相关 `EQ-NO` 孤儿使结果 PARTIAL |
| SUM | 冻结属性 `inspection-amount` = 10+20=30，物理重复 Pair 不重复算入；在 PARTIAL 下仅 KNOWN_SUBSET |
| COUNT_DISTINCT | 两个唯一 InspectionLot ObjectRefs，返回 `2` |
| 篡改 `v2.` 签名 | HTTP 400、无对象/键/敏感来源 payload |
| 物理/Join 限制 | 用 `maxPhysicalRows=2` 或 `maxJoinCandidates=1` 强制真实数据超限 → HTTP 429，不能截断返回部分成功 |
| Bridge 撤权 & 恢复 | `REVOKE SELECT` 第三表使 GET 或 PAGE 失败且不得泄漏字段；恢复 SELECT 后同旧 cursor 继续 HTTP 410 |

上述真实用例在相应 CI 成功前属于**实现并提交的验收目标**，不能预写 PASS。检查成功后在 PR 评论中写明运行 ID 和 head；失败保留原始 CI 日志，不删负例。

## 4. 强制风险与边界

- SourcePG **没有数据快照持久化**。源的结构指纹由当前受控元数据计算，`freshness=UNKNOWN`；若来源内容未改变结构但发生增删，重新执行查询可能变化。相同 frozen cursor 内的结果保持首次提交的快照，不表示可证明历史 PostgreSQL 事实；当读权限/结构/修订失效时必须 fail-closed。
- 三表扫描受 10k 物理行、10k Join、8MiB 来源/响应与 3 秒逻辑 deadline 约束；旧实际 M8.1 查询对 sourcePhysicalRow/Join 做 fail-before-success 的限制，Bridge 也必须维持。不能用 LIMIT 截断后误判“完整”。
- 冻结缓存沿用原 32 槽、16MiB 序列化估计及 4 个 builder 限额；**不是精确 JVM 堆内存硬隔离**。真正生产压测、授权并发和跨 Worker 场景仍需在 T5 补证。
- **M13 Agent 调用只读 ObjectSet 路径**：本轮仅扩展受控 v2 LinkTraverseService，没有给 Agent 新的任意 SQL、修改数据或 Action 能力；需在同 Head Required Quality 中包含其既有测试。
- **T4 React 未开发**：目前所有新增真实 UI 效果都通过 HTTP 与现有 ObjectSet 结果合同验证；已签收的独立 UX-High 静态原型不能代替真正 React 与会话/路由/375px 验收。
- **T5 未完成**：R1 B01–B18 / R2 C01–C12 端到端验收、授权成本/扇出压力、多 Worker/源故障竞态、独立全功能 Review、Windows 11 实机 UAT 均不得声称完成。M8.3 二跳/Action 严禁跨越 scope。

## 5. 下一节点

- **T3 出口**：最新同 Head 真实 PG17/HTTP 回归与 Required Quality 全绿，独立复核新旧 v2 Schema 和 Bridge 非泄漏负例；归档到 #103 的完成说明。仍保持 Draft。
- **T4 正式 React 可操作交付**：按已签收 UX-High H01–H12 复用现有 OntoForge 导航/组件与设计系统，将 Bridge 工作台、FULL 校验、CAS 恢复、OUT/IN ObjectSet、三方证据接到真实 Spring HTTP；无孤立二级工具菜单、不直接复制 HTML 静态原型。
- **T5 严格验收**：新增完整 PG17→HTTP→React Chromium 黄金故事，覆盖主流程与异常/权限/预算，按 R1/R2 验收矩阵独立深 Review，同 Head 所有 Required Quality 成功后才能从 Draft 改 Ready；M8.1 Windows UAT #102 仍单独追踪。
