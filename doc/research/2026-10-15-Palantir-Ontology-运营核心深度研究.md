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

