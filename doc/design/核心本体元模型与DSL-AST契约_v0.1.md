# 核心本体元模型与 DSL/AST 契约设计 V0.1

> 状态：正式架构评审草案，未签收、未开始生产代码开发  
> 日期：2026-10-14  
> 上游：[本体产品功能架构与技术架构设计 V1.1](../本体产品功能架构与技术架构设计_v1.1.md)（PR #2 待合并）  
> 适用：通用 Ontology Core 的 P0 模式设计；制造业专业能力由领域本体包扩展  
> 标准边界：GB/T 48000.3—2026 是**标准数字化本体**的建模要求，不能声称本通用平台已符合该国标

## 0. 目标与机器可读交付

目标是形成可由程序解析、形式化验证、审查、编译、发布的 Schema 模型契约，而非简单类/关系 CRUD。必须让建模、语义引擎、权限、Agent Context 使用同一稳定语义身份和权威已发布版本。

本次提供以下设计制品：

- [输入 DSL JSON Schema](./schema/ontology-package-v0.1.schema.json)（JSON Schema Draft 2020-12）
- [规范化 Typed AST JSON Schema](./schema/ontology-ast-v0.1.schema.json)
- [制造质量模型包合法示例](./examples/manufacturing-quality-v0.1.json)
- [其规范化 AST 示例](./examples/manufacturing-quality-ast-v0.1.json)
- [非法 DataProperty 结构反例](./examples/manufacturing-quality-invalid-data-property-v0.1.json)
- [完整正反例及验收矩阵](./tests/DSL-AST-契约验收案例_v0.1.md)

这两份 Schema 是**本项目设计候选**，不是 GB/T 标准附录规定的官方 DSL；结构校验成功不等于本体语义/权限/安全/合规验证成功。

## 1. 核心边界与不变量

| 对象 | P0 所在边界 | 与什么不能混淆 |
|---|---|---|
| OntologyPackage | Schema Definition | 可变草稿、部署实例 |
| EntityType | 模型包的类型定义 | 真实 Entity 实例 |
| DataProperty | 数据类型属性 | ObjectProperty 或观测数值 |
| ObjectProperty | 有方向的对象关系定义 | 已成立的 RelationFact |
| Axiom | 形式化语义声明 | SHACL 必填约束或业务风控 |
| Term / Concept | 独立的业务术语与概念注册表 | 同名即同义，或与 EntityType 等同 |
| Shape / Constraint | 模型约束编译和质量服务 | 生产动作安全授权 |
| Entity / RelationFact | 实例与事实运行时 | 模型静态类型 |
| Observation / State / Event | 状态/时序/事件运行时 | 类型属性定义 |
| Rule / Capability / Action | 独立的规则、能力和行动契约 | 已授权执行、实际 Tool 实例 |
| Evidence / Suggestion | 溯源与受审候选 | 已发布权威事实 |

核心不变量：Type != Instance；Schema Release != Business Data Snapshot；ActionDefinition != Execution；DerivedFact != Observation；未经审核的 Candidate != AuthoritativeFact。

本轮核心 JSON Schema 只覆盖 EntityType、DataProperty、ObjectProperty、DisjointClasses 等最小子集。Term/Concept Registry 由 F24 管理，Action Runtime 由独立控制面管理，Schema 保留未来扩展接口，不因本轮没实现就把这些概念硬塞进某个字段。

## 2. 分层后端架构

~~~mermaid
flowchart TB
 A["Studio / API / SDK"] --> B["DSL Schema + Parser"]
 B --> C["Typed AST"]
 R["Namespace Registry"] --> D["Import + Reference Resolver"]
 L["Locked Dependency Versions"] --> D
 C --> D
 D --> E["Semantic/Compatibility Linter"]
 E --> F["RDF/OWL Emitter (Jena)"]
 E --> G["SHACL Shape Emitter"]
 F --> H["Immutable Artifact Bundle"]
 G --> H
 H --> I["Quality + Review + Approval"]
 I --> J["Published Release / Projection"]
 P["Policy Enforcement"] -.-> I
 P -.-> J
~~~

建议逻辑模块：ontology-contract、ontology-parser、namespace-registry、ontology-compiler、ontology-quality、ontology-release、ontology-graph-adapter。它们可以在 Java 21/Spring Boot 的模块化单体内实现；不是预先开发七个微服务。PostgreSQL 持有 Registry/审批/版本索引；Jena 承担标准语义操作；TDB2 等存储是**可重建 RDF 投影**，不是 Schema 真相源或高频工业数据存储。

## 3. 稳定语义身份与包合同

**三个身份不可混用**：

1. 内部 stable id：对草稿修订稳定，不从显示名称、源系统字段或位置自动产生；示例的可读 id 不代表生产须用业务可推导 ID。
2. 外部 IRI：语义资源的全球可引用标识；已发布后改变 name、labels，不应自动变更 IRI。弃用/迁移需保留引用和审批记录。
3. packageId@version：不可变发布坐标，其实际内容由 releaseDigest 与 imports 锁文件绑定。

