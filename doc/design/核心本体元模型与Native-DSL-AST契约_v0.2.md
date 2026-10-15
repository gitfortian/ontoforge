# Native DSL/AST V0.2：企业运营本体核心契约

> **2026-10-15；架构评审候选，未签收、未实现正式运行时。**
> 对齐 [统一总体架构 V1.3](../本体产品总体功能与技术架构设计_v1.3.md)；PG17 ObjectSet / Action 实测证据已集中在 V1.3 第6、8和13章；V0.1 历史定义文档不再作为现行合同，仅保留 [V0.1→V0.2 迁移矩阵](./V0.1到NativeV0.2迁移矩阵.md) 和回归夹具。
> 本文件所有 JSON 格式均为本项目方案，不声称属于 Palantir 私有实现或国家标准的官方 DSL。

## 0. 顶层原则：本体是受治理的语义执行基础

企业系统的语义资产来自 Object/Link、Schema/Type、Data Binding、确定性 Rule/Function、Decision Strategy、Policy、Action。它们并不必须全部转为 OWL。**事实推理和规则计算得出的结论，不是行动授权；外部操作被接受，也不是业务结果达成。**

必须严格区分五个合同平面：

| 平面 | 定义什么 | 不能被误认为 |
|---|---|---|
| Native Definition | 可发布的 ObjectType、Property、LinkType、Binding、Function、Decision、Policy、Action 声明 | 已存在的实例和业务事实 |
| Typed AST | 明确 kind、已锁定依赖、稳定顺序的编译产物 | 已审核并激活的 Release |
| ObjectSet Query | Typed Filter / Traverse / Aggregate 等 Lazy 逻辑计划与预算 | Java 内存数组或任意 SQL |
| Query Result Evidence | Accuracy、Completeness、Freshness、来源版本与 Scope | 一定完整的企业实时真相 |
| Semantic Execution Record | 决策来源、联合版本、审批、执行、回执和结果 | 本身拥有执行权限的命令 |

## 1. 本次机器可读交付

| 用途 | 契约与样例 |
|---|---|
| 输入模型包 | [Native Package JSON Schema](./schema/ontology-package-v0.2.schema.json)、[制造质量模型包](./examples/manufacturing-quality-v0.2.json) |
| 规范化 AST | [Typed AST Schema](./schema/ontology-ast-v0.2.schema.json)、[制造质量 AST](./examples/manufacturing-quality-ast-v0.2.json) |
| ObjectSet 逻辑查询 | [Query Schema](./schema/objectset-query-v0.2.schema.json)、[质量影响四跳查询](./examples/manufacturing-impact-query-v0.2.json) |
| QueryResult 证据 | [Result Schema](./schema/objectset-result-v0.2.schema.json)、[部分来源查询样例](./examples/manufacturing-impact-partial-result-v0.2.json)、[执行失败但不伪造数据的样例](./examples/manufacturing-impact-failed-result-v0.2.json) |
| 运营执行证据 | [Semantic Execution Record Schema](./schema/semantic-execution-record-v0.2.schema.json)、[UNKNOWN 样例](./examples/manufacturing-action-unknown-v0.2.json) |
| 验收 | [正反例及局部语义检查](./tests/test_native_v02.py)；GitHub Actions native-contract-v02.yml |

五个 Schema 采用 JSON Schema Draft 2020-12、additionalProperties:false。**结构合法不代表引用合法，引用合法不代表审批已完成，审批已完成不代表当前事实完整，也不代表 Action 获准执行。**

## 2. Native 模型及身份

- Package：schemaVersion=0.2.0、packageId、version、已锁定 imports(packageId,version,digest)。Package Coordinate、内容摘要、发布审批和 Active Release 是四个不同的状态/身份。
- ObjectType：稳定 id、apiName、primaryKeyRef、Property。主键必须为自身存在的必填简单类型，变更时需要影响分析和迁移，不允许覆盖旧对象身份与运营编辑。
- Property：valueType、required、searchable、classification、enumValues。required 是结构与质量期望，不是多源实时对象一定完整的证明；不可用不可检索的字段构造静默失败的查询。
- LinkType：sourceTypeRef、targetTypeRef、cardinality、evidenceRequired。LinkType 的存在并不自动断言任何一条实际 LinkFact；真实关系来自有版本的 LinkBinding 和来源证据。
- Native ID 默认不强制绑定 HTTP IRI。V0.1 的 namespaceIri 与节点 IRI 作为可选语义适配映射保留，不能静默丢弃。

跨语言 canonicalJSON（考虑 RFC 8785/JCS）、ASTDigest、LockDigest、ReleaseDigest、签名及激活状态是正式 P0 Compiler/Registry 责任，**此处的示例 sha256 字符串只是结构测试占位符**，未实现真实摘要校验。

## 3. Data Binding：权威落在属性，而非整个源系统

BindingDefinition 包含 sourceRef、sourceSchemaVersion、ObjectType、sourceKey、版本、FieldMappings、IdentityRule、refreshMode 和 Completeness。每个 FieldMapping 分别声明 authority=AUTHORITATIVE 或 SUPPLEMENTAL。不能因为 MES 是批次数据源，就认为 MES 对订单金额拥有权威。

本轮的保守语义校验拒绝一个对象类型的同一个 Property 同时拥有多个 AUTHORITATIVE 来源，要求专门的冲突裁决机制后方可扩展。SourceKey 只表示来源内身份；跨来源合并必须有可审 EvidencePolicy，不可按同名键推导实体等价。SourceRef 只允许指向连接注册表，**不能写入凭证、Token、连接密码**。

