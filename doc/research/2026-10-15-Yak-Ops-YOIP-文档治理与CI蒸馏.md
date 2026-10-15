# Yak Ops 项目研发方法、YOIP、文档体系与 CI 深度蒸馏（面向 OntoForge）

> 研究日期：2026-10-15。研究对象：[weifuwan/yak-ops](https://github.com/weifuwan/yak-ops/tree/85338827bdbb745eaa6a017031640674a298a4f9)，核对 commit `85338827bdbb745eaa6a017031640674a298a4f9` 的仓库文件，以及 YOIP-5 的设计与实现 PR。对照基线：[OntoForge V1.3](../本体产品总体功能与技术架构设计_v1.3.md)（**待签收候选**）及 [Native V0.2](../design/核心本体元模型与Native-DSL-AST契约_v0.2.md)（**架构评审候选，非已实现编译器**）。
>
> **证据口径**：「仓库规则」= 文档声明；「代码可见」= 读到的脚本/工作流机制；「运行通过」必须有对应 Actions 结果或本机实际执行。本研究没有执行 Yak Ops 本地集成测试，也不宣称其产品质量或发布能力已由我们独立验收。
>
> 本报告仅作为外部方法研究，不是 OntoForge 第二套架构权威。对应实施制度草案：[OFIP-0001](../proposals/OFIP-0001-需求提案文档治理与CI证据门禁.md)。

## 1. 核心判断

Yak Ops 的核心资产不是某个模板或 CI YAML，而是「**设计决策和实现事实分离、不同风险配不同验证、每次交付留下对应版本的可信证据**」。对 OntoForge 应复制这种工作机制，而不是复制 Yak Ops 数据同步、YakFlow、Quartz、CDC 的业务模块。

更准确的端到端闭环：

```text
用户问题/场景 → 影响面判定
  ├─ 日常修复：Issue/任务 → 小 PR → 定向测试 → 用户实际验证
  └─ 重大设计：OFIP Discussion PR → 方案评审/异议 → 显式 Accepted
                                          ↓
                        按黄金故事切片实现（一个 PR 一个实际结果）
                                          ↓
             变更范围 CI + 影响契约/安全负例 + 集成/浏览器证据
                                          ↓
                   人工签收 → 再进入下一切片/发行版
```

关键独立状态：`Discussion/Accepted/Rejected/Superseded`（**设计决定**）、`Planned/In Progress/Implemented`（**实现状态**）、`Not Run/Failed/Passed`（**测试证据**）、`Released`（**发行事实**）。绝不凭合并 PR 或 CI 成功自动改变另一种状态。

## 2. Yak Ops 的证据地图：看到了什么

| 对象 | 实际证据 | 可提炼约束 |
| --- | --- | --- |
| 工作入口和 AI 协作 | [AGENTS.md](https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/AGENTS.md) | 一处说明命令、模块、禁止依赖、提交与验证规则；只记录实际运行的检查；不因 AI 提交放宽审核 |
| 贡献工作流 | [.github/CONTRIBUTING.md](https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/.github/CONTRIBUTING.md) | 大变化讨论在前、Accepted 再开发，日常修复免设计提案；设计争议留在 PR |
| YOIP 模板 | [.github/YOIP_TEMPLATE.md](https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/.github/YOIP_TEMPLATE.md) | 采用 Flink FLIP 的六章：Motivation、Public Interfaces、Proposed Changes、Compatibility/Deprecation/Migration、Test Plan、Rejected Alternatives |
| 产品设计实例 | [YOIP-5](https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/docs/yoips/YOIP-5-data-integration-sync-product-model.md) 和 [提案 PR #1636](https://github.com/weifuwan/yak-ops/pull/1636) | **用户故事、统一入口、类型/步骤、版本兼容、失败语义、阶段能力开放**先于前端实施；Accepted 后独立实现 [#1637](https://github.com/weifuwan/yak-ops/pull/1637)、[#1639](https://github.com/weifuwan/yak-ops/pull/1639) 等 |
| 运行时设计实例 | [YOIP-1](https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/docs/yoips/YOIP-1-checkpoint-and-end-of-input.md)、[YOIP-2](https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/docs/yoips/YOIP-2-task-execution-lifecycle.md)、[YOIP-4](https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/docs/yoips/YOIP-4-schedule-lifecycle-quartz.md) | 结束输入不等于可靠完成；业务 Instance 不等于物理 Attempt；调度提交不等于执行成功。分清状态的权威责任 |
| 贡献 PR 模板 | [.github/PULL_REQUEST_TEMPLATE.md](https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/.github/PULL_REQUEST_TEMPLATE.md) | 目的、重要变更、实际测试与未测项、影响面、reviewed head/base SHA、AI 辅助责任 |
| 质量 CI | [quality-check.yml](https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/.github/workflows/quality-check.yml) | 受影响的后端/前端/分发各自校验；聚合为稳定 PR Required Checks |
| 范围选择与其自身测试 | [detect-changes.sh](https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/scripts/ci/detect-changes.sh)、[test-detect-changes.sh](https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/scripts/ci/test-detect-changes.sh) | PR 使用 base...head 的完整差异；未知文件默认扩大测试；CI 选择器有回归样例；workflow_call 走全量 |
| 集成测试与证据 | [backend-tests.yml](https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/.github/workflows/backend-tests.yml)、[backend_tests.py](https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/scripts/tests/backend_tests.py) | IT 注册清单、选中套件的报告与最少执行数、跳过/缺失/失败报错；`NOT SELECTED` 不等于 `PASS` |
| 浏览器验证 | [playwright-ui-tests.yml](https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/.github/workflows/playwright-ui-tests.yml) | 使用真实生产构建 + 浏览器交互场景；与后端数据链路集成测试分责 |
| 发行证据 | [v1-release-gate.yml](https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/.github/workflows/v1-release-gate.yml)、[release-publish.yml](https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/.github/workflows/release-publish.yml) | Release Gate 校验候选与人工证据，Publish 再绑定相同版本/commit/SHA/产物；发布和门禁分开 |
| 前端设计规则 | [FRONTEND_RULES.md](https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-ui/FRONTEND_RULES.md) | 领域页面→领域 Service→HTTP transport 单向依赖；共享 UI 不承载业务；少文案、真实状态、可访问性；普通 UI 改动不要求写 YOIP |

注意：YOIP-3 的现行章节格式短于统一六章模板，说明**制度与历史产物并非全都严格一致**；不能据此宣称所有 Yak Ops 设计都已完全标准化。YOIP-4 的 Accepted/Implementation: Pending 也说明「设计采纳」并不等于「产品实现」。研究到的是其机制和部分代码，不是完整第三方生产验收。

## 3. YOIP 最重要的六个思想

### 3.1 先判断是否值得升级为 Proposal

重大公共契约、跨模块架构、序列化/持久化格式、恢复模型、外部 API、用户配置和显著兼容性变化才强制走 YOIP。普通 Bug、类重命名、页面样式与私有重构直接走聚焦 PR。避免「每修一个字段也新增三份文档」的形式主义。

### 3.2 设计决定需要**显式人类接受**

提案起始 `Status: Discussion`、`Decision: Pending`；讨论在 proposal PR，维护者明确 [VOTE] 或签收后才变成 Accepted。Proposal PR **不混入实现代码**；Rejected 的设计不合入现行合同。设计验收、代码验收、正式发布是三道不同的门。

### 3.3 契约先于类图

YOIP 的 Public Interfaces 不只包含 REST：还包含 Config、数据库格式、序列化状态、Checkpoint、监控、UI 语义和外部消费者。Test Plan 必须覆盖异常/兼容/反例，而不是只写成功路径。对 OntoForge 更要关注版本 digest、主键、Link identity、Binding authority、ObjectSet Scope、Action approval/UNKNOWN 等合同。

### 3.4 将设计评审与实现切片解耦

YOIP-5 定义了最终统一数据同步产品语义，但每个实现 PR 只开放已完成后端和真实验收的有限能力。**完整产品目标不意味着首次 PR 必须实现完整目标**。OntoForge 一样：OFIP 批准 P0 模型生命周期，0.2 PR 只交付可保存/可重开 ObjectType，后续独立交付校验/发布，避免造假 UI。

### 3.5 当前事实归代码、类型、测试、已签收合同

AGENTS/CONTRIBUTING 明确反对为每个内部改动同步 Architecture/Capability Markdown。产品用户手册只记录可观察行为；重大历史决策在 Proposal/PR；代码 JavaDoc/测试记录当前 API 语义。可减少两套互相矛盾的“最新架构”。

### 3.6 特殊安全风险不能用文档代替执行证明

如 YOIP-1 提醒 `END_OF_INPUT != checkpoint confirmed != sink committed`，YOIP-2 区分意图/Instance 与 Job/Attempt，YOIP-4 区分 Cron 触发与真正执行。这些不应直接转成 OntoForge 的执行架构，只能迁移**边界和证据思想**：`Rule result != Authorization`；`Action dispatched != remote accepted != outcome verified`；`SourceFact != Object projection`。

## 4. Yak Ops CI 的机制与适用边界

### 4.1 三层运行节奏

| 阶段 | Yak Ops 可见设计 | OntoForge 建议 |
| --- | --- | --- |
| 普通 PR | Scope Detect + 单元/静态规范 + 受影响的真实数据库 IT；聚合 Required Check | **现在**：统一稳定的 `quality-gate`，保留 Java21 + PostgreSQL/Flyway + TS build + Native JSON Schema + docs；明确显示测试选择和未测范围 |
| 变更较大/可验证用户故事 | 数据库隔离套件、Backend E2E、UI 浏览器用例独立运行 | **P0-A**：ObjectType API/PG 集成 + 拒绝测试 + 前端保存/重开浏览器黄金故事；不要拿 Playwright 代替真实后端测试 |
| 正式发行 | 强制全量质量/IT、发布候选产物、人工 E2E/迁移复审/已知限制、发行验证并签名/归档 | **到 Pilot 前**：再引入 release gate 与 publish 分离；S0 不建完整 Docker/K8s 发行体系 |

### 4.2 细节值得直接学

- `detect-changes.sh` 在 PR 用 merge-base `base...head` 差异，在 push 用 before/current；**未知变化 fail closed**（扩大为全量），而不是未知就跳过。其自身存在 `test-detect-changes.sh`。
- 聚合 `PR Required Checks` job 依赖三个质量 job，`always()` 汇总并要求 job success；即使仅文档变化也应有稳定、清晰的合并门，而非全部 workflow 因 `paths` 不运行、分支规则一直等。
- Backend Tests 通过可用套件矩阵和 `backend_tests.py` 映射影响面，维护具体 `*IT` 注册表、JUnit XML 出现情况和最低方法数；选中套件 missing/skipped/failure 即拒绝，未选为 `NOT SELECTED`。
- CI 与真实产品用户验收分责：Quality Gate ≠ Browser E2E ≠ Release Gate。文档只改校验引用/结构，不默认要启动数据库；但一旦修改 Schema/Flyway/API 就必须测试真实边界。
- 发布 Gate 必须记录版本、精确 commit SHA、候选产物与人工证据，Publish 回溯验证相同对象，避免「拿另一个提交的测试绿灯发布当前源码」。
- CI 脚本、Maven wrapper、检查器不应擅自修改代码来掩盖失败；Job 名称反映实际测试类型，不宣传未覆盖范围。

**不照搬的前提**：Yak Ops 已有多 Connector、多数据库、恢复和生产发布链路；OntoForge 仍是 S0，只需小而确定的 PostgreSQL + Native 合同 + UI。Oracle/CD​​C、复杂套件矩阵、Kubernetes 发布、全量 E2E、数十个模块当前都没有业务归属，不应提前移植。

## 5. OntoForge 当前差距（设计基线原始检查点 `603602b`；2026-10-15 已更新到 `6f98f5a`）

| 维度 | 已有价值 | 实际差距 | 建议优先级 |
| --- | --- | --- | --- |
| 产品架构 | V1.3 唯一候选总体架构、Native V0.2 JSON Schema/样例 | 缺「重大变更如何提案、谁批准、已接受合同如何修改」的明确治理；不能把 V1.3 误标为 Accepted | P0 |
| 文档 | `doc/README.md` + `design/research/implementation/tests` 分层，文档链接/历史基线回归 | 缺 Proposal 审议和历史决策索引；潜在未来旧文档回流和状态歧义 | P0 |
| AI 开发协作 | 已规定模块名和 S0 实施边界 | 没有统一 `AGENTS.md`、贡献工作流、PR 模板/真实验证填报 | P0 |
| 自动 CI | 已有 S0 后端/前端、Native V0.2、架构测试、PG POC | 五套 workflow 各自 path/部分 `push` 绑定历史功能分支；无稳定聚合、测试影响选择器/自测与“未运行”报告 | P0/P1 |
| 前端 | TypeScript 与构建 CI | 尚无统一的 lint/format/架构边界和真实浏览器黄金故事；没有用户可操作的 Native 功能供其验证 | P0-A 后再增 |
| 数据库 | Flyway V1、真实状态 API、研究期 PG RLS/竞态 POC | 尚未有生产 Native 持久化及模型行为反例；PG 研究 POC 不是发布代码的 Runtime E2E | P0-A 起 |
| 发行 | 真实 S0 本地启动与说明（Windows 原生 PostgreSQL PR #2 已合并；Windows 用户本机实际验收仍待核实） | 尚无正式 Release Gate、证据绑定与可部署制品；现阶段不应建设生产发布链 | Pilot 前 |

**尤其注意**：PR #2 已经合并到 `main`；这说明配置与手册已经进入代码基线，**不等于**用户 Windows 主机实际验收已通过。当前每个 PR 的 CI 成功不能替代本机操作验证。GitHub Repository Ruleset/required checks 尚未确认为强制，不能通过编辑 YAML 假装拥有仓库合并保护。GitHub `main` 当前没有启用 branch protection，是否需要 Repository Ruleset/required checks 要单独确认权限和设置，不能通过编辑 YAML 假装已强制禁止绕过。

## 6. 推荐 OntoForge 文档信息架构：在现有结构上最小增量

```text
README.md                        # 产品介绍 + 唯一本地启动入口
AGENTS.md                        # AI/开发者简短工程不变量与检查命令（待批准）
.github/CONTRIBUTING.md          # 决策、提案、PR 工作流（待批准）
.github/PULL_REQUEST_TEMPLATE.md # 目的、契约、证据、未测项（待批准）
.github/OFIP_TEMPLATE.md         # 六章模板、独立状态（待批准）
doc/
  README.md                      # 权威目录与生命周期索引；继续保留
  本体产品总体...v1.3.md         # 唯一总体架构，仍待签收
  design/                        # Native V0.2 Schema、样例、兼容、可执行合同
  proposals/                     # OFIP-0001 ...（仅重大设计，方案与讨论链接）
  implementation/                # 当前可执行的环境部署与用户操作指南
  research/                      # 外部蒸馏和证据，不升级为产品真相
  tests/                         # 文档/契约验证
```

**文档所有权**：重大设计决策在 Accepted OFIP；整体架构仍只有 V1.3 一个候选基线入口；结构化 Machine Contract 由 Schema+真实测试承担；运行事实由代码、迁移与回归测试承担；手册只写真实可用功能；Issue 存需求待办，PR 存审查/实现证据。研究论文、陈旧中间态、每个内部类的 Markdown 说明不重复作为权威。

## 7. OFIP 适用清单与第一个真实用户故事

| 变化 | 是否需要 OFIP | 判断原因 |
| --- | --- | --- |
| P0 Native 模型 Package/Type/Link 发布及身份/版本/摘要合同 | 是 | 长期公共/持久化合同，版本和兼容性敏感 |
| Data Binding 的 Property Authority 与跨源对象 Identity | 是 | 来源、对象身份、冲突处理和撤权语义 |
| ObjectSet 逻辑查询/权限/Scope/Accuracy 合同 | 是 | 安全边界与外部 SDK 语义 |
| Function/Decision/Action/Policy/Outbox 与审批核验 | 是 | 高风险业务副作用和可恢复性 |
| 前端普通 UI 排版调整或按钮样式 Bug | 否 | 不改变公共配置/状态/兼容性 |
| 内部类提取、日志优化、不变合同下的性能重构 | 否 | 聚焦 PR + 必要测试足够 |
| 新的本地开发命令/安全重要操作约束 | 一般否 | 更新运行手册，PR 说明兼容/安全影响 |
| 正式改变跨模块分层、公开插件 SPI 或运行/发布语义 | 是 | 会影响多个实施 PR 与外部调用方 |

第一个实现实例建议围绕 **S0-MVP-0.2「Manufacturing ObjectType 草稿建模 → 保存 → 重新打开 → 字段规则拒绝」**，一个最小纵向切片。设计先定义对象身份、可保存字段、草稿生命周期、非法输入、同名冲突和版本策略；真正的写库/前端交互/迁移集成测试留在实施 PR，不由 Proposal 假称“已支持”。

## 8. 分阶段吸收路线与门禁

| 步骤 | 交付/Scope | 明确不做 | 独立验收 |
| --- | --- | --- | --- |
| **研究 + OFIP-0001（当前 PR）** | 研究证据、OFIP 制度/CI 策略提案，保持 Discussion | 不自动变更强制规范、业务代码或 CI | 用户明确决定（接受/修改/拒绝） |
| **工程治理 PR（批准后）** | `AGENTS.md`、`.github/CONTRIBUTING.md`、`OFIP_TEMPLATE.md`、PR 模板和 doc 索引；轻量文档链接检查 | 不新增按组件拆的 Markdown，不修改 V1.3 合同语义 | 样例提案/日常修复适用性验证，路径链接检查 |
| **CI 可信基线 PR（批准后，S0）** | 保留 S0/Native/Docs/PG 实验工作流真实测试，增加 `quality-gate`、稳定 REQUIRED 判定、全量影响策略、清晰的未测范围 | 不引入假 Browser/E2E、Oracle/CDC/RLS 生产宣称 | 模拟 docs/backend/UI/schema/shared/unknown 全部类别；unknown fail closed；证明跳过不会伪装通过 |
| **P0-A ObjectType 垂直交付** | Accepted 的具体 Model OFIP → PostgreSQL Flyway/HTTP/UI/负例/可重复用户操作 | 不创建无调用者的抽象层、Registry 全套或 Agent | 真实浏览器创建、保存、打开、校验错误 + API/PG 双向断言 |
| **Pilot / Release Gate** | 版本/SHA/产物绑定、不可变迁移复核、完整安全/回归/人工 E2E 证据 | S0 不做生产部署宣称 | 合法候选通过；改 SHA、缺报告、缺安全证据必失败 |

## 9. 风险、取舍与最终建议

1. **首要价值是可信边界，不是多加门禁数量**。只保留能自动执行或人工真实验收的不变量；不为每个模块搞大而全设计。
2. **CI must fail closed，但不把所有 PR 强制跑 POC 压测**。正式业务 CI 不应被研究型 PostgreSQL 80 万/600 万扇出实验无条件拖垮；研究 POC 继续独立可运行，必要时按核心运行合同触发。
3. **未测和失败要区分**。文档-only 路径跳过 Java/DB 可以合理，但应显式显示为 `NOT SELECTED`，不宣传「全链路通过」。
4. **不要复制 Flink/YOIP 或第三方代码**。只借鉴公开的思想和模板结构，使用 OntoForge 自己的 OFIP 编号、领域术语、风险门禁和现有 V1.3 及 Native V0.2。
5. **S0 的规则不是生产安全的替身**。目前未实现鉴权、Policy Enforcement、Object Runtime 和 Action；即便所有现有 CI 通过，也不能说具备这些能力。
6. **OFIP 决策必须由维护者显式签收**。未签收继续 Discussion，不能以提案 PR 合并、自动化绿灯或研究结论作为设计批准。

**结论**：优先采用「少量权威文档 + OFIP 重大提案 + 一个 PR 一个有形交付 + 与真实验证范围匹配的稳定 CI + 用户实际签收」。这套机制可为 OntoForge 后续从 P0 到 P3 扩展提供约束，而不是现在重复 Yak Ops 的模块数量、数据同步引擎与发布复杂度。
