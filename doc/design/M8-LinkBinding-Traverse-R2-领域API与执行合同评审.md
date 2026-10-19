# M8 R2 · LinkBinding 领域状态机、API/Schema 与有界 Traverse 技术合同评审

> **Status: R2 Review Candidate — NOT APPROVED.** 日期：2026-10-19。
>
> 上游：[M8 Issue #47](https://github.com/gitfortian/ontoforge/issues/47)；第一轮已签收并合并：[PR #77](https://github.com/gitfortian/ontoforge/pull/77) / M8 R1；当前权威提案：[OFIP-0010](../proposals/OFIP-0010-M8-LinkFact与有界遍历.md)（**Discussion / Decision Pending / Implementation Not Started**）；基座：[M7 #75](https://github.com/gitfortian/ontoforge/pull/75)。
>
> 本文是对**M8.1 外键双向一跳**的 R2 第二轮领域/API/安全/查询规划；M8.2 桥表、M8.3 显式两跳只保留扩展合同和后续切片，不作为首切片验收。**本 PR 是设计，不包含 Java/React/Flyway/Schema 生产变更，不代表 UX-Low/UX-High 已签收，也不代表维护者 OFIP 投票。**

## 0. 基座核验（先界定真实代码已做什么）

1. **Native V0.2** 的 LinkType 包括 id、apiName、sourceTypeRef、targetTypeRef、cardinality（ONE_TO_ONE/ONE_TO_MANY/MANY_TO_ONE/MANY_TO_MANY）及 evidenceRequired。这只是模型定义；冻结为 LOCAL_SNAPSHOT 后才有可依赖的模型身份。已有 Native 编辑器“关系类型”页签，尚无通用 LinkBinding 实例绑定工作台。
2. **M5 DataBindingService**：端点 Object Binding revision 是不可变证据，包含 bindingId/revision/digest、同 Snapshot/ObjectType、sourceId/schema/table/PK 列/fieldMappings/schemaFingerprint/identityDomainId；数据库列身份必须具备 PK 或单列 UNIQUE，且至少两项非主键属性映射。服务端现有受控来源目录，不能绕过白名单将客户端 SQL/表名直接执行。
3. **M7 ObjectSetQueryService**：local-query-v1、固定类型合同、单端绑定版本闭包、有限只读 PG REPEATABLE_READ、最多 10,000 来源行、最多 8 MiB、3 秒来源执行 deadline、最多 50 条/页、临时结果冻结/签名 cursor（最长 5 分钟）、逐页授权/来源复核及 410 失效。M7 运行时 StoredSnapshot 属于内部实现；**不提供可直接假定的多端 Traverse API 或多源历史快照**。
4. **既有 traceSet** 是制造样例限定查询，不是用户自建关系来源映射；不能将其通过测试当作 M8 通用性验收。M7 前端对象查询在“对象集合查询”工作台，M8 一步遍历从这个现有工作流自然延伸。
5. **PostgreSQL 数据边界**：应用元数据 JDBC 与受控事实来源 JDBC 可能分属不同连接；不能宣称保存 LinkBinding 的元数据事务与读取来源事实是一个原子事务。只读来源核验/执行必须在事实来源侧的**一条**隔离事务中完成，应用侧持久化与源侧通过版本/结构证据及执行前后复核隔离。

## 1. R2 冻结候选和不变量

- M8.1 只处理同一个不可变 LOCAL_SNAPSHOT 下的两个用户自定义 ObjectType 与一个 LinkType。
- 只接受模式 FOREIGN_KEY；FK 只能在 SOURCE 端或 TARGET 端，引用另一端 **M5 已绑定的权威身份列**。关系方向永远是 LinkType 定义的 source → target，不因 FK 所在端、OUT/IN 操作而改变。
- 实际关联来自受控 PG 中的准确、类型兼容的 FK = PK 匹配；无隐式 text cast、trim、大小写折叠或跨身份域合并。NULL FK = 无关系，不等同空字符串。
- LinkFact 键候选：snapshotId + linkTypeId + source ObjectRef（含 identityDomainId 与类型化 PK）+ target ObjectRef。**来源证据、读取时间、重复物理行数不改变逻辑关系键**；业务平行边/时态关联属于未来设计。
- 绑定不可变 revision，不同版本有明确 digest；新修订通过 CAS 生成。已绑定历史版本能否在当前资格下执行要显式评审（本轮建议：符合全部当前授权/来源约束的**明确历史版本**可以查询，不静默跟随 head）。
- 结果只有一个当前执行 scope，任何失败都不能返回未核验的部分对象/关系载荷；允许的 PARTIAL 仅限**确定、可授权、且属于此次遍历范围内**的孤儿质量情况。

## 2. 版本模型、身份与状态机（R2-D1）

### 2.1 建议核心实体

| 对象 | 身份/不可变内容 | 状态与责任 |
| --- | --- | --- |
| LinkTypeDefinition | snapshotId、payloadSha256、linkTypeId、source/target ObjectType、cardinality、evidenceRequired | 只表达关系类型定义；不保存实例边 |
| EndpointLock | bindingId、revision、digest、identityDomainId、objectTypeId、sourceId、schema/table、PK/sourceKey、schemaFingerprint | **双端**独立引用 M5 已冻结且当前可核验的 Binding Revision；相同 Snapshot |
| FKMapping | fkOwner=SOURCE/TARGET、fkSourceColumn、referencedOwner、referencedIdentityColumn、匹配类型、Join equality 规则 | FK 列从受控的 FK 所在端真实来源表选择；必须核验列存在、源读取资格、类型兼容 |
| LinkBindingHead | linkBindingId、packageId、snapshotId、linkTypeId、currentRevision、CAS 时间戳 | 同一 snapshot + linkType 在 MVP 中最多一个 head；不代表所有旧 revision 均失效 |
| LinkBindingRevision | linkBindingId + revision、digest、typeHash、两端 EndpointLock、FKMapping、保存时 schemaFingerprint、checkedAt、validationScope、quality | 修订内容不可变；明确前置依赖版本，不自动换 latest |
| LinkFact | linkFactKey、source/target ObjectRef、linkTypeRef、linkBindingRevision、SourceReadRefs、关系证据 | 查询时从真实来源得出；M8.1 不持久化全量实例边 |
| TraversalExecution | executionId、queryDigest、root scope、direction、LinkBinding 与双端版本闭包、临时结果快照 | 一次执行与所使用的当前授权/来源资格绑定，游标不授予新授权 |

**原则：** LinkBinding 的版本锁不允许客户端伪造。服务端根据指定 snapshot、LinkType 和指定 M5 revisions 重新读取 digest，校验来源结构指纹，计算规范 digest。不同 identityDomainId 即使 key 文本相同也不能配对。

### 2.2 状态转移候选

- **未配置**：LinkType 定义已冻结，尚无 LinkBindingHead → 页面提示“尚未绑定实例关系”，不可查询。
- **编辑中（客户端）**：从已有 revision 复制为待编辑草稿，选择 FK/端点；不修改现有有效 revision，也不自动创建正式 DB 状态。
- **预演/核验**：只读运行，返回 ValidationReport + 作用域 + 证据 + 可用期限（或 digest）和问题列表；该动作不新建 revision、不改变激活指针。
- **校验通过可保存**：结构、权限、基数检查完整；允许 **VALID**（无影响范围内孤儿）或 **VALID_WITH_ORPHANS**（有可确认孤儿、允许按 PARTIAL 查询）两种质量结果。错误/权限不明、身份冲突、超预算的 **FAILED** 不允许保存为可执行 revision。
- **创建**：expectedRevision=0；同一 (packageId, snapshotId, linkTypeId) 若已存在 head，则 409；用应用 DB 事务插入 head + revision=1，CAS/idempotencyKey 防重。
- **修订**：expectedRevision=head.currentRevision；成功时保存 revision+1，CAS 移动 head；失败时保留用户编辑，绝不覆盖最新 revision。
- **读取**：显式 revision 可读；历史 revision 标记 historical=true；读取历史不代表其当前仍能执行。
- **执行资格判定**：查询时同时检查引用版本仍完整、两端权限仍有效、两端 sourceSchemaFingerprint 与 LK 映射一致、LinkType 定义 hash 与快照一致。建议**允许合法显式历史版本查询**，但若来源/身份域/结构变化导致该旧修订无法核验，则 409 fail-closed。新 head 的存在本身不使旧结果瞬间变成错误；真正的授权/来源资格变化必须失效旧快照。
- **弃用/删除**：M8.1 不提供破坏性硬删除；历史修订不能静默复活或覆盖新 head。需要撤销/停用 LinkBinding 的动作单独 R2/R3 决策，MVP 最低要求是授权/来源撤销会禁止执行。

**待确认：** 验证报告与保存之间出现来源或端点变化时，必须再核验/拒绝保存，而非把旧 validate token 当永久资格。保存响应丢失时，客户端按 idempotencyKey/read revision 恢复，禁止盲目重建。

### 2.3 FK × 基数合法性矩阵

| FK 位置 | 可自然表达的类型 | 必需约束/拒绝 |
| --- | --- | --- |
| SOURCE 外键 → TARGET 唯一身份列 | MANY_TO_ONE | SOURCE 至多一个 TARGET；TARGET 可有多个 SOURCE；反查无须改变边方向 |
| SOURCE 外键 → TARGET 唯一身份列 | ONE_TO_ONE | 额外确认 **每个 TARGET 至多一个 SOURCE**，应有真实约束或完整请求相关集合的验证；否则冲突失败 |
| TARGET 外键 → SOURCE 唯一身份列 | ONE_TO_MANY | 每个 TARGET 至多一个 SOURCE；SOURCE 可有多个 TARGET |
| TARGET 外键 → SOURCE 唯一身份列 | ONE_TO_ONE | 额外确认 **每个 SOURCE 至多一个 TARGET**；多个不同 target 行指向同 source 为可达冲突 |
| SOURCE 或 TARGET 外键 | MANY_TO_MANY | **M8.1 明确不支持**；在 M8.2 使用 BRIDGE_TABLE，不通过重复端点 PK 绕过 M5 约束 |
| SOURCE 外键 + ONE_TO_MANY / TARGET 外键 + MANY_TO_ONE | 方向与表达能力不匹配 | 配置校验时报 422 INVALID_LINK_MAPPING；用户可修正 FK owner 或 LinkType 定义后重冻快照 |

**自关联：** 当 sourceTypeRef=targetTypeRef 时仅允许显式 FK 与同快照的确定端点绑定，不将 source/target 名称相同视作方向不明；M8.1 测试自环单步能有限终止，二跳循环属于 M8.3。多重平行边、联合 FK/PK、跨 PG/跨 package 不是本期能力。

## 3. 验证语义与质量双作用域（R2-D2）

### 3.1 Validation 与 Traverse 必须分开

- **VALIDATION_SCOPE**：用于保存前校验 FKMapping + 端点 Binding。可选择 SOURCE_TABLE_BOUND 或 TARGET_TABLE_BOUND 的明确受控范围；如果全范围超过预算，则报告 **INCOMPLETE_VALIDATION**，不将样本称为全局 VALID。预览最多只返回明确标记的 SAMPLE / UNKNOWN_TOTAL；“抽样有孤儿”不能证明整份绑定基数合法。
- **TRAVERSAL_SCOPE**：输入一个有权限的、已核验根 ObjectRef，或 M7 类型化有界根集合查询；结果质量只针对本次可证明的根集合/关系事实闭包。全局孤儿不污染一个无关 root 的 completeness。

### 3.2 状态矩阵

| 场景 | 保存前校验 | 某次 OUT/IN 查询结果 |
| --- | --- | --- |
| FK 为 NULL | 合法空关系计数，仅在覆盖该事实的范围 | 不产生 LinkFact；若 root 当前 FK NULL，为 SUCCESS/EMPTY 且准确，不虚构断链 |
| FK 为 STRING 空文本 | 按精确值匹配；不能当 NULL | 能匹配合法空文本目标时返回边，否则本 root 范围内 ORPHAN |
| FK 非空但另一端无匹配 | VALID_WITH_ORPHANS，仅当可完整核验且允许保存该质量 | 只有本次 root 受到孤儿影响且权限充足时才 PARTIAL / KNOWN_SUBSET |
| 一端未授权/表不存在/读取未知 | FAILED，不泄漏存在性 | FAILED、无边/对象载荷；不能标为 ORPHAN |
| 同一已绑定 ObjectRef 身份/值冲突 | FAILED / 409 | FAILED，不能先按过滤条件遮蔽冲突 |
| 声明 cardinality 不成立 | FAILED / 409 | FAILED，不允许挑一条或转成 PARTIAL |
| 物理结果重复，但同一个逻辑 LinkFact | M8.1 由端点唯一键约束；逻辑去重防御 | 一条 LinkFact，计数与输出对象去重；M8.2 验证真实桥表重复物理行 |
| 来源或关联大到无法完整核验 | FAILED / 429 | FAILED，禁止“LIMIT 后看起来 0/10 条”冒充完整 |
| 并发来源漂移 | 不允许保存旧校验结果 | FAILED，已签发分页 snapshot 永久失效 |

**质量信封建议**：quality 包含 accuracy=(EXACT|UNKNOWN)、completeness=(COMPLETE|PARTIAL)、freshness=UNKNOWN、answerScope=(REQUESTED_SCOPE|KNOWN_SUBSET)、scopeKind=(VALIDATION_SCOPE|TRAVERSAL_SCOPE)、knownOrphanCount、nullFkCount、validationCoverage=(FULL|SAMPLE|NOT_VERIFIED)。只要权限不足或预算失败，改为 FAILED 信封，不发成功质量字段当作假证据。结果仅在确有本次 root 的孤儿时允许 PARTIAL，已知子集 count 可准确，但不能当完整影响人数/对象数。

**数据规模问题**：当无法在预算内核验 FK 全表基数和孤儿时，创建并激活资格如何成立？本轮候选为：只允许 FULL 且预算内完成的校验生成可执行修订；大型来源未来需增量/索引验证新提案，不放宽首版 fail-closed。

## 4. 版本化 API 与 Schema 决策（R2-D3/D8）

### 4.1 D8 决策建议：采用 local-query-v2，而不是往 v1 加 TRAVERSE

| 路线 | 优点 | 核心风险 | R2 建议 |
| --- | --- | --- | --- |
| 给 local-query-v1 加 TRAVERSE | 单版本入口，前端统一 | v1 的 root Binding 是单端；local-result-v1 的 BindingVersion.kind 只能 OBJECT，quality.completeness 只能 COMPLETE，sourceReadRef 为单个来源；追加多端/LinkFact/PARTIAL 会破坏严格合同和现有消费者 | 不选 |
| **增加 local-query-v2 / local-result-v2** | 保留 M7 路径/Schema/响应不变；新增独立 LinkBinding ref、根/输出类型、双端来源读取、PARTIAL、link/path 证据和多端结果冻结 | 需要维护新版本合同/适配服务，代码不能不经评审重复复制整个 M7 | **推荐** |
| 上马 Native V0.2 正式 ObjectSet/IndexSnapshot | 可统一长远语义 | 当前没有正式发布、授权上下文和索引合同；会伪造已存在的生产能力 | 本阶段禁止 |

**建议 v2 不改变 v1 的 GET/POST 合同与行为**；由明确 queryVersion 路由或独立 endpoint 解析 v2，要求 v1 回归测试完全通过。v2 与 v1 的共享组件，只能在有实际消费者后抽取纯领域验证、cursor 签名策略和 PG 只读操作，避免引入空 Core/Graph 项目。

### 4.2 LinkBinding REST/API 候选（路径名称仍待 R2 正式签收）

| 路径（候选） | 方法 | 语义 |
| --- | --- | --- |
| /api/runtime/link-bindings/validate | POST | 只读验证 LinkBindingDraft + 明确验证 scope/budget，返回 validationReport、candidateDigest、schemaFingerprints、错误与恢复 |
| /api/runtime/link-bindings | POST | create expectedRevision=0 + idempotencyKey；复核校验/权限/指纹，201 返回 immutable revision |
| /api/runtime/link-bindings/{linkBindingId} | PUT | CAS expectedRevision=currentRevision；新不可变 revision；冲突返回 409 |
| /api/runtime/link-bindings?packageId= | GET | 只返回当前授权 package 的关系配置摘要及版本 |
| /api/runtime/link-bindings/{linkBindingId}/revisions | GET | 返回可见不可变 revision 列表 |
| /api/runtime/link-bindings/{linkBindingId}?revision=N | GET | 明确 revision 的只读详情；historical 标记 |
| /api/runtime/object-sets/traverse | POST | local-query-v2：解析类型化根、最多一段 Traverse、冻结关系结果与质量 |
| /api/runtime/object-sets/traverse/pages | POST | opaque cursor 翻页，**重新核验两端授权/来源/LinkBinding 与类型快照闭包** |

**请求草案（结构概念；不是机器 schema）：**

- LinkBindingDraft：modelSourceRef {kind:LOCAL_SNAPSHOT,snapshotId}、packageId、linkTypeId、sourceBindingRef {bindingId,revision,digest?}、targetBindingRef {bindingId,revision,digest?}、mode=FOREIGN_KEY、fkOwner=(SOURCE|TARGET)、fkSourceColumn、referencedPk=OTHER_ENDPOINT_SOURCE_KEY、expectedRevision、idempotencyKey（仅 create）、expectedSchemaFingerprints。
- TraverseRequest：queryVersion=local-query-v2、rootTypeRef、rootBindingRef、rootSelector (one ObjectRef | bounded RootFilter of same ObjectType)，steps=[TRAVERSE {linkTypeRef,linkBindingRef,direction,maxDepth:1} → TargetFilter* → terminal(SORT?,PROJECT?,PAGE or AGGREGATE)]；根谓词**只出现于 rootSelector**，不允许在 TRAVERSE 之外重复声明 ROOT FILTER 造成两套根作用域、limits。**禁止**从旧 M7 cursor 直接解析为 root 授权，除非经独立合同确保根集合来源与当前主体/版本闭包。
- 响应：executionId、queryDigest、resultSnapshotRef、expiresAt、rootTypeRef、outputTypeRef、bindingVersions [{kind:OBJECT|LINK,...}]、versionLock、sourceReadRefs[]、objects[]、links[]、linkCount、pathCount、quality、budget。M8.1 单步的 pathCount 可等于逻辑 linkCount，但字段必须独立命名，为 M8.3 保留路径维度。

### 4.3A 可独立审查的结构化合同候选

为了使第二轮审核不只停留在口头 DTO，随本设计 PR 同时提交三份**非生产** Draft 2020-12 JSON Schema 候选和负例测试：

- [local-link-binding-v1.candidate.schema.json](candidates/m8-r2/local-link-binding-v1.candidate.schema.json)：仅 M8.1 FOREIGN_KEY，包含同 Snapshot 引用、双端明确 revision、FK owner/column、expectedRevision、create idempotencyKey 和 FULL validation scope。
- [local-query-v2.candidate.schema.json](candidates/m8-r2/local-query-v2.candidate.schema.json)：ObjectRef/有界根筛选、显式 root binding、一段 TRAVERSE、M7 算子子集、只能下调的预算限制。
- [local-result-v2.candidate.schema.json](candidates/m8-r2/local-result-v2.candidate.schema.json)：两端 Object Binding + LinkBinding 版本、多个 SourceReadRef、对象与 LinkFact 结果、COMPLETE/PARTIAL、来源 freshness UNKNOWN 和严格 FAILED 无载荷。
- [R2 候选合同测试](tests/test_m8_r2_candidate_contract.py)：覆盖候选 Schema 有效性、假 SQL / Release、错误类型、两段 Traverse、预算升级、伪造部分结果及 v1 非兼容输入。

**明确的可信边界**：

1. `referencedIdentityColumn` **不能由客户端自由指定**。它必须由服务端从已锁定的“被引用端 M5 Binding revision.sourceKey”解析，校验 FKOwner 相反端、identityDomain、来源列类型与 PG 一致性。候选 LinkBinding 请求因此只允许明确 FK 所在端及其真实来源列。
2. 结构 Schema 的 `contains` 最多只能检查**恰好一个** TRAVERSE / 终结步骤；运行时还必须检查步骤先后、rootSelector 根谓词与 TRAVERSE 后 TARGET FILTER 的端点类型、字段映射、M8.1 FK×基数约束、对象身份和授权，不可把 Schema PASS 误称为可执行验证。
3. **多端证据不可缩水**：成功结果至少包含 SOURCE 和 TARGET 两种 role 的 SourceReadRef 与 SchemaFingerprint，并冻结 source/target 两条 OBJECT Binding 版本和一条 LINK Binding 版本。即使自关联共享物理表，也必须记录两端语义角色，不可用 M7 的单个 sourceReadRef 冒充双端复核。候选结果 Schema 已以 contains 约束要求这些元素；端点引用真实性仍由服务端按 Binding 重算。
4. 两端 current source/database grants、跨服务元数据版本一致性、关联源权限与 FULL 质量验证均无法由静态 Schema 保证；在生产实现前必须配对真实 PG17 + API E2E 负例。
5. 这些候选文件在 `doc/design/candidates`，**并非**当前运行时解析/发布的 `doc/design/schema` 正式合同，也不授权把 v2 接口提前纳入生产服务。R2 经过产品/架构批准后才可定稿并回填 OFIP-0010。

### 4.3 API 强校验

- 必填/额外字段：schema 的 additionalProperties:false；所有端点引用均精确 UUID/枚举/整数范围验证。未知操作符、超过一步 Traverse、根/输出类型不匹配、无主根、混用两端属性、跨 snapshot、未绑定 FK 来源列均 422。
- FK 来源必须与已锁定端点的实际 sourceId/schema/table 相符，来源标识来自服务端只读目录和白名单，不接受客户端拼接 SQL 标识符或表达式。
- 类型比较必须与 M5/M6 的 PK/属性类型定义一致：STRING 精确大小写/空格，INTEGER 规范化后同值比较；对 DECIMAL/UUID 等不在 M6 对象 PK 合同中的类型不得暗自拓展。
- 成功响应含准确 root 输入类型、relation direction、输出类型、每段明确版本锁与 SourceReadRef；断链展示不得泄露无权端点是否存在。
- 构建可重现 queryDigest：规范化字段顺序/数组顺序/版本闭包；同查询 AST + 明确绑定版本与预算得到同 digest。queryDigest 不代表源数据内容摘要。
- 校验报告、保存结果、执行结果使用各自身份与状态，不能用一个通用 successFlag 抹平资格差异。

## 5. 数据库持久化、隔离与幂等（R2-D4）

**候选两张应用元数据表（迁移编号不在 R2 假定）：**

- link_binding_head(link_binding_id PK, package_id, snapshot_id, link_type_id, current_revision, created_at, updated_at)，UNIQUE(package_id, snapshot_id, link_type_id)。
- link_binding_revision(link_binding_id, revision, content_digest, model_payload_sha256, source_binding_id, source_binding_revision, source_binding_digest, target_binding_id, target_binding_revision, target_binding_digest, fk_owner, fk_source_column, referenced_identity_column, source_schema_fingerprints_json, validation_scope_json, validation_quality_json, checked_at, created_at)，PRIMARY KEY(link_binding_id,revision)。idempotencyKey 与操作关联的约束需单独方案，避免创建响应丢失重复操作。

数据库写入仅在服务端校验成功后开始事务：SELECT head FOR UPDATE，检查 expectedRevision，INSERT immutable revision，CAS 更新 head；失败不污染先前有效 revision。保存前再次检查来源结构与版本锁；若验证到保存之间源数据改变，不能用持久化操作制造数据事实已原子冻结的错误承诺。并发编辑一方返回 409，向用户显示旧/新差异与明确重试入口。

**来源事务**：M8.1 两张实际来源表在同一白名单隔离 PG 来源连接中执行 read-only REPEATABLE_READ；不能先取根表、换另一 PG 连接取目标表再声称同一一致性快照。数据库的 TRUNCATE/DDL 改动与授权撤销必须在执行前后通过 schema fingerprint 与当前授权资格检测守住，不宣称已经拥有历史可复现来源事实。

## 6. 有界执行、去重和分页（R2-D5）

### 6.1 单次执行阶段

1. 身份/权限/模型源与端点 Binding 解析（服务端）；检查 LinkBinding 定义、LinkType 方向、root 类型和版本闭包。
2. 核验根 ObjectRef 或执行有界根过滤，rootKey 不允许脱离对象身份域成为任意字符串；选择事实来源于已有白名单绑定。
3. 在同一次受控 PG read-only REPEATABLE_READ 中执行：根范围定位 → 关系 FK 读取 → 关联目标定位 → 双端主键/关联类型/基数/孤儿检查 → 去重结果 → 排序投影/聚合。
4. 每阶段对**去重之前**的来源读取量、候选关系数与中间 Join 扇出计预算；在生成无界集合之前拒绝超额，不能先做无限 JOIN 再最终 DISTINCT LIMIT。
5. 完成结果、证据/质量后，验证两端和关系版本/来源资格，再冻结本次成员、link 数与排序；成功后一次性生成查询结果和签名 cursor。
6. 每页取出冻结结果，重新检查**全部**涉及端点 Binding revision、LinkBinding revision、Snapshot payload、各来源结构指纹/当前读取资格；任何失败使当前 execution 永久失效，恢复权限旧 cursor 也不复活。
7. TTL ≤ 300 秒、单页 1–50、应用重启后 410；不跨 HTTP 请求持有来源 PG 事务，不在快照中承诺 PG 源历史可读。

### 6.2 限额候选与失败语义

| 限额 | R2 首切片建议 | 必要证据 |
| --- | --- | --- |
| 来源扫描总预算 | 所有根/源/目标物理行合计 ≤ 10,000（不得理解为每端各 10k） | 预算前置且按 sourceReadRef 可核验 |
| FK/Join 中间候选 | **≤ 10,000**，默认不超过全局来源扫描预算 | 高扇出实测 10,001 拒绝，不能截断后 SUCCESS |
| 逻辑关系/路径 | LinkFact ≤ 10,000，单步 pathCount ≤ 10,000 | 对象、关系、路径三套独立计数 |
| 来源读取量 | 多端合计 ≤ 8 MiB | 超过后 429，零部分载荷 |
| 结果冻结内容 | 多端对象/关系/证据合计 ≤ 8 MiB；服务级快照总量需复用/合理限制 M7 内存治理 | 实际序列化超限测试 |
| 执行时间 | 来源与双端权限/结构复核共享总 3 秒预算（壁钟 API 包装另有开销，指标需明确） | 慢 SQL/第二端超时、不可无限等候 |
| AST | 一段 TRAVERSE maxDepth=1；M7 类型筛选/终结步骤仍有界 | 两段/错位置/无 root fail-closed |
| 游标 | 只允许服务端签发、主体/执行/快照版本绑定、最长 5 分钟 | 篡改/跨主体/过期/重启/撤权 410/403 |

以上数字是**设计候选**，不是已运行结论。M8.2 可能需要独立桥表物理行预算；M8.3 单路径数可能大于最终对象数，必须在正式实现前由 R2/R3 明确总预算与路径额度。超时 → 503 SOURCE_TIMEOUT；超预算 → 429 QUERY_BUDGET_EXCEEDED；不能用模糊 TIMEOUT/UNKNOWN 空结果替代。

### 6.3 身份去重和顺序

- output objects：按完整 ObjectRef（packageId, objectTypeId, identityDomainId, key.valueType, canonicalKeyValue）去重，排序补稳定的 ObjectRef tie-break。
- links：按 LinkFact 自己的 sourceRef/targetRef/linkType 身份去重；方向切换不改变 LinkFact 原始 source、target。
- paths：一跳按单 LinkFact 路径标识计数；根有多个对象到同输出对象时，objectCount 不等于 linkCount。
- FK NULL 不贡献 link；非空指向缺失但无权限时 fail-closed，不给敏感存在性信息；只在可完整授权的 root scope 内容许 PARTIAL。
- 稳定 resultSnapshot 的成员与 count 不因源后来 INSERT/DELETE/UPDATE 改变，但新查询重新获取当前事实；页间来源漂移/失权使旧快照丧失执行资格。

## 7. 授权传播与失败恢复（R2-D6）

授权应在以下边界逐个 fail-closed：root package/snapshot、LinkType、source/target Binding、LinkBinding、两张来源表、字段读资格与结果页当前主体。MVP 只声称已实现的包级 JWT 权限和隔离来源白名单；**未实现的真实细粒度行列权限不作为已存在能力**。若未来 RLS/列脱敏，则所有关系存在性/孤儿统计都必须在该授权上下文下重审。

| 故障/操作 | 返回建议 | 用户下一步 |
| --- | --- | --- |
| 关联配置不足、字段类型错误、非法方向、非支持的 FK×基数组合 | 422 INVALID_LINK_MAPPING / INVALID_TRAVERSE_TYPE | 回配置页选择正确 FK/端点 |
| 任一 ObjectRef 冲突、基数冲突 | 409 OBJECT_IDENTITY_CONFLICT / LINK_CARDINALITY_CONFLICT，失败无数据 | 查看有权范围内的问题证据，修正来源后重新验证 |
| 版本 CAS 冲突、已锁版本不合格、DDL 漂移 | 409 BINDING_REVISION_CONFLICT / BINDING_SCHEMA_DRIFT | 保留编辑，重开指定版本、比对再修订 |
| 两端之一无权或 SELECT 撤销 | 403/统一无权码，或 503 来源不可用；只显示授权范围允许的摘要 | 联系授权方；恢复后**重新执行**，旧 cursor 不复活 |
| 根存在但合法 0 关联 | SUCCESS、COMPLETE、empty objects/links | 清晰显示真实 0，不提示修来源 |
| 当前 root 的可确认孤儿 | SUCCESS、PARTIAL/KNOWN_SUBSET + 质量证据 | 显示已知子集，提醒尚有未匹配引用 |
| 全局其他根的孤儿 | 本次 root 输出仍 COMPLETE（如无其他缺口） | 在单独 ValidationReport 中可查看全局质量 |
| 预算、超时、未知来源状态 | 429 / 503，零对象/关系/聚合载荷 | 收紧 root 或调整来源质量后新执行 |
| 过期、重启、任何已复核失败的 cursor | 410 RESULT_EXPIRED | 重新执行，不提供“继续旧页” |

**恢复纪律**：失败显示 executionId、queryDigest（仅允许的非敏感字段）、失效 snapshotRef、目标操作（修配置/重验/重新执行），不留下旧结果对象误当新结果。未保存编辑返回时必须询问保留/丢弃，而不是静默覆盖。

## 8. React 工作台信息架构（R2-D7；不是原型签收）

不增加单独“专业工具”菜单，不把旧高保真复制成 M8。推荐两条自然入口：

- 从 **Native 建模 → 关系类型** 的当前定义页签，在已冻结 Local Snapshot 的 LinkType 上显示配置状态与“配置实例关联”，进入统一 LinkBinding 工作区。
- 从 **对象集合查询 → 当前对象结果/对象详情** 点选已配置的可见 LinkType（正向/反向），进入“一跳关联对象”查询结果面板，且可返回原 root/snapshot（返回时对当前资格重新核验）。

**LinkBinding 工作台 4 个顺序区**：LinkType 定义摘要（只读）→ 两端 Object Binding 版本确认 → SOURCE/TARGET 外键列来源选择 → 预演验证/错误证据 → 保存并返回关系查询（保存与运行分开）。UI 不能同时出现可任意组合的“新建、自动、运行、保存、发布”等多组抢眼按钮。固定顶部版本/来源提示，明确“未配置 / VALID / VALID_WITH_ORPHANS / FAILED / 历史版本不可执行”。

**查询结果区**：root 与方向、输出类型、分页去重对象、LinkFact 明细与来源、quality/answerScope、版本闭包、执行预算；零结果与失败/部分结果三种视觉语义不可混淆。响应中若 ROOT FILTER 输入来自已有 M7 对象集合，需重新解析并核验 root 合同，不能把游标当授权 token。

**UX-Low 黄金故事** 至少包含：首次无关系配置、双端版本不匹配、FK SOURCE/TARGET 切换、ONE_TO_ONE 冲突、孤儿根/非孤儿根区分、验证成功保存并刷新重开、CAS 冲突、来源 DDL 漂移、撤权/恢复后 cursor 410、慢响应覆盖保护、未保存返回、移动端窄屏。UX-High 需要用户另外签收，并在生产 React 运行时端到端复验，不得仅通过静态 HTML。

## 9. R2 测试矩阵与可证伪验收

| 试验 ID | 正例/反例（真实 PG17 / API / Chromium） | 必须断言 |
| --- | --- | --- |
| L01 | 两个自建 ObjectType + LinkType 冻结；端点 M5 Binding PK+至少两非主键映射 | 无固定制造类型 switch；两端同 snapshot + identityDomain |
| L02 | TARGET FK 的 ONE_TO_MANY：EQ-001 → LOT-11、LOT-12；LOT-11 → EQ-001 | OUT=2，IN=1，LinkFact 原始端点不翻转 |
| L03 | SOURCE FK 的 MANY_TO_ONE，多个 SOURCE 指向一个 TARGET | 正/反方向语义和 cardinality 独立正确 |
| L04 | ONE_TO_ONE + TARGET FK 两不同 target 指向同 source | LINK_CARDINALITY_CONFLICT 409，不只返回第一条 |
| L05 | FK NULL 与空 STRING，合法空/未匹配不可混为一谈 | NULL 不产生 LinkFact，空 STRING 精确匹配或可确认孤儿 |
| L06 | 全局 LOT-14 孤儿，但 root=EQ-001 | root 仍 COMPLETE；BindingValidation 可报告孤儿 |
| L07 | root=LOT-14 反向，非空 FK 指向缺失设备 | 仅授权确认时 PARTIAL/KNOWN_SUBSET，准确的 scope 标识 |
| L08 | 权限/SELECT 不足，存在/不存在目标的两组数据 | 相同安全拒绝类别；无可枚举端点/孤儿提示 |
| L09 | 显式历史 LinkBinding Revision 与当前合法端点版本 | 若 R2 通过“历史可执行”则正常查；不静默换新 head |
| L10 | 新修订 CAS 争用、保存响应丢失重试、服务重启读取 | 单 head、不可变修订、幂等与历史版本可观察 |
| L11 | FK/PK 结构漂移、端点版本 mismatch、身份域变化 | 409，当前 cursor 永久失效；恢复需新执行 |
| L12 | 扫描+JOIN 恰超过 10k，中间扇出超过额度、8 MiB 来源/结果、3 秒超时 | 分别 429/503，无部分载荷，超限不得先 JOIN 后 LIMIT |
| L13 | 跨页 INSERT/DELETE/UPDATE、同排序值 tie-break | 原执行冻结成员稳定；新执行读取新事实 |
| L14 | B0 grant 撤销/恢复、来源 SELECT 撤销/恢复、伪造/跨主体 cursor、真实 Boot 重启 | 撤销后旧 cursor 永远 410，不泄露新数据 |
| L15 | Type/Link/Binding 方向错误、两段 Traverse、跨 Snapshot、任意 SQL | 422/403 fail-closed，无 SQL 注入 |
| L16 | React 全部 U1–U8，失败恢复和返回来源 | 可完成配置→验证→保存→OUT/IN→证据→修复闭环 |
| L17 | M2/M3 traceSet、M4–M7 Binding/Query、Native Schema、JWT/local 隔离回归 | 同 head Required Quality PASS；不以 M8 覆盖旧功能 |
| L18 | M8.2 bridge M:N、M8.3 两跳菱形/循环 | 本切片明确 NOT SELECTED，不得误标 PASS |

**预期执行程序**：PG17 实例独立的测试数据库与只读来源角色；Flyway 前向迁移；Spring Boot 真实 HTTP；React/Chromium 真实表单/路由；注入受控 DDL、grant/SELECT 撤销及服务进程重启。客户端权限不能靠前端禁用按钮代替 API 拒绝。

## 10. 需要产品/架构对 R2 逐项批准的决策

| 决策 | 我的建议 | 尚须正式评审的事实 |
| --- | --- | --- |
| **R2-D1** 版本资格 | M5 风格不可变 LinkBinding revision + CAS；明确历史 revision 只在仍合格时可执行 | 当前 head 新 revision 是否改变旧结果当前资格；idempotency 方案 |
| **R2-D2** 质量/孤儿 | 双 scope；允许可确认的 VALID_WITH_ORPHANS、仅相关 root 才 PARTIAL | 校验必须 FULL 才能保存；如表超过预算如何显式拒绝 |
| **R2-D3** 合同分层 | 独立 local-link-binding-v1（MVP），local-query-v2/local-result-v2；v1 原样兼容 | API 具体 DTO、JSON Schema 机器验证与版本迁移 |
| **R2-D4** 来源一致性 | PG 来源单事务 REPEATABLE_READ + 应用元数据 CAS；版本/结构前后核验 | 物理边界、失败恢复与跨数据源元数据不可原子性 |
| **R2-D5** 扇出与预算 | 全链路 10k/8MiB/3 秒候选，Join 候选/关系路径单独计数，超预算 fail-closed | SQL plan 实测预算保护，不能先爆炸再 LIMIT |
| **R2-D6** 授权与游标 | 两端 + LinkBinding 完整版本/权限闭包，每页复核；恢复授权旧 cursor 410 | 无权可见性策略；不假设已有行列级安全 |
| **R2-D7** UX/任务 | 原 LinkType 与 ObjectSet 入口，四段配置/校验/保存/查询，列表 + 证据优先 | UX-Low 首屏负担/返回路径、UX-High 独立签收 |
| **R2-D8** 技术版本 | **local-query-v2 / local-result-v2**，不破坏已验收的 local-query-v1 | 若坚持扩展 v1，需要证明严格 Schema 的向后兼容 |

**本轮不批准直接开发。** 需要完整技术/产品评审后，将批准的 R2 合同写回 OFIP-0010 单一权威文档（保持 pending 直到有权维护者显式投票）；再出**新 M8.1 UX-Low** → 产品签收 → **新 M8.1 UX-High** → 产品签收 → OFIP Accepted → 一条可交付生产纵向 PR。

## 10.1 合同自审：根作用域唯一来源（R2-RV1）

**发现并消除一个设计歧义**：原始草案同时允许 rootSelector 中的有界根筛选，以及 TRAVERSE 之前另外一组 ROOT FILTER 步骤，容易令根对象是否被双重过滤、版本/字段校验按哪一组执行产生歧义。

本 R2 的严格候选已统一为：**rootSelector 是根集合的唯一选择入口**；查询 steps 中恰好一条 TRAVERSE，随后可追加只应用在**输出端类型**的 TARGET FILTER、SORT、PROJECT 和 PAGE/AGGREGATE。结果类型由 LinkType 定义方向 + OUT/IN 决定，不允许客户端自行声明。针对“ROOT FILTER 步骤绕开 rootSelector”增加了独立负例合同测试。

此项是 R2 审查中的候选纠错，尚不是 OFIP-0010 的正式技术决策；实现时仍须对 step 顺序与端点属性做服务端语义校验。

## 11. 本轮产出与验收状态

| 项目 | 真实状态 |
| --- | --- |
| R1 D1–D7 | 产品认可、[PR #77](https://github.com/gitfortian/ontoforge/pull/77) 已合并 |
| R2 需求/领域/API/Schema/预算评审文档 | **本文件：待评审候选** |
| R2-D8 | 推荐 v2；**未签收** |
| OFIP-0010 | Discussion / Decision Pending / Implementation Not Started |
| M8.1 UX-Low/UX-High | NOT STARTED / NOT SIGNED |
| M8.1 PG17/Java/React/Chromium 生产实现 | NOT STARTED / NOT RUN |
| M8.2 桥表、M8.3 两跳 | 规划中，不得提前宣传交付 |
| 正式 Release | NOT RELEASED |

**审核原则：** 本文件有意把歧义显式暴露为 R2-D1–D8 选择项；任何新 REST URL、数据表、Schema、参数与运行时类名都仍是设计候选，不应把设计建议伪装成仓库中已实现的功能。