输入 JSON DSL 的顶层为 schemaVersion、packageId、version、namespaceIri、imports、entityTypes、dataProperties、objectProperties、axioms。imports 每项需锁定 packageId、version 与 sha256 digest，不准以可变 latest 作为已发布依赖。跨包引用仅能读取被锁定且有授权的资源，不能覆盖他人命名空间；依赖图禁止循环。

示例 https://example.org/ontology/manufacturing/quality#ProductLot 仅是说明用途的占位 IRI，不能作为生产命名空间。namespace 的 owner、IRI 分配、停用、别名、跨租户访问需通过 Registry 和 Policy 管理。JSON Schema 的 format:uri 不能检查 namespace 所有权，也不能证明两个企业实体是同一真实对象。

## 4. 类型、数据属性、对象属性及公理合同

### 4.1 EntityType

必需 kind=EntityType、id、iri、name、labels、definition；可选 subClassOf 和 equivalentClass。parent 关系不能自指、形成继承环、或引用未锁定/无权访问的类型。equivalentClass 具有强语义，不允许因名称或向量相似自动确认。

GB/T 48000.3—2026 规范性附录A.1的 Properties、HasSubclass 在通用 AST 中分别由属性定义的 domain、类型 subClassOf **派生**，避免维持两个重复而可能冲突的事实源。导出标准文档 Profile 时必须能完整提供该类元数据；不能因此声称平台已通过标准符合性测试。

### 4.2 DataProperty

必需 kind=DataProperty、id、iri、name、labels、definition、domain、datatype。datatype：string、boolean、integer、decimal、date、dateTime、uri。可选 constraints：minCount、maxCount、pattern、allowedValues、minInclusive、maxInclusive、unit。

- domain 是类型 IRI；datatype 的标准导出映射为适用的 XSD 值域。
- 必填值由 Shape 的 minCount>=1 表达；maxCount=1 需要 SHACL 验证，不单靠 OWL 功能公理。
- pattern 不适用于数值类型；minInclusive/maxInclusive 需要兼容类型；minCount 不能超过 maxCount。
- 严禁 DataProperty 配置 inverseOf、symmetric、transitive 等对象关系行为。

### 4.3 ObjectProperty

必需 kind=ObjectProperty、id、iri、name、labels、definition、domain、range；可选 inverseOf、characteristics、constraints。对象关系明确起点和终点；inverseOf 必须指向方向相容的 ObjectProperty。Schema 预留 functional、inverseFunctional、symmetric、transitive；实际可执行语义取决于明确的编译/推理 Profile，不能只因字段被解析就声明具备完整推理能力。

RDFS/OWL 的 domain/range 可产生类型蕴含，并非封闭世界数据拒绝规则。是否允许某个真实对象参与关系，还要执行 Shape 与业务授权。

### 4.4 Axiom

当前机器合同只允许 DisjointClasses（至少两个互异的类 IRI）；subClassOf/equivalentClass 由 EntityType 提供。复杂 OWL 类表达式、属性链及完整 OWL2 DL 不在 P0。公理不等于业务审批或 Agent 动作许可。必须把 **OWL 推理、SHACL 校验、业务 DomainRule、ActionPolicy** 分开实现和测试。


## 5. 规范化 AST、SourceMap 和信息摘要

[AST Schema](./schema/ontology-ast-v0.1.schema.json) 的根字段为 astVersion、packageId、version、namespaceIri、resolvedImports、nodes。节点是带 kind 判别的类型化联合，明确区别 EntityType、DataProperty、ObjectProperty 和 DisjointClasses；编译器不允许通过字段的值类型临时判断关系种类。

节点顺序为 EntityType→DataProperty→ObjectProperty→Axiom，同一类节点按 IRI（无 IRI 的公理按稳定 id）UTF-8 字节序排列，不得使用可受运行环境影响的 Locale 排序。规范化 AST 要在同一输入、同一锁文件、同一版本编译策略下稳定生成。

发布制品建议计算四类摘要：

- astDigest = SHA256(canonicalJSON(normalizedAst))
- lockDigest = SHA256(canonicalJSON(resolvedImports))
- releaseDigest = SHA256(canonicalJSON({astDigest,lockDigest,compilerProfileDigest,artifactContractVersion}))
- projectionDigest = SHA256(实际 RDF/SHACL 投影的规范化构建输出)，并绑定对应 releaseDigest

上述是待 POC 的内容摘要设计，不是当前已有代码。canonicalJSON 建议选用经过跨语言验证的 JCS/RFC8785 或同等明确算法，须测试 Unicode、数字表示、键排序。用户、审查时间、随机任务 ID 不应影响逻辑模型内容 digest。SourceMap 将 JSON Pointer/semantic path 关联到源文件行列、审查意见和诊断，不混进模型的权威语义内容。

## 6. 编译器阶段、错误报告和失败策略

