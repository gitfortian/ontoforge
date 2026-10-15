# DSL/AST 契约 V0.1：正反例与验收案例

> 设计阶段测试基线；除已确认的 JSON 文件解析及字段结构静态核对外，以下绝大多数案例均为 **Planned（未在生产编译器执行）**。不能将本文误解为 CI 已全绿或代码功能已交付。  
> 上游：[核心本体元模型与 DSL/AST 契约 V0.1](../核心本体元模型与DSL-AST契约_v0.1.md)

## 1. 文件与预期

| ID | 输入 | 预期结果 | 状态 |
|---|---|---|---|
| F-001 | [合法质量模型 JSON](../examples/manufacturing-quality-v0.1.json) | DSL Schema 结构可接受；语义编译待实现 | Structural review only |
| F-002 | [预期 AST JSON](../examples/manufacturing-quality-ast-v0.1.json) | AST 节点形态合法且顺序确定 | Structural review only |
| F-003 | [DataProperty 带 inverseOf](../examples/manufacturing-quality-invalid-data-property-v0.1.json) | 输入 DSL Schema 必须拒绝未知属性 inverseOf | Structural review only |
| F-004 | 任何仅标签更名而保持 id/iri 的新草稿 | 语义 ID 与公开 IRI 不得自动变更 | Planned |

说明：上述两个 JSON Schema 是独立的文件，可使用支持 Draft 2020-12 的 JSON Schema Validator 验证。Schema 负责字段形状，不可单独断言引用正确、推理一致、SHACL 实例符合及权限安全。

## 2. 输入 Schema 与 Typed AST Gate

| Case | 测试输入/操作 | Expected | Gate |
|---|---|---|---|
| S-001 | 合法示例文件 | 类型、数据属性、对象属性、DisjointClasses 在 AST 中均保留 | PASS |
| S-002 | 缺 schemaVersion/packageId 或 IRI | 必需字段拒绝，输出 modelPath | BLOCK |
| S-003 | 非法 kind/额外属性 | 不进行宽容忽略，拒绝构建 AST | BLOCK |
| S-004 | DataProperty 中出现 inverseOf | JSON Schema 拒绝 | BLOCK |
| S-005 | 同一文件中不同类型拥有相同 IRI | IRIPolicy 报冲突，不只靠 Schema | BLOCK |
| S-006 | 两个节点具有重复内部 id | AST Registry/Linter 报冲突 | BLOCK |
| S-007 | namespaceIri 不属于当前包所有者 | 服务端 Registry 拒绝 | BLOCK |
| S-008 | name/label 更新但 id/iri 保持 | 变更可追踪，不产生新类型身份 | PASS |
| S-009 | imports 的锁定摘要与已注册包内容不同 | 拒绝引用，不透明自动更新依赖 | BLOCK |
| S-010 | 包依赖形成闭环 | 输出依赖链并阻断构建 | BLOCK |
| S-011 | 数据属性 pattern 附着整数类型 | 静态类型检查拒绝 | BLOCK |
| S-012 | minCount 大于 maxCount | 约束静态分析拒绝 | BLOCK |
| S-013 | 不存在的 Domain/Range 目标 | 解析失败并指出引用位置 | BLOCK |
| S-014 | 类继承环或自继承 | 类型 Linter 拒绝 | BLOCK |
| S-015 | ObjectProperty 的 inverseOf 引用 DataProperty | 类型 Linter 拒绝 | BLOCK |

## 3. RDF/OWL、SHACL、推理和国标适配 Gate

| Case | 场景 | Expected | Gate |
|---|---|---|---|
| M-001 | 同一合法 DSL 两次编译 | AST 摘要稳定；RDF/SHACL 语义一致 | PASS |
| M-002 | Turtle 导出、导入、回读 | 元信息、类型、关系和约束未丢失 | PASS |
| M-003 | JSON-LD 序列化往返 | 标准 Profile 中可支持的语义不丢失 | PASS |
| M-004 | 缺少 lotNumber 且 Shape minCount=1 | SHACL 返回定位到实体/属性的质量报告 | BLOCK |
| M-005 | qualityStatus 输入未允许枚举值 | SHACL 返回违规 | BLOCK |
| M-006 | 一实体被断言为 ProductLot 和 CustomerOrder | DisjointClasses 检查发现冲突或推理不一致 | BLOCK |
| M-007 | 使用不被当前 Jena Profile 支持的复杂公理 | 明确 UNSUPPORTED，不能“未报错=正确” | BLOCK |
| M-008 | OWL domain/range 可以推断额外类型 | 不将该推断伪装为已通过封闭世界值域验证 | CHECK |
| M-009 | 标准领域 Profile 导出类型附录A八项描述字段 | 所有适用元数据项可追踪、验证 | FUTURE |
| M-010 | 标准领域 Profile 导出属性附录A七项描述字段 | Domain/Range/TypeOfTerms 映射正确 | FUTURE |

## 4. 发布、兼容与权限 Gate

| Case | 场景 | Expected | Gate |
|---|---|---|---|
| R-001 | 质量 Gate 失败后提交审查 | 拒绝进入 Approved | BLOCK |
| R-002 | 批准后修改定义、约束或导入锁 | 原批准 digest 失效，必须重新审核 | BLOCK |
| R-003 | Registry 已创建发布条目、RDF 投影构建失败 | 不进入 Active；允许幂等安全重试 | BLOCK |
| R-004 | 重放完全相同的投影事件 | 不重复激活不同版本，不破坏现有索引 | PASS |
| R-005 | 删除类型或收紧已投产属性枚举 | 影响报告包含消费者/实例/查询，需迁移审查 | BLOCK |
| R-006 | 未授权用户调用 compile/publish 端点 | 后端拒绝，不能只靠 UI 禁用 | BLOCK |
| R-007 | 用户权限撤销后访问 Graph/SPARQL/MCP | 权限即时重新评估；不能通过缓存/关系扩展泄露 | BLOCK |
| R-008 | 查询引用 Draft 模型版本 | 生产上下文拒绝或只返回 Active release | BLOCK |
| R-009 | Agent 上报新的对齐候选 | 仅进入候选审查，不写权威图 | PASS |
| R-010 | 回滚模型版本但此前已经创建工单 | 保留外部业务事实；不得声明工单已自动撤销 | PASS |

## 5. P0 实施顺序与可复现证据

P0-A：JSON Schema + AST + Registry；证据是结构用例、正反 fixture、IRI/导入约束与重复构建测试。

P0-B：Jena + RDF/OWL + SHACL + Quality Report；证据是 Turtle/JSON-LD 往返、Shape 违规报告、DisjointClasses 冲突与 UNSUPPORTED 测试。

P0-C：ChangeSet/Review/Release + 激活；证据是审批摘要、投影幂等、撤权和回滚用例。

P0-D：制造业跨系统场景；使用 ProductLot/CustomerOrder 元模型验证业务语义链条；真实影响分析的事实证据由 F07/F08 提供，不能凭类型存在自动捏造关系。

**自动化实现建议**：将合法/非法 fixture 作为 CI 固定资产；所有失败案例断言结构化错误码、模型路径和修复建议，不能只断言“抛出了异常”。性能阈值与推理准确率应由实测 POC 确认，不能照抄研究资料中的演示数字。
