# OntoForge · 砺元｜本体产品设计与架构文档

**产品品牌：OntoForge（砺元）；工程 Maven / Java / 前端命名统一使用 ontoforge。**

**唯一现行总体架构入口：[本体产品总体功能与技术架构设计 V1.3](./本体产品总体功能与技术架构设计_v1.3.md)**

> 状态：**正式统一架构基线候选，待产品、技术、安全联合评审签收**。代码/运行时与生产部署尚未完成；实验通过不代表生产能力已经签收。
>
> 设计方向：Enterprise-native Operational Ontology，连接 Object/Link、Data Binding、ObjectSet、Rule/Function、Decision、Policy、Action 与可追溯证据。**推理结论不等于行动授权**。

## 当前有效文件

| 文档/合同 | 权威职责与状态 |
|---|---|
| **[总体功能与技术架构 V1.3](./本体产品总体功能与技术架构设计_v1.3.md)** | **唯一现行总体设计基线候选**：定位、F01–F29、限界上下文、PG17 证据、统一 ADR、路线与签收 Gate |
| [Native DSL/AST V0.2](./design/核心本体元模型与Native-DSL-AST契约_v0.2.md) | 配套**机器可读技术合同**：五份 [V0.2 Package JSON Schema](./design/schema/ontology-package-v0.2.schema.json)、Typed AST、制造质量样例、[契约测试](./design/tests/test_native_v02.py) |
| [V0.1 → Native V0.2 迁移矩阵](./design/V0.1到NativeV0.2迁移矩阵.md) | 历史 IRI、公理与 EntityType 的显式兼容/拒绝约束，不是第二份总体架构 |

## 证据与参考研究（非第二套架构权威）

| 材料 | 用途 |
|---|---|
| [Palantir Ontology 官方材料深度研究](./research/2026-10-15-Palantir-Ontology-运营核心深度研究.md) | 公开产品事实、抽象关系与设计边界；不代表知晓厂商私有实现 |
| [GB/T 48000.3 专项差距矩阵](./research/2026-10-14-GBT48000.3-本体建模要求差距矩阵.md) | 标准数字化专业建模 Profile 与通用企业本体的适用范围 |
| [OMBOK / GB/T 48000.4 研究](./research/2026-10-14-OMBOK-GBT48000.4-蒸馏与差距分析.md) | 治理、语义共识及质量的研究证据 |
| [真实 PostgreSQL/Outbox POC](../experiments/pg-runtime/README.md) | 可运行的代码、PostgreSQL 17 服务端实测、授权/竞态负例；与 [PR #8](https://github.com/gitfortian/ontology-system/pull/8)、[PR #9](https://github.com/gitfortian/ontology-system/pull/9) 原始 CI 互相核验 |

## 重大设计提案与研发治理

| 文件 | 状态与用途 |
| --- | --- |
| [OFIP-0001：需求提案、文档治理与 CI 证据门禁](./proposals/OFIP-0001-需求提案文档治理与CI证据门禁.md) | **Accepted / 分阶段实施**：重大设计审批、单一文档权威、PR 证据；当前实施开发规范/模板，可信 CI 门禁将在独立 PR 实施，尚未完成 |
| [OFIP-0002：Native ObjectType 草稿建模 MVP](./proposals/OFIP-0002-ObjectType-草稿建模MVP.md) | **Accepted / Code Merged（[PR #7](https://github.com/gitfortian/ontoforge/pull/7)）/ Windows 验收 NOT RUN / Not Released**：领域/技术/交互已评审，真实 PostgreSQL、Java API、React 与 Chromium CI 已通过；[Windows 验收记录](./implementation/S0-MVP-0.2-Windows-验收.md) 待实际执行：提供[高保真原型](./prototypes/ofip-0002-objecttype-highfi.html)及[低保真基线](./prototypes/ofip-0002-objecttype-lowfi.html)，[31 项真实 Chromium 评审均通过](https://github.com/gitfortian/ontoforge/pull/6#issuecomment-6192752743)；上述设计原型仍为内存模拟，但 PR #7 已另外提交真实 API/PG/React 实现，已合并，但 Windows 实测尚未完成，不得称为已发布 |
| [OFIP-0003：S0-MVP-0.3 模型快照、结构差异与发布边界](./proposals/OFIP-0003-S0-MVP-0.3-模型快照与发布边界.md) | **Discussion / Decision Pending / Implementation Not Started**：第一轮 Domain-Gate 推荐 DG1–DG9，限定不可变本地 SchemaSnapshot、稳定内容摘要、快照比较；尚无认证/工作空间，正式 Review/Release/Activation 不得假实现，用户需评审决定 |
| [Yak Ops YOIP、文档体系和 CI 研究](./research/2026-10-15-Yak-Ops-YOIP-文档治理与CI蒸馏.md) | 外部工程治理蒸馏及 OntoForge 适配建议；仅研究证据，不能取代 V1.3 或已实现代码 |

**状态区分**：设计是否 Accepted、代码是否 Implemented、测试是否 Passed、产品是否 Released 是不同事实。OFIP-0001 的明确接受只针对研发治理方向，**不会自动签收 V1.3 总体架构和 Native V0.2 产品合同**。规范入口见 [AGENTS.md](../AGENTS.md)、[贡献指南](../.github/CONTRIBUTING.md)、[OFIP 模板](../.github/OFIP_TEMPLATE.md)、[PR 模板](../.github/PULL_REQUEST_TEMPLATE.md)。

## CI 验证证据（OFIP-0001 第二阶段）

[可信质量门禁范围、结果解释及验收手册](./implementation/S0-CI-可信质量门禁.md) 描述已在 [PR #5](https://github.com/gitfortian/ontoforge/pull/5) 合并并通过实测的固定 `Required Quality Gate`、完整差异选择器、PostgreSQL/Flyway、Native Schema、前端和研究 POC 的独立证据。**工作流合并和 CI 成功不代表 GitHub Ruleset/branch protection 已配置**；浏览器/本机/生产验收仍须另行执行。

## 历史文件与清理原则

- V1.0、V1.1、V1.2 总体架构 Markdown 均被 V1.3 统一替代；旧的 OWL-first V0.1 设计说明与旧版验收说明不再作为现行技术依据。
- 各轮 POC 的阶段性研究报告已由 V1.3 第6、8、13、14章的定量证据、决策边界和 GitHub Actions 原始执行记录取代；旧 Markdown 不再留在当前文档目录中。
- **保留** V0.1 的 JSON Schema 和制造业正反例，供 V0.2 自动化迁移与历史兼容回归使用；它们不代表旧版模式仍是平台权威。
- 删除的是当前 Git 文件树中的过期文档，**Git 历史及原 PR 仍保留**，如有审计需要可按历史 commit 恢复查阅。
- 新增/调整设计必须引用 V1.3 的唯一入口；发生重大路线变化时更新 ADR、注明 POC/权衡，再通过独立 PR 修订或创建下一版本，**不得平行维护多份所谓现行总体架构**。

## 实施顺序

**S0 工程底座 → P0-A Native Model → P0-B Model Governance → P1 Binding / ObjectSet → P2 Function / Agent → P3 Governed Action → P4 产品化**。每个阶段 PR 应完成可验收用户闭环，包含实际启动/操作路径、安全负例和可重复证据。S0、正式生产编译器、真实业务系统连接与 Action 都不因本次文档归一而视为已实现。
