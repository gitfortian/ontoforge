# OntoForge M8.2 · BridgeTable 多对多关系 R1 产品与领域边界评审

> **状态：R1 REVIEW CANDIDATE / 待产品审定，不是实施授权。** 日期 2026-10-19。基线 main@5d189bc4704fcf51405f2eb5fe78947c198801b2（M8.1 [PR #93](https://github.com/gitfortian/ontoforge/pull/93) 已合并）。上游 [Issue #47](https://github.com/gitfortian/ontoforge/issues/47)、[OFIP-0010](../proposals/OFIP-0010-M8-LinkFact与有界遍历.md)、[M8 R2](M8-LinkBinding-Traverse-R2-领域API与执行合同评审.md)。
>
> **严格 PR 预算：M8 总共仅三份功能纵向交付 PR：M8.1 #93（MERGED）/ M8.2 当前唯一 Draft PR / M8.3 未来唯一 PR。** 本文件及未来 R2/R3、UX-Low、UX-High、实现和回归全部留在同一 M8.2 Draft PR，绝不另起设计/原型/修复 PR。
>
> **治理边界：OFIP-0010 的 Accepted 只批准 M8.1，尚未授权 M8.2 生产实现。** 在产品/领域/API 决策、全新 UX-Low 和 UX-High 分别签收、维护者明确准入前，仅允许设计材料、隔离研究和预案，不允许动生产 Java、Flyway、React 或发布合同。

## 1. 用户故事、预期效果和不做的内容

**用户故事**：用户定义“维修工单 MaintenanceOrder”和“设备 Equipment”两个自定义 ObjectType，并在同一个已冻结 Local Snapshot 里声明 MANY_TO_MANY LinkType “工单关联设备”。M5 已将两个 ObjectType 分别映射到实际 PG 表。关联事实不是工单表/设备表各自外键，而在同一受控 PG 的桥表 maintenance_order_equipment(work_order_no, equipment_code) 中。

1. 在 Native 已冻结的 LinkType 上选择“配置实例关联”，看到 LinkType 的原始 SOURCE → TARGET 定义和两个明确修订的 M5 绑定。
2. 选择 **BRIDGE_TABLE** 模式；从当前来源白名单选择桥表，分别把桥表 work_order_no → SOURCE 已绑定的权威 sourceKey、equipment_code → TARGET 已绑定的权威 sourceKey。不能手输 SQL、跨库选择或客户端重定义对端 PK。
3. 先只读 FULL 核验，再 CAS + idempotencyKey 保存不可变 LinkBinding revision。清楚报告桥表总物理行、完整有效 pair、缺失端点、NULL、重复 pair 和预算失败。
4. 从“工单 MO-001”OUT 查看设备 E01/E02，或从“设备 E02”IN 查看工单 MO-001/MO-002。原 LinkFact 始终保留 SOURCE=工单、TARGET=设备，不因为 IN 反向而对调。
5. 对象/逻辑关系/路径各自去重，允许多个工单指向同一设备。返回对应 SOURCE、TARGET、BRIDGE 三类来源角色证据；执行失败、漂移、失权和过期分页可明确恢复。

**M8.2 交付边界**：一个用户自定义 MANY_TO_MANY LinkType、两个已保存 Object Binding 修订、一个同源桥表、两条单列 FK → 唯一/单列权威 sourceKey、显式 OUT/IN **一跳**、现有 M8.1 的 FILTER/SORT/PROJECT/AGGREGATE/PAGE 与质量/授权/恢复。

**本轮严格不做**：任意多表桥接、联合 FK/PK、跨 sourceId/跨 PostgreSQL JOIN、独立桥表 ObjectType 自动生成、桥表业务写回、相同端点的多条平行事件边、时态 LinkFact、关系行级 ACL 伪支持、CDC、materialized LinkFact、第二个 Traverse、M8.3 两跳、Action、图数据库或新菜单。

## 2. R1 架构核验：可复用与必须新增

| 已在 main 的真实事实 | M8.2 必须如何处理 |
| --- | --- |
| Native V0.2 LinkType 已支持 MANY_TO_MANY 声明；其本身不等于有实际 LinkFact | 复用 LinkType 定义、Snapshot/TypeRef；无有效 LinkBinding 时不展示假的关系事实 |
| M5 的两端 Binding revision/digest/identityDomain/sourceKey/schemaFingerprint 已冻结 | 两端保留原独立身份域；不能以不同 identityDomainId 拒绝合法 FK=PK JOIN |
| M8.1 的 LinkBindingService 仅接受 FOREIGN_KEY，V10 的 runtime_link_binding_revision 强制非空 fk_owner、fk_source_column、referenced_identity_column | **不能把 BRIDGE_TABLE 填入 FK 字段或写占位值**。R2 必须设计向前兼容 V10 的 Bridge Revision 扩展/附属不可变详情；不重写 V10、旧 revision 或旧 contentDigest |
| M8.1 LinkTraverseService 当前只读两个端点表，冻结 SOURCE/TARGET 两端的指纹与 SourceReadRefs | M8.2 查询必须读桥表并输出 **三种语义来源角色**；不允许只改 UI 标签、遗漏 Bridge 指纹/授权/预算/版本 |
| V2 query/result 已有 ROOT、TRAVERSE 一步、FILTER/SORT/PROJECT/PAGE/AGGREGATE、有界结果、逐页当前资格复核 | 保持同一 ObjectSet 查询工作台与 v1/v2 契约；BRIDGE 不应导致新平行查询接口或改动 M7 v1 行为 |
| M8.1 内置来源/输出预算、32 冻结槽/16 MiB 结果 JSON、最多 4 个在建执行许可 | 预算必须覆盖**三表合计**，还需测 JOIN 中间候选/桥表重复和桥表扇出；JSON 容量限制不等于 JVM Heap/RSS 硬上限 |
| Yak Session V8 / Workspace V9 / M8.1 V10 已合入 main | 任何 Bridge 新持久化只加 **下一可用前向 Flyway**；不预写固定版本号，R2 以当时最新 main 的 Flyway 列表确定 |

## 3. R1 必须明确的八项产品裁决

### D1 · 桥表是关系事实来源，不是自动第三种业务对象（建议 A）

桥表具有 sourceId、schema/table、明确两列、schemaFingerprint、readStarted/readEnded、可能存在的物理行定位证据，但默认 **不生成第三 ObjectType/BridgeObjectRef**。需要关系自身属性（工时、角色、有效期、状态）作为业务实体时，应由用户另外建对象或后续独立版本建模，不能静默把不同事件压成一条普通边。

### D2 · LinkFact 身份是端点对，不是桥表的行 ID（建议 A）

本期逻辑键 = frozen Snapshot/LinkType + 完整 SOURCE ObjectRef + 完整 TARGET ObjectRef。**一对物理端点 pair 即一条 LinkFact**；桥表的重复物理行必须计入 duplicatePairRowCount/来源证据，但不能凭重复行数膨胀 linkCount、COUNT_DISTINCT 或 SUM。

不同 pair 同时指向一个设备是合法 MANY_TO_MANY。若业务必须保留同一对端点间多条可区分事件，即超出 M8.2 的“简单关系”合同，不能去重假装只有一个事件事实。

### D3 · 两列独立精准关联（建议 A）

桥表 sourceFkColumn/targetFkColumn 必须明确引用两端 M5 revision.sourceKey。以真实 PostgreSQL 列类型与 M6 类型化对象身份规则验证，不做隐式 text CAST、TRIM、大小写折叠、跨源 ID 合并或任意 SQL 表达式。两列 NULL 都不生成 LinkFact；STRING 空串不是 NULL。

SOURCE 和 TARGET 的 identityDomainId 通常不同，**JOIN 使用受控 FK 值与权威 PK 值匹配，身份相等/去重使用完整 ObjectRef**。

### D4 · FULL 验证与查询局部质量（建议 A）

保存前：同一 PG 只读 REPEATABLE_READ 读取三表，在统一预算内完成完整 Bridge→两端核验。有效 pair、source/orphan、target/orphan、双端缺失、NULL 一端/两端、重复 pair 分别计数；允许 VALID_WITH_ORPHANS，但不能将无权/未知/超预算解释为孤儿或通过校验。

查询时按请求 root 作用域判质量：
- OUT：当前 SOURCE root 的 bridge.sourceFk 与 root 匹配，但非空 bridge.targetFk 找不到 TARGET → 此 root 的 PARTIAL/KNOWN_SUBSET；别的 SOURCE root 的 target orphan 不能污染当前结果。
- IN：当前 TARGET root 的 bridge.targetFk 与 root 匹配，但非空 bridge.sourceFk 找不到 SOURCE → 此 root 的 PARTIAL/KNOWN_SUBSET。
- NULL 为未形成关联，单独计数；只有合法 0 边则 COMPLETE + 真空结果；source/target 重复身份冲突/不可核验权限/错误列/超预算都 FAILED 且零结果载荷。

### D5 · 版本、CAS 和未知提交恢复（建议 A）

复用既有唯一 Head、不可变 Revision、operation receipt、expectedRevision CAS 与 CREATE/UPDATE idempotencyKey。Revision 必须显式冻结 mode=BRIDGE_TABLE、桥表列映射/白名单 sourceId/schema/table、三方指纹、双端 Binding 修订/摘要、模型 LinkType hash 与 FULL 验证报告。已冻结 FK revision 的原含义永不被重解释。

正式查询时检查被引用的 Bridge Revision 和两端 Binding **当前**权限/结构/有效性；版本未知或漂移不可自动选择 latest。丢失保存回包以同 operation UUID 恢复，不能重复创建 revision。

### D6 · 三表一致性、资源、当前授权（建议 A）

SOURCE、TARGET、BRIDGE 全部同一个受控 sourceId 和只读 PG **同一连接、同一次 REPEATABLE_READ**；不能先读桥表再换另一 JDBC 连接核验两端，却声称统一数据快照。与元数据 CAS 并不构成跨库原子提交。

设计基线首选继承 M8.1 统一预算：**3 表物理行总计 ≤10,000，JOIN 中间候选 ≤10,000，逻辑 LinkFacts/路径 ≤10,000，来源/结果各 ≤8 MiB、执行 deadline 3s、页 ≤50、TTL ≤300s**；源内允许更保守限值，禁止因第三张表直接把单表 10k 扩张成三表各 10k。高扇出超额返回 429/503，没有部分成功或错误的 0。

冻结结果需 SOURCE/TARGET/BRIDGE **三个不同角色的 SourceReadRef**，并冻结模型/三份 schemaFingerprint/LinkBinding+双端 Binding 版本；每页重新核验 3 份当前 SELECT/白名单、包权限/快照、端点/桥表结构，任何撤权使签名 cursor 失效且恢复后不可复活。行列级 ACL 目前未提供，不宣称已实现。

### D7 · UI 保持原用户主线（建议 A）

Native 关系类型 → 选择已冻结 LinkType → LinkBinding 中选择“外键/桥表” → 确认两个端点不可变版本 → 桥表来源与双列映射 → 预演 FULL → 保存 CAS → 返回 ObjectSet 正反向一跳查看证据。

默认选择已声明 MANY_TO_MANY 的关系类型时，优先明确提示 BRIDGE_TABLE；不展示“同名字段自动推断”作为已核验映射，不在导航再加独立工具菜单。首屏先看到双端和关系来源、下一步唯一主操作。低保真/高保真必须**重新设计并分别签收**，不直接复制 M8.1 UI 或以 CSS 改版替代原型。

### D8 · 纵向交付与治理（建议 A）

M8.2 **恰好一个 Draft PR**：R1→R2（API/Schema/CAS/版本/质量）→必要 R3 高保证→UX-Low 签收→UX-High 签收→OFIP-0010 M8.2 **独立 Accepted 记录**→实际 Java/前端/迁移→真实 PG17/HTTP+React Chromium→Required Quality 同 head→独立 Review→Ready/merge。

M8.1 已合并的 API/Schema、JWT/Session、V10 迁移、对象/Link/路径计数、查询缓存和错误/恢复都必须回归。M8.3 两跳严格独立，不得搭车。Issue #102 的 M8.1 Windows 原生 UAT 继续作为正式发布前独立门禁，不被本轮伪造成 PASS。

## 4. R1 专业审查中确认的风险与第二轮必选方案

| 编号 / 严重度 | 风险与目前的代码事实 | R2 必须裁决的可执行方案 |
| --- | --- | --- |
| P1-01 / 阻断 | V10 runtime_link_binding_revision 为 FK-only 非空列；CREATE/UPDATE 目前的 DTO/DAO 同样 FK-only | 在“不改旧修订、无假 FK 占位”的条件下选择**泛化映射列+互斥 CHECK**还是**不可变 Bridge 详情附表**；证明升级与历史 digest/receipt 不变 |
| P1-02 / 阻断 | v2 只扫描两张端点表，sourceReadRefs/版本锁只有两端；桥表结果会引入第三方真实事实 | 设计三方读取/授权/指纹和结果模型，桥表不应冒充 OBJECT Binding；旧 M8.1 成功合同不新增必填 Bridge 字段 |
| P1-03 / 阻断 | 物理重复与多个关系事件可能共享相同 endpoint pair | 明确“一个 pair 一个逻辑边”的产品范围及错误/重复证据，不允许丢失需要区分的 event 语义 |
| P1-04 / 阻断 | 3 表 JOIN 与双端孤儿存在中间扇出、过大校验与信息侧信道 | 前置预算/独立 root 局部质量/跨权限撤权与重复 row/聚合测试完整矩阵 |
| P1-05 / 阻断 | OFIP-0010 仅批准 M8.1 | 在同一个 Draft PR 内补 M8.2 独立决议，未获许可不得实现生产功能 |
| P2-01 / 后续 | 桥表物理重复可能无稳定单行标识 | 多行聚合时保留表/列/读取范围和 duplicateRowCount；无 PK 不承诺可永久定位每一条物理行，不采用 ctid 作稳定身份 |
| P2-02 / 后续 | 桥表有效 pair 可能很多，而 3 表统一 10k 行限制仍很小 | 本期明确能力边界与超额恢复，优化索引/受控查询策略必须有真实 EXPLAIN + Java 压测，不通过降低完整性或偷换分页预算兜底 |
| P2-03 / 后续 | 来源授权撤回后全局 orphan 数可能泄漏不可见关联 | 在同一当前主体权限下判定“未知授权”优先于“孤儿”，任何无法确认完整读资格返回失败，不推断泄露 pair |

**本 R1 评审推荐意见**：产品范围原则成立，可进入 R2 详细方案设计；但 **P1-01–05 未裁决之前不得进入生产代码**。本文件状态仍 REVIEW CANDIDATE，不能冒充 Owner 正式签收。

## 5. R1 可证伪验收矩阵（M8.2 正式交付前必须真实执行）

| ID | 正向 / 负向黄金故事（隔离 PG17 + Spring HTTP + React Chromium） | 成功或拒绝语义 |
| --- | --- | --- |
| B01 | 2 个自定义 ObjectType + MANY_TO_MANY LinkType + 同 Snapshot 双端 M5 Binding，identityDomain 不同 | 合法受控桥表关系，不能 hardcode 工业类型 |
| B02 | 桥表两列分别指向端点已锁 sourceKey；列顺序颠倒、未知列/任意 SQL/类型不兼容 | 正例 FULL；坏列/SQL 类型化 422，既有 Head 无新增 |
| B03 | MO-001→E01/E02，E02→MO-001/MO-002 | OUT/IN 关系方向和结果准确，反向不反转 LinkFact.source/target |
| B04 | bridge 存在同一 pair 的两条重复物理行 | 一条 LinkFact，一个逻辑关系，duplicateRowCount 明确，SUM 不重复 |
| B05 | bridge 两个 FK 分别 NULL、一边 NULL、空串、孤儿 SOURCE、孤儿 TARGET | 不把 NULL/空串或未知权限当 orphan；OUT/IN 各自局部质量真实 |
| B06 | 桥表 pair 各合法不同、多个 SOURCE→同 TARGET、单 SOURCE→多 TARGET | MANY_TO_MANY 全部保留但对象集合按身份去重 |
| B07 | Bridge 表变更、字段类型/指纹漂移、无 SELECT、跨 package/Snapshot、跨 sourceId | 403/409/422/503 fail-closed，无敏感物理表或对象泄漏 |
| B08 | CAS 竞争更新、同 key 不同 body、响应 UNKNOWN 与真实服务重启重放 | 不可变 revisions、单一 Head、一次 operation receipt |
| B09 | 三方分别由同一 REPEATABLE_READ 读取，同时另事务增删来源行 | 本次结果一致，不拼凑三份不同时间的事实 |
| B10 | bridge 及两端总行数 10,001、JOIN 中间候选 10,001、超 8 MiB、超时 | 429/503 且无 objects/links/pathCount 的假成功 |
| B11 | 大扇出与 1,000 pair 分页，保存后删除来源，检查已冻结/新执行差异 | 同一结果快照成员稳定，重新执行反映新来源 |
| B12 | 已签发 cursor 后撤销 BRIDGE SELECT，再恢复 | 旧 cursor 永久 410；无关系存在性侧信道 |
| B13 | COUNT_DISTINCT 与 SUM 对重复 pair、共享目标的结果 | 语义按唯一输出对象/LinkFact，禁止把路径数当对象数 |
| B14 | SELECT root=无边、root=合法有边但不完整、root=跨包无权 | 0 是 COMPLETE 真空；局部孤儿 PARTIAL；越权失败无对象载荷 |
| B15 | Bridge row 无稳定 PK，但具两列关系 FK 与重复行 | 显示物理重复数和来源表级证据，不伪造逐行稳定 ID |
| B16 | M8.1 FK 旧 revision、v2 旧 result、M7 v1、Yak Session/Workspace、Flyway V8–V10 | 全部旧回归不变，前向迁移可在已有历史库执行 |
| B17 | 全新 UX-Low/UX-High：配置、FULL、CAS、OUT/IN、质量、漂移、撤权、375px 移动端 | 两阶段分别真实签收，正式页面再以 Chromium E2E 证实 |
| B18 | V11 或其他新迁移真实脚本在隔离 PG17 顺序升级，保存/读取历史 FK revision | 无覆盖 V10，无历史记录内容/摘要变化，无旧幂等回执失效 |

**自动化选择原则**：这些是下一阶段的验收目标，当前全部 **NOT RUN / NOT IMPLEMENTED**；已有 M8.1 的测试不能冒充 B01–B18。版本“V11”仅为示例，最终由最新 main 的 Flyway 号确定。

## 6. 后续关口与独一 PR 执行顺序

- **R1（本文件）**：用真实代码证据确认产品故事、边界、D1–D8 建议、P1/P2 风险和 B01–B18；等待正式产品评审意见，不作为已签收。
- **R2**：敲定机器合同（BRIDGE_TABLE draft/revision/schema & v2 result）、不可变迁移方案、权限/三方事务、两类作用域质量、JOIN 计划及 429/503 拒绝策略；在**当前 Draft PR**追加文件并进行第二轮 Review。
- **UX-Low**：在当前 PR 提交全新可操作 HTML+评审说明，完成独立浏览器故事验证与产品签收。
- **UX-High**：在当前 PR 提交全新高保真，覆盖正式样式、375px、版本/错误/恢复，签收前不进入生产实施。
- **OFIP**：M8.2 独立决策写在当前 PR 的审议记录及原 OFIP 文档扩展，不假定 M8.1 Accepted 自动覆盖 M8.2。
- **实施**：获授权后仍只在当前 PR 里做纵向 Java/PG/React 与测试、CI 修复、独立终审，Ready/merge 最终只保留一个 PR。禁止第二个 M8.2 实施 PR。

**本轮交付性质**：真实 R1 产品/领域 Review 文档 + 唯一 Draft PR。无生产代码修改、无 M8.2 CI/PG E2E 通过声明、无用户/Owner 设计签收。
