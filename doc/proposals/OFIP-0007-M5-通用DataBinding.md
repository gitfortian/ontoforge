# OFIP-0007：M5 通用 Data Binding 与只读来源映射

**Status:** Discussion

**Discussion:** [PR #58 — [DISCUSS][OFIP-0007–0016] M5-M14 需求 Review 与方案设计](https://github.com/gitfortian/ontoforge/pull/58)

**Decision:** Pending（等待维护者显式决策及 permalink）

**Implementation:** Not Started

**Release:** Not Released

**Related:** [M5 #44](https://github.com/gitfortian/ontoforge/issues/44) · [M4 #43](https://github.com/gitfortian/ontoforge/issues/43) · [M4 提案 PR #54](https://github.com/gitfortian/ontoforge/pull/54) · [M4 实现 PR #56（已合并）](https://github.com/gitfortian/ontoforge/pull/56) · [OFIP-0001](./OFIP-0001-需求提案文档治理与CI证据门禁.md) · [V1.3 候选](../本体产品总体功能与技术架构设计_v1.3.md) · [Native V0.2 候选](../design/核心本体元模型与Native-DSL-AST契约_v0.2.md)

> 2026-10-18 基于 main `6968ea0804f57b2b82f237e37f812bcadb32f343` 的设计评审。本文只提案、不授权实施 M5 或生产连接器，也不改变 M4 已交付边界。M4 类型解析与目录已由 [PR #56](https://github.com/gitfortian/ontoforge/pull/56) 合并；本提案消费其 TypeSourceRef/TypeRef 候选合同，不推定 M5 已实现或产品已发布。

## Motivation

采购业务建模者已创建 `supplier`，希望把本机合成采购表的 `vendor_code/name/rating` 映射为自己的主键和属性，预览后保存，重开仍能看见该映射及其模型版本。现有固定制造三表的十项配置无法完成此任务。

实际基线已有以下能力，不能作为“从零实现”重新建设：

- [QualityMvpController](../../ontoforge-boot/src/main/java/io/ontoforge/boot/controller/QualityMvpController.java) 已有白名单表发现、样本预览、固定十项 Mapping、绑定持久化及 revision 核验。
- 同一控制器和 [QualityImpactObjectSetTest](../../ontoforge-boot/src/test/java/io/ontoforge/boot/QualityImpactObjectSetTest.java) 已实现 M2/M3 的制造追溯与受限查询，并非只有 Schema。
- [Native Package Schema](../design/schema/ontology-package-v0.2.schema.json) 的 BindingDefinition/FieldMapping/IdentityRule 是候选结构，不能当成已部署通用绑定。
- [本机核心优先决策](../implementation/MVP-核心优先与yak-ops认证复用决策-20261017.md) 允许显式 `mvp-local` 无登录使用；JWT/OIDC 不作为 M5 的本机前置。

成功定义：用户为非制造 ObjectType 选择一个本地模型快照，发现允许的 PG 测试表，配置主键和至少两个属性，看到真实有限样本与校验说明，保存并重开；非法来源、类型不兼容和并发修改均有可恢复错误。

非目标：跨源实体融合、生产连接器、任意 SQL/表达式、数据库写回、CDC、正式 SchemaRelease、关系绑定和 Object Runtime。关系留给 M8，实例读取留给 M6。

## Public Interfaces

### 1. 版本与类型引用

消费 OFIP-0006 候选：`TypeSourceRef` 互斥为 `VALIDATED_DRAFT(draftId, revision)` 或 `LOCAL_SNAPSHOT(snapshotId)`；`TypeRef={sourceRef, objectTypeId}`。不混填两种来源，不用显示名称或“最新模型”做引用。

- 预览允许精确 VALIDATED_DRAFT；持久绑定首版仅接受 LOCAL_SNAPSHOT，使旧绑定不会随草稿保存改变。
- 服务端从真实快照解析 packageId、ObjectType/Property 与 `payloadSha256`，不信任客户端声称的 VALID/摘要。
- 本地快照摘要采用裸小写 64 hex；Native V0.2 的 `sha256:` 摘要只能经显式解析/格式转换，不能直接做字符串相等，也不能把模型摘要当来源数据水位。
- `SCHEMA_RELEASE` 来源由 M10 独立引入；M5 不依赖正式发布，避免 M5→M10→M5 循环。

### 2. 候选本机合同 `local-binding-v1`

| 字段 | 含义与约束 |
| --- | --- |
| bindingId / revision | 服务端标识与单调 CAS revision；每版内容不可变 |
| typeRef / payloadSha256 | 被绑定的精确本地快照类型及服务端核验摘要 |
| sourceId / schema / table | 服务端登记的单 PG 来源及目录解析结果；不是 JDBC URL |
| sourceSchemaFingerprint | 服务端计算的所需表/列/PG 类型/约束摘要，非数据内容摘要 |
| sourceKey / identityDomainId | 单列来源键及独立稳定身份域；必须映射该类型 primaryKeyRef |
| fieldMappings | `(propertyRef, sourceColumn, authority)`，Property 在所属 ObjectType 内解析 |
| refreshMode / identity | 首版仅 `ON_DEMAND` / `SOURCE_KEY`，冲突策略 `REJECT` |
| digest / validation | 版本内容摘要及校验时刻/覆盖范围/问题列表；不能由客户端填写 PASS |

每个类型首版只允许一个当前可用绑定，一个属性只映射一次，映射 authority 为 AUTHORITATIVE。SUPPLEMENTAL、APPROVED_CROSS_SOURCE、转换表达式和自定义 SQL 均明确拒绝；“单源权威”只代表该绑定的声明，不证明外部事实正确。

Native V0.2 候选包含 SemVer、sourceSchemaVersion 等不同字段。本合同是独立本机 DTO/profile，须在实施 PR 新增版本化 Schema 和转换测试，不直接声称 V0.2 兼容；revision 不能冒充 SemVer。

下游 `bindingVersions` 统一使用引用项 `{bindingId, revision, digest, sourceId, kind}`，本 M 的 kind=OBJECT；M8 另有 LINK。revision 为本地整数版本，不能冒充 Native V0.2 的 BindingVersion SemVer；M10 runtimeLock 通过相同字段显式适配。

### 3. HTTP 与可见错误候选

- `GET /api/runtime/sources/{sourceId}/tables?packageId=...`：先核验指定包的来源发现权限，再返回允许且可访问的表、列、PG 类型与截断标记；目录超过预算不能静默给出“全部”。
- `POST /api/runtime/bindings/preview`：提交候选映射，返回至多 20 行样本、类型诊断、样本覆盖与来源检查说明；不写绑定。
- `POST /api/runtime/bindings`：创建时 `expectedRevision=0`；`PUT /api/runtime/bindings/{id}` 带 expectedRevision 保存新不可变版。
- `GET /api/runtime/bindings/{id}?revision=n`：重开指定版；缺省返回当前版并明确 revision。

错误统一包含 `code / safeMessage / fieldPath / executionId`；400 格式错误，JWT 无凭据/不合法为401、无权为403，有权后不存在为404；409 `BINDING_REVISION_CONFLICT`/`BINDING_SCHEMA_DRIFT`，422 `UNSUPPORTED_MAPPING_TYPE`/`INVALID_TYPE_REFERENCE`/`IDENTITY_CONFLICT`，429 `QUERY_BUDGET_EXCEEDED`，503 `SOURCE_UNAVAILABLE`/`SOURCE_TIMEOUT`。不向响应泄露连接串、SQL、凭据或源行值。

上述 `/api/runtime/**` 目前不在本机安全路由白名单中；实施须精确新增所需路径及拒绝测试，不能使用 `/api/** permitAll`。

## UX-Low Prototype Candidate

[M5 Data Binding low-fidelity interactive prototype](../prototypes/m5-data-binding-lowfi.html) is a review artifact, not production UI. It demonstrates the end-to-end path from a fixed Local Snapshot and custom ObjectType through allowlisted PostgreSQL table selection, typed field mapping, bounded preview/validation, immutable binding revision, and reopening. It includes simulated source drift, duplicate-key conflict, source unavailability, budget exhaustion, and save-time CAS conflict states. The sample supplier table and data are explicitly marked as future isolated-test fixtures; they are not claimed to exist on `main`.

**Prototype status:** Candidate for UX-Low review only. No UX sign-off, high-fidelity sign-off, runtime API, Flyway migration, or M5 implementation is implied. The standard workflow still requires review of this prototype, then a high-fidelity prototype and explicit sign-off before the production UI is developed.

## UX-High Prototype Candidate

[M5 Data Binding high-fidelity interactive prototype](../prototypes/m5-data-binding-highfi.html) refines the accepted UX-Low flow into a product-like workspace with a binding directory, empty state, five-step editor, typed mapping diagnostics, source and mapped-object previews, bounded validation, immutable revision save, read-only detail with historical revision reopening, and explicit new-revision flow when a binding already exists for the same ObjectType.

The high-fidelity candidate also demonstrates source drift/unavailability recovery, identity-domain changes, duplicate-key and budget failures, save-time CAS conflict recovery, and unsaved-edit discard confirmation. Its dedicated Chromium tests are in [test_m5_binding_highfi_browser.py](../prototypes/tests/test_m5_binding_highfi_browser.py).

**Status:** UX-Low accepted by [PR #60](https://github.com/gitfortian/ontoforge/pull/60); UX-High is a separate review candidate. The HTML and browser checks remain mocked; no runtime API, Flyway migration, real PostgreSQL validation, or production UI is implied. UX-High sign-off is required before M5 implementation begins.

## Proposed Changes

### 1. 用户流程与校验边界

1. 从 M4 选择已保存的 Local Snapshot 和 ObjectType，进入“数据绑定”，页面显示来源类型、snapshotId 与模型摘要。
2. 发现单个登记的本机 PG 来源；只显示允许的 `public.demo_* / public.mvp_source_*` BASE TABLE，排除视图、外部表、系统表。
3. 选择来源键与属性列；在客户端即时提示，在服务端目录/类型/所属关系复核后生成预览。
4. 预览显示至多 20 行、采样策略和“不是全量校验”；用户修复问题后保存，服务端重查版本和来源指纹再写入。
5. 重开看到保存版、已检查范围及可用/需复核状态；绑定不可用时保留历史定义，提示修复并创建新版本。

保存执行预算内的全表身份和全部已映射属性校验，候选上限 10,000 源行、3 秒；超限返回429 `QUERY_BUDGET_EXCEEDED`，不能以样本无冲突宣布 VALID。映射列完全相同的重复行可以记录重复量，键相同而属性不同、NULL 主键/必填值必须阻断。保存之后来源仍可变化，M6 每次读取仍须复核。

### 2. 最小类型矩阵

| Native Value Type | 允许的 PG 类型与转换 |
| --- | --- |
| STRING | text/varchar；uuid 仅以标准小写 UUID 文本显式映射；保留文本大小写及空白 |
| INTEGER | smallint/integer/bigint；规范十进制有符号整数字符串传输，禁止浮点绕转 |
| DECIMAL | numeric/decimal，有限精确值按十进制字符串传输；拒绝 NaN/Infinity/静默舍入 |
| BOOLEAN | boolean；不把字符串、0/1 自动解释为布尔 |
| DATETIME | timestamptz，统一 ISO-8601 UTC；无时区 timestamp 首版拒绝 |
| ENUM | text/varchar 且值属于模型枚举；不自动补新枚举 |

不支持的数组、JSON、二进制和 SQL 表达式返回明确错误。Native 主键仍限 STRING/INTEGER；来源键归一化与 M6 共用同一合同，不能每版变化。

### 3. 状态、证据与安全责任

持久版保存映射，不复制业务数据。版本记录 VALIDATED/INVALID 校验结果与 `checkedAt / checkedRows / sourceSchemaFingerprint`；当前可用性由服务端检查，M9 统一漂移与证据留存。旧版定义不可覆盖，新验证不能回写抹除旧报告。

sourceId 对应外部配置的固定隔离 PG，不接受客户端主机、端口、URL、用户名或密码。来源连接使用只读专用角色和只读事务，标识符先目录核验再引用，所有值参数化；测试证明该角色无法 INSERT/UPDATE/DDL。现行应用元数据连接仍可写自己的绑定表，不能把 `readOnly=true` 当数据库权限证明。

遵循现有服务端授权入口：绑定写核验所属包 WRITE_DRAFT；来源发现核验显式 packageId 的 READ_DRAFT；预览从 TypeRef 推导包，草稿用 requireDraft(READ_DRAFT)、快照用 requireSnapshot(READ_SNAPSHOT)，服务端拒绝包不一致；读持久绑定同时核验其包及真实快照读取权限。`mvp-local` 为无真实主体的单人隔离模式，保留 loopback/数据库白名单及页面标识，不构造虚假 AuthorizationContext。非本机 JWT 路径保留签名、issuer/audience、跨包和撤权负例；未来 HttpSession 另行评审，不耦合 yak-ops 领域或 data-ops。

来源结构漂移、失权或连接失效必须拒绝新预览/读取，错误不成为空样本成功；M9 收敛检测和追踪，而非延后 M5 的基本来源证据。

### 4. 实施切片与责任

M5 第一个实现 PR 交付“自定义 supplier→PG 映射→预览→校验→保存→重开”全流程及负例。映射/身份校验为实际可复用领域逻辑，JDBC 和 HTTP 为适配；不为未来建立无人调用的模块。

维护者在接受时指定产品/合同复核责任及 UI 原型签收。M6/M7 仅消费已验证绑定版，M8 增加关系绑定；任何扩展支持矩阵都需合同测试与明确范围。

## Compatibility, Deprecation, and Migration Plan

- 保留现有 `/api/mvp/quality/**` 与 `mvp_demo_binding`；不偷偷改变旧 JSON 或把旧固定 Mapping 宣称为通用版。
- 若提供导入，必须显式向用户展示旧制造类型、对应快照、十项映射转换与校验结果；无法定位精确模型版本时拒绝转换。首版可不自动导入。
- 新建独立绑定版本存储及当前版指针，通过实施时下一个未占用 Flyway 号前向迁移；V1–V6 等已应用迁移保持不变，不假定未来编号空闲。
- 每个 identityDomainId 在首次绑定生成；属性映射修订保持身份域，来源表或键语义变化必须显式生成新身份域并提示旧 ObjectRef 不自动重定位。
- 草稿和历史快照不修改；模型换快照需用户明确重绑并校验，旧版绑定可读定义不保证已变化来源仍能读事实。
- 回滚仅切回兼容、重新验证通过的绑定版；既有查询证据和对象引用保留其原版本，不能回写历史以伪造一致。

## Test Plan

以下均为**实施验收要求**，不是本提案已执行结果。

| 类型 | 成功场景 | 必需负例/风险边界 | 真实验收 |
| --- | --- | --- | --- |
| 合同 | 自定义 supplier，主键及六类属性合法映射 | 未知/跨类型 Property、重复映射、非法来源引用、摘要前缀误用、未支持转换 | 新增 local-binding-v1 Schema/DTO 夹具及领域单元测试；现有 Native 结构回归 |
| PG/持久化 | 保存→读取→进程重启，历史版与摘要一致 | CAS 竞争、事务失败、NULL 键、重复键冲突、decimal 精度、NaN、超预算、升级旧库 | 新增 `GenericBindingIntegrationTest`；隔离 TEST_DB_* 下 `mvn -pl ontoforge-boot -am verify` |
| 来源/安全 | 固定白名单表、只读来源角色、正确包权限 | 非本机/非白名单/视图/注入、来源 UPDATE/DDL 拒绝、签名伪造、跨包、撤权、连接失效 | 实际 PG 权限与 HTTP 负例；本机与 JWT 两种 profile 各验收 |
| UI | 新建非制造类型→选择快照→映射→真实预览→保存→重开 | 取消预览不持久化、来源漂移、校验错误定位、并发覆盖阻断 | React/Chromium E2E；用户在专用本机合成库实际操作 |
| 回归 | M2/M3 原制造流程继续可用 | 不改变旧路由/绑定 JSON/固定接口错误语义 | 原 QualityImpactObjectSetTest 与已有产品 E2E |

提案 PR 只做文档治理/相对链接检查；运行时、PG、浏览器、来源只读权限、迁移和生产发布均 **NOT RUN**。实际文档命令结果及受测 head SHA 由 PR 报告；CI 使用 PASS/FAIL/NOT SELECTED/NOT RUN 分层记录。

## Rejected Alternatives

1. **把固定十项 Mapping 换成更多固定采购字段**：无法验证通用性；以用户创建的类型和属性 ID 驱动映射。
2. **只预览三行就允许宣称全源有效**：样本无法证明唯一性、类型或必填约束；采用有界全量校验并显式拒绝超限。
3. **允许绑定“最新草稿”**：来源和对象语义可在用户不知情时改变；持久版固定 Local Snapshot，不等待 M10 正式发布。
4. **接受 JDBC URL/自由 SQL/任意转换**：扩大凭据、网络、注入及执行面；首版固定来源、表列映射和严格类型矩阵。
5. **用新 revision 重新定义对象身份**：普通属性调整会破坏下游引用；身份域独立，来源键语义变化显式重绑。

---

**Decision Gate:** Discussion 不授权实施。维护者须在提案 PR 显式记录 `[VOTE] Accepted` / `Rejected` 与决定链接；绿色 CI、合并或 Issue 评论均不替代该决定。
