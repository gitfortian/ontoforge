# OntoForge · 砺元｜本体产品设计与架构文档

> **产品完整功能范围入口（V1.1，待签收）**：[八大功能域 F01–F29，216 个三级产品能力](./product/OntoForge-产品完整功能树-三级-V1.1.md)；[Core 15 项验收合同与 8 条黄金故事](./product/OntoForge-Core功能验收合同与黄金故事-V1.1.md)；[正式签收跟踪 Issue #82](https://github.com/gitfortian/ontoforge/issues/82)。与 V1.3 的职责不同：V1.3 仍为**唯一总体架构权威**，V1.1 为产品功能分解视图。  
> **编号迁移追溯**：[原 125 条 AS-IS 完整分类表](./product/OntoForge-ASIS到全产品功能树-追溯与分类-V1.1.md)，明确保留已实现事实、移出范围排除与验收门禁。
> **第二轮复核（最新状态）**：[V1.1 产品功能第二轮独立复核报告](./product/OntoForge-V1.1-第二轮独立产品功能复核-20261019.md)：216 三级产品能力，现阶段 **57 已实现限定 / 5 部分 / 154 规划**，审查明确只允许**范围候选**，不可据此宣称 UAT/Release 已完成；[编号迁移](./product/OntoForge-ASIS到全产品功能树-追溯与分类-V1.1.md)已修正为 71/26/16/6/6。  
> **当前产品三级功能盘点入口（待全域签收，2026-10-19）**：[OntoForge V1.0 实施能力盘点（12 个交付模块 / 30 个实现组 / 125 个条目）](./implementation/OntoForge-产品功能说明书-三级功能-V1.0.md)。按 `main=c9de0336` 审核 M4–M7 的当前实现和未交付边界；下方旧 OFIP 阶段状态、2026-10-17 AS-IS 是历史记录，不能覆盖最新基准。本页 V1.3 总体架构入口继续是唯一架构权威。
> **正式功能评审**：[评审报告（REVISE / 暂不签收）](./implementation/OntoForge-三级功能正式产品评审报告-20261019.md)：本次识别 V1.3 八域、F01–F29 覆盖缺项和质量门混计等 P1；并未把当前 PR 自动批准为完整产品功能基线。  

**产品品牌：OntoForge（砺元）；工程 Maven / Java / 前端命名统一使用 ontoforge。**

**唯一现行总体架构入口：[本体产品总体功能与技术架构设计 V1.3](./本体产品总体功能与技术架构设计_v1.3.md)**

> 状态：**正式统一架构基线候选，待产品、技术、安全联合评审签收**。代码/运行时与生产部署尚未完成；实验通过不代表生产能力已经签收。
>
> 设计方向：Enterprise-native Operational Ontology，连接 Object/Link、Data Binding、ObjectSet、Rule/Function、Decision、Policy、Action 与可追溯证据。**推理结论不等于行动授权**。

- **[砺元本体产品与研发统一术语词汇表（256 个主词条）](./砺元本体产品统一术语词汇表.md)**：产品、UX、Java/React、测试与用户帮助统一用语；不是第二套架构。
- **[当前已实现功能全景 AS-IS](./implementation/OntoForge-AS-IS-已实现功能全景-20261017.md)**：当前 main 中用户真正可操作的完整功能、成熟度、接口与证据；不替代 V1.3。
- **[当前实际技术/数据/执行架构全景 AS-IS](./implementation/OntoForge-AS-IS-实际架构全景-20261017.md)**：运行模块、DB ER、重要调用链、部署与限制；不替代 V1.3。
- **[M3 Native 对象集合浏览与标量字段筛选（PR #40 开发候选）](./implementation/M3-Native-ObjectSet-对象浏览实现与验收-20261017.md)**：用户选择对象类型、筛选、去重与来源追溯。
- **[M2 质量追溯 ObjectSet 影响集合与分页实施说明](./implementation/M2-ObjectSet-制造质量影响集合实现与验收-20261017.md)**：一个批次完整关系路径/去重订单/断链和分页，#38 开发候选。
- **[精简 MVP 核心优先与 yak-ops HttpSession 复用决策](./implementation/MVP-核心优先与yak-ops认证复用决策-20261017.md)**：含本机开发路径、#17 修正、ObjectSet 下一轮验收。
- [MVP 历史总账与未来范围 F01–F29](./implementation/OntoForge-MVP-产品功能全景与成熟度矩阵.md)
- [S0-MVP-0.3 本轮交付说明与下一步进度](./implementation/S0-MVP-0.3-交付说明-20261016.md)
- [每轮交付说明模板](./implementation/交付说明模板.md)

> **2026-10-17 AS-IS 代码状态补充**：历史架构/OFIP 描述中的“P1 尚未实现”是全功能层级；目前 main 已合并 #32/#33 的**本机 PG 测试数据两跳追溯子场景**。#17 B0 已合并但不作为精简 MVP 登录路线；#34 脱敏 CSV 导入已经合并。应参考上面的两份 AS-IS 盘点核对，不将这些当成正式架构签收。
>
## 当前有效文件

| 文档/合同 | 权威职责与状态 |
|---|---|
| [统一术语词汇表](./砺元本体产品统一术语词汇表.md) | **256 个中文/英文术语、边界、阶段与使用规则**，为用户文案、研发接口和下期 OFIP 共享一个术语入口；不变更机器合同 |
| **[总体功能与技术架构 V1.3](./本体产品总体功能与技术架构设计_v1.3.md)** | **唯一现行总体设计基线候选**：定位、F01–F29、限界上下文、PG17 证据、统一 ADR、路线与签收 Gate |
| [Native DSL/AST V0.2](./design/核心本体元模型与Native-DSL-AST契约_v0.2.md) | 配套**机器可读技术合同**：五份 [V0.2 Package JSON Schema](./design/schema/ontology-package-v0.2.schema.json)、Typed AST、制造质量样例、[契约测试](./design/tests/test_native_v02.py) |
| [V0.1 → Native V0.2 迁移矩阵](./design/V0.1到NativeV0.2迁移矩阵.md) | 历史 IRI、公理与 EntityType 的显式兼容/拒绝约束，不是第二份总体架构 |

工程目录、依赖、F01–F29 归属及当前模块化落地：[完整工程模块结构规划](./implementation/S0-工程模块规划与命名规范.md)。本文件是 V1.3 的工程分解；正式产品发布/治理合同仍按原状态管理。外部依据：[Yak Ops 固定提交模块核验](./research/2026-10-17-Yak-Ops-模块结构与依赖核验.md)。

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
| [OFIP-0017：Yak 通用账号与工作空间接入](./proposals/OFIP-0017-Yak通用账号与工作空间接入.md) | **Accepted（[维护者决定](https://github.com/gitfortian/ontoforge/pull/94#issuecomment-6261109442)）/ B1 In Progress / Not Released**；默认本地账号 + Session、显式 OIDC 兼容；可信身份、前向迁移、管理员/空间授权与逐用户 cursor 验收合同 |
| [OFIP-0001：需求提案、文档治理与 CI 证据门禁](./proposals/OFIP-0001-需求提案文档治理与CI证据门禁.md) | **Accepted / 分阶段实施**：规范和可信 CI 已独立交付；当前门禁、保护配置和实际证据见下方 CI 手册，后续按真实场景演进 |
| [OFIP-0002：Native ObjectType 草稿建模 MVP](./proposals/OFIP-0002-ObjectType-草稿建模MVP.md) | **Accepted / Code Merged（[PR #7](https://github.com/gitfortian/ontoforge/pull/7)）/ Not Released**；对象/属性编辑与校验的设计及实现入口，当前环境验收统一见产品进度总账 |
| [OFIP-0003：模型快照、结构差异与发布边界](./proposals/OFIP-0003-S0-MVP-0.3-模型快照与发布边界.md) | **Accepted / Code Merged（[PR #11](https://github.com/gitfortian/ontoforge/pull/11)）/ Not Released**；本地不可变历史和结构风险；正式 Release 未实现；当前环境验收统一见产品进度总账 |
| [OFIP-0004：多对象与 LinkType 关系建模](./proposals/OFIP-0004-多对象与LinkType关系建模MVP.md) | **Accepted（受托产品决议）/ Code Merged（[PR #13](https://github.com/gitfortian/ontoforge/pull/13)）/ Not Released**；同包对象/关系定义、校验和本地历史；当前成熟度/环境结果统一见产品进度总账 |
| [OFIP-0006：M4 通用 ObjectType Runtime 类型解析与注册](./proposals/OFIP-0006-M4-通用运行时类型注册.md) | **Accepted / Implemented / Not Released**：[PR #56](https://github.com/gitfortian/ontoforge/pull/56) 已合并；TypeRef 双来源、Native Candidate 权威解析、包授权只读 API、Runtime 类型目录及 PG17/Chromium 自动化检查均通过；Windows 本机人工验收仍未运行；附 [UX-Low 草案](./prototypes/m4-runtime-type-catalog-lowfi.html) |
| [OFIP-0005：P0-B 模型治理、可信审核、正式发行与激活回退](./proposals/OFIP-0005-P0B模型治理审核发布与激活MVP.md) | **Discussion / DG-1 产品领域 + TC-2 详细合同候选已提交 / Implementation NOT STARTED / Not Released**：7 个治理资源及角色/身份服务端信任边界；审批摘要/真实 JCS 内容、ReleaseManifest 版本语义、依赖锁、发布与激活 CAS、故障恢复及不可变历史兼容；13 项 TC2 合同主题、8 项待定技术决策和 16 项未来可执行负例；不含可信认证、审核或发布生产代码 |
| [OFIP-0007：M5 通用 Data Binding 与只读来源映射](./proposals/OFIP-0007-M5-通用DataBinding.md) | **Accepted（[PR #58 正式投票](https://github.com/gitfortian/ontoforge/pull/58#issuecomment-6244833403)）/ UX-Low Accepted / UX-High Accepted / Implementation In Progress（[PR #63 Draft](https://github.com/gitfortian/ontoforge/pull/63)）/ Not Released**；Issue [#44](https://github.com/gitfortian/ontoforge/issues/44)；[低保真原型](./prototypes/m5-data-binding-lowfi.html)、[高保真原型](./prototypes/m5-data-binding-highfi.html) 与[实施前高保证审查](./implementation/M5-实施前高保证审查与实施切片.md) 已完成；当前 PR 包含 V7、只读来源角色、通用绑定 API/React 工作台与真实 PG17/HTTP 测试，CI/评审待完成 |
| [OFIP-0008：M6 通用 Object Runtime](./proposals/OFIP-0008-M6-通用ObjectRuntime.md) | **Discussion / Decision Pending / Implementation Not Started / Not Released**；Issue [#45](https://github.com/gitfortian/ontoforge/issues/45) 已完成需求 Review；ObjectRef 身份域、PropertyValue PRESENT/NULL/MISSING、冲突拒绝及来源证据合同；待维护者显式决定 |
| [OFIP-0009：M7 通用 ObjectSet AST 与有界查询](./proposals/OFIP-0009-M7-通用ObjectSet查询.md) | **Discussion / Decision Pending / Implementation Not Started / Not Released**；Issue [#46](https://github.com/gitfortian/ontoforge/issues/46) 已完成需求 Review；local-query-v1、类型化 AST、有界结果快照/游标、精确聚合与权限复核；待维护者显式决定 |
| [OFIP-0010：M8 LinkFact 来源映射与有界遍历](./proposals/OFIP-0010-M8-LinkFact与有界遍历.md) | **Accepted（仅 M8.1） / [Owner 受托正式决策](https://github.com/gitfortian/ontoforge/pull/88#issuecomment-6259511734) / Implementation Not Started / Not Released**；M8.1 已签收 UX-Low #84、UX-High #87 并明确批准单列 FK 双向一跳生产研发准入；M8.2 桥表与 M8.3 两跳仍待独立决策 |
| [OFIP-0011：M9 来源证据、漂移复核与查询一致性](./proposals/OFIP-0011-M9-来源证据漂移与一致性.md) | **Discussion / Decision Pending / Implementation Not Started / Not Released**；Issue [#48](https://github.com/gitfortian/ontoforge/issues/48) 已完成需求 Review；四维质量、UNKNOWN checkpoint、结果快照与源历史区分、漂移修复闭环；待维护者显式决定 |
| [OFIP-0012：M10 发行边界与运行时版本锁](./proposals/OFIP-0012-M10-发行边界与运行时版本锁.md) | **Discussion / Decision Pending / Implementation Not Started / Not Released**；Issue [#49](https://github.com/gitfortian/ontoforge/issues/49) 已完成需求 Review；复用 OFIP-0005，区分 M10-A 本地版本锁与 M10-B 正式发行/激活，Core 范围待决 |
| [OFIP-0013：M11 通用 Ontology Studio](./proposals/OFIP-0013-M11-通用OntologyStudio.md) | **Discussion / Decision Pending / Implementation Not Started / Not Released**；Issue [#50](https://github.com/gitfortian/ontoforge/issues/50) 已完成需求 Review；连续工作流/上下文一致性收口，M5–M9 各自仍须先交付 UI；原型 Gate 保留 |
| [OFIP-0014：M12 沙箱受控 Action](./proposals/OFIP-0014-M12-沙箱受控Action.md) | **Discussion / Decision Pending / Implementation Not Started / Not Released**；Issue [#51](https://github.com/gitfortian/ontoforge/issues/51) 已完成需求 Review；独立模拟系统、可信双主体审批、持久幂等、UNKNOWN 对账和结果核验；不接生产写/物理控制 |
| [OFIP-0015：M13 Agent 只读本体上下文](./proposals/OFIP-0015-M13-Agent只读上下文.md) | **Discussion / Decision Pending / Implementation Not Started / Not Released**；Issue [#52](https://github.com/gitfortian/ontoforge/issues/52) 已完成需求 Review；REST 先行、渐进发现/查询/证据，MCP 后续独立适配；正式对外需 M10 与身份安全 Gate |
| [OFIP-0016：M14 多领域 MVP 验收与交付证据](./proposals/OFIP-0016-M14-多领域验收与交付证据.md) | **Discussion / Decision Pending / Implementation Not Started / Not Released**；Issue [#53](https://github.com/gitfortian/ontoforge/issues/53) 已完成需求 Review；作为 Core/Extended 共用验收出口，M10/Core 口径待维护者决定；不新建第二套总账 |
| [S0-MVP-0.3 功能说明、全面 Review 与验收矩阵](./implementation/S0-MVP-0.3-功能说明与全面Review验收矩阵.md) | 基于 PR #11 实际代码核查冻结事务、JCS 摘要、不可变 PG V3、REST、Type-scoped Diff、React 历史跨页和 Windows 验收；列出 T1–T12 覆盖/缺口、真实回归与 Not Released 边界 |
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
