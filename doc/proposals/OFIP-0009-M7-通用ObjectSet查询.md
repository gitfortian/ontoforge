# OFIP-0009：M7 通用 ObjectSet AST 与有界查询执行

**Status:** Accepted

**Discussion:** [PR #58 — [DISCUSS][OFIP-0007–0016] M5-M14 需求 Review 与方案设计](https://github.com/gitfortian/ontoforge/pull/58)

**Decision:** Accepted — 维护者于 2026-10-18 在 [PR #58 决策评论](https://github.com/gitfortian/ontoforge/pull/58#issuecomment-6248979717) 显式记录 `[VOTE] Accepted`

**Implementation:** In Progress — [PR #75 通用 ObjectSet 查询纵向交付](https://github.com/gitfortian/ontoforge/pull/75)（真实生产验收尚未完成）

**Release:** Not Released

**Related:** [M7 #46](https://github.com/gitfortian/ontoforge/issues/46) · [M3 #39](https://github.com/gitfortian/ontoforge/issues/39) · [OFIP-0007](./OFIP-0007-M5-通用DataBinding.md) · [OFIP-0008](./OFIP-0008-M6-通用ObjectRuntime.md) · [OFIP-0001](./OFIP-0001-需求提案文档治理与CI证据门禁.md) · [V1.3 候选](../本体产品总体功能与技术架构设计_v1.3.md) · [Native V0.2 候选](../design/核心本体元模型与Native-DSL-AST契约_v0.2.md)

> 2026-10-18 基于 main `29cb194a706ee1785c0f40ef6ddf982493205383` 重新核对的设计候选。M6 PR #68/#71 已合并并通过合并后 S0/Required Quality。维护者已在 [PR #58 决策评论](https://github.com/gitfortian/ontoforge/pull/58#issuecomment-6248979717) 显式 Accepted；M7 生产纵向交付正在 [PR #75](https://github.com/gitfortian/ontoforge/pull/75) 实施，尚未完成验收或发布。现有 M2/M3 的真实受限查询继续有效，不能把本提案当成重做全部查询的授权。

## Re-review after M6 implementation (2026-10-18)

M6 的有界 Object Runtime 已通过 [PR #68](https://github.com/gitfortian/ontoforge/pull/68) 合并；来源撤权与结构漂移的失败语义通过 [PR #71](https://github.com/gitfortian/ontoforge/pull/71) 合并。合并后 main 的 [S0 CI](https://github.com/gitfortian/ontoforge/actions/runs/39107566534) 与 [Required Quality Gate](https://github.com/gitfortian/ontoforge/actions/runs/39107566591) 均 PASS。以下是基于真实 M6 API/实现边界的 M7 设计约束；这不是 OFIP 接受投票，也不授权生产实现。

1. **M6 是点读，不是集合读取。** `POST /api/runtime/objects/read` 接受一个规范键；M7 必须设计有界 bulk source execution，不得对每个对象循环调用 M6 点读端点，也不能继续把 M3 固定制造类型 switch 扩成通用查询。
2. **完整查询属性范围与输出投影分离。** M7 必须对 `primaryKey ∪ filter ∪ sort ∪ aggregate ∪ project` 中所有被使用的属性执行类型转换与冲突核验；只向调用方输出 Project 选中的属性。不能先过滤重复物理行再检查冲突，否则筛选条件可能把冲突隐藏。
3. **预算必须是整次查询的独立上限。** 候选固定上限为物理行/去重对象各 10,000、来源扫描字节总量 8 MiB、执行 3 秒、冻结结果序列化大小 8 MiB、单页 1–50、AST 最多 16 节点。数据库端需在向 JVM 物化字段前核验整次来源扫描预算；M6 的单对象 8 MiB 预算不能乘以对象数。
4. **结果快照不是源数据历史快照。** 首版只承诺同一执行的有界结果成员稳定；不承诺跨请求持有 PG MVCC snapshot，也不把 source schema fingerprint 当成数据 freshness。无可信业务水位时 `freshness/checkpoint=UNKNOWN`。
5. **每页仍须复核权限与来源。** 游标/结果快照不授予访问权限；每页检查当前包权限、Binding/类型版本可用性、来源 schema 与只读访问。来源撤权/断线返回 503、结构漂移返回 409、结果过期返回 410，失败不输出对象/聚合载荷。
6. **执行合同复用 M6 语义。** ObjectRef、精确 INTEGER/DECIMAL 字符串、`PRESENT/NULL/MISSING`、冲突失败关闭与不捏造 freshness 继续保持一致；M7 的 QueryResult 是新合同，不冒充 Native V0.2 的正式 Release/Index/Authorization refs。

### UX-Low prototype candidate

[M7 UX-Low review note](../implementation/M7-ObjectSet-UX-Low评审说明.md) 和 [static prototype](../prototypes/m7-objectset-lowfi.html) 验证了用户流程与失败恢复；产品所有者已于 2026-10-18 明确签收 UX-Low 与 UX-High（[PR #74](https://github.com/gitfortian/ontoforge/pull/74)）。该签收不代替正式决策；OFIP-0009 已由维护者 Accepted，生产实现正在 [PR #75](https://github.com/gitfortian/ontoforge/pull/75) 中进行，真实 PG17/API/React-Chromium 验收仍须通过。

## Motivation

采购分析者希望在自己建模并绑定的 supplier 中筛选“等级为 A 且额度大于 1000”，按额度和对象身份稳定排序，选择显示列，翻页并统计不同供应商数；同一个执行的后续页面不能因源表更新而漏项或重复。

现有 [QualityMvpController](../../ontoforge-boot/src/main/java/io/ontoforge/boot/controller/QualityMvpController.java) 的 `/object-set` 已为三个制造类型实现 eq/prefix、按身份去重计数、排序、分页、预算及来源列；`/trace-set` 已处理多路径影响集合。[QualityImpactObjectSetTest](../../ontoforge-boot/src/test/java/io/ontoforge/boot/QualityImpactObjectSetTest.java) 有对应真实 PG/HTTP 正反例。缺口是脱离固定类型 switch 的 Typed AST、更多类型和跨页一致性。

当前 sourceFingerprint 为 Mapping JSON 摘要，不能证明源数据未更新；每个 HTTP 请求自己的 REPEATABLE READ 也不等于整个分页会话共享 MVCC 快照。新功能必须显式解决此边界。

成功定义：用户基于 M5/M6 自建采购类型完成 Filter→Sort→Project→Page，看到准确范围内的 COUNT_DISTINCT/SUM 与基础证据；源更新不改变该执行已冻结结果，非法操作、权限变化、超预算和过期游标均拒绝并可重新执行。

非目标：任意 SQL、跨源 JOIN、关系遍历/递归（M8）、用户代码、近似聚合、通用分布式优化器、保存可复用 ObjectSetDefinition、生产权限签收。ObjectSet 逻辑定义仍是惰性计划；本次结果快照是独立执行资源，不把成员数组当定义。

## Public Interfaces

### 1. 先版本化本机合同，避免伪造正式能力

现有 [Query V0.2 Schema](../design/schema/objectset-query-v0.2.schema.json) 强制 `schemaReleaseRef`；[Result V0.2 Schema](../design/schema/objectset-result-v0.2.schema.json) 强制 `schemaReleaseDigest / indexSnapshotRef / authorizationScopeRef`。M7 之前只有 Local Snapshot、虚拟读取和可选本机无身份模式，不能填假 release/index/授权引用通过 Schema。

候选采用独立 `local-query-v1` request/result Schema/DTO/profile，在实施 PR 增加结构及语义测试；保留 V0.2 候选不原地放宽。M10 才引入 SCHEMA_RELEASE 来源和适配，二者转换需明确拒绝无法提供的证据。本提案不修改机器 Schema。

```text
QueryRequest = {
  queryVersion: "local-query-v1", rootTypeRef: TypeRef,
  bindingRef: { bindingId, revision }, steps, limits?
}
versionLock = {
  typeSourceRef, payloadSha256,
  bindingVersions: [{ bindingId, revision, digest, sourceId, kind }],
  sourceSchemaFingerprints
}
```

rootTypeRef 的 sourceRef 与绑定精确来源必须一致；首版持久绑定来自 LOCAL_SNAPSHOT。versionLock 由服务器解析并回填，不接受客户端替代主体、策略或已校验模型。payloadSha256 裸 64 hex 与 Native `sha256:` 前缀经显式解析，任何摘要都不能代替数据 checkpoint。

### 2. 有限 Typed AST

| 节点 | 首版合同 |
| --- | --- |
| Filter | 最多 8 个 AND 条件；EQ/IN、数字与时间 GT/GTE/LT/LTE、STRING PREFIX、可空字段 IS_NULL；按类型白名单 |
| Sort | 最多 3 个字段及 ASC/DESC；固定 NULLS LAST；服务端补 ObjectRef 稳定平局排序 |
| Project | 本类型已存在且可见的 propertyRefs，最多100、不重复；ObjectRef/证据保留 |
| Page | 末尾一次，pageSize 1–50；首请求不带 cursor，后续只用同执行 cursor |
| Aggregate | 末尾一次，COUNT_DISTINCT（ObjectRef）、SUM（DECIMAL/INTEGER）；与Page互斥，不支持groupBy |

固定阶段为 Filter*→Sort?→Project?→Page 或 Filter*→Aggregate；非法顺序直接 422，不按 SQL 直觉重排。首版拒绝 Traverse、NE、OR、REGEX、AVG、近似计算和自由表达式；后续扩展必须更新 Schema/预算及反例。

Literal 使用 `{valueType,value}`：INTEGER/DECIMAL 为 M5/M6 规范数值字符串，BOOLEAN 为布尔，DATETIME 为 UTC 时间串；IN 为不超过100个同型 literal。编译先校验 Native 类型、枚举、searchable/可见属性与操作符，再执行安全 SQL 参数绑定，不能把字符串金额当字典序比较，也不能用 JS number 悄悄损失精度。

EQ/IN/范围比较对 NULL/MISSING 为 UNKNOWN，不命中；IS_NULL 只匹配来源实际 NULL，未映射 MISSING 不匹配。过滤必须基于完成 M6 去重与所需属性一致性检查后的对象，不以 MIN/首行在冲突值中过滤出“成功”。

SUM 按 ObjectRef 计算一次；空集合返回准确0，含 NULL/MISSING 的输入返回 `AGGREGATE_VALUE_UNAVAILABLE`，不默认把缺失当0。JSON 数值以精确规范十进制字符串输出，COUNT_DISTINCT 为非负整数串。多条物理行不能重复累计金额。

### 3. 执行结果、证据与错误

结果继承 M6 `executionId / executedAt / typeSourceRef / payloadSha256 / bindingVersions / sourceReadRef / accessMode`，增加 versionLock、queryDigest、resultSnapshotRef、expiresAt、objects 或 aggregate、totalCount、nextCursor 以及质量四维：

- `status=SUCCESS/FAILED`；首版预算或来源失败全部拒绝，不交付部分聚合。FAILED 无 objects/aggregate/totalCount 伪造成功载荷。
- `accuracy=EXACT` 仅针对已完整读取并去重的明确范围；`completeness=COMPLETE` 仅表示该授权绑定表请求范围，非整个企业完整。
- `freshness=UNKNOWN`，除非服务端有可验证的业务新鲜度依据；sourceReadRef 说明 RR 读取时间及 `checkpoint.status=UNKNOWN`，不捏造同步水位。
- `answerScope=REQUESTED_SCOPE` 只适用于完整执行；M7自身首版全成全败。M8首次关系读取就须按其提案处理孤儿引用的PARTIAL/KNOWN_SUBSET，M9统一治理，不能推迟M8基础语义或以字段名自称已支持。
- `resultSnapshotRef` 指向预算内冻结结果，**不是 indexSnapshotRef、SchemaSnapshot 或源 MVCC 快照**；accessMode=LOCAL_ISOLATED 时没有真实 authorizationScopeRef。

候选 API 为 `POST /api/runtime/object-sets/query` 与 `POST /api/runtime/object-sets/pages`（后者只提交 cursor，不另传 AST）。错误保留现有 JWT 401/403 与有权后资源404语义；400 INVALID_CURSOR，409 BINDING_SCHEMA_DRIFT/BINDING_UNAVAILABLE，410 RESULT_EXPIRED，422 INVALID_QUERY_AST/UNSUPPORTED_QUERY_OPERATOR，429 QUERY_BUDGET_EXCEEDED，503 SOURCE_UNAVAILABLE/SOURCE_TIMEOUT。错误只含安全代码、节点路径、executionId，不泄露SQL或源行值。

## Proposed Changes

### 1. 用户与编译流程

1. 选择 M4 类型快照及 M5 可用绑定，页面按真实字段类型显示允许的筛选/排序/投影操作，不增加未实现按钮。
2. 用户设置采购筛选并执行；服务器验证包权限、快照读取权限、版本锁、AST 类型与预算，先编译后读源。
3. 专用来源连接的一个只读 RR 事务内读取预算内候选对象、校验身份/受需属性、执行逻辑过滤/聚合，计数和结果来自同一来源读取。元数据连接不与来源天然同事务；执行前后复核绑定/类型锁及可用状态，若变更拒绝发布，不承诺跨连接原子快照。
4. 成功才发布执行结果和来源读证据，页面显示明确范围、执行时刻、新鲜度未知及计数；无结果是完整查询得到0而非失败兜底。
5. 用户翻页读取同执行冻结结果；过期、权限变化或来源不可用提示重新执行，不拼接新执行页。

动态绑定编译替换固定类型 switch 的必要部分，但不强迫把完整控制器一次迁移；复用 M6 的身份/值语义，JDBC 为执行适配。SQL 谓词下推可以优化，必须证明与先去重/校验的逻辑等价，不能把冲突或 NULL 行过滤隐藏。

### 2. 稳定分页与预算

候选固定上限：一次执行来源物理行数与去重对象数分别最多10,000、来源扫描总量8 MiB、3秒、冻结结果序列化大小8 MiB、单页50，AST至多16节点；客户端只能下调。超任何预算都失败，不签发cursor、不返回“精确部分计数”。先完成预算内完整执行，再物化有界结果；不长期保持数据库事务或 MVCC snapshot 跨请求。

结果快照候选 TTL 5分钟，每进程至多10份且总大小不超过80MiB，超配额拒绝新执行，过期清理；实施压测后可经评审调整。快照可为内存资源，重启后410明确失效，不承诺持久历史/离线浏览。

cursor 为不可伪造的服务端签名/随机句柄，关联 queryDigest、resultSnapshotRef、版本锁、排序、位置、到期和服务端访问范围。游标不提供权限；拒绝跨query/type/package、篡改、超长、过期和另一主体借用。签名密钥外部配置或进程生成，不提交日志/Git。

每页重新核验当前服务端包/快照权限、绑定可用状态、来源授权与结构指纹；本机明确无真实主体。检查超时/断线返回503，结构漂移使旧快照失效；不回退离线历史页。来源正常新增/修改不重算既有成员，新的 execution 可以不同；重新检查来源不代表冻结结果实时最新。

稳定顺序采用类型对应比较：数字按数值、时间按瞬时、STRING 用固定二进制/代码点顺序，NULLS LAST，再追加完整 ObjectRef 唯一排序；不能依赖本机 collation 或无 tie-breaker 的 LIMIT/OFFSET。物化后以确定位置翻页。

### 3. 安全、一致性和范围责任

消费 M5 登记的单 PG 白名单只读来源，SQL 参数化，不允许客户端改连接/表列/身份域。读取本地模型快照须沿用 requireSnapshot 的 READ_SNAPSHOT 核验，包和 Binding 读仍从服务端派生授权；拒绝先查后脱敏。

`mvp-local` 不要求OIDC，保留 opt-in、loopback与隔离库，不把本地占位身份当审计主体；JWT路径保留签名/包授权/撤权兼容测试。未来 HttpSession 为独立评审，不能通过查询引擎复制用户体系或耦合其他业务产品。

M9 收敛 SourceReadEvidence、漂移和留存策略；本 M 已提供真实读时间/版本/范围及失败信封。M8 的关系查询同样使用该执行边界，不能用路径数冒充对象数或在多跳途中先截断再算精确聚合。

### 4. 实施切片

一个实现 PR 完成非制造 supplier 的类型化筛选、排序、投影、翻页和 COUNT_DISTINCT/SUM，附真实PG及浏览器验收。预算内物化是首版一致性选择，不是建立永久全量 Object Store。产品/合同/测试责任由维护者接受时指定。

## Compatibility, Deprecation, and Migration Plan

- 现有制造 `/object-set`、`/trace-set` 接口与错误保持回归，不能无提示改成新AST或改变旧cursor编码。
- 新 request/result profile 与 Native V0.2 互不冒充；未来 SCHEMA_RELEASE 适配明确提供真实发行、索引/授权证据，无法映射时拒绝转换，不用空字符串占位。
- bindingVersions 与M10 runtimeLock统一引用项 `{bindingId, revision, digest, sourceId, kind}`，本M为OBJECT、M8扩展LINK；revision为本地整数，不是Native BindingVersion SemVer。正式合同适配显式解析，不能靠同名字段猜版本。
- 本机快照和绑定历史不修改；queryDigest 绑定规范化AST、类型来源与绑定锁，不等于源数据摘要。源数据可变，重放查询不保证同结果。
- 结果快照仅临时资源，首版无数据库迁移；若实施需要持久执行元数据，使用届时下一个未占用Flyway号，不改历史迁移。升级/重启令旧游标410，不假兼容读取未知格式。
- 回滚可关闭新路由并清理到期结果资源；保留版本与测试证据，不修改旧接口降低断言。来源/权限撤销立即阻断后续页，不以缓存保留访问。

## Test Plan

以下是**实施验收要求**，不是本提案执行证据。

| 类型 | 成功场景 | 必需负例/风险边界 | 真实验收 |
| --- | --- | --- | --- |
| AST/合同 | 自定义采购与制造模型共用查询；精确数值literal | 未知/非searchable/跨类型字段、错误类型、节点次序、注入、超深/超长、伪授权/Release/index字段 | 新增local-query-v1 Schema夹具及类型编译单元测试；保留Native V0.2回归 |
| PG/语义 | 重复行去重后筛选，按ObjectRef计数/SUM，空集为0 | MIN隐藏冲突、NULL/MISSING当0、double精度、枚举越界、单对象重复金额、超预算/超时/断线 | 新增 `GenericObjectSetQueryIntegrationTest`；隔离TEST_DB_*下 `mvn -pl ontoforge-boot -am verify` |
| 分页/一致性 | 同执行多页无重漏，计数与结果同一RR读 | 页间插入/删改、同值排序、跨查询借cursor、篡改/过期/重启、漂移、配额、来源检查失联 | 实际并发PG更新与HTTP会话测试；不以单线程重复请求代替 |
| 权限/来源 | 本机无IdP完成故事；JWT正确包可读 | 无Token/伪签名/过期/跨包、页间撤权、另一主体cursor、未允许表、来源只读角色写拒绝 | 新路由HTTP拒绝测试及B0旧路径回归 |
| UI | 自建supplier→筛选/排序/选列→翻页→统计→证据 | 失败与准确空集区别、非实时提示、过期重查、类型错误可修复 | React/Chromium E2E；真实用户在隔离合成库操作 |
| 回归 | M2/M3制造追溯、原筛选与游标继续有效 | 无新增固定业务switch，旧错误与基数边界不弱化 | QualityImpactObjectSetTest及已有产品E2E |

提案 PR 仅验证文档治理/相对链接；运行时AST、PG并发、浏览器、签名/撤权、负载及发行全部 **NOT RUN**。实际文档结果由PR记录受测head，实施按PASS/FAIL/NOT SELECTED/NOT RUN区分覆盖，不以结构Schema通过宣称执行正确。

## Rejected Alternatives

1. **在旧制造switch继续增加采购类型**：只能证明第二个固定场景；动态类型和绑定驱动才验证通用性。
2. **把Local Snapshot摘要填入schemaReleaseRef并虚构index/auth引用**：混淆治理、安全与数据证据；独立本机profile后显式适配。
3. **每页新事务加mappingFingerprint就称分页稳定**：源数据仍可改变成员/排序；采用预算内完整执行的有界结果快照。
4. **跨HTTP保持长PG事务/导出MVCC快照**：增加连接、清理与VACUUM成本，当前上限无需承担；短事务物化结果与TTL可验证。
5. **截断后返回成功聚合/全部数量**：把未知当精确；预算失败不交付成功载荷，后续PARTIAL单独评审。

---

**Decision Gate:** Accepted。正式维护者投票 permalink：[PR #58 #issuecomment-6248979717](https://github.com/gitfortian/ontoforge/pull/58#issuecomment-6248979717)。生产实现由 [PR #75](https://github.com/gitfortian/ontoforge/pull/75) 承担；其合并、生产验收和 Release 分别记账，CI PASS 不自动代表发布。
