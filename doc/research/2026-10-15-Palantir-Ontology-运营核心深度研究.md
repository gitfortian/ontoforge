# Palantir Ontology 运营核心深度研究：Language → Data Binding → ObjectSet → Function → Action

> **研究时间**：2026-10-15  
> **研究目的**：为 ontology-system 的正式架构冻结提供证据与可验证决策，不是 Palantir 产品说明书或复刻路线图。  
> **研究基线**：[本体产品功能架构与技术架构 V1.2](../本体产品功能架构与技术架构设计_v1.2.md)（已进入 main）、已有 DSL/AST V0.1（历史契约）。  
> **主要一手资料**：Palantir Foundry 官方 Ontology、Object Backend、Indexing、Functions、Action Types、Permissions、Branching 文档，详见末尾证据索引。  
> **阅读纪律**：下文每个关键结论标注「**官方可证实**」「**架构推断**」「**自主建议/待 POC**」。Palantir 对外服务架构不等于其未公开的源代码、数据库实现、算法与保证。  
> **范围限制**：这是一轮公开材料的架构/工程研究；没有运行 Palantir 租户实例、生产数据或性能测试，所有性能阈值需我们自己验证。  
> **状态**：架构研究交付稿，尚未批准新工程代码或变更 V1.2 冻结决策。

## 0. 执行结论：学体系，不学目录名

**官方可证实**：Palantir 表述 Ontology 为数据、逻辑、行动与安全共同构成的系统，而不是独立的薄语义层；概念上按 Language、Engine、Toolchain 三类能力组织；其 Ontology Backend 则公开列出 Ontology Metadata Service、Object Databases、Object Set Service、Actions、Object Data Funnel、Functions on Objects 等组件 [P01][P02]。

**我们的架构推断**：产品最重要的不是某一种图表示，而是能够维持以下连续能力：
- **Language**：明确企业有什么对象、关系、行为、约束以及可暴露的能力；
- **Data Binding / Identity**：真实 ERP/MES/QMS 数据如何确认为哪类对象、哪条关系；
- **Object Runtime / ObjectSet**：业务和 Agent 能否在授权与时态限定下做搜索、关联和聚合；
- **Function**：业务计算能否组合可复用对象/对象集合，明确计算版本、依赖和副作用意图；
- **Action**：操作能否在独立授权与校验下修改本体对象或外部系统，形成执行状态和可审计结果；
- **Toolchain**：Studio、SDK、质量诊断、测试、分支、版本/审核与开发体验，使上述能力真的可交付。

**对 V1.2 的总体判断**：坚持 Native Ontology 作为权威、OWL 按需互操作、模块化单体起步；但补强「对象修改覆盖层/事实所有权」「Lazy ObjectSet 契约」「Function 编辑意图模型」「授权传播和数据派生」「多源属性完整性」「索引版本与快照」「场景分支/模拟」「动作预演与执行状态」等工程不变量。不要先把这些全部开发成微服务。

## 1. 证据分层：Palantir 到底对外承诺了什么？

| 研究对象 | 官方可证实的事实 | 不应据此推断的事情 |
|---|---|---|
| Language / Engine / Toolchain | 是概念性系统组织；覆盖对象、动作、逻辑、实时查询、写入与管理 [P01] | 三个独立源码仓库或固定的微服务数量 |
| OMS | 定义 ObjectType、LinkType、ActionType 等元数据 [P02] | OMS 使用某种特定 SQL/图数据库 |
| Object Databases | 负责对象存储、索引、查询及用户编辑协同；v2 与 v1 有重大架构变化 [P02][P03] | 所有数据都实时一致、无限索引、没有容量或查询限制 |
| Object Set Service | 搜索、加载、过滤、聚合等读能力，应用共享 [P02] | ObjectSet 等于普通内存对象列表、每个查询都有精确聚合 |
| Object Data Funnel | 读取 Datasource 与 Action 编辑并向对象数据库索引，维护更新 [P02][P04] | 一定使用某个开源 CDC 引擎或部署拓扑 |
| Action | 应用用户对象编辑，提供条件/权限/历史数据 [P02][P09] | 可以无视外部系统事务失败，或调用权一定由 OWL 推导 |
| Function | 服务端受隔离运行，支持 TS/Python、对象读/关系遍历与本体编辑 [P10][P11] | 所有 Function 都只能读、或者都能随意产生副作用 |
| Ontology Edit/Branching | 模型更改有分支、提案、合并与安全隔离机制 [P18] | 分支保证所有支持之外的外部系统写入也绝不产生副作用 |

