# OFIP-XXXX: 一句话说明设计主题

**Status:** Discussion

**Discussion:** 待创建的 `[DISCUSS][OFIP-XXXX]` 提案 PR 链接

**Decision:** Pending（只有维护者显式决策后，填写 `[VOTE]`、日期和 PR comment/review permalink）

**Implementation:** Not Started（后续实现 PR 逐项链接；Acceptance 不是 Implementation）

**Release:** Not Released / N/A

**Related:** [V1.3 总体架构候选](../doc/本体产品总体功能与技术架构设计_v1.3.md) · [Native V0.2 机器合同候选](../doc/design/核心本体元模型与Native-DSL-AST契约_v0.2.md) · 相关 Issue/OFIP

> **复制到 `doc/proposals/OFIP-XXXX-short-title.md`，并把上面相关链接改成真实的相对路径**（从 doc/proposals/ 到架构文档只需 `../`）。本文件是 `.github/` 中的模板，链接仅为模板上下文，不能直接把它移动后不校验。
>
> 仅针对重大领域设计、公共契约/持久化/兼容性或高风险操作；普通修复不需要 OFIP。提案 PR **不混入生产代码或永久 CI 改造**。状态改变需显式决定，不能凭绿色 CI 或 PR 合并推断。

## Motivation

- **用户是谁、遇到什么问题**：写出具体用户故事，而非技术词汇清单。
- **当前证据与缺口**：实际代码、Schema、API、持久化与真实测试；将已实现/研究 POC/计划严格区分。
- **成功定义**：一条可以在产品中真实操作的黄金故事；必要的不可用/失败/无权限/来源变化路径。
- **非目标**：本次不做什么，避免默认把整个未来路线一次性交付。

## Public Interfaces

说明本设计新增、修改、弃用的真实消费者合同；没有变化必须写 **None**。按需覆盖：

- Native ObjectType、Property、LinkType、Package、标识、版本与 AST/Schema
- REST/SDK/MCP、配置字段、错误码、前端可见状态及行为
- PostgreSQL/Flyway 持久格式、事件、快照、查询/Result Evidence
- 来源身份/权威、ObjectSet 作用域、权限与撤权
- Function/Decision/Action/Policy、审批、幂等、UNKNOWN/回执与业务结果核验

标明谁拥有状态与权限判断，不允许客户端选择 `AuthorizationContext` 冒充授权。

## Proposed Changes

- **产品流程和操作边界**：入口、步骤、状态、用户决策、成功/失败后能继续做什么。
- **领域责任**：数据源、Native 模型、Binding、Object Runtime、Decision、Action 的边界。
- **版本和权威**：Draft/Validate/Review/Release/Activation、来源水位和语义快照如何关联。
- **实施切片**：第一 PR 交付什么可用结果，后续需要补什么；不要为未来需求创建空模块或假按钮。
- **安全和一致性**：明确不确定/未授权/过期/失败语义，禁止推理结论直接产生副作用。

若采用图或页面草图，必须能对应可执行或可审核的真实行为，避免把产品原型当作已实现的支持范围。

## Compatibility, Deprecation, and Migration Plan

- 已存模型包、对象 ID、版本/摘要、旧 AST/JSON/Schema/DB 数据将如何变化？
- 使用哪种前向 Flyway 迁移；已运行的迁移不可修改，是否需要兼容读取/拒绝与导出？
- 历史草稿、已发布版本及已存在实例是否保持可查询；无法兼容时如何安全阻断？
- 回滚针对何种状态；外部写入和已经发生的副作用不可被本地回滚抹除。
- 跨来源身份/权限变更如何影响缓存、投影和下游引用？
- 不涉及的项目请写 None，并简述理由。

## Test Plan

以**实施验收要求**描述，不能宣称是提案 PR 已执行的测试。至少列明：

| 类型 | 成功场景 | 必需负例/风险边界 | 真实验收 |
| --- | --- | --- | --- |
| 合同/类型 | 正常 Schema/AST/配置 | 错字段、重复 ID、非法类型/版本/引用 | 确切的测试文件与命令 |
| 持久化（适用时） | 保存→读取→重启数据仍在 | 冲突、升级失败、历史不兼容 | PostgreSQL/Flyway 集成测试 |
| 安全/来源（适用时） | 授权且来源完整 | 无权、来源撤权、过期、未知/部分结果 | 拒绝测试与实际证据 |
| UI/用户故事（适用时） | 浏览器从入口到完成 | 校验错误、失败恢复、重新打开 | 真实浏览器步骤/E2E |
| Action（适用时） | 授权预演→审批→回执→结果 | 重复、超时 UNKNOWN、版本漂移、未核验 | 独立运行/安全负例 |

分别标注 `PASS/FAIL/NOT SELECTED/NOT RUN` 与受测 commit SHA。哪怕通过了静态检查，也不得把实际浏览器/工业现场验证写为成功。

## Rejected Alternatives

列出认真考虑过的路径，以及放弃它们的事实依据、约束、成本、安全风险。至少写出关键取舍；不能只有“没有”。

---

**Decision Gate:** 讨论中的 OFIP 不作为实现授权；请维护者在 Proposal PR 上明确记录 `[VOTE] Accepted` / `Rejected`，并在此更新 Status/Decision。重大修改已接受合同必须重新审议。