| 阶段 | 输入→输出 | 最低负例 |
|---|---|---|
| C01 Parse | UTF-8 JSON→原始对象 | 非法语法、重复 key、过深嵌套、资源超限 |
| C02 Schema | 原始对象→结构检查 | 缺字段、未知字段、错误类型 |
| C03 Typed AST | DSL→归一化节点与 SourceMap | 非法 kind、无法归一化 |
| C04 Namespace | AST→标识已注册 | IRI 碰撞、所属空间无权写、重复 id |
| C05 Imports | 锁定依赖→已解析符号 | 缺依赖、循环、摘要不一致 |
| C06 Semantics | 已解析 AST→内部语义 IR | 无效 domain/range、继承环、非法 inverse/约束 |
| C07 Emit | IR→OWL/RDF 与 SHACL | 不支持的公理或不可交换的输出 |
| C08 Quality | 投影+检验实例→报告 | 不相交冲突、Shape 违规、推理能力未知 |
| C09 Compatibility | 新/旧发布→影响报告 | 删除类型、改值域、收紧基数而无迁移 |
| C10 Release | 签收内容→不可变版本 | 旧哈希审批、撤权、投影激活失败 |

诊断包含 code、severity、message、modelPath、sourceLocation、relatedIris、repairHint、compilerVersion。错误代码族建议 E_PARSE、E_SCHEMA、E_IRI、E_IMPORT、E_TYPE、E_AXIOM、E_SHACL、E_COMPAT、E_AUTH、E_RELEASE。区分 UNSUPPORTED、UNKNOWN、VIOLATION，不允许把未测试的 OWL 逻辑宣布为已满足。

JSON Schema 负责结构；编译器负责引用和语义；SHACL 负责声明的闭世界实例验证；业务运行时及 Action Policy 负责业务动作风险与授权。不能把一次结构校验通过当成“可上线、符合国标、可自动行动”的证明。

## 7. 审批、发布和投影激活状态

~~~mermaid
stateDiagram-v2
 [*] --> Draft
 Draft --> Validating: validate
 Validating --> Draft: failed
 Validating --> InReview: passed
 InReview --> Draft: requestChanges
 InReview --> Approved: signedDigest
 Approved --> BuildingProjection: publish
 BuildingProjection --> Active: projectionVerified
 BuildingProjection --> FailedActivation: failure
 FailedActivation --> BuildingProjection: safeRetry
 Active --> Deprecated: retire
~~~

Approved != Active。发布到 PostgreSQL Registry 与 TDB2 等 RDF 存储不是天然同一笔事务；可以通过 Outbox、幂等投影任务和激活记录确保 Agent 只读取已验证 Active 的发布版本。

批准结果应绑定 ChangeSet、规范化 AST、依赖锁、质量报告及预期 releaseDigest，修改任一内容使旧批准失效。禁止通过 REST、SDK 或 MCP 绕过 Review/Quality Gate；撤权在关键步骤重新校验。发布失败不得把部分写入的图投影作为当前有效版本。Schema 回滚不能回滚 ERP/QMS 中已发生的工单、订单操作。

## 8. 兼容性与四层包扩展

| 变更 | 兼容性策略 | 验收要求 |
|---|---|---|
| 追加可读 label/非语义备注 | 一般可兼容 | 仍留变更审计 |
| 新增可选属性/独立类型 | 通常可兼容 | 引用、权限、查询复验 |
| 改变 IRI 已有语义定义 | 高风险 | 禁止静默重定义；考虑新 IRI/迁移 |
| 删除属性、类型、关系 | 破坏性 | 消费者影响、退役和迁移报告 |
| 收紧基数、枚举、数值范围 | 可能破坏性 | 用真实实例和黄金查询回归 |
| 调整 domain/range/inverse/disjoint | 改变推理语义 | 重新执行 OWL/SHACL/兼容检查 |
| 改变 Action / Capability 契约 | 独立安全变更 | 不能借 Schema 更新自动扩大执行权限 |

四层包：通用核心 → 制造基础本体 → 质量/BOM/供应链领域本体 → 企业扩展本体。扩展不能直接修改所依赖包的正式核心定义，必须使用自有命名空间与依赖锁；违反约束或隐藏式覆写需阻断发布。Package 版本号并不替代实际语义 diff 和实例兼容检查。

GB/T 48000.3 第9章提供模块化扩展原则的参考。该标准特有的 Standard、Clause、InformationUnit、InformationForm、StandardizationProcess 应属于独立可选 Standard Document Ontology Profile，不应写死到制造业企业通用内核。

## 9. 与实例、Agent 及受控行动的接缝

生产 Query/Context 只能消费有授权的 Active release，并明确返回 Schema release、mapping version、facts/evidence、时间和授权范围。草稿类型、未审 AlignmentCandidate、FactCandidate 不得自动进入生产 Agent Context。

P0 DSL 不直接包含实体实例或源表物理绑定，也不包含可执行工具实现。若 QualityBatch 可以关联 CustomerOrder，**仅表示关系类型合法，不代表真实批次影响了某个订单**。实际关系须由已审来源映射及有证据的 RelationFact 提供。ActionDefinition 同样不能因为类型定义存在就成为可执行 Tool。

标准第3部分“行动”实体描述标准条款规定的检验步骤；本产品 Action Runtime 的 ToolBinding、RiskPolicy、Approval、Idempotency、Execution、Verification 是另外一套受控合同。