## 2. Language：语义类型是业务能力的公共合同

### 2.1 ObjectType 与 Object 不能混淆

**官方可证实**：[P05] 将 ObjectType 定义为真实业务实体/事件的模式，属性有类型；Object 是其中一个实例。创建对象类型时包含元数据、Backing Datasource、属性映射、Primary Key/Title Key；也支持创建暂不连接数据源的对象类型，随后才绑定源 [P06]。

**架构推断**：Schema 的发布时间不必与实例数据接入同步。对象类型的稳定 ID/API Name、标题属性、主键合同与数据源、实例索引，应有不同的治理职责。不能因为业务源的字段改名就直接改变公开 ObjectType API。

**高风险细节**：Palantir 文档提到编辑持久绑定到对象 Primary Key；变更主键可能触发清除既有编辑的交互警告 [P07]。因此我们必须把业务主键变更视为需显式迁移和重新索引的高风险操作，不能当作简单字段 rename。

### 2.2 LinkType vs LinkFact 与关联来源

**官方可证实**：LinkType 是两个 ObjectType 之间关系的 Schema；Link 是两个具体对象间的事实。Palantir 公开支持外键驱动、join-table 驱动以及对象承载的 Link；后者可以保存关联自身的业务属性 [P08]。

**决定性细节**：[P08] 的一对一链接被称为**预期的基数指示**，但该基数并不自动强制执行。**定义链接关系 ≠ 真实关联数据正确。**

**自主建议**：将 LinkType Schema、LinkBinding、LinkFact、LinkQualityReport 分开。属性/来源/时间证据复杂的关联可用有身份的关联对象表达；生产质量追溯必须对孤儿关联、重复关联、错误外键和时态冲突执行校验。

### 2.3 Interface 与状态

**官方可证实**：Palantir Interface 为多个 ObjectType 提供共享形状和多态契约 [P05]，Action 也可以面向 Interface 提供通用操作，但公开文档说明其支持范围/权限边界存在限制 [P16]。

**自主建议**：InterfaceType 应当是长期 Native 元模型的一等概念，P0 可先声明合同再实现；不要为了“支持 OWL 等价类”把不同对象的运行时能力默认等价。状态、事件、动作也不能强行都当成普通数据属性。

## 3. Data Binding：不是一次字段映射，而是实例构建与持续维护

### 3.1 从来源到对象索引

**官方可证实**：[P06] 的对象类型配置允许先选择数据源后自动初始化模型属性映射，之后可调整源、属性和主键；关系绑定则可能由外键或关联表形成 [P08]。[P04] 描述 Funnel 提供批处理与流式索引；[P19] 说明新版对象索引默认增量处理，源 Schema 变化时可在后台切换替换流水线。

**我们的实现路线**：接入 Connection、SourceResource、BindingDefinition、IdentityRule、IndexBuild、ObjectSnapshot。Binding 应独立版本化，并携带其 SourceSchemaVersion、SchemaRelease、完整性范围、刷新策略和依赖锁。不要把 SQL 字段名写进 ObjectType 权威定义本身，也不要让异步索引失败直接改变已发布 Schema 状态。

### 3.2 多源融合 ≠ 自由合并

