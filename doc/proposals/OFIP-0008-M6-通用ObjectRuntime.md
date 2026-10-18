# OFIP-0008：M6 通用 Object Runtime、对象身份与属性读取

**Status:** Discussion

**Discussion:** [PR #58 — [DISCUSS][OFIP-0007–0016] M5-M14 需求 Review 与方案设计](https://github.com/gitfortian/ontoforge/pull/58)

**Decision:** Pending（等待维护者显式决策及 permalink）

**Implementation:** Not Started

**Release:** Not Released

**Related:** [M6 #45](https://github.com/gitfortian/ontoforge/issues/45) · [M5 #44](https://github.com/gitfortian/ontoforge/issues/44) · [OFIP-0007](./OFIP-0007-M5-通用DataBinding.md) · [OFIP-0001](./OFIP-0001-需求提案文档治理与CI证据门禁.md) · [V1.3 候选](../本体产品总体功能与技术架构设计_v1.3.md) · [Native V0.2 候选](../design/核心本体元模型与Native-DSL-AST契约_v0.2.md)

> 2026-10-18 基于 main `6968ea0804f57b2b82f237e37f812bcadb32f343` 的设计候选。本提案仅设计通用只读实例读取，不把模型快照变成事实快照或正式发行。

## Motivation

采购分析者保存 supplier 数据绑定后，希望输入 `SUP-001`，看到自己定义的名称、等级、额度及其来源；同一标识在不同类型/身份域中不能串成同一对象，来源缺失或矛盾也不能显示成空字符串或任取一行。

当前代码并非没有实例读取：[QualityMvpController](../../ontoforge-boot/src/main/java/io/ontoforge/boot/controller/QualityMvpController.java) 的 object-set 已按三个固定制造类型读取，处理限定标量冲突、同身份物理行去重、稳定排序及受限筛选。[QualityImpactObjectSetTest](../../ontoforge-boot/src/test/java/io/ontoforge/boot/QualityImpactObjectSetTest.java) 覆盖这些真实 PostgreSQL/HTTP 行为。缺口是通用 TypeRef/ObjectRef、动态属性和明确的值/失败语义。

M6 成功闭环：从 M5 保存的精确绑定版本进入“对象读取”，输入规范键，看到非制造对象的多个类型化属性、版本和来源说明；相同键重复一致行仍为一个对象，矛盾行被拒绝，来源断开不会变成“对象不存在”。

非目标：全量 Object Store、全局实体消歧、跨源身份合并、关系遍历、流式同步、计算属性/推断、对象写入和 Action。按需虚拟读取为首版模式。

## Public Interfaces

### 1. 身份与版本分离的候选合同

本机 `local-object-v1` 建议定义：

```text
ObjectRef = {
  packageId, objectTypeId, identityDomainId,
  key: { valueType: STRING | INTEGER, value: canonicalString }
}
ObjectReadRequest = { bindingId, bindingRevision, key, propertyRefs? }
```

- TypeRef 引用 M4/OFIP-0006 的精确来源和类型；M5 持久绑定首版为 LOCAL_SNAPSHOT。服务端从绑定解析 TypeRef，返回 `typeSourceRef / payloadSha256`，不允许同一请求另选“最新”类型。
- ObjectRef 表达业务对象身份，bindingRevision、模型摘要、执行时间是读取证据，不进入对象主键。属性映射修订不会让所有对象重新获得 ID。
- identityDomainId 由 M5 首次绑定生成并在同一来源键语义下稳定；换来源表/键语义产生新身份域，不能把旧 ObjectRef 自动解释成新对象。
- 同包不同类型或同类型不同身份域即使 key 一样也不相等。ID 显示名不可替代 ObjectRef，ObjectRef 也不是物理行地址。
- STRING 精确保留大小写/空白，不 trim、不大小写折叠；INTEGER 使用 M5 的规范十进制整数串（`0`、无前导零、无 `+`，禁止 `-0`）。客户端非规范键返回 422，不悄悄改义。

MVP 只支持单列 STRING/INTEGER 主键；复合键、模糊匹配和 APPROVED_CROSS_SOURCE 请求明确 `UNSUPPORTED_IDENTITY_STRATEGY`。

### 2. PropertyValue 与读取结果

| 字段/状态 | 语义 |
| --- | --- |
| propertyRef / valueType | 所属 ObjectType 的属性 ID 与被绑定快照类型 |
| PRESENT / value | 已读取且能按类型无损表达；0、false、空字符串都为有效值 |
| NULL | 来源列实际 SQL NULL；只对非必填属性允许，没有 value |
| MISSING | 模型有该非必填属性但绑定没有映射；没有 value 与伪造来源列 |
| provenance | PRESENT/NULL 提供 sourceId、表列、bindingId/revision 与 sourceReadRef |

STRING/ENUM 为 JSON string，BOOLEAN 为 JSON boolean；INTEGER/DECIMAL 为规范数值字符串，DATETIME 为 ISO-8601 UTC 字符串。必填 NULL、同身份属性互斥、枚举越界和不可无损转换返回错误，首版不输出部分对象，也不把 INVALID/UNKNOWN 偷塞进成功 value。

每次读取返回服务端 `executionId / executedAt / typeSourceRef / payloadSha256 / bindingVersions / sourceReadRef / accessMode` 及 `accuracy / completeness / freshness / answerScope`。bindingVersions 项统一为 `{bindingId, revision, digest, sourceId, kind}`，本 M 的 kind=OBJECT；revision 为本地整数，不是 Native SemVer。M9 统一证据收口；M6 就必须提供以上基础字段。

- 来源按单次 PostgreSQL REPEATABLE READ 事务读取，sourceReadRef 指向已读关系/列、来源结构指纹、读起止时间与一致性说明。
- 只有完全读取绑定表中此身份的全部相关行并校验成功，才可对明确请求范围给 EXACT/COMPLETE/REQUESTED_SCOPE。
- 没有源系统业务水位时 checkpoint/freshness 为 UNKNOWN；刚执行 SQL 不证明业务资料最新。空可选属性不表示读取失败。
- `accessMode=LOCAL_ISOLATED` 明确本机无真实用户身份；JWT 模式主体、包授权与当前撤权由服务器解析，不接受 caller 自填 AuthorizationContext。

`status=SUCCESS` 才有完整ObjectRef/属性载荷；失败为FAILED，质量未知，无对象成功载荷。首版不输出部分对象。不存在是有权且来源读取成功后的404，必须与503失败区分。

### 3. HTTP 与错误候选

`POST /api/runtime/objects/read` 接受上述 ObjectReadRequest；用 body 传键以避免 URL 分段/编码歧义。没有自由 SQL、物理列名、任意 predicate 或授权上下文入口。

| HTTP / code | 可见行为 |
| --- | --- |
| 400 INVALID_OBJECT_REQUEST | 格式/未知字段/体积不合法，未执行查询 |
| 401 / 403 | JWT无有效凭据为401、服务端无权为403；授权前不读取对象，不泄露对象存在性 |
| 404 OBJECT_NOT_FOUND | 已有权且来源查询成功，此身份没有行；不是连接失败 |
| 409 BINDING_UNAVAILABLE / BINDING_SCHEMA_DRIFT | 绑定失效或结构漂移，提示返回绑定修复 |
| 409 OBJECT_IDENTITY_CONFLICT / PROPERTY_VALUE_CONFLICT | 同身份行的所需属性互相矛盾，禁止首行胜出 |
| 422 INVALID_OBJECT_KEY / PROPERTY_TYPE_MISMATCH | 键不规范、属性不属于类型、必填缺值或类型不匹配 |
| 429 QUERY_BUDGET_EXCEEDED / 503 SOURCE_UNAVAILABLE或SOURCE_TIMEOUT | 超预算、超时/断线，拒绝结果，不给空对象 |

错误带 executionId、稳定 code、safeMessage 和允许公开的 propertyRef；无源值、SQL、原始异常或凭据。无权必须在源读取前拒绝，并有相同策略的负例。

## Proposed Changes

### 1. 用户流程

1. 用户打开 M5 已保存绑定，页面显示类型、snapshotId、绑定 revision 和隔离模式。
2. 输入该类型的单列键；服务端核验绑定版、模型摘要、键类型、属性请求和来源可用性。
3. 在一个只读 RR 事务中读取此身份所有物理行，仅按绑定列投影并执行统一转换。
4. 返回一个 ObjectRef、属性列表、同身份物理行数量及证据入口；不存在、不可用和冲突分别显示。
5. 用户可改键重试；遇到漂移/绑定错误可返回映射修复，旧证据保持原执行版本。

默认读取绑定快照的所有属性；propertyRefs 为可选显式子集，最多 100 个且不重复。主键始终用于身份验证，不能通过少投影隐藏键冲突。未映射的非必填属性显示 MISSING；必填属性不得未映射。

### 2. 去重与冲突规则

一个键对应多条物理行时，按 `(ObjectRef, propertyRef)` 比较无损类型转换后的值与 NULL 状态：所有值一致才折叠成一个对象。不存在“第一条”“最新一条”“MIN 值”或以查询顺序决定权威。未声明排序/时间权威就不能自动选值。

可选属性的 NULL 与非 NULL 同时出现也视为冲突，避免把来源缺失自动覆盖；重复 NULL 则为一个 NULL。只有读取到的受需属性参与属性冲突判断，结果需明确 selected propertyRefs；主键与绑定可用性检查始终执行。

候选限额为单身份最多 10,000 行、100 属性、3 秒及 1 MiB 输出，读取 `limit+1` 不能只截断后宣布成功。预算外终止并返回错误；限额服务端固定上界，客户端只能请求更小预算。

对象主键与主键 PropertyValue 必须由相同规范化逻辑得出并相等；禁止 uuid/text 和 integer 的多重转换制造同 ID。DECIMAL 禁止 float round-trip、NaN/Infinity；DATETIME 无时区值不能猜时区。

### 3. 领域、来源与权限责任

Object Runtime 消费模型和绑定，把 SourceFact 按版本投影为对象读取结果；对象结果不是来源事实的替代权威，也不推断新的事实。身份与 PropertyValue 转换应共享给 M7/M8，避免详情页、筛选和聚合各自决定不同值。

沿用 M5 的登记来源、只读角色、目录白名单、参数化值与标识符校验；不能通过查询 DTO 接入第二个来源或跳过绑定。源结构核验和对象读取使用同一专用来源连接的 RR 事务；应用元数据为另一连接，读取前后复核精确绑定版本/可用状态，变化则拒绝发布结果，不能宣称跨连接原子快照或在失败后退回缓存成功。

默认安全路径沿用服务端包 READ_DRAFT 核验，并从绑定真实snapshotId调用 requireSnapshot(READ_SNAPSHOT)推导并校验包，不让客户端自填包绕过。不能先读对象再过滤。`mvp-local` 仍需显式 opt-in、loopback、隔离库及醒目标记，不要求 IdP；未启用本机模式则保留 JWT 及包授权负例。新路由精确接入现有两个 profile，默认未允许的路由仍 deny。

首版不新增共享对象值缓存；每次读取核验当前包权限、来源可用状态和绑定版。M9 可增加统一证据留存/漂移流程，不能追溯把本次 UNKNOWN 改为 FRESH。

### 4. 实施切片

一个实现 PR 完成“采购 supplier 绑定→按键读取→属性与证据→不存在/冲突/失败恢复”真实前后端。只在确有调用时抽取共享身份/转换/读取逻辑，不建全量对象表或空 Repository 工厂。

类型/接口与 PG 适配责任分开评审；维护者接受时明确产品与合同负责人。UI 原型签收与真实 E2E 是实施门槛，不以类型编译替代。

## Compatibility, Deprecation, and Migration Plan

- 不改变现有固定制造 Fact/Trace/ObjectSetPage，也不将其简写 `id` 直接认定为完整 ObjectRef；旧 API 保持回归。
- 新 ObjectRef/PropertyValue 用 `local-object-v1` Schema/DTO 独立版本化。Native V0.2 的模型属性定义不是值传输格式，不能声称现有 Schema 已验证该合同。
- 模型快照和绑定版本只读保留；同身份的后续读取值可不同，历史执行结果必须带自己的来源读证据，不宣称可重放当前源得出同值。
- M6 本身按需读取不要求新业务事实表；若实施为执行元数据留存新增表，采用届时下一个未占用前向 Flyway 迁移，不改已应用迁移。
- 删除或改变主键、切换身份域时显式拒绝旧引用或保留旧域读取入口；无条件把旧 key 解释成新语义不可接受。属性增减需以新的绑定/快照明确采用。
- 回退实现可停用新路由，不能改写历史 ObjectRef 或伪造不存在的对象；来源角色撤权使后续读取立即失败，不回退到先前缓存。
- 跨来源身份合并、对象写回、历史 as-of 查询为 None：首版没有这些合同，不预留假实现或自动兼容承诺。

## Test Plan

以下是**实施验收要求**，本提案不宣称运行时通过。

| 类型 | 成功场景 | 必需负例/风险边界 | 真实验收 |
| --- | --- | --- | --- |
| 身份/值合同 | 自定义类型、STRING/INTEGER 键、六种值类型 | `01/+1/-0`、空白/大小写串号、跨类型/跨身份域、超大整数/decimal、NaN、时区、枚举越界 | 新增 ObjectRef/PropertyValue 单元测试与 local-object-v1 Schema 夹具 |
| PG/重复行 | 相同键相同值折叠，重复 NULL 一致 | 不同值、NULL/非NULL、必填NULL、类型冲突、10,001行、超时、断线 | 新增 `GenericObjectReadIntegrationTest`；隔离 TEST_DB_* 下 `mvn -pl ontoforge-boot -am verify` |
| 版本/来源 | 固定 snapshot/binding版按键读，结果带真实来源列 | 草稿变化不影响快照、换身份域拒绝旧ref、来源漂移、结构核验竞态、重启后绑定可读 | 真实 PostgreSQL 事务与故障测试；记录 base/head 与 CI |
| 权限 | 本机隔离无登录完成故事；JWT正确包可读 | 伪造/过期签名、跨包、撤权、来源只读角色不许写、越权不可经404/冲突泄露值 | 保留 B0 负例并增加新路由 HTTP 测试 |
| UI | supplier输入键→多属性值→证据→重新打开 | 不存在/NULL/MISSING/冲突/超时分别显示，false/0不渲染为空 | React/Chromium E2E 与用户本机合成数据验收 |
| 回归 | M2/M3 固定制造与建模/快照正常 | 不改变现行接口或以新路径绕过安全 | 原 QualityImpactObjectSetTest 与相关产品 E2E |

提案 PR 文档治理/链接检查的实际结果以 PR 为准；PG、浏览器、签名/授权、迁移、工业环境和发行验收均 **NOT RUN**。后续按实际受测 commit SHA 分别记录 PASS/FAIL/NOT SELECTED/NOT RUN。

## Rejected Alternatives

1. **把 ObjectRef 定义成一段裸业务 ID**：不同类型、来源键空间会碰撞；显式 package/type/身份域与键类型。
2. **ObjectRef 包含每次 Binding revision**：普通属性改名会造成身份雪崩；版本属于读取证据，改变键语义才改变身份域。
3. **把所有属性 `::text` 或默认 NULL/空字符串**：丢失精度、类型和缺失语义；采用无损类型转换与显式 NULL/MISSING。
4. **MIN/首行/最新行自动消冲突**：来源没有已批准的权威/时间选择合同；冲突必须可见且安全拒绝。
5. **先建全量通用 Object Store**：当前单 PG 按键读取已有实际来源，复制增加刷新和一致性成本；先按需虚拟读取。

## UX-Low Prototype Sign-off（2026-10-18）

- 原型：[M6 Object Runtime UX-Low](../prototypes/m6-object-runtime-lowfi.html)
- 签收记录：[M6 UX-Low 原型签收记录](../implementation/M6-ObjectRuntime-UX-Low评审说明.md)
- 交付与回归：[PR #64](https://github.com/gitfortian/ontoforge/pull/64)；[专用 Chromium workflow（5/5 PASS）](https://github.com/gitfortian/ontoforge/actions/runs/39098922723)；[全量原型回归（132 tests PASS）](https://github.com/gitfortian/ontoforge/actions/runs/39098922643)。
- 产品所有者已明确认可对象身份、属性值语义、错误与恢复、来源证据四项交互原则；无阻断性 UX-Low 修改项。
- **UX-Low 已签收；下一阶段进入 UX-High 高保真设计，UX-High 必须独立评审并签收。**
- 本签收只覆盖静态原型与交互合同，不证明 Runtime API、PostgreSQL、权限或生产代码已实现。
- OFIP-0008 的 Decision 仍为 Pending。UX-Low 与 UX-High 均签收且维护者依治理流程显式记录 `[VOTE] Accepted` 之前，不得启动 M6 生产 Java/React/Flyway 实施。

## UX-High Prototype Candidate（2026-10-18）

- 原型：[M6 Object Runtime UX-High](../prototypes/m6-object-runtime-highfi.html)
- 评审说明：[M6 UX-High 原型评审说明](../implementation/M6-ObjectRuntime-UX-High评审说明.md)
- UX-Low 已签收；UX-High 当前仅为高保真候选，等待产品所有者独立评审与签收。
- Chromium workflow 将回归 UX-Low 与 UX-High 两套原型；Mock 测试不证明生产 Runtime、PG17、权限或真实 API 已实现。
- 产品评审需确认对象身份、属性表格、错误恢复、来源证据、视觉层级与窄屏布局。未签收前不得视为最终 UX。
- OFIP-0008 仍为 `Decision: Pending`。UX-Low 与 UX-High 均签收且维护者依仓库治理流程显式 `[VOTE] Accepted` 前，不启动 M6 生产 Java/React/Flyway 实施。

---

**Decision Gate:** Discussion 不授权实施。维护者须在提案 PR 显式记录 `[VOTE] Accepted` / `Rejected` 与决定链接；代码合并、CI 或评论不替代设计和实际验收。
