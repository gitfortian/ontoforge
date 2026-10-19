# OFIP-0010: M8 LinkFact 来源映射与 ObjectSet 有界遍历

**Status:** Accepted

**Discussion:** [初始设计 PR #58](https://github.com/gitfortian/ontoforge/pull/58) · [M8.1 研发准入决策 PR #88](https://github.com/gitfortian/ontoforge/pull/88)

**Decision:** [VOTE] Accepted — 2026-10-19，仓库 Owner `gitfortian` 依据本轮受托授权，正式批准 **M8.1 单列 FK / OUT-IN 一跳**；批准依据、M8-D1–D8 裁决和两项 P1 约束见 [PR #88 决策永久链接](https://github.com/gitfortian/ontoforge/pull/88#issuecomment-6259511734)。

**Accepted Scope:** M8.1 ONLY — 单列外键 LinkBinding，双端 M5 Binding 锁定，FULL 校验、不可变 CAS/幂等修订、OUT/IN 一跳、受控分页与当前来源/权限复核。

**Out of Scope / Pending Decisions:** M8.2 桥表 M:N、M8.3 显式两跳、跨来源/无界图遍历，**未经本次 Accepted 授权**；须未来独立产品及治理门禁。

**Implementation:** Not Started

**Release:** Not Released

**Related:** [M8 #47](https://github.com/gitfortian/ontoforge/issues/47) · [M5 Binding](OFIP-0007-M5-通用DataBinding.md) · [M7 ObjectSet](OFIP-0009-M7-通用ObjectSet查询.md) · [M9 证据](OFIP-0011-M9-来源证据漂移与一致性.md) · [V1.3](../本体产品总体功能与技术架构设计_v1.3.md) · [Native V0.2](../design/核心本体元模型与Native-DSL-AST契约_v0.2.md)

> **当前裁决说明（2026-10-19）：** 本提案仅有 **M8.1 首阶段**经过仓库 Owner 授权的正式 Accepted，见顶部决策永久链接；下文早期 Discussion / Pending 文字为设计演进时的历史记录。M8.2/M8.3 尚未批准，M8.1 产品生产实现也仍未开始。遵守 [OFIP-0001](OFIP-0001-需求提案文档治理与CI证据门禁.md)，不重复实现 M4；本决策记录 PR 仅调整提案状态和引用，不改生产代码、Flyway 或已部署 Schema。

## Motivation

用户已将自定义“检验批次”“设备”映射到隔离 PostgreSQL，接下来需要回答“这一批次涉及哪些设备”，并能从设备反查批次、打开每条关系的真实来源。
只有 LinkType 声明时，页面不能宣称已经存在关系；只有源外键非空时，也不能忽略目标对象不存在或身份冲突。

当前证据基线为 main `6968ea0804f57b2b82f237e37f812bcadb32f343`：

- `QualityMvpController.traceSet()` 已实现固定制造三对象、两 LinkType 的双跳去重、路径计数、分页、断链计数和请求内 `REPEATABLE_READ`。
- `QualityImpactObjectSetTest` 真实 HTTP/PG 覆盖 2 工单、3 订单、4 路径、物理重复事实、两种断链、单向基数冲突及游标错用；这是已有能力，不应重复新建制造专用入口。
- [M2 实施说明](../implementation/M2-ObjectSet-制造质量影响集合实现与验收-20261017.md) 明确当前重复 ID 行用于有限场景，尚无通用关系来源映射；[M3](../implementation/M3-Native-ObjectSet-对象浏览实现与验收-20261017.md) 仍限定三种固定类型。
- [Native LinkBinding 候选](../design/schema/ontology-package-v0.2.schema.json) 只有 `sourceRef/sourceKey/targetKey` 等字段，不能完整表达外键和桥表映射、端点 Binding 锁或孤儿处理策略。

Issue 方向合理，但“一跳、两跳、外键、桥表、双向、多路径”全放在一个实现 PR，范围过大；同时缺少关系身份、方向、基数、空值、断链和计数语义。
成功定义先收窄为：用户配置一条自定义 LinkType 的外键映射，验证、保存后双向查看一跳关系与来源，重复物理行不重复显示业务关系，失败时可以定位并重新校验。

非目标：跨来源 Join、跨包身份合并、递归图语言、无界遍历、图数据库、物化 Link Index、CDC、时态多重边、推理生成权威关系、外部写入及 Actions。
单隔离 PG 内多张受控测试表的 Join 在范围内；“同一 PG”不代表可以访问任意表或提交任意 SQL。

## Public Interfaces

以下是待批准的 `local-link-binding-v1` 与 M7 `local-query-v1` 扩展，不声称兼容现有 Native V0.2 wire format。

| 合同 | 候选内容与约束 |
| --- | --- |
| LinkBinding 身份 | `bindingId/revision/digest`，修订不可变；引用同一 Local Snapshot 的 `linkTypeId`、source/target Object Binding 版本及 sourceId |
| 模型引用 | 复用 `TypeRef={sourceRef,objectTypeId}`；`sourceRef` 为 `LOCAL_SNAPSHOT(snapshotId)` 或 `VALIDATED_DRAFT(draftId,revision)`；持久 LinkBinding 仅允许前者 |
| 外键映射 | `mode=FOREIGN_KEY`；明确外键位于 SOURCE 或 TARGET 端、该端的关系列、另一端的已绑定主键；只允许类型兼容的标量等值连接 |
| 桥表映射 | `mode=BRIDGE_TABLE`；sourceId 下允许的 relation、sourceKey、targetKey；两列分别连接已锁定的端点主键，不把桥表误当对象 |
| 遍历步骤 | `kind=Traverse/linkTypeRef/direction=OUT或IN/maxDepth=1`；`linkTypeRef` 解析到当前版本锁中的 LinkType；首切片至多一个 Traverse，非整个查询只有一个节点 |
| 结果 | `objects[]` 按 M6 对象身份去重；`links[]` 记录 LinkType、两个类型化 ObjectRef、Binding 修订及 `sourceReadRef`；显式区分对象数、关系数、路径数 |
| 执行信封 | 继承 M7 `executionId/versionLock/resultSnapshotRef/sourceReadRef`、质量四维及 accessMode，追加参与的 LinkBinding 与端点 Binding 版本 |

`bindingVersions` 项统一为 `{kind,bindingId,revision,digest,sourceId}`，kind 区分 OBJECT/LINK；Binding ID 的不可变内容解析类型/关系引用，不接受客户端替换 sourceId 或把 revision 解释为 SemVer。

外键位置不会改变 LinkType 的定义方向；反向查询交换遍历起止端，返回的 LinkFact 仍保留原始 source/target。
逻辑关系身份为“当前语义版本 + LinkType + source ObjectRef + target ObjectRef”；重复源行合并为同一 LinkFact，保留证据数量，不生成多个假关系。
当前不支持同端点但不同业务含义、有效期或事件的多条平行关系；这类业务应先建明确关联对象或单独提案，不能静默压缩。

拟新增的真实消费者入口为包范围下 LinkBinding 的 `validate/create/read revisions`、关系配置 UI 以及 M7 查询接口的 Traverse 扩展；准确 URL 与 DTO 在 Accepted 后的实现 PR 固化并覆盖契约测试。
`validate` 不写入激活版本，成功保存以预期 revision 做 CAS，产生新修订；读取只使用明确版本，不隐式切 latest。
保存响应必须包含新 revision/digest/所锁版本与校验范围，不能仅返回“成功”。

首切片的扩展合法阶段固定为 `RootFilter* → Traverse → TargetFilter* → (Sort? → Project? → Page | Aggregate)`；RootFilter/TargetFilter 都仅沿用 M7 有界 AND 条件，不增加 OR 或任意算子重排。
Traverse 之前的属性按根类型解析，之后的 Filter/Sort/Project/Aggregate 按当前输出端点类型解析；方向决定该输出类型，不能继续把根属性当目标属性。
M8.3 可在第二段 Traverse 后再次对当前输出类型过滤，总 Traverse 至多二；其余排序/投影/分页或聚合只在末尾出现。服务端执行锁必须收集每段 LinkBinding、根/中间/最终端点的完整 Binding 闭包，不能只锁输出类型。

| 失败 | 候选错误与用户恢复 |
| --- | --- |
| 引用/方向/类型不成立 | 422 `INVALID_LINK_BINDING` / `INVALID_TRAVERSE_TYPE`；定位字段，保持旧有效版本 |
| 基数或对象身份冲突 | 409 `LINK_CARDINALITY_CONFLICT` / `OBJECT_IDENTITY_CONFLICT`；不返回挑选后的假单值 |
| 版本冲突、结构漂移 | 409 `BINDING_REVISION_CONFLICT` / `BINDING_SCHEMA_DRIFT`；重新打开、验证、明确保存新修订 |
| 超深度、访问量/路径预算或超时 | 422 `UNSUPPORTED_TRAVERSE_DEPTH` / 429 `QUERY_BUDGET_EXCEEDED` / 503 `SOURCE_TIMEOUT`；本次 FAILED，无空集合冒充成功 |
| 无当前读取资格 | 沿用统一授权层的安全拒绝，不输出受限表名、端点存在性、断链数或 Explain |

M5–M7 的已批准错误与信封优先；上表新增错误在实施时需映射成统一目录并测试，不能因名称相近产生多个含义。

## Proposed Changes

### 1. 从定义到有来源的真实关系

1. 用户选择同一不可变 Local Snapshot 中的 LinkType；显示定义方向、基数和 evidenceRequired。
2. 从 M5 受控来源目录选择外键或桥表，显式选择两个已绑定端点；禁止按列同名自动确认关系。
3. 预览分别显示有效匹配、合法空引用、非空孤儿引用、重复事实和基数冲突；预览是预算内样本，不证明全库完整。
4. 保存时复核当前模型摘要、所有 Binding 修订、来源结构和可访问状态；CAS 通过才发布新的不可变 LinkBinding 修订。
5. 用户从一个对象或有界 ObjectSet 选择“关联对象”，得到一跳去重结果、质量提示及可展开来源；可切反向并重新执行。
6. 结果详情复用 M7 冻结结果分页与 M9 证据面板；结构漂移、撤权、TTL 失效有明确恢复入口。

无配置时显示“尚未配置关系来源”；不能把已有 LinkType 画成已存在的业务连线，也不能显示假关系示例混入真实结果。

### 2. 身份、基数和断链

端点身份复用 M6 的源内主键规则；不做隐式字符串 cast、trim、大小写折叠或跨源同键合并。
合法 NULL 外键表示未提供该关系，单独报告质量计数；空字符串按 M5/M6 STRING 精确值处理，可以匹配合法的同值键，不自动等同 NULL；非法键另行拒绝。
非空外键找不到可读取目标是断链；只在确认具备全部相关读取资格后显示可见孤儿计数，不能经“断链”泄露被拒绝的端点。
本期无行/列授权时，仅在现有包权限及隔离来源规则覆盖整个读范围时运行；不能声称已支持细粒度企业授权。

基数遵从 LinkType source→target：ONE_TO_ONE 双端至多一、ONE_TO_MANY 限制每个 target 的 source 数、MANY_TO_ONE 限制每个 source 的 target 数、MANY_TO_MANY 两端不作唯一限制。
执行前对结果涉及的端点核查所有相关可访问关系事实，包括反方向约束；不能先过滤掉冲突边后声称满足基数。
核查受统一预算限制，无法完成时 FAILED；单次查询通过只证明此次涉及端点，不宣称整份 Binding 已全库认证。
冲突拒绝本次结果；孤儿允许返回已验证子集，但标为 PARTIAL/KNOWN_SUBSET，并给出明确的断链原因。
准确统计完整请求所需关系时，存在断链不能声称业务影响总量已完整；已知子集计数仍可为 EXACT。

### 3. 有界执行、去重和结果稳定性

先解析并校验 Typed Traverse，再生成受控 SQL；标识符来自已验证来源目录并安全引用，所有值参数化。
端点来源、桥表、计数、基数检查和结果读取在同一连接、一次只读 `REPEATABLE_READ` 内完成，不跨 HTTP 请求持有 PG 事务。
首期候选上限继承 M7：3 秒执行、10,000 个访问对象/关系事实与结果预算、每页 1–50；路径预算独立计量，服务端可收紧，客户端不可放宽。
预算按去重前扫描的受控事实及中间扇出设防，不能先生成海量 Join 再依赖最终 LIMIT；使用真实执行计划核验保护有效。
只在完整预算内查询完成后，生成 M7 的有界结果快照；TTL 5 分钟，进程重启失效，页大小/游标约束继承 M7。
冻结结果保持该 execution 的成员/排序/计数；不意味着来源 PG 的 MVCC 历史被保存，或后续源变化不影响新查询。
每页重新核验当前授权、Binding/来源状态和结构；临时来源不可核验时 fail-closed，恢复后须满足原 TTL，不重算旧结果成员。

对象输出按最终类型化身份去重；关系输出按逻辑关系身份去重；路径输出按有序 LinkFact 身份元组去重。
同一对象从两条路径到达只能算一个对象，路径数仍可为二；禁止将路径数当作影响对象数或重复汇总对象属性。
稳定排序使用类型化身份与路径完整元组作唯一 tie-breaker，固定比较语义；不得依赖数据库当前行顺序。
证据保存受控表列、连接规则、物理重复数、读取区间及版本；无稳定源行键时明确行级定位不可用，不能把 `ctid` 当长期身份。

### 4. 每个实现 PR 的独立出口

| 切片 | 用户可操作结果 | 进入下一切片的门禁 |
| --- | --- | --- |
| M8.1 首交付 | 一条自定义 LinkType 的外键映射保存→验证→一跳 OUT/IN→去重/证据/错误恢复；完整前后端 | PG 双端基数、孤儿/重复、安全负例及 Chromium 黄金故事通过 |
| M8.2 后续 | 同一隔离 PG 的桥表 M:N，复用 M8.1 全流程，无平行关系假设 | 桥表身份/重复/空值/扇出反例与 UI 验收通过 |
| M8.3 后续 | 两个显式 Typed Traverse 组合成恰好两跳路径与最终对象集合 | 各跳类型衔接、循环/自关联、菱形路径、多路径与预算通过 |

二跳使用两段明确 LinkType 路径，各步 `maxDepth=1`，总深度最多二；不把现有 V0.2 允许 maxDepth≤8 当成产品已支持。
二跳闭环可回到已出现对象，允许有限二步路径但不递归展开；最终对象/关系仍去重，路径保留有序身份。
M8 主 Issue 作为上述切片的验收汇总；M8.1 完成只能勾选首切片，不能将桥表、两跳标为已交付。

领域责任保持 Native 定义、Binding、对象身份、查询计划和来源适配分离；只有真实调用方出现才抽取模块，不新建空 Core/Graph 工程。
现有 `mvp-local` 隔离模式是当前黄金故事；JWT 包授权路径保留正反例回归，OIDC/未来 yak-ops HttpSession 不作为核心遍历的前置实施任务。

## Compatibility, Deprecation, and Migration Plan

- 不改历史 V1–V6、Native V0.2 Schema、Local Snapshot 内容或原 M2/M3 响应；新功能使用版本化 local profile，不塞入不存在的 schemaRelease/index/auth 字段。
- 持久 LinkBinding 采用实施时下一个未占用的前向 Flyway 迁移，记录 immutable revision/digest、端点 Binding 锁和明确来源模式；旧迁移不可重写。
- 从现有 `mvp_demo_binding` 导入只可作待用户复核的候选配置；重复事实的旧场景不能未经复核自动转换成通用 LinkBinding。
- 单个 Local Snapshot 不同、LinkType 删除/端点/基数/证据要求改变或任一 Binding 变更时，旧版本仍可查看定义，但不能自动重绑定执行；重新验证并保存新版本。
- 回滚激活指针需再次校验当前来源；历史映射“曾经有效”不是现在可读的证据。查询结果快照按 TTL/撤权/结构漂移失效，不回退外部事实。
- 跨来源、时态平行边、正式 SchemaRelease/IndexGeneration 适配均需后续合同评审；未来变更不能静默改变本期关系身份。

## Test Plan

以下为未来实现验收，不是本提案 PR 的运行成绩；实施文件名为计划目标，尚未创建。

| 类型 | 成功与必需负例 | 真实验收目标 |
| --- | --- | --- |
| 合同/类型 | 自定义双端类型、同 Snapshot；错方向、错端点、草稿持久化、错类型、未知字段、错误版本拒绝 | 计划 `LinkBindingContractTest`、`ObjectSetTraverseContractTest`，正反 JSON/HTTP 用例 |
| PG/Flyway | 外键双向、一对多/多对一/一对一/多对多、保存重启；孤儿、NULL、重复、冲突、CAS、升级失败 | 计划 `LinkBindingPostgresTest` / `ObjectSetTraversePostgresTest`；隔离 `ontoforge_test_*` + TEST_DB_*，`mvn -pl ontoforge-boot -am verify` |
| 稳定性/预算 | 固定结果跨页、源并发增删不修改已冻结成员；TTL/重启/撤权/漂移失效，超级节点不突破中间预算 | 真实 PG17 持续写入与超时注入；记录执行计划、计数及执行 commit |
| 安全 | 本机隔离边界、JWT 正反例；任意 SQL、未允许表、篡改游标、反向越权及错误侧信道拒绝 | 复用既有安全测试，新加真实 HTTP 负例；禁止连接开发库或生产源 |
| 二跳后续 | 菱形路径最终对象去重、路径不丢，自环/双向循环有界；错类型衔接/第三跳拒绝 | M8.3 独立真实 PG + API/Chromium 验收，首切片标 NOT SELECTED |
| UI/黄金故事 | 自定义模型→端点 Binding→关系配置→校验→双向查询→证据→错误修复后重试 | 真实 React/Chromium；重开配置和结果，不能用静态构建代替 |

提案 PR 仅运行文档治理与链接检查，并记录 base/head SHA；上述后端、PG、前端运行、浏览器、用户安装及 Release 均为 NOT RUN。
实现 PR 分别报告 PASS / FAIL / NOT SELECTED / NOT RUN；保留现有 M2/M3 和 JWT 兼容测试，CI PASS 不等于用户安装、生产或发行验收。

## Rejected Alternatives

1. **按 LinkType 自动生成业务边**：定义只规定允许的关联，无源事实会伪造 LinkFact；必须经过显式映射和真实端点匹配。
2. **一次实现外键、桥表、任意多跳和图引擎**：当前无通用关系映射，失败分支和评审面过大；以三条可独立使用的纵向切片交付。
3. **所有 ID 转 text 后 Join**：可能合并不同类型/格式的身份并掩盖映射错误；先锁端点主键规则，显式验证兼容性。
4. **最终 DISTINCT/LIMIT 解决全部问题**：无法防止中间扇出、基数冲突、未知断链和错误计数；分别治理对象/关系/路径身份及预算。
5. **跨页保留 PG 事务，或用模型摘要假称数据快照**：会占用连接并混淆版本与事实；使用有界结果快照和真实读取证据，不承诺源历史重放。

---

**Decision Gate:** 维护者须分别确认关系映射合同、三段交付范围、双端基数/断链语义、结果快照与预算策略。Discussion 不授予实施权限，合并/CI 不自动改变 Decision、Implementation 或 Release。

---

## 2026-10-19 治理进展：R1 / R2 产品设计均已签收（不构成 OFIP Accepted）

- **R1**：产品所有者在 ChatGPT 会话中明确“认可，进入 R2”，签收 [PR #77](https://github.com/gitfortian/ontoforge/pull/77) 中经纠错的 D1–D7 产品方向。R1 已 squash 合并 `58636a8f6264838826119e59d24646e892cd59e6`。
- **R1 审核修正的规范依据**：不同查询 root 的孤儿不混算；M5 强制来源唯一键，外键端点不可构造重复主键作为合法去重正例；ONE_TO_ONE + TARGET FK 用于可达的真实基数冲突；历史 Binding revision 与当前授权资格分开判定。
- **R2 产品设计已正式审定**：产品所有者明确要求“正式审定”，批准 [PR #79](https://github.com/gitfortian/ontoforge/pull/79) 的 [R2-D1–D8 设计基线](../design/M8-LinkBinding-Traverse-R2-领域API与执行合同评审.md)（见“10.2 R2 产品正式审定记录”）。包括 LinkBinding 不可变修订与 CAS、双 scope 孤儿处理、FULL 验证资格、单 PG 双端一致性、全端预算/权限/游标、现有 UX 入口，以及**独立 `local-link-binding-v1` + `local-query-v2/local-result-v2`**。三份 [候选 JSON Schema](../design/candidates/m8-r2/) 尚未由生产服务消费；合并设计 PR 不等于维护者对 OFIP 的 Accepted。
- **差异处理**：本 OFIP 早期外键+桥表+两跳总体规划保留为路线图；产品已确认首交付仅 M8.1 外键双向一跳（M8.2 桥表、M8.3 显式两跳单独评审/验收）。本文如存在与已审定 R2 关于 scope、历史版本资格、FULL 校验、FK 方向/基数、query v2、双端授权/快照/预算不一致的早期草案文字，**以后附产品审定的 R2 合同为设计基线**；在获得维护者正式决策前，该基线仍不是已生效生产规范。
- **门禁未变**：`Status: Discussion`；`Decision: Pending`；`Implementation: Not Started`；`Release: Not Released`。**产品 R2 签收只是设计授权**，当前没有任何因此而自动成立的维护者 `[VOTE] Accepted`；新 M8 UX-Low、UX-High 均未签收；未授权生产实现。

### R2 产品审定的正式合同摘要（OFIP 决策仍 Pending）

| 部分 | 2026-10-19 已批准的 M8.1 设计基线 | 后续证据门槛 |
| --- | --- | --- |
| 身份/版本 | 同 LOCAL_SNAPSHOT 的 LinkType + 双端 Object Binding 不可变修订；LinkBinding Head、Revision、CAS 与幂等；历史修订仅在当前完整可核验时执行 | 历史重开、保存重试、版本漂移与 CAS 冲突 |
| FK / 基数 | FK 只能指向对端 M5 身份列；SOURCE FK→MANY_TO_ONE，TARGET FK→ONE_TO_MANY，可额外校验 ONE_TO_ONE；M:N 留待 M8.2 | PG 外键方向、NULL/空字符串、基数/身份冲突 |
| 验证 / 质量 | ValidationScope 与 TraversalScope 分离；只接受在预算内 FULL 核验后保存 VALID 或 VALID_WITH_ORPHANS；只让受影响 root 显示 PARTIAL/KNOWN_SUBSET | 不能用抽样冒充完整；无关 root 不被孤儿污染 |
| 查询合同 | local-link-binding-v1，独立 local-query-v2 / local-result-v2；rootSelector 唯一根条件、一段 TRAVERSE、Target Filter 与终结查询 | M7 v1 原样兼容，严格 Schema / AST 安全负例 |
| 多端事务 / 证据 | 同隔离 PG 的一次只读 REPEATABLE_READ；应用元数据 CAS 非跨库原子；两端 SourceReadRef、两条 OBJECT 和一条 LINK 版本及来源指纹 | 结构漂移与权限撤销、执行前后资格复核 |
| 预算 / 游标 | 双端扫描合计 10k、中间 Join ≤10k、LinkFact ≤10k、来源/结果各 ≤8 MiB、来源执行 3s、page ≤50、TTL ≤300s | 禁止先无界 Join 再截断；撤权恢复旧 cursor 仍 410 |
| UX | Native“关系类型”→配置/核验/CAS 保存→ObjectSet 一跳 OUT/IN→证据/异常恢复 | **全新** UX-Low、UX-High 必须分别签收 |

以上是经产品审定但**尚待 OFIP-0010 维护者决议**的权威设计基线。候选 REST 资源路径、迁移号、物理表名和 JSON Schema 不意味着已开发。正式生产实现仍需 UX 双阶段签收 + 维护者明确的 `[VOTE] Accepted`（理由、日期与 permalink）。

---

## 2026-10-19 · M8.1 UX 双阶段签收后，正式实施决策待维护者表决

**这一节是待表决的设计提案更新，不是投票或生产实现状态变更。** 顶部仍保持 `Status: Discussion / Decision: Pending / Implementation: Not Started / Release: Not Released`，不能将“用户批准高保真原型，要求启动开发”解释为维护者已完成 `[VOTE] Accepted`。

### 已满足的产品与浏览器设计证据

1. **R1/R2 产品与架构设计**：[#77](https://github.com/gitfortian/ontoforge/pull/77) / [#79](https://github.com/gitfortian/ontoforge/pull/79) 已签收合并。以 R2-D1–D8 审定版作为 M8.1 的候选实施合同，而不是本文早期包含 M8.2/M8.3 的总路线大范围草案。
2. **UX-Low**：[#84](https://github.com/gitfortian/ontoforge/pull/84) 已签收合并，静态 Chromium 18/18 PASS。
3. **UX-High**：[#87](https://github.com/gitfortian/ontoforge/pull/87) 已正式签收合并 `86da1c71`。高保真同 head `7c82c9f0` 的 [Chromium 27/27](https://github.com/gitfortian/ontoforge/actions/runs/39193857575)、[Docs](https://github.com/gitfortian/ontoforge/actions/runs/39193857818)、[原型通用回归](https://github.com/gitfortian/ontoforge/actions/runs/39193857568) 和 [Required Quality](https://github.com/gitfortian/ontoforge/actions/runs/39193857670) 均已成功。已纠正撤权证据暴露、草稿/冻结版本混用、375px 用户任务入口过深。
4. **用户产品签收**：2026-10-19 当前会话用户进一步明确“原型通过，继续开发启动”。这只改变产品设计/实施准备的准入状态，不自动改变 OFIP 维护者的独立治理状态。

### 请维护者正式决议的八项合同（仅 M8.1 首交付）

- **M8-D1：范围**：同一 Local Snapshot 下用户自定义 LinkType 的单列 FK 双向一跳；M8.2 BRIDGE_TABLE、M8.3 两跳独立交付，拒绝一次性扩大。
- **M8-D2：身份**：冻结 SOURCE/TARGET M5 Binding 不可变 revision、digest、身份域；LinkFact 身份为 LinkType + 两端类型化 ObjectRef + 快照，不因 OUT/IN 颠倒。
- **M8-D3：验证/质量**：只有预算内 FULL 才允许 VALID/VALID_WITH_ORPHANS 保存；NULL 无关系，非空孤儿仅影响本次受影响 root 的 PARTIAL/KNOWN_SUBSET，基数/身份冲突 FAILED。
- **M8-D4：版本/API**：LinkBinding Head/Revision CAS 与创建 idempotency；独立 `local-link-binding-v1` 及 `local-query-v2/local-result-v2`，保证 M7 `local-query-v1/local-result-v1` 原样兼容。
- **M8-D5：PG 事务**：同一隔离 PG 连接只读 REPEATABLE_READ 验证/Join 双端，来源连接限制为已批准表列；与应用元数据 CAS **不主张跨库原子事务**。
- **M8-D6：预算/结果**：全端扫描、Join 候选、LinkFact 均 ≤10k，来源/结果各 ≤8MiB，超时 3s，页 ≤50、TTL ≤300s；Join 扇出预算前置、结果冻结且来源当前读资格按页核验。
- **M8-D7：授权/恢复**：快照 + 两端 OBJECT + LINK Binding + 来源双端的当前授权闭包；无授权不透露敏感源信息；撤权旧 cursor 永久失效，恢复授权也不可复活。
- **M8-D8：产品验收**：复用已签收两套 UX 与项目现有 React 工作区，单纵向 PR 交付真实 PG17 + Spring Boot API + React Chromium 黄金故事及负例/旧功能回归，不把静态原型用例当生产 E2E。

**实施前高保证准入及 L01–L18 验收矩阵：** [M8.1 实施前审查](../implementation/M8-1-实施前高保证审查与纵向交付验收矩阵.md)。该审查列明真实 `main@86da1c71` 的 M5/M7/Native/Security/React/Flyway V7 可复用责任，以及 LinkBinding 与 Traverse 缺口；无任何生产代码在本次决策 PR 中修改。

### 第二轮复核后，请维护者在 M8-D2 / M8-D4 中确认两项细节

**IDEMPOTENCY（P1）**：已签收的 UX 支持“修改已有 revision 后保存结果 UNKNOWN → 用幂等键恢复”，但当前 R2 结构化请求仅在创建 `expectedRevision=0` 时必需 `idempotencyKey`；更新若省略，在服务端成功写入但响应丢失后，仅重试原 CAS 可能错误返回 409。建议强制 CREATE/UPDATE 都绑定操作级 idempotencyKey + 规范化请求 digest，应用数据库中随 revision 原子写入；同键同 body 恢复原结果、同键不同 body 安全 409。若不采用此方案，维护者应写出可证明等价的 UPDATE UNKNOWN 恢复策略，并覆盖跨 package 与服务重启后恢复的负例。

**IDENTITY（P1）**：不同 ObjectType 的 `identityDomainId` 正常可以不同，绝不能以「两端 domain 不相等」拒绝合法 LinkFact。身份域参与每个端点完整 `ObjectRef` 的相等、版本、去重；而 FK JOIN 判断依赖受控来源实际 FK 对另一端受控 sourceKey 的**严格类型兼容值等价**，并且 source/target ObjectType 与 LinkType 声明一致。相同 key 文本不能自动让两个对象 Ref 相等，但也不能禁止确有外键事实的跨类型关系。保存及执行均须有 PG 真实负例。

详细取舍与精确测试期望列于 [M8.1 高保证实施前审查 §3.1](../implementation/M8-1-实施前高保证审查与纵向交付验收矩阵.md)。两项只补足已批准方向的风险边界，**未修改**尚未生效的生产 Schema、REST 或 DB；如果维护者认为构成 R2 实质改变，应退回产品/架构复议，而不是静默推进。

### 治理操作说明

维护者须在本次 **OFIP-0010 独立决策 PR** 留下可追溯、带理由和日期的明确 `[VOTE] Accepted` / `Rejected` / `Request changes`，**至少覆盖以上 M8-D1–D8 与 M8.1 单阶段边界**。投票真实存在后，再将其 permalink 回填本文件的 `Decision`，变更 `Status` 并按独立流程合并 Accepted 设计。**不得代维护者投票或在其缺席时合并为 Accepted。**

在上述决议成立前，只授权审查、计划与非生产证据准备。真实 M8.1 Java / React / Flyway / PG17 运行时仍 `NOT STARTED`，M8.1 专属生产黄金故事仍 `NOT RUN`，不存在因为 #87 已合并就“自动开发成功”的事实。


---

## 2026-10-19 · 当前有效决定：[VOTE] Accepted（**仅 M8.1 首阶段**）

**权威决策：** [仓库 Owner 在 PR #88 的受托正式投票](https://github.com/gitfortian/ontoforge/pull/88#issuecomment-6259511734)；GitHub 评论署名 `gitfortian`，`author_association=OWNER`，时间 `2026-10-19T04:15:18Z`（新加坡时间 12:15:18）。决定经用户在本轮对话明确委托由已授权的 GitHub 连接代为提交，**不声称存在另一位独立批准人**。本提案顶部元数据现作为阶段性权威状态。

### 已获批准的首阶段范围

- M8-D1–D8 的详细裁决以投票评论为准：不可变的 Local Snapshot + 两端 M5 Binding；单列 FOREIGN_KEY 的 FK 所在端、Cardinality 与实际 FK→PK 精确值匹配；预算内 FULL 方可保存 VALID / VALID_WITH_ORPHANS；只对受影响根产生 PARTIAL / KNOWN_SUBSET；独立 v2 版本的单步 OUT/IN Traverse；双端 REPEATABLE_READ 读取、当前授权/来源、结果缓存及受控分页。
- **P1 幂等恢复已裁定：** 创建与更新均采用操作级 idempotencyKey/规范请求摘要的安全重复处理；提交结果 UNKNOWN 时先恢复已成功的不可变 revision。同 key 不同 body 拒绝，跨包拒绝，新 key + 旧 expectedRevision 仍按 CAS 409；这一决定必须在生产 DTO/正式 JSON Schema 与真实 PG17 / HTTP 负例中实现，不能继续把候选 create-only Schema 当正式合同。
- **P1 身份语义已裁定：** 不同 ObjectType 的 identityDomainId 可以不同；完整 ObjectRef 不能跨域误判相等或去重，但 FK 真实、受控、类型兼容地指向另一端源主键时允许构成合法 LinkFact。不能仅因源、目标域 ID 不等而拒绝关系。
- 实施以 [R2 已审定领域/API/执行合同](../design/M8-LinkBinding-Traverse-R2-领域API与执行合同评审.md) 和 [高保证 L01–L18 测试矩阵](../implementation/M8-1-实施前高保证审查与纵向交付验收矩阵.md) 为依据；使用 [UX-Low #84](https://github.com/gitfortian/ontoforge/pull/84)、[UX-High #87](https://github.com/gitfortian/ontoforge/pull/87) 已签收的产品路径与恢复交互。实现需先基于最新 main 核查真实 Flyway 迁移占用，不重写旧迁移。
- **M8.2 桥表 M:N / M8.3 显式两跳保留 Discussion 候选，均须另行评审及签收，不随 M8.1 自动生效。**

### 状态与交付边界

`Status: Accepted` 表示 **M8.1 设计准入与生产研发启动授权**，不是本体实例关系功能已完成。`Implementation: Not Started` 与 `Release: Not Released` 继续真实保留。新 M8.1 纵向生产 PR 需要 Java 21 / Spring Boot + PostgreSQL 17/Flyway + React/TypeScript，真实 PG17/HTTP/Chromium 全链路、来源只读角色及 JWT 负例、CAS 并发/幂等/游标撤权和 M5–M7 回归；所有证据和 PR 同 head。研究型 [PG17 #89](https://github.com/gitfortian/ontoforge/pull/89) 的 14 项 PASS 与高保真原型 27 项 PASS 不能代替生产 E2E。

本节替代上文任何基于以前时间点的“未投票”“Decision Pending”描述，但不改变其作为历史审查记录的含义。
