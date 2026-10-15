# Contributing to OntoForge · 砺元

本仓库使用 [OFIP-0001](../doc/proposals/OFIP-0001-需求提案文档治理与CI证据门禁.md) 确立的**重大设计先评审、逐 PR 可验收交付、证据与状态分离**流程。本规则面向人工贡献者与 AI Coding Agent。请同时遵循根目录 [AGENTS.md](../AGENTS.md) 和 [README.md](../README.md)。

## 先判断：需要 OFIP 还是普通 PR？

| 变更 | 执行方式 |
| --- | --- |
| Native ObjectType/LinkType/Package/AST/Compiler/Release 核心约束或版本语义 | **OFIP 先行**，明确接受后再实施 |
| Data Binding/Identity/Authority、ObjectSet 查询/授权/证据、Function/Decision、Action/Policy/Outcome | **OFIP 先行**，必须覆盖安全及失败边界 |
| 影响其他系统的 REST/SDK/MCP、持久化格式、兼容性、跨模块架构、重大用户工作流程 | **OFIP 先行** |
| 普通 Bug、UI 样式、内部重构、局部性能、测试补齐、文档错误或本地启动问题 | 单个聚焦 PR 即可，不强制设计文档 |

现有 [总体架构 V1.3](../doc/本体产品总体功能与技术架构设计_v1.3.md) 与 [Native V0.2](../doc/design/核心本体元模型与Native-DSL-AST契约_v0.2.md) 仍按文件状态分别评审；**OFIP-0001 Accepted 不等于它们自动签收**。

## OFIP 流程

1. 确认目标功能和现有 OFIP 是否已覆盖。选用下一个未使用的 `OFIP-XXXX`，不要自动重新编号历史记录。
2. 从 [.github/OFIP_TEMPLATE.md](OFIP_TEMPLATE.md) 复制至 `doc/proposals/OFIP-XXXX-short-title.md`。初始状态固定为 `Status: Discussion` / `Decision: Pending`。六章包括 Motivation、Public Interfaces、Proposed Changes、Compatibility/Deprecation/Migration Plan、Test Plan 和 Rejected Alternatives。
3. 提交**只含提案及必要引用文档**的 PR，标题 `[DISCUSS][OFIP-XXXX] 主题`。记录用户故事、异常/权限分支、兼容性、候选方案与每项有意义的取舍，不把设计文档当作运行事实。
4. 在该 PR 上讨论并记录维护者的**显式决策**（如 `[VOTE] Accepted` 或 `Rejected`），回填决定链接、理由与时间。设计的 Discussion/Accepted 是独立于 PR merged 和 CI succeeded 的状态；没有记录明确决定就不能标 Accepted。
5. 只有 Accepted 的设计才能成为后续多个实现 PR 的依据。每个实现 PR 链接相关 OFIP 并完成一个可操作用户故事、必要的反例和完整的实际验收路径。
6. 重大改变既有 Accepted 公共合同需要重新讨论或新 OFIP；拒绝的提案保留 PR 讨论，不作为有效产品规则发布。不要把每个实现 PR 的过程日志追加到 OFIP 中。

对现有 OFIP-0001 的阶段性批准见 [PR #3 决定记录](https://github.com/gitfortian/ontoforge/pull/3#issuecomment-6189658540)。当前只启动规范治理；CI 体系重构是**后续单独 PR**。

## 实现 PR 的质量与审查

- **一个 PR 一个可验证交付**：必须有清晰用户结果，不接受“先搭一批未被实际使用的空模块/接口/页面”。
- 先核对目前代码、直接调用方、已有测试与数据库事实，不能猜后端能力。严格区分文档方案、POC 与产品实现。
- 公共合同变更同步更新真正的 JSON Schema/TS/Java/SQL 测试，并检验旧版本或旧数据的兼容/拒绝行为。
- 已应用 Flyway 迁移不修改历史；使用前向迁移测试升级路径。不能使用 `repair`、清库或改 CI 来隐藏兼容问题。
- S0 未提供认证和权限；任何涉及真实数据、Actions、安全隔离的功能必须有独立 Gate，不可因原型展示或 Agent 推荐而放行。
- 实施 PR 提交前说明：动机、用户可见结果、受影响合同、真实执行的命令/CI、未执行或阻断的项目、Review base/head SHA、手动验收方式。
- 一旦修改 PR，必须检查新的 head SHA 是否还对应所报告的验证结果；以前一次通过替代新提交属于无效证据。
- 架构文档、研究、JavaDoc、用户文档各有主人。Routine 修复不必同步新建描述性 Markdown；用户能看到的行为变化应更新用户操作文档。
- AI 辅助内容仍由提交者负责审查、运行验证与报告，不因使用 AI 自动降低质量门禁。

## 本地开发和验证

开发入口以 [README](../README.md) 为准；Windows 11 原生 PostgreSQL 见 [启动与验收指南](../doc/implementation/S0-Windows11-本机PostgreSQL-启动与验收.md)。

```text
mvn -pl ontoforge-boot -am verify          # 需可连接的 PostgreSQL 和 DB_PASSWORD
cd ontoforge-ui && npm install && npm run build
python -m unittest discover -s doc/tests -p 'test_*.py' -v
python -m unittest discover -s doc/design/tests -p test_native_v02.py -v
```

Native 测试依赖 `jsonschema==4.25.1`，按需安装。以上是命令参考，**并不表示本 PR 已执行所有命令**。运行时/权限/浏览器/部署的独立验收必须另列。

## 文档权威和维护纪律

| 文档入口 | 只负责什么 |
| --- | --- |
| `doc/README.md` | 唯一有效文档索引、明确设计/研究/实现状态 |
| `doc/本体产品总体功能与技术架构设计_v1.3.md` | 当前**唯一架构基线候选**，不是宣称已生产签收 |
| `doc/design/` | Native DSL/AST 机器合同、兼容映射与可执行结构化验证 |
| `doc/proposals/` | 重大提案、选择、反对理由和签收链接 |
| `doc/research/` | 外部研究/试验结果，不得充当本项目生产事实 |
| `doc/implementation/` | 用户/开发者可执行的当前启动、运行、验收步骤 |
| Code / JavaDoc / TypeScript / Flyway / Tests | 现行真实行为与可验证的安全/兼容边界 |
| GitHub PR / Actions | 代码审查、实际测试结果、未测项目及版本证据 |

不得仅因为增加类、移动包或重命名组件，就创建另一套“最新架构”。避免两份相互矛盾的产品真相源。

## CI 与合并纪律

目前仓库的 S0、Native V0.2、架构文档和 PG 研究检查均为不同覆盖范围。**未运行 = NOT RUN，未选择 = NOT SELECTED，只有真正执行且成功才是 PASS。**

未来统一 Quality Gate 的范围选择应 unknown fail closed、绑定实际测试提交和报告；本阶段还没有强制实现这一门禁。GitHub Ruleset/Required Checks 设置应单独确认；不要通过仓库 YAML 假装保护已开启。

PR 模板见 [PULL_REQUEST_TEMPLATE](PULL_REQUEST_TEMPLATE.md)，应逐项如实填写适用项目，不适用的部分写 N/A 和理由。提交者需根据已知风险决定是否请维护者额外评审，不要自动绕过。
