# OntoForge M8.2 · BridgeTable R2 合同评审：API / 版本 / 持久化 / 三方来源 / 执行边界

> **状态：R2 TECHNICAL REVIEW COMPLETED · CONTRACT CANDIDATE / 待正式产品审定、待 OFIP-0010 M8.2 专项授权。** 日期：2026-10-19。**不是生产实施完成报告，不替代 UX-Low/UX-High 签收**。唯一纵向 [PR #103](https://github.com/gitfortian/ontoforge/pull/103)，上游 [R1 评审](M8-2-BridgeTable-R1-产品范围与LinkFact证据评审.md)、[Issue #47](https://github.com/gitfortian/ontoforge/issues/47)。事实代码基线 `main@5d189bc4704fcf51405f2eb5fe78947c198801b2`，M8.1 [#93](https://github.com/gitfortian/ontoforge/pull/93) MERGED。
>
> **决策分级**：本篇 R2-A01–A10 为可执行的**审查推荐合同**；不是产品 Owner 投票或维护者批准。M8.1 OFIP Accepted **仅覆盖 FK 一跳**，不能当 M8.2 实施授权。PR #103 当前仅修改 `doc/design` 下评审与候选机器 Schema，不改 Java / Flyway / React 生产源码。B01–B18 真实 PG/HTTP/Browser 仍 NOT RUN。

## 0. 核对实际工程，排除「桥表只是 FK 加一个选项」

| 实际 main 代码 / 文件 | 可重复核查的约束 | R2 必须变更 |
| --- | --- | --- |
| `V10__local_link_binding_revisions.sql` | `runtime_link_binding_revision.fk_owner/fk_source_column/referenced_identity_column` **NOT NULL**；元数据 Head `current_revision` 以复合 FK 锁定 revision；不可变 Revision / Operation 触发器 | 只允许新前向迁移扩展模式，不修改 V10，不把 Bridge 双 FK 捏造成旧单端 FK |
| `LinkBindingController.parse` | 严格字段白名单，当前 `LinkRequest` 为 `local-link-binding-v1 / FOREIGN_KEY` | API **版本判别**而非放宽 v1 白名单：v1 FK DTO 原样保留，新 `local-link-binding-v2` 仅 BRIDGE_TABLE |
| `LinkBindingService.context/fullValidation` | 仅读 SOURCE/TARGET；FULL 使用 source 连接独立 `REPEATABLE_READ`；CAS + idempotency 由 metadata DB 事务处理 | 第三张受控 Bridge 表、双 FK / 三方来源指纹、准确重复与两侧孤儿统计，同一 source read transaction |
| `LinkTraverseService` | 在旧查询中只冻结双端 Binding、SourceReadRefs 与指纹；最多 4 并发 builder、32 份 JSON / 16 MiB 驻留 | Query v2 保持**一段** TRAVERSE，但桥表分支必须读/核三方并冻结三方准入、拒绝部分成功 |
| `local-result-v2.candidate.schema.json` + `M8TraverseSchema` | `schemaFp.role`/`sourceRead.role` 仅 `SOURCE`、`TARGET`，旧 `success` 支持双端；`link.sourceReadRefs.minItems=2` | Bridge 成功结果单独校验扩展型 v2 schema，强制 `BRIDGE` 角色；**旧 FK 成功结果仍用原 schema** |
| M5 Native / Yak Session / Workspace | 两端绑定锁定 `sourceKey`、`digest`、`identityDomainId` 和真实来源权限；V8/V9 历史迁移有效 | Bridge 不能成为偷偷生成的 OBJECT Binding；B0 包+Source grants / 当前结构，第三方独立复核 |

## 1. R2-A01｜版本化 API：保留 LinkBinding v1，桥表采用严格 v2

**审核建议：方案 A。** 不改变现有 endpoint 名称，只由 `contractVersion` 严格路由：

| HTTP | 已实现外键 v1 | 新桥表 v2（设计候选） |
| --- | --- | --- |
| `POST /api/runtime/link-bindings/validate` | `local-link-binding-v1` 原有字段、响应与校验不变 | `local-link-binding-v2` + `mode=BRIDGE_TABLE`；只读 FULL，不插入 Head/Revision |
| `POST /api/runtime/link-bindings` | v1 create，不动现有持久语义 | v2 create，`expectedRevision=0` + operation UUID + 规范化请求 digest |
| `PUT /api/runtime/link-bindings/{id}` | v1 FK UPDATE 原样 | v2 Bridge UPDATE，`expectedRevision` 必须匹配 Head；桥表类型/SourceID 作为本次新 revision 冻结 |
| `GET list/detail/revisions` | FK 旧字段结构与数值保持 | 返回的 Bridge 详情带 `mappingMode=BRIDGE_TABLE` 与 `bridgeSourceRef`/三方指纹；旧 FK 的 JSON 兼容性须回归 |
| `POST /api/runtime/object-sets/traverse` / `pages` | `local-query-v2` 一跳 FK 行为不变 | **仍是 local-query-v2**，服务端依已签名/已锁定 LinkBinding 的 `mappingMode` 选择 Bridge 执行器；不接受客户端自由传入 SQL/Bridge 表或新 TRAVERSE |

桥表请求的机器候选：[严格 Draft 2020-12 Schema](candidates/m8-2/local-link-binding-v2-bridge.candidate.schema.json)。仅 `LOCAL_SNAPSHOT` 持久模型、`MANY_TO_MANY` LinkType、确切两个 Endpoint Binding 修订+digest、单个 Bridge 表双列 FK；`additionalProperties:false`，无 `fkOwner`、`fkSourceColumn`、`referencedIdentityColumn`、`sql`、`targetPk` 客户端入口。创建和更新都要求 `idempotencyKey`；校验可携同键但不得消耗幂等回执。

**合法请求示意（示例 UUID/digest 是占位符，不是已执行授权）**：

```json
{
  "contractVersion": "local-link-binding-v2",
  "modelSourceRef": {"kind": "LOCAL_SNAPSHOT", "snapshotId": "11111111-1111-4111-8111-111111111111"},
  "packageId": "manufacturing-quality",
  "linkTypeId": "order-equipment",
  "sourceBindingRef": {"bindingId": "22222222-2222-4222-8222-222222222222", "revision": 2, "digest": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
  "targetBindingRef": {"bindingId": "33333333-3333-4333-8333-333333333333", "revision": 5, "digest": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"},
  "mode": "BRIDGE_TABLE",
  "bridgeSourceRef": {
    "sourceId": "local-pg-sandbox", "schema": "public",
    "table": "maintenance_order_equipment",
    "sourceFkColumn": "work_order_no", "targetFkColumn": "equipment_code"
  },
  "expectedRevision": 0,
  "idempotencyKey": "44444444-4444-4444-8444-444444444444",
  "expectedSchemaFingerprints": {
    "source": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
    "target": "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
    "bridge": "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
  },
  "validationScope": {"kind": "VALIDATION_SCOPE", "coverage": "FULL"}
}
```

字段值 `sourceId/schema/table/column` 是**用户选择**，不是可信可执行标识符。服务端重新经 M5 allowlist/schema 元数据解析，核对 `bridgeSourceId == sourceBinding.sourceId == targetBinding.sourceId`，只以白名单 quote identifier 生成 SELECT；字段类型必须与两个版本锁定的 `sourceKey` **各自兼容**。FK 字段与引用列的对比禁止隐式 CAST/trim/大小写折叠；模型 LinkType 端点从 Snapshot 重取，**cardinality 必须 MANY_TO_MANY**。Bridge 表在本期须与两个端点物理表不同，避免在当前实现中把关系事实表误识别为 ObjectType 表。自关联或额外关联属性不自动扩展。

## 2. R2-A02｜数据库版本方案：推荐前向扩展共用 Revision 表 + 互斥 CHECK

**明确选择方案 A（待产品/维护者批准后才可写真正迁移）**，而非另建一套 Bridge Head：维持现有 `runtime_link_binding` Head、CAS、`runtime_link_binding_operation`，共用 `runtime_link_binding_revision` 的 `link_binding_id,revision` 不可变身份；只通过**下一个未占用 Flyway 版本**追加 Bridge 详情列和模式互斥约束。

迁移 SQL **审查示意、不是已运行脚本**：

```sql
ALTER TABLE runtime_link_binding_revision
    ADD COLUMN mapping_mode TEXT NOT NULL DEFAULT 'FOREIGN_KEY',
    ADD COLUMN bridge_source_id TEXT,
    ADD COLUMN bridge_schema TEXT,
    ADD COLUMN bridge_table TEXT,
    ADD COLUMN bridge_source_fk_column TEXT,
    ADD COLUMN bridge_target_fk_column TEXT,
    ADD COLUMN bridge_schema_fingerprint CHAR(64);

ALTER TABLE runtime_link_binding_revision
    ALTER COLUMN fk_owner DROP NOT NULL,
    ALTER COLUMN fk_source_column DROP NOT NULL,
    ALTER COLUMN referenced_identity_column DROP NOT NULL;

ALTER TABLE runtime_link_binding_revision
    ADD CONSTRAINT ck_link_mapping_mode_and_payload CHECK (
      (mapping_mode = 'FOREIGN_KEY'
       AND fk_owner IS NOT NULL AND fk_source_column IS NOT NULL
       AND referenced_identity_column IS NOT NULL
       AND bridge_source_id IS NULL AND bridge_schema IS NULL
       AND bridge_table IS NULL AND bridge_source_fk_column IS NULL
       AND bridge_target_fk_column IS NULL AND bridge_schema_fingerprint IS NULL)
      OR
      (mapping_mode = 'BRIDGE_TABLE'
       AND fk_owner IS NULL AND fk_source_column IS NULL
       AND referenced_identity_column IS NULL
       AND bridge_source_id IS NOT NULL AND bridge_schema IS NOT NULL
       AND bridge_table IS NOT NULL AND bridge_source_fk_column IS NOT NULL
       AND bridge_target_fk_column IS NOT NULL AND bridge_schema_fingerprint IS NOT NULL)
    );
```

实际生产迁移还须为 Bridge schema/table/columns 添加受控命名正则、SHA-256 长度/格式 CHECK，以及 LinkType 的 M:N 运行时断言；不可把 DDL示意直接当上线 SQL。**数据库 CHECK 不会替代真实权限、schemaFingerprint 和 FK→sourceKey 类型检查**。

**为什么不首选 Bridge 详情附表**：只做附表而 V10 旧 revision 仍要求非空 FK 字段，必须伪造 FK 值，或另做 Revision 超类型 + 额外完整性触发器和 FK 重构；对于只支持单桥表的 MVP，不如前向增加互斥映射字段可核验。若维护者认为未来多个映射模式需要独立聚合存储，可在 R3 提出证据充分的替代，但**禁止两套 Head、两套版本号和 V10 行 UPDATE**。

**不变量及升级测试**：
- V10 的旧 FK revision 行由新列默认视为 `FOREIGN_KEY`，**没有 UPDATE/DELETE 旧行**，保留原 `content_digest`、端点锁、历史 DTO 和幂等 operation receipt，不回填重新计算旧 hash。
- 新 Bridge revision 的 canonical content digest **包含** `mapping_mode/bridgeSourceRef/3 fingerprints/quality/snapshot/linkTypeHash/2 endpoint digest/revision`，CREATE/UPDATE 的 operation request digest 包含完整规范请求、预期 revision，但不包含 operation key 本身；同 UUID 同 payload 重放返回原 revision；同 UUID 异 payload/跨 Head 拒绝。
- 既有元数据 `@Transactional` 只覆盖 MetaDB Head/Revision/Operation；来源 Full 读在另一 read-only PG 事务，**不宣称跨两数据库原子一致性**。事务和锁顺序沿用 M8.1：operation-key → CREATE identity lock 或 UPDATE head lock → 来源校验 → 写 revision/receipt → CAS head。不能先建 Head 后才发现来源/端点无权。
- 新详情读路径需按 `mapping_mode` 判别，FK 响应保持旧可见字段，Bridge 详情不把三个 FK-only 字段设为假值；v2 详情应由清晰的映射对象表达并被机器 Schema 校验。
- 模式转换只能在**同一个不可变 Snapshot + LinkType**允许的 cardinality 约束下执行；M8.1 的 FK 仅支持非 M:N LinkType，正常不能把其旧 Head 改写成 M:N Bridge。任何服务端规避 Snapshot 不可变性去转换应 409 失败。

## 3. R2-A03｜真实 LinkFact / Bridge pair 身份与重复行分类

- **逻辑 LinkFact key**：`snapshotId + linkTypeId + source ObjectRef + target ObjectRef`。ObjectRef 必须包含 package、ObjectType、identityDomainId、key.valueType、canonical key.value。OUT/IN 仅改变遍历方向，不能颠倒 LinkFact 的 SOURCE/TARGET 字段。
- 每条 bridge 物理行有两个独立 FK 值；JOIN 按各自已锁 M5 `sourceKey` 做精准等值，不以相同字符串认定跨域 ObjectRef 相等。**多个物理行对应同 pair** 只输出一个逻辑边，重复数量是来源质量证据，不是多条事件。
- 没有稳定行级主键时不伪造 `bridgeRowId` 或 PG `ctid` 作为长期可追溯证据。只承诺表/列/指纹、读取区间、已读物理行数量、匹配 pair 数、重复行计数；稳定源行定位能力须另行立项。
- 本期不支持“同端点 pair 的多个有效期/班次/维修事件”：若来源表达这类多重业务事实，UI 和文档必须明确**M8.2 简单多对多只表示关联存在性**，不能用去重结果当事件报表，也不能自动赋予 SUM“按事件行求和”的语义。
- 对 BOOLEAN、NUMERIC 或混合整数/字符串 FK 值的隐式 cast 不批准；只支持服务端可证明和 M6 ObjectRef 相容的 STRING/INTEGER 主键值。**空字符串不是 NULL**；若端点空主键值无法编码为现有 ObjectRef（当前 `key.value` `minLength:1`），应 409 `OBJECT_IDENTITY_UNREPRESENTABLE` 严格失败，不能产生畸形 Ref 或误标为无关联。

### FULL 全表质量账本（分类须互斥）

设 `bridgePhysicalRows` 是桥表实际读到的物理行数，每行按以下互斥顺序计入恰好一个桶：

1. `nullBothRows`：sourceFK 和 targetFK 均 SQL NULL；
2. `nullSourceOnlyRows`、`nullTargetOnlyRows`：仅对应一侧 SQL NULL；
3. 对两 FK 都非 NULL 的行检查 SOURCE/TARGET：`sourceOrphanOnlyRows` / `targetOrphanOnlyRows` / `bothOrphanRows`；
4. 两端都匹配时进入 `matchedPhysicalRows`。

`bridgePhysicalRows = nullBothRows + nullSourceOnlyRows + nullTargetOnlyRows + sourceOrphanOnlyRows + targetOrphanOnlyRows + bothOrphanRows + matchedPhysicalRows`。

`distinctMatchedPairs` 为成功匹配且类型化身份不同的唯一 (SOURCE,TARGET) 对数，`duplicatePairRows = matchedPhysicalRows - distinctMatchedPairs`。若完整验证读 10,000 以内、准确归类，可保存 `VALID` 或 `VALID_WITH_ORPHANS`（orphan 任一桶非零）；非法主键冲突/权限或预算未知必须失败而不是选择 orphan。

## 4. R2-A04｜一跳 OUT/IN 局部质量与聚合合同

| 询问 / 真实事实 | 是否显示边 | 范围质量 |
| --- | --- | --- |
| OUT 根 SOURCE=MO-001，有桥表 (MO-001,E01)、(MO-001,E02) | 输出 E01/E02 与两条完整方向 LinkFact | COMPLETE + EXACT |
| IN 根 TARGET=E02，有 (MO-001,E02)、(MO-002,E02) | 输出 MO-001/MO-002；**LinkFact 仍 SOURCE=工单** | COMPLETE + EXACT |
| (MO-001,E99) 且 E99 缺失 | OUT(MO-001) 不返回 E99 假边，当前 root 的 `knownOrphanCount` 增加 | PARTIAL / KNOWN_SUBSET |
| (MO-999,E02) 且 MO-999 缺失 | IN(E02) 存在当前 root SOURCE orphan | PARTIAL / KNOWN_SUBSET |
| 同表别的 root 孤儿 | 当前 root 不受影响 | COMPLETE（禁止全局污染） |
| (MO-001,NULL) 或 (NULL,E02) | 不构造边，明确 NULL 桶 | COMPLETE（没有孤儿则真实 0） |
| 同 pair 被物理桥表存两次 | **一条** LinkFact，一次输出对象/逻辑 SUM | `bridgeDuplicatePairRowCount=1`（仅针对所查询可读 root 作用域） |
| 任一表无权、schema/版本漂移、主键冲突、预算不明 | 不返回任何部分对象、边或聚合载荷 | FAILED，受控 403/409/429/503 |

**`knownOrphanCount` 在查询中按当前方向、当前 root 范围的去重**未匹配候选关联 pair**计，非桥表物理重复行；物理重复由诊断计数单独披露。COUNT_DISTINCT 统计去重的最终 ObjectRef；SUM 仅对每个最终输出对象的单一被授权数值属性求和，**不得**因多条路径/重复 bridge 行再次加同一目标的值。两端都有读取资格才能区分 orphan 与未授权。聚合失败不能返回旧 value=0 充当成功。

可选查询过滤、排序和投影仍严格按输出端类型解释；从 SOURCE 过滤转到 TARGET 的字段类型必须跟随 OUT/IN 修正。根仅允许现有 M8.1 的有界 `OBJECT_REF / BOUNDED_ROOT_FILTER`，每次至多一步 TRAVERSE、maxDepth=1。**M8.3 两跳不提前放开。**

## 5. R2-A05｜三方来源读取、时点一致性与限额

**必须同源、同事务**：SOURCE/TARGET/BRIDGE 的 allowlist 元数据、白名单表列、三份 schemaFingerprint、每一侧 SELECT 资格先授权；对事实执行通过**同一来源 PG Connection** 的 `READ ONLY + REPEATABLE_READ + autoCommit=false`。不能用不同事务拼接三张表，也不能假装 MetaDB CAS 与 SourceDB read 是一个原子操作。

第一版采用清晰的物理扫描/预算拒绝：来源物理行（SOURCE+TARGET+BRIDGE）**合计 ≤10,000**、读取完整所需的投影字段字节合计 **≤8 MiB**；不允许 SOURCE 10k + TARGET 10k + BRIDGE 10k；JOIN 去重前物理候选/路径预算 ≤10k，去重后对象/LinkFact ≤10k，串联校验与最终准入统一 **3s** deadline（不承诺操作系统/HTTP 总墙钟绝对 3s）。响应 JSON ≤8 MiB，分页 1–50，冻结快照 TTL ≤300s；M8.1 全局 32 slots/16 MiB 及 4 并行 builder 准入共用，不为 Bridge 绕过上限。

扫描必须有物理前置上限（例如 `LIMIT maxRows+1` + 实际消费计数）并累计物理字节/已探测行；超额**不可截断为 SUCCESS**。桥表没有 `unique pair` 时，早期 JOIN 可能产生高扇出，必须在建立不可控中间列表之前计算/核实候选量；只在预算完整满足后创建冻结 result。测试必须包含接近 10k 和 10,001 反例，以及不同 SELECT 故障与真实 EXPLAIN，记录慢 SQL/连接与源端锁竞争的行为。

来源结构/授权须在生成成功信封之前再次核对，以抵御 metadata/source grant 变化；桥表证据 freshness 只能为 UNKNOWN，不能把 `schemaFingerprint` 或 `queryDigest` 冒充事实内容 hash / 历史重放凭证。

## 6. R2-A06｜结果 JSON 严格 Bridge 专属证据合同

**本轮新增第二份机器候选**：[Bridge 专用 local-result-v2 Schema](candidates/m8-2/local-result-v2-bridge.candidate.schema.json)，由现有 M8.1 结果候选继承结构，仅允许由服务端判别执行结果分流验证：

- **成功**：`queryVersion=local-query-v2`、`linkMappingMode=BRIDGE_TABLE`、`sourceReadRefs` 强制恰好各至少一条 SOURCE/TARGET/BRIDGE（本期三角色各一条）、`versionLock.sourceSchemaFingerprints` 强制 SOURCE/TARGET/BRIDGE 全部齐备；不改 `bindingVersions` 的 **2 OBJECT + 1 LINK** 语义，Bridge 事实来源不是第三 Object Binding。
- 每条 Bridge 派生的 `links[].sourceReadRefs` 至少三个引用，服务端断言三者均指向同一执行信封内**不同角色**的来源记录。结果候选提供 `bridgeDuplicatePairRowCount`（当前 TRAVERSAL_SCOPE 内匹配 pair 多余物理行数），不泄漏超出当前用户授权范围的全局 bridge 明细。
- 当前已通过 M8.1 的 FK 响应继续使用**原** `local-result-v2.candidate.schema.json`；不能直接给所有旧 FK 请求都强制要求 BRIDGE role 或 `linkMappingMode`。查询返回映射模式从服务端持久 LinkBinding 推导，而不是信任传入 `mode`；非法伪造 Bridge 输出应由运行时校验拒绝。
- `FAILED` 复用原 v2 失败 envelope `status/executionId/queryVersion/code/message/fieldPath?`，严禁 `objects,links,sourceReadRefs,aggregate,nextCursor` 等部分内容；必须有安全字段白名单检查并避免物理表名/主键值出现在无权消息中。
- 服务器还要做 JSON Schema 无法证明的**跨字段完整性检查**：三个 role 的 SourceReadRef ID 相异、schemaFingerprint 与 Link Revision 映射一致、LinkFact 的 SOURCE/TARGET 按 LinkType 原方向且全部被当前授权覆盖、签名游标 actor+快照闭包+Bridge 当前结构/SELECT 绑定，真实读取事务时间可信且来源数据未声称可历史重放。

### 结果示例（字段剪裁仅说明新增字段，不是完整合规响应）

```json
{
  "status": "SUCCESS",
  "queryVersion": "local-query-v2",
  "linkMappingMode": "BRIDGE_TABLE",
  "bridgeDuplicatePairRowCount": 1,
  "sourceReadRefs": [
    {"role": "SOURCE", "id": "<generated UUID>", "table": "maintenance_order"},
    {"role": "TARGET", "id": "<generated UUID>", "table": "equipment"},
    {"role": "BRIDGE", "id": "<generated UUID>", "table": "maintenance_order_equipment"}
  ],
  "versionLock": {
    "bindingVersions": ["SOURCE OBJECT revision", "TARGET OBJECT revision", "LINK revision"],
    "sourceSchemaFingerprints": ["SOURCE fingerprint", "TARGET fingerprint", "BRIDGE fingerprint"]
  },
  "links": [
    {"source": "MO-001 ObjectRef", "target": "E01 ObjectRef",
     "sourceReadRefs": ["SOURCE UUID", "TARGET UUID", "BRIDGE UUID"]}
  ]
}
```

注意以上是**示意片段**（不是合法 schema 实例）；正式回应仍包含 M8.1 既定 `executionId/queryDigest/resultSnapshotRef/budget/quality/objects/links` 等全部强制字段，不可将示意片段作为测试期望。

## 7. R2-A07｜冻结游标及当前授权闭包（新增 Bridge、重启失效）

运行时 `Frozen` 保存映射 mode=BRIDGE_TABLE、Bridge sourceId/schema/table/两 FK 列、bridge schemaFingerprint、LinkBinding id/revision/digest、双端 M5 版本 digest、Local Snapshot payload、当前主体、每次执行结果/TTL、冻结 JSON 精确字节预算。无独立 Bridge Binding ID，Bridge mapping 已写入 LinkBinding revision digest。

1. `pages` 接口继续只接受签名的 `v2.` opaque cursor；不能让客户端指定 table/revision 代替 LinkBinding 锁；恶意篡改 400/410。
2. 每页按当前主体重新验证 package、snapshot、LinkBinding revision、SOURCE/TARGET M5 绑定版本、**BRIDGE 三列源/表读权限和结构指纹**；来源不可核验 fail-closed，不输出旧对象/边、不会泄漏桥表是否存在。
3. SELECT 撤销后当前 frozen execution 标记永久无效；即使重新 GRANT，旧 cursor 仍 410。JVM 真重启/TTL 过期同样 410；要恢复需从冻结配置**重新执行**，而不是复活旧页。
4. 物理 bridge 数据变更但 schema/权限不变时，已冻结的**成员仍是本次已读结果**；新请求重新读取最新源事实。不能宣称旧页证明源表当前内容或能历史重放 PG 快照。

M8.1 旧 FK cursor 的各项已有保障与返回码语义必须保持，不能在 Bridge 的分支中共享错误的旧 `Frozen` 身份。

## 8. R2-A08｜错误目录、恢复操作和写回纪律

| 服务端拒绝场景 | HTTP/错误码候选 | UI 恢复，不泄漏私有来源 |
| --- | --- | --- |
| v1 携带 `bridgeSourceRef`，v2 携带 `fkOwner`/SQL 或未知字段 | 422 `MALFORMED_LINK_CONTRACT` / `UNSUPPORTED_BINDING_MODE` | 保留表单，指出合同版本与具体字段 |
| v2 的 LinkType 非 MANY_TO_MANY、来源两列与端点错位/不兼容 | 422 `LINK_CARDINALITY_MISMATCH` / `LINK_BRIDGE_COLUMN_INCOMPATIBLE` | 重选 LinkType、修正两列方向 |
| 包/快照/双端 digest 不一致或被篡改 | 404/409 `LINK_ENDPOINT_VERSION_MISMATCH` / `BINDING_VERSION_DRIFT` | 重新打开已冻结快照，不自动切 latest |
| 三方指纹漂移或有效 Bridge 映射无来源资格 | 409 `LINK_SOURCE_SCHEMA_DRIFT` / 403 `SOURCE_NOT_ALLOWED` | 去授权/来源页修复后 FULL 重验 |
| 冻结游标桥表 SELECT 已撤销 | 403/410；旧游标重新授权后仍 `RESULT_EXPIRED` | 当前结果立即失效，重新执行 |
| 操作 UUID 不一致/同键不同体/已提交 UNKNOWN | 409 `IDEMPOTENCY_KEY_CONFLICT` 或同键同体返回原 Revision | 原幂等键读回回执，不盲目创建新版本 |
| 三表扫描/Join 超上限、结果冻结容量超限、deadline | 429 `QUERY_BUDGET_EXCEEDED` / `RESULT_SNAPSHOT_BUDGET` / 503 `QUERY_TIMEOUT` | 减少根/收紧来源，禁止伪造 0/空结果 |
| 无边、只有 NULL、有限 orphan | 成功 `COMPLETE` / `PARTIAL` | 显示「真实 0」「已知子集」不同文案，不把未知授权显示孤儿 |

这些是**候选编号**；R3 必须对照旧生产错误码目录统一最终常量，禁止引入语义重叠的新异常码。无权时需要统一安全 envelope，不能通过 403/404 差异暴露他包 LinkBinding 是否存在。删除/重试/回滚的 UI 不能误认为可覆盖不变历史修订。

## 9. R2-A09｜选择本期能力范围而非隐性扩大

| 切片 | 本次 R2 可进入后续 UX 的合同 | 拒绝进入本期 |
| --- | --- | --- |
| 原生定义 | 同一 Snapshot 的两种自定义 ObjectType + MANY_TO_MANY LinkType | 临时 draft 自动执行、跨 package |
| 端点与来源 | 两个 M5 Binding 的精确修订、一个同 PG allowlist Bridge table、各一 FK | 多桥表、跨 PG、联合 FK、任意 SQL、桥表作为第三业务 ObjectType |
| 查询 | 一个方向确定的 TRAVERSE + M8.1 原有 FILTER/SORT/PROJECT/PAGE/AGGREGATE | 第二 Traverse/递归/跨多条路径/M8.3 |
| 关系身份 | pair 存在性、逻辑 LinkFact 去重、物理重复证据 | 同 pair 多事件、时态/有效期/桥表属性求和 |
| 认证 | B0 package/Workspace、当前来源白名单/SELECT、每页复核 | 未落地的行列级权限，不能虚构功能 |
| 运维 | 前向 PG migration 与旧版本一同存续 | 改 V8/V9/V10、重建既有库、把 Linux CI 视为 Windows UAT |

## 10. R2-A10｜方案取舍与高保证可证伪验收

**接受**：共用 Head/Revision/Operation + 前向 Bridge 互斥映射扩展；local-link-binding-v2 Bridge 专属请求合同；原 `local-query-v2` 单跳接口并按持久映射 mode 执行；Bridge 专用成功结果机器校验；真实三表来源证据/当前资格/预算。

**否决**：
1. 另一个独立 bridge-join 数据开发入口或新菜单（破坏用户故事）。
2. Bridge 行直接创建第三种 ObjectType 或根据 LinkType 伪造所有可能边。
3. 借修改 V10、触发器或回填历史 digest 来“简化兼容”。
4. 把 source/target 字段同名猜测、implicit CAST 或空字符串替换为 NULL。
5. 在 2 端验证成功后补读 Bridge，却称三方一致。
6. 用最终 `DISTINCT LIMIT` 隐瞒桥表重复、孤儿或超预算。
7. 在 QUERY 结果没有 BRIDGE role/3 fingerprints 的情况下声称“有完整来源证据”。
8. 没有正式 OFIP-0010 M8.2 独立授权就提交 Java/Flyway/React 生产实现。

### 接受/拒绝测试（仍需在 **同一 #103** 实施并真实跑）

| 测试门槛 | 精确必须证明的行为 | 计划真实证据 |
| --- | --- | --- |
| C01 | v1 FOREIGN_KEY 的 POST/GET/UPDATE 和 JSON shape 保持，v2 Bridge schema 正例和额外 SQL/FK 字段反例，未知模式 422 | DTO/schema contract 与 PG17/HTTP |
| C02 | 冻结 M:N Native + 双端 M5 版本 + Bridge 3 fingerprints；错包/错 snapshot/revision/digest/列类型严拒 | Native/M5/LinkBinding PG17 Full E2E |
| C03 | 只读三表同一 PG 事务；真 FULL，`nullBoth+nullSourceOnly+nullTargetOnly+orphanThreeBuckets+matchedPhysical=bridgePhysical` | 隔离 PG17、READ ONLY/REPEATABLE_READ 并发源事实注入 |
| C04 | MO-001 OUT E01/E02、E02 IN MO-001/MO-002，方向不反转，三个 SourceReadRefs 对应版本/来源 | Java HTTP 输出与 schema 校验 |
| C05 | 同 pair 2 物理行不重复 LinkFact、SUM/COUNT_DISTINCT 不重复；无稳定行主键不伪造证据 | PG 真实重复 pair + HTTP AGGREGATE |
| C06 | OUT/IN 各自 scoped orphan；NULL/空串/未授权/真零互斥；未知授权绝不算 orphan | 真实 PG/JWT/Session 授权正反例 |
| C07 | V10→新 Flyway 顺序升级、历史 FK digest/receipt 逐字节不变、CAS/CREATE & UPDATE 双 worker 并发/UNKNOWN 重启恢复 | Flyway isolated Upgrade + PG17 幂等 |
| C08 | 源三表物理 10,001、Join 10,001、>8MiB、>3s，错误无 objects/links/cursor；有真实 EXPLAIN | SQL 计划 + HTTP 压力测试 |
| C09 | Bridge SELECT 撤销/恢复：旧 cursor 410 不复活；改变 Bridge DDL 指纹拒绝；源内容变动后的冻结页不重算 | 真 PostgreSQL GRANT/REVOKE/DDL/JVM restart |
| C10 | 原 M8.1 FK + M7 v1 + Native/M5 + JWT/Session + Workspace + Windows PS parser，最新 Head 三组 Required Quality | 真实 CI 全套回归，同 SHA |
| C11 | 全新低保真和高保真分别通过产品签收，正式 React 重开/保存、错误恢复和 375px 用户操作 | 单 PR HTML prototype → Chrome → production React |
| C12 | 仅本 PR 覆盖 M8.2，无第二个功能 PR 或未获准 M8.3/Action 代码；完整功能交付说明与下一步 | PR diff/审查/签收记录 |

### 进入 UX-Low 的签收前置（当前仍待正式裁决）

- [x] R2 技术评审**候选文件与两份 Draft JSON Schema**已经准备、可交叉审查；不是生产 schema。
- [ ] 产品 Owner 审定 R1 D1–D8 与本轮 R2-A01–A10 推荐路线，特别确认“同 pair 一逻辑边”及 **前向共表互斥模式**。
- [ ] 维护者/架构审查接受三方来源与预算/权限完整合同，且 OFIP M8.2 决策需要另行正式批准；UX 阶段可在最终研发授权之前，但 **Java/Flyway/React 生产开发必须在 UX-Low/UX-High 和正式 OFIP 后**。
- [ ] 在**同一 Draft #103** 提交全新、可操作 UX-Low，不额外创建 PR；全新 UX-High 在低保真签收后推进。

**R2 评审结论**：上述合同在既有 M8.1 实际代码约束下具备可实施路线，建议“**有条件技术通过，提请产品审定；生产开发仍关闭**”。若源代码、合同候选 Schema 与产品审定有分歧，先在 #103 记录并修订，不用暗改生产行为避开评审。
