# OFIP-0010: M8 LinkFact 来源映射与 ObjectSet 有界遍历

**Status:** Discussion

**Discussion:** 待创建的 `[DISCUSS][OFIP-0007–0016]` MVP 功能提案 PR（创建后回填链接）

**Decision:** Pending（须维护者显式记录决定、理由、日期及 permalink）

**Implementation:** Not Started

**Release:** Not Released

**Related:** [M8 #47](https://github.com/gitfortian/ontoforge/issues/47) · [M5 Binding](OFIP-0007-M5-通用DataBinding.md) · [M7 ObjectSet](OFIP-0009-M7-通用ObjectSet查询.md) · [M9 证据](OFIP-0011-M9-来源证据漂移与一致性.md) · [V1.3](../本体产品总体功能与技术架构设计_v1.3.md) · [Native V0.2](../design/核心本体元模型与Native-DSL-AST契约_v0.2.md)

> 本文为功能正式设计提案，不是已批准合同或实现说明。遵守 [OFIP-0001](OFIP-0001-需求提案文档治理与CI证据门禁.md)；不复核正在实施的 M4，仅消费其候选 TypeRef。提案 PR 不改生产代码、数据库迁移或现有机器 Schema。

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
