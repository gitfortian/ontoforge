# 本体产品设计文档

本目录保存 `ontology-system` 的正式产品和技术架构设计文档。架构研究及待评审决策以 GitHub Issue 管理。

| 文档 | 版本 | 状态 |
| --- | --- | --- |
| [本体产品功能架构与技术架构设计 V1.0](./本体产品功能架构与技术架构设计_v1.0.md) | V1.0 · 2026-10-14 | 保留历史基线 |
| [本体产品功能架构与技术架构设计 V1.1](./本体产品功能架构与技术架构设计_v1.1.md) | V1.1 · 2026-10-14 | 待评审：语义工程 + GB/T 48000.3 核心建模契约 |
| [本体产品功能架构与技术架构设计 V1.2](./本体产品功能架构与技术架构设计_v1.2.md) | V1.2 · 2026-10-15 | **当前架构评审候选**：Palantir 对标，Native 核心 + 可选 OWL，ADR-25–32 |
| [Palantir Ontology 运营核心深度研究](./research/2026-10-15-Palantir-Ontology-运营核心深度研究.md) | 2026-10-15 | **官方证据 + 架构推断 + POC Gate**：Language、Binding、ObjectSet、Function、Action、OSv1/v2、安全传播 |
| [POC-Query / POC-Edit 第二轮验证报告](./research/2026-10-15-POC-Query-Edit-第二轮验证报告.md) | 2026-10-15 | **24 项实际通过；PostgreSQL 脚本尚未实测**：ObjectSet 多跳、独立 HTTP Outbox、崩溃恢复与选型门禁 |
| [ObjectSet / Action 最后一轮架构选型收口与冻结提案](./research/2026-10-15-ObjectSet-Action-架构选型收口与冻结提案.md) | 2026-10-15 | **30/30×2 真实 PG17 测试**：80万/600万高扇出 Link、RLS 实测、Claim fencing、架构冻结/待验门禁 |
| [PostgreSQL 17 ObjectSet / Outbox 真实运行验证](./research/2026-10-15-POC-PostgreSQL17-ObjectSet-Outbox-真实运行验收.md) | 2026-10-15 | **实测证据**：PG17.11，2万/10万批次、各14项测试通过，EXPLAIN、RLS、事务 Outbox 与崩溃恢复 |
| [OMBOK、GB/T 48000.4 研究与差距分析](./research/2026-10-14-OMBOK-GBT48000.4-蒸馏与差距分析.md) | 2026-10-14 | 原文边界、采纳思想及新模块映射 |
| [GB/T 48000.3 本体建模要求差距矩阵](./research/2026-10-14-GBT48000.3-本体建模要求差距矩阵.md) | 2026-10-14 | §5–9、规范性附录 A、ADR-20–24 与 P0 Gate |
| [Native DSL/AST 核心契约 V0.2](./design/核心本体元模型与Native-DSL-AST契约_v0.2.md) | V0.2 · 2026-10-15 | **架构评审候选**：Native Schema、Typed AST、Binding、ObjectSet Query/Result、Decision/Action 执行证据；可运行正反例 |
| [核心本体元模型与 DSL/AST 契约 V0.1](./design/核心本体元模型与DSL-AST契约_v0.1.md) | V0.1 · 2026-10-14 | **历史技术合同**：原 OWL-first 倾向，Native V0.2 将另行修订并完成迁移评审 |

## 深度研究路线

- Palantir 的 Language→Binding→ObjectSet→Function→Action 是主要商业产品对标；研究文档明确区分官方公开事实与本项目推断，不将产品能力当作实现与性能已验证。
- 原生本体 V1.2 仍是产品路线；关键 ObjectSet、编辑写回与授权机制须以 POC 取得工程证据之后再冻结底层实现。

## 产品定位

**通用本体核心 + 制造业及产业生态专业语义体系 + Agent 上下文与受控行动能力。**

底层引擎不绑定行业，行业特色通过基础本体、领域本体包、企业扩展和 Agent 能力包体现。平台连接现有 ERP、MES、PLM、QMS、WMS、EAM 等系统，不替代这些业务系统。

## 研究与评审

- [Issue #1：Apache Jena / BKN Foundry 蒸馏与架构研究](https://github.com/gitfortian/ontology-system/issues/1)
- V1.0/V1.1 保留为演进历史；V1.2 将企业原生本体（Object/Link/ObjectSet/Function/Action）确立为架构候选主线，OWL 作为按需互操作。尚待评审，不代表研发授权。
- **核心本体元模型与 DSL/AST 契约 V0.1 已形成独立评审稿**，配套 JSON Schema、制造质量示例、AST 与正反例；本次仅提交 doc/，尚未实现生产编译器。

## 文档管理约定

- 已发布版本不直接覆盖；重大修改应产生新版本并记录变更。
- 评审中的架构决策需标明状态、权衡、验证方式和签收条件。
- 文档中使用 Mermaid 维护架构图，以便 GitHub 在线渲染和代码审查。