**官方可证实**：Object Storage v2 支持 Multi-Datasource Object Type，公开文档描述 Column-wise MDO 可将不同数据源的属性子集汇聚到同一对象；同时明确 Streaming 不能用作 MDO 来源、当前不支持 Row-wise MDO，需谨慎区分“概念上存在”与“产品已经支持” [P20]。

**重要实践警告**：[P21] 的 Required Property 校验在索引/Action 上有作用，但多数据源对象中若缺少某一数据源记录，最终对象仍可能出现该必填属性缺值的情况。这说明**跨源对象完整性不能单靠单个 Schema required=true 推断**。

**自主建议**：每个对象/属性提供来源与覆盖信息：
- 哪个 source 是该属性的 authoritativeSource；
- 不同来源是否属于同一实体身份（基于稳定 Key 和审核后的 IdentityRule）；
- 数据是否已索引、是否过期、是否存在冲突/缺口；
- 查询策略是否允许采用替代来源或返回部分结果。

不得因为用户没有某源访问权或索引尚未完成就把“未查到”解释为“不存在”。

### 3.3 漂移、乱序和索引版本

**官方可证实**：Funnel 支持批处理增量差分 [P19]；低延迟 streaming 索引支持秒/分钟级的运营数据更新，但文档明确提示乱序事件在“最新更新覆盖”语义下可能导致错误结果 [P22]。此外 OSv1→OSv2 的重大变更证明底层对象存储能力和 API 会演进 [P03]。

**自主建议**：IndexBuild 应持有 sourceCursor、watermark、effectiveAt、schemaReleaseId、bindingVersion、buildGeneration、activeAlias、errorState。数据源乱序需要具体版本/时间戳/序号策略，不能盲目依赖最后到达者胜出。生产查询要能报告对象快照的实际新鲜度及部分源失败。



## 4. ObjectSet：我们最需要吃透的查询抽象

### 4.1 ObjectSet 不应该是内存数组

**官方可证实**：Palantir Function Types Reference 明确区分把对象数组作为函数参数，与传递 ObjectSet。数组必须先加载实例；ObjectSet 可在实际加载前执行 filter、search-around/遍历和 aggregate，因而可提高规模与性能；官方还建议优先用 ObjectSet 传递大量对象 [P11]。ObjectSet Service 负责搜索、过滤、聚合、加载，聚合在高基数场景可能不精确，可请求偏好精度并通过响应字段获知实际准确性 [P02][P12]。

**架构推断**：ObjectSet 最适合定义为**类型化、可组合、延迟求值的逻辑查询计划或不变查询句柄**，而不是普通 Java List，也不一定每次都物化一组主键。是否保存可复用查询定义、是否保存查询结果快照和何时加载实体，应是三个独立 API 行为。

### 4.2 推荐 Native Query AST

| 节点 | 输入输出 | 必须审查的约束 |
|---|---|---|
| RootObjectSet | ObjectTypeRef + SchemaRelease | 类型可见性、空间范围、数据准备状态 |
| Filter | Typed Predicate + ObjectSet | 字段的 searchable/indexable 标志、比较值类型、字段权限 |
| SearchAround / Traverse | ObjectSet + LinkType + direction + depth | link 来源、关系权限、路径数量及预算 |
| Union / Intersect / Exclude | 多个同型/接口兼容集合 | 多源范围、版本兼容、差集与存在性侧信道 |
| Project | 属性选择/衍生列 | 属性/对象权限、已加载数据、时间语义 |
| Aggregate | Count/Sum/Group/Average/Histogram | 准确/近似/部分结果标识、小群体隐私 |
| Materialize | 明确的 Snapshot/Export 执行 | 物化成本、数据权限传播和撤权 |
| Page / Cursor | 排序、页、查询快照/一致性 token | 结果重排、过期游标、权限快照不能复用 |
| Explain | QueryPlan、索引、来源、时态与成本 | 避免解释接口泄露源表名/敏感过滤值 |