需要长期分离：SourceFact、可重建的 Object/Link Index、OperationalEdit、EffectiveObject、ExternalReceipt，以及各自版本、SourceCheckpoint、Freshness 和权限失效时刻。

## 4. ObjectSet：逻辑类型 + 安全与质量证据

ObjectSetDefinition 记录可复用集合资源的 rootType；QuerySpec 负责动态 Filter、Traverse、Project、Sort、Aggregate、Page。保存的定义、静态主键列表、动态成员和 QueryResult Snapshot 不是同一种资源。

QuerySpec 不接受 rawSql 或由用户自填的 AuthorizationContext；服务器负责解决主体、Policy、SchemaRelease、BindingVersion。Filter 的类型、非 searchable 字段、Traverse 方向和目标、Aggregate 字段、运算顺序及预算需在查询编译时检查。金额等指标必须按业务对象 Identity 去重，不能按多条 Link 路径重复汇总。

ObjectSetResult 分开标记 Accuracy(EXACT/APPROXIMATE/UNKNOWN)、Completeness(COMPLETE/PARTIAL/UNKNOWN)、Freshness(FRESH/STALE/UNKNOWN)、answerScope(REQUESTED_SCOPE/KNOWN_SUBSET)。**对已知子集的精确计数，不代表整个企业的完整计数**；PARTIAL 必须标明 KNOWN_SUBSET；宣称 COMPLETE 时所有列明的来源 Checkpoint 也必须 COMPLETE。COUNT_DISTINCT 必须为非负整数，近似聚合必须给误差范围。执行 FAILED 时返回安全错误代码而不是捏造一个空集合或数值 0，不可把未授权对象存在性泄露给客户端。

PR #8/#9 通过 PostgreSQL 17 的 80 万与 600 万高扇出关联查询、RLS、去重和竞态 POC，说明其可作为 P0 基础存储候选。真实规模的成本/容量/冷热缓存及 Agent 端到端 SLA 仍未签收，必须保留 Logical Query AST 与 Physical Plan 的分离。

## 5. Function、Decision、Policy、Action 的严格界线

| 资产 | 声明内容 | 不得自动获得 |
|---|---|---|
| Rule | 确定性规则、Artifact 版本和摘要 | 当前业务事实完整性 |
| Function | 签名、ImplementationDigest、输入、预算、READ_ONLY / EDIT_PLAN | 外部业务系统写入 Token |
| DecisionStrategy | READ_ONLY Function 与规则的组合、确定性结果 | 自动执行 Action |
| Policy | DATA_ACCESS / ACTION_AUTHORIZATION / APPROVAL 规则版本 | 因存在 Policy 就自动放行 |
| Action | 目标、EditPlan Function、Decision、Policy、Risk、EffectScope、Approval、Executor 与核验要求 | 已获授权或已实际执行的事实 |

READ_ONLY Function 不能声明任何修改；EDIT_PLAN 只产生 CHANGE_SET。HIGH/CRITICAL Action 必须 ALWAYS Approval，外部命令必须有 executorRef，且行动目标必须位于 Function 声明的 EffectScope 中。审批应绑定真实发布的 Function/Policy/Schema/Binding 版本，**不是对可漂移的 latest 引用授权**。

Agent/Query 的推理结论只是候选决策；Action Runtime 在 Preview/审批与真正派单时分别做权限、来源状态、目标冲突、版本及效果范围复核。外部 ERP 超时必须保留 UNKNOWN，优先按幂等键和业务回执对账；HTTP 确认不等于生产业务效果成功。

## 6. Semantic Execution Record 与模型发布

执行证据应关联 Knowledge Snapshot、SchemaReleaseDigest、BindingVersion、SourceCheckpoint、FunctionDigest、RuleVersion、DecisionVersion、PolicyVersion、ActionVersion、主体/批准人、目标/效果范围、IdempotencyKey、事件、外部回执与最终业务状态核验。

本轮 Runtime Record Schema 的 VERIFIED_SUCCESS 必须同时满足 ACCEPTED RemoteReceipt 和 CONFIRMED OutcomeVerification；UNKNOWN 不能声称业务结果已确认。**这是服务端生成的证据结构，不是一个可由客户端 JSON 伪造的批准或真实执行记录。** 防篡改存储、签名、重放输入归档和异步对账由未来运行时实现。

发布过程建议 Schema/AST/Ref Resolver → Compatibility/Quality → Signed Review → Immutable Release → Binding Activation → ObjectIndex Generation；OWL/RDF/SHACL 属于按需互操作派生 Profile，不是 Native 发布前提。任何 Schema/Function/Policy Digest 变化都应触发原审批和依赖兼容性重验。

## 7. V0.1→V0.2 迁移与研发签收

详见 [V0.1 到 Native V0.2 迁移矩阵](./V0.1到NativeV0.2迁移矩阵.md)。EntityType→ObjectType、DataProperty→Property、ObjectProperty→LinkType 必须检查身份、域、值类型、关系与主键；V0.1 DisjointClasses / equivalentClass 等推理公理没有 Native 一对一等价表达时，应报告 PARTIAL/UNSUPPORTED，而不是静默消失。

**本 PR 只签收模型定义、查询请求与执行证据的候选合同**，并通过自动测试验证结构/局部语义。正式 Runtime、真正授权、Registry 依赖解析、SourceMap、内容摘要、不可变 Release、业务写回及工业系统核验均待分阶段 PR 完成交付。建议下一步先完成 S0 后 P0-A 的创建→校验→保存→审查→发布→按版本读取闭环，不以大量抽象类代替可验收业务能力。
