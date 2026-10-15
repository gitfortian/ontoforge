# OFIP-0001：OntoForge 需求提案、文档治理与 CI 证据门禁

**Status:** Accepted

**Discussion:** [PR #3 — [DISCUSS][OFIP-0001]](https://github.com/gitfortian/ontoforge/pull/3)

**Decision:** [VOTE] Accepted — 2026-10-15，维护者审议后要求按规划分阶段实施；[PR #3 决定记录](https://github.com/gitfortian/ontoforge/pull/3#issuecomment-6189658540)。本决定仅涵盖 OFIP-0001 研发治理方向与阶段安排，不自动签收 V1.3/Native V0.2 产品技术合同。

**Implementation:** In Progress — 第一阶段开发规范/文档治理在独立 PR 实施；第二阶段可信 CI 必须另开 PR 并通过实际验证；不因本次状态更新声称全部实施完成。

**Release:** N/A

**Reference:** [Yak Ops YOIP / CI 源码研究和适配结论](../research/2026-10-15-Yak-Ops-YOIP-文档治理与CI蒸馏.md)、[OntoForge V1.3 唯一总架构候选](../本体产品总体功能与技术架构设计_v1.3.md)、[Native V0.2 候选合同](../design/核心本体元模型与Native-DSL-AST契约_v0.2.md)

> 本提案参考 Flink FLIP 与 Yak Ops YOIP 的六章结构，但 OFIP 是 OntoForge 自己的流程，不代表 Apache 官方治理。已被维护者**批准分阶段实施**；本文件记录治理决策，具体模板与后续 CI 仍按各自实施 PR 单独评审/验收。本提案本身不修改产品业务代码或现行 CI。

## Motivation

OntoForge 已经拥有 V1.3 总体设计、Native V0.2 结构合同和 S0-MVP-0.1 基座。后续每个阶段都会引入跨来源身份、模型发布、授权查询、确定性决策和受控 Action 等高风险合同。如果研发没有清晰的评审入口、实现证据及文档真相源，很容易出现：

- 未经明确批准的方案直接进入开发；设计稿、PR、CI、产品交付状态混淆。
- 随着每次重构不断生成重复 Markdown；没有人知道哪份才是当前权威。
- 后端/前端/研究 POC 的绿灯被汇总为“产品已可用”，遗漏真实数据库、浏览器、撤权或错误恢复。
- Workflow 按临时历史分支触发或因 `paths` 过滤不产生状态；缺少稳定、可解释的 PR 合并门禁。
- AI 开发为了通过检查增加空模块、规则文档或绕过测试，反而扩大维护成本。

**目标**是最少制度建立最强可信边界，不以实现大 CI 平台、产出文档数量或模仿 Yak Ops 模块结构为目的。

## Public Interfaces

本提案首先影响**研发流程和仓库可见的维护者合同**，不是产品运行时 API：

1. 新增 `OFIP-XXXX` 命名与设计状态（Discussion / Accepted / Rejected / Superseded）以及独立的 Implementation / Release 状态。
2. 新增提案模板 `.github/OFIP_TEMPLATE.md`、`doc/proposals/` 目录和索引；重大设计使用独立 Proposal PR，标题建议 `[DISCUSS][OFIP-XXXX] ...`。
3. 新增简单 `AGENTS.md`、`.github/CONTRIBUTING.md`、`.github/PULL_REQUEST_TEMPLATE.md`，描述开发、变更判定、提交与验收规则。
4. 将来引入稳定的 PR `Required Quality Gate`，汇总已选择的实际验证范围，并展示 `PASS` / `FAIL` / `NOT SELECTED`，不把未执行当通过。
5. 实施者仍遵守 V1.3 唯一架构候选和 Native V0.2 候选合同，不因 OFIP 而自动改动任何产品 API、数据库格式、线上行为或发布版本。

## Proposed Changes

### 1. 何时必须创建 OFIP

必须：新增重大领域子系统、变更 Native Type/Link/Package/Compiler/Release 合同、Data Binding/Identity/Authority、ObjectSet 查询与结果质量、Function/Decision、Policy/Action/审批/效果核验、公开 SDK/MCP/REST、持久化/版本兼容性、跨模块边界，以及影响用户任务模型的重大交互变更。

不必须：普通 Bug、内部类/包重构、无公共语义变化的性能改进、标准列表页面细节、视觉样式、测试补齐、局部部署手册修正。小变更仍需明确 PR 和必要自动化测试，不需要单独设计稿。

若不确定，先在任务/PR 中写「受影响消费者与兼容性」；存在重大合同变化时升级 OFIP，而不是盲目生成文档。

### 2. 六章固定格式与职责

沿用六章：**Motivation / Public Interfaces / Proposed Changes / Compatibility, Deprecation, and Migration Plan / Test Plan / Rejected Alternatives**；顶部记录 Discussion、Decision、Implementation、Release。Proposed Changes 以业务流程、领域状态机、权威责任和异常语义为主，不做类清单。

- **用户问题**：用户角色、场景、黄金故事、成功与失败/无权限/来源变化分支。
- **公共契约**：模型 Schema、DSL/AST、主键/Link Identity、REST/SDK/State、错误码、授权责任与不可变版本。
- **兼容/迁移**：来源影响、历史草稿/发布版本、Flyway 前向迁移、回滚/失败处理及尚不支持的能力。
- **验证**：至少一个成功闭环、关键负例、持久性及可重复步骤，明确哪些交给单元、PG 集成、浏览器和用户实际验收。
- **阶段**：标记当前已实现/本轮交付/未来待实现，不在 UI 中伪装未来能力。

### 3. 提案决定与实现 PR 分离

1. 先基于 main 开 `[DISCUSS][OFIP-XXXX]` 的**纯文档 PR**；PR 内容包含提案、引用依据，设计审议不混入生产代码。
2. 由维护者 review/明确给出 `[VOTE] Accepted` / `Rejected` 或要求修改；把理由、决定链接和日期回填提案元数据。
3. Accepted 提案单独合并，未来实现 PR 必须引用它和当前唯一架构基线。关键公开契约被实质修改时，需要重新评审/新 OFIP。
4. 每个实现 PR 为一个完整用户可操作切片，包含真实可见行为、后端支撑、测试、异常恢复/兼容和手工验收路径；完成某 PR 不代表整个 OFIP Implemented。
5. Release 只根据真实发布事实更新；`Implementation: Pending` 可与 `Status: Accepted` 长期并存。
6. 拒绝设计可保留在关闭的 PR 讨论，不进入现行合同树。不能把绿色 CI、PR merge 或 ChatGPT 文本默认为用户签收。

### 4. 文档单一权威与维护纪律

- `doc/README.md` 是权威目录；V1.3 保持**唯一总体架构候选**，由明确产品/技术/安全评审最终签收；OFIP 不自动把它标成 Accepted。
- `doc/design` 放机器合同（Schema、样例、迁移矩阵）与当前高价值结构说明，不能凭原型宣称 Runtime 已实现。
- `doc/proposals` 放重大设计及清楚的生命周期；`doc/research` 放外部蒸馏，不拥有本项目实施权威；`doc/implementation` 只放可执行启动和验收步骤。
- API/状态/数据库当前行为以代码、Flyway、JavaDoc/TypeScript 和测试为准；重大设计决定以 Accepted OFIP 为依据，两者不一致要记录待收敛，不能掩盖差异。
- 不因内部类重命名或每轮小 PR 同步若干描述性架构 Markdown；过期方案归档或清理，保留 Git 历史/兼容夹具。

### 5. CI 分层（候选策略，等待独立实现 PR）

**S0 基线建议：**

| 门禁 | 触发与身份 | 必须的真实验证 | 未覆盖边界 |
| --- | --- | --- | --- |
| Docs + Proposal | 修改架构、提案、研究或合同 | 文档链接/目录状态；对 Native Schema 更改执行现有 JSON Schema 回归 | 不声称运行时验收 |
| Backend Quality | 修改 Java/Flyway/根 pom/相关 CI | Java21 编译/单元/真实 PG17 Flyway 及 UUID 状态测试 | 不声称 Object Runtime/Action 已实现 |
| Frontend Quality | 修改 React/TS/npm | TypeScript + production build；以后引入按需要的 lint/format/架构合同 | 不等于浏览器保存/交互已验收 |
| POC Evidence | 修改 `experiments/pg-runtime` 或显式研究调用 | PG17 扇出/权限/Outbox 实验与原始报告 | 不等于产品业务 E2E |
| Stable Required Gate | 每个 PR / main（**不应用仅 paths** 使 required job 消失） | 汇总所需检查及触发/未触发范围；依赖的选中检查失败/取消/未完成则失败 | 手工验收仍单独确认 |

当具体领域增加时再引入测试注册清单、Suite Matrix、契约变化影响映射。最初只有 PostgreSQL 一种生产候选数据库，禁止为了“全覆盖”照抄 Oracle/CDC 等 Yak Ops 测试矩阵。

**强制规则：**

- 根据完整 base...head diff 选范围（含新增/删除/改名两端）；未知/共享配置改动 `fail closed` 转全量；选择器应有自己的正反例测试。
- 所有判定必须绑定实际 commit SHA、测试命令、JUnit/前端测试报告/CI Run，避免以前一次绿灯证明当前代码。
- 工作流不跳过必需检查；未选测试明确 `NOT SELECTED`，选中测试跳过/缺失报告不能报告 `PASS`。
- PR 的「代码/契约质量门」与「浏览器黄金故事」「发布门」分开，检查名描述实际验证项。
- **不自动修改 GitHub Ruleset/branch protection**；是否开启 required checks 和谁有维护权限单独经维护者确认。

**未来 Release Gate**：等 P0/P1 真实产品发行需要出现后，再分离候选质量/迁移/安全负例/人工验收与正式发布；Publish 验证 Gate Run、候选 commit SHA、构建摘要、产物身份。S0 不提前造正式生产发布流水线。

### 6. 首轮试点和现有 PR

现有 [Windows 11 本机 PostgreSQL 统一 PR #2](https://github.com/gitfortian/ontoforge/pull/2) **已合并**，但 Windows 实机验收仍是独立 S0 任务；不能用本提案替代实际环境验证，也不与本提案 PR 混入代码修改。

按照本次 Accepted 的阶段决定，先提交工程治理 PR（模板/AGENTS/索引），再以独立 PR 实施轻量 CI 稳定门禁；然后以 S0-MVP-0.2 `ObjectType` 草稿保存和重开作为首个领域 OFIP 的验证应用。所有新功能以真实可运行体验为最终收口。

## Compatibility, Deprecation, and Migration Plan

- 不更改现有 Java 21/Spring Boot/React/PostgreSQL API，也不修改 Flyway V1、PG POC、Native V0.2 Schema 和现有产品功能。
- 不将现有 V1.3 与 V0.2 研究/候选文档**追认**为已 Accepted；后续按真实签收处理。
- 当前 GitHub Issues 可以继续承载需求与缺口；OFIP 用 proposal PR 记录重大设计争议和签收；实现使用独立 PR。不会强制迁移历史 Issue、PR 或编号。
- 已发表规范/迁移不可被删除后重新编号来掩盖兼容问题；未来产生新语义需要显式兼容矩阵/前向迁移。
- 拟新增模板/脚本会单独实施、评审与 CI；当前 proposal PR 不修改现有工作流或仓库 Ruleset。

## Test Plan

**本提案 PR 仅应运行并报告：**
1. 文档相对链接有效、研究/提案均能定位；研究与规范的身份、状态清晰。
2. 现有 Native V0.2/架构文档回归若实际执行需记录 workflow、commit SHA 与结果。
3. 单独人工评审：重大变更/普通 Bug 是否易判断；四个状态是否互不替代；对于 P0/P1/P3 是否存在安全漏项。
4. 本次**未运行**：本机 PG、业务 API、浏览器、Action、生产发布，不能写成这些测试通过。

**批准之后工程治理/CI 实施 PR 的验收标准：**
- 模拟 Docs-only、Java/Flyway、TS、Native Schema、POC、共享/未知路径，CI 选择符合规则，unknown 触发全量。
- 模拟选中检查失败/取消/缺报告时聚合 required gate 失败；未选应显示 `NOT SELECTED`，不是 `PASS`。
- 新旧文档目录只有一个 V1.3 权威基线，Proposal 必须有真实决策链接才能标 Accepted。
- PR 模板强制说明当前 head/base SHA、实际验证/未验证项、合同/迁移/安全影响。
- S0 原有 PostgreSQL/Flyway/React 与研究型 Native Schema/POC 的自动化验证不被弱化。

## Rejected Alternatives

1. **全部需求必须写 OFIP**：拒绝。琐碎 PR 会造成评审拥堵、文档膨胀，偏离“少而有效”。
2. **直接复制 Yak Ops YOIP/Java模块/CI矩阵**：拒绝。Yak Ops 面向数据同步与 Flink-like Runtime，OntoForge 是工业运营本体；领域安全与验证项完全不同。
3. **每次变更自动改 V1.3、Capability 文档和测试文档**：拒绝。权威重复而易漂移，代码/测试和决策各有唯一主人。
4. **将绿灯 CI 视为方案已签收或系统可生产使用**：拒绝。设计决定、覆盖范围、用户实际验收、生产验证必须独立。
5. **一次性落地所有 CI、发行、权限、Runner 或微服务**：拒绝。现阶段 S0 规模没有对应产品价值；以真实可验收用户故事逐步增加门禁。

---

### 已记录的维护者决策（见 PR #3）

- **D1 — Accepted**：采用 OFIP 编号、Proposal-only PR 和显式 `[VOTE]` 决策。
- **D2 — Accepted**：采用六章模板、设计/实现/测试/发布状态分离，仅重大变化需要 OFIP。
- **D3 — Accepted**：维护单一文档权威；V1.3/Native V0.2 保留各自**待签收**状态，不新增无用文档。
- **D4 — Accepted as staged roadmap**：先实现治理模板，再独立交付 S0 可信 CI；P0 以后根据真实故事补充浏览器与发行验证。
- **D5 — Accepted for staged implementation**：允许按步骤实现首轮治理规范，**不**授权在提案 PR 内直接修改业务代码、CI 或 GitHub Ruleset。

**决策已记录；当前执行第一阶段开发规范与文档治理。CI 稳定门禁与 P0 ObjectType 必须分开交付，并以各自真实测试和用户验收为准。**