**官方给出的两个反面启示**：一是 Function 的 object parameter 比 ObjectSet 参数更容易导致频繁的对象加载 [P23]；二是 TypeScript v2 中针对未标记 Searchable 的字段的过滤可能通过类型检查，却在运行时返回空结果，因此**对象类型定义的可搜索能力必须由编译/查询计划层真实校验**，不能只由 SDK 语法接受 [P24]。

### 4.3 执行计划边界

**自主建议**：将 Query Definition/AST 与 Query Execution/Physical Plan 分开：
- 对外保持统一 ObjectSet API，具体执行可选择 SQL、搜索引擎、图、时序或联邦来源；
- 增加 LIMIT、最大遍历深度、请求/结果字节数、COST_BUDGET、TIMEOUT 与 CANCELLATION；
- 返回 schemaReleaseId、bindingVersion、sourceCheckpoint、indexSnapshot、observedAt、freshness、accuracy、completeness 与 authorizationContextRef；
- “部分返回”不得伪装成完整成功；准确性、范围完整性、实体存在性分别声明；
- **不可为了性能在中间结果先做未授权聚合，再最后过滤**；禁止通过 count/exists/异常/耗时泄露无权对象。

### 4.4 官方容量说明只是能力证据，不是我们的 SLA

[P02] 文档公开描述 OSv2 支持十亿级以上乃至单类型数百亿级索引吞吐场景、单 Action 最多编辑 10,000 对象、默认 SearchAround 上限 100,000 对象、单类型属性上限 2,000 等说明。具体功能可能依赖租户配置、场景和版本；**不能把这些数字抄成 ontology-system 的 P0 性能指标**。我们应按设备/批次的真实数据量分别测试基数、关系扇出、过滤/聚合、增量索引、授权开销与批量编辑。

## 5. Function：计算行为必须建模，但不要错置权限

### 5.1 Function 不是纯粹的只读公式

**官方可证实**：Functions 能在服务端隔离环境运行，支持对本体对象和关系进行读取、计算以及 Ontology edits；TS/Python 是官方支持的函数语言 [P10]。Function-backed Action 支持跨对象、跨关系的复杂批量编辑逻辑 [P13]。

这纠正了一个容易偏离 Palantir 的误解：Function 并不总是 ReadOnly。但“函数具备编辑逻辑”与“任何用户可以直接执行编辑”是两个不同问题。

**自主建议**：在 Native 定义中明确区分：
- **QueryFunction**：只读或无外部副作用，允许 ObjectSet 计算并返回可解释值；
- **OntologyEditFunction**：返回一份具有类型、目标和预期效果的 ChangeSet / EditPlan（可以声明拟议修改），但其生产提交由 ActionRuntime 授权并执行；
- **ExternalEffectFunction**：外部服务读取可作为受限适配器；有外部写入的逻辑不得在普通函数执行路径隐式生效，应升级为 Action 受控执行器。

**注意**：这是一项我们自己的安全体系设计，不声称 Palantir 内部也是这种统一的 EditPlan 实现。

### 5.2 Function 版本升级可绕过原本的 Action 审查意图

**官方可证实**：[P14] 说明 Function-backed Action 可配置自动升级到兼容 Function 版本，但警告：Function 编辑权限与 Action 编辑权限并非完全绑定，自动升级可能使无法直接编辑 Action 的用户改变其行为。Palantir 也记录了 provenance 的某些约束与检查。

**这是本轮最高优先级的架构研究发现之一**。

**自主建议**：
- ActionVersion 永远固定 functionReleaseDigest/implementationDigest；不允许生产自动漂移至 latest；
- 任何可改变 Action 目标对象、外部副作用、读取范围或风险等级的 Function 更新，必须重新执行 Action 影响分析和审批；
- EditPlan 的实际目标范围不能超过签收时的 declaredEffectScope；
- 无论 Function 源码是否变化，调用者、批准者、执行者三种身份和权限需独立保留；
- Function 可计算有副作用的**计划**，不能因此取得执行 Token；Action 控制最终提交、幂等/审批和审计。

