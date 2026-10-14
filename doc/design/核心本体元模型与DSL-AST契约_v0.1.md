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