### 5.3 函数编排的规模问题

**官方可证实**：Palantir 建议优先传递可查询的 ObjectSet 而不是大量内存对象 [P23]。当对象规模增加时，若我们允许每个 Object 调用一次外部 Function、再联邦查两次源，容易出现 N+1 查询和超时雪崩。

**自主建议**：FunctionDefinition 标注 batchCapability、inputSetCompatibility、readScope、declaredEffects、timeout、memoryLimit、networkEgressPolicy、versionLock；必要时由 QueryRuntime 推送聚合，避免把海量对象加载至 JVM/脚本沙箱。

## 6. Action：提交运营决策，而不只是改一行数据

### 6.1 Action 是对对象和链接的受控变更入口

**官方可证实**：ActionType 定义一组可应用于对象、属性和链接的修改；包含参数、规则、提交条件和副作用 [P09]。简单规则可以创建/删除对象、修改属性、创建/删除链接；复杂场景使用 Function-backed Action [P13]。Side Effects 支持通知和 Webhooks，可调用外部 REST API 或 ERP 系统 [P15]。

**重要的落地启发**：在真实工业现场，Action 可能同时影响本体中的“运营决策视图”和外部 ERP/QMS 中的业务状态。因此需要至少区分：
- 计算/校验完成（计划有效）；
- 平台内对象编辑已提交；
- 外部系统动作已被接受（可能异步）；
- 外部系统业务事实已确认达到预期；
- 索引/物化视图已捕获后续变化。

这些状态不能合并成一个 success 布尔值。一个发送 Webhook 成功的网络响应，不等于业务冻结真实生效。

### 6.2 提交条件和预演

**官方可证实**：[P25] 通过 Action submission criteria 限制哪些对象状态允许提交；[P26] 的 Test Run 返回拟议对象/链接变动、执行路径和副作用预览，**不提交对象编辑且跳过提交后的真实 Side Effects**。

**自主建议**：我们的 Action 在生产执行前提供 Preview/Explain/EditPlan，校验目标/参数/政策/证据/对象快照，返回 ProposedEdits 和 ExternalEffectsPreview。生产提交必须对目标对象状态及授权**重新检查**，不可把数分钟前的预演结果当成持续有效的授权凭证。预演和正式操作有不同的资源限额与审计。

### 6.3 操作审计不能只保存成功记录

**官方可证实**：[P27] 明确 Action Log 记录的是成功提交，失败由 Action Metrics 跟踪；来自 Action 之外的编辑不一定进入 Action Log。可见不同日志/历史来源不能互相替代。

**自主建议**：建立统一不可篡改的 ActionAttempt、Decision、Approval、Dispatch、Receipt、Reconciliation、AuditEvent，至少覆盖 Proposed、Rejected、Denied、Pending、Running、Unknown、Succeeded、Failed；外部执行失败、被拒绝与未获授权也要保存最小必要诊断（避免泄露敏感输入）。不要认为“成功 ActionLog”可以代替审计闭环。

### 6.4 权限不能让所有写入都落到无边界服务账号

**官方可证实**：[P28] 明确 Action 应用权限包括看见 ActionType、编辑 ActionType、以给定参数应用 ActionType 等层次；它还涉及对象类型、关联类型、源及提交流程的权限。[P29] 的 Read/Write Authorization（Beta）作为已有权限和提交条件的补充，控制行动期间的读边界与写入最低安全级别，而不是赋予新权限。

**自主建议**：Action 的调用身份、输入阅读身份、目标编辑权限、外部执行身份和审批权限明确区分，使用 scope-bound credentials；在 SDK/Agent/MCP、后台批处理及函数入口均应用同样的 Policy Enforcement。对 Action 依赖的资源版本和权限做启动时及执行前校验；撤权后不能通过已缓存 Action 计划或后台任务继续越权提交。

