# M8.1 · LinkBinding 与一跳关联查询 UX-Low 评审说明

**状态：UX-Low Review Candidate，尚未产品签收。** 日期：2026-10-19。

- 设计基线：[R1 PR #77](https://github.com/gitfortian/ontoforge/pull/77)、[R2 PR #79](https://github.com/gitfortian/ontoforge/pull/79) 已产品批准且合并。
- 治理门禁：[OFIP-0010](../proposals/OFIP-0010-M8-LinkFact与有界遍历.md) 仍是 Discussion / Decision Pending / Implementation Not Started。UX-High 未开始。
- [打开交互原型](../prototypes/m8-1-linkbinding-lowfi.html)；[真实 Chromium 自动测试](../prototypes/tests/test_m8_1_linkbinding_lowfi_browser.py)；[专用 CI](../../.github/workflows/m8-1-linkbinding-lowfi-browser.yml)。
- 所有版本、来源、错误及快照均是本地合成夹具。原型不连接真实 PG、不执行 JWT 授权、不写入任何真实 API。

## 1. 用户故事与页面入口

采用 OntoForge 现有的“本体建模 → 关系类型”与“对象集合查询”两种自然入口，不增加孤立的“专业图谱工具”主菜单。一个工作台分五个明确阶段：

1. **配置来源**：选择同 LOCAL_SNAPSHOT 下两端已冻结 M5 Binding revision、FK 所在端和白名单列；被引用的端点主键只读显示，禁止输入任意 SQL。
2. **只读核验**：显示 FULL、VALID、VALID_WITH_ORPHANS、FAILED；超预算、版本/类型不合格、基数冲突、无权或来源不可用不得保存。
3. **保存版本**：expectedRevision CAS、不覆盖历史、保存响应丢失时幂等查询；保存动作与查询动作严格分离。
4. **OUT / IN 查询**：明确根对象及方向；OUT EQ-001 查询 LOT-11、LOT-12，IN LOT-11 反查 EQ-001，逻辑 LinkFact 原始 SOURCE → TARGET 不翻转；单次结果跨页保持 snapshot。
5. **证据与恢复**：保留双端 Binding、LinkBinding、SourceReadRef、quality、executionId、snapshot、freshness UNKNOWN；撤权或漂移后隐藏旧对象载荷，恢复也必须重新执行。

首版没有关系图画布。对象列表、版本闭包和来源证据优先于可视化装饰。

## 2. 关键合同 / 异常恢复映射

| 体验场景 | 必须呈现的结果 |
| --- | --- |
| 无 LinkBinding 执行查询 | 明确提示先配置核验保存，不虚构成功空集 |
| FK OWNER 错端、端点不在同快照、列缺失或类型冲突 | 422 INVALID_LINK_MAPPING，返回配置重选 |
| 完整核验成功 | VALID 或 VALID_WITH_ORPHANS；预演不自动保存 |
| 验证超过 10k 或来源撤权 | 429/503 FAILED；抽样 SAMPLE/UNKNOWN_TOTAL 不能保存 |
| ONE_TO_ONE + TARGET FK 冲突 | 409 LINK_CARDINALITY_CONFLICT；不能按 ONE_TO_MANY 合法夹具误判 |
| 真实空关系 | LOT-13 FK NULL 或 EQ-002 无批次，SUCCESS / COMPLETE / 0 |
| 全局孤儿 | LOT-14 在 BindingValidation 报告；EQ-001 OUT 仍 COMPLETE |
| 本次根孤儿 | LOT-14 IN 返回 PARTIAL / KNOWN_SUBSET，只反映本次作用域 |
| 保存 CAS 409 或响应未知 | 保留编辑；响应未知提供幂等键状态查询 |
| 后续页撤权或 schema drift | 旧对象与 LinkFact 隐藏；保留错误证据；恢复后原 cursor 不复活 |
| TTL / Boot 重启 / 来源超时 | 410 / 503 与继续操作提示，不显示旧成功结果 |
| 未保存返回与窄屏 | 明确离开确认；字段与步骤响应式堆叠 |

## 3. 产品交互审查待签收项目

| 编号 | 待评审问题 | 状态 |
| --- | --- | --- |
| UX1 | 现有关系类型页签进入配置，是否自然且能返回来源 | 待审 |
| UX2 | 双端版本、FK OWNER、映射列与目标 PK 是否降低首次判断负担 | 待审 |
| UX3 | 核验 → 保存 → 查询三步是否清晰，按钮不过多 | 待审 |
| UX4 | VALID_WITH_ORPHANS 与相关 root PARTIAL 是否易理解 | 待审 |
| UX5 | SUCCESS/EMPTY、PARTIAL、FAILED 三种结果反馈是否准确 | 待审 |
| UX6 | CAS、保存未知、来源漂移、撤权、TTL 的恢复是否完整 | 待审 |
| UX7 | OUT/IN 原始 LinkFact 方向、翻页与双端证据是否可理解 | 待审 |
| UX8 | 未保存离开与 375px 小屏信息层级是否合理 | 待审 |

## 4. 自动化、审查及研发边界

本原型安排 **16 项真实 Chromium 交互检查**，涵盖入口、未绑定状态、来源错误、FULL 与抽样区别、可确认孤儿、基数冲突、CAS/未知保存、OUT/IN 一跳、稳定快照分页、空结果、撤权/漂移、超时、未保存导航和移动端横向溢出。测试通过后仅说明前端原型状态表达成立，不代表真实 PG17、API、JWT 或版本持久化可用。

**UX-Low 签收门槛**：专用 Chromium job PASS，独立交互 Review 完成，产品所有者明确签收。未签收时 PR 必须保持 Draft。签收后才允许制作全新 M8.1 UX-High；UX-High 再次独立签收及 OFIP-0010 正式维护者决策通过之前，不得开发生产代码。

## 5. 功能进度（不混淆阶段）

| 工作 | 当前状态 |
| --- | --- |
| M8 R1 与 R2 领域/API 合同 | 已产品签收并合并 |
| M8.1 UX-Low 原型及自动化 | 本 PR 提交设计评审，待验收签收 |
| M8.1 UX-High | NOT STARTED |
| OFIP-0010 | Decision Pending |
| M8.1 Java / React / Flyway / LinkFact 运行时 | NOT STARTED |
| M8.1 PG17 + API + Chromium 生产黄金故事 | NOT RUN |
| M8.2 桥表与 M8.3 两跳 | 后续独立阶段 |
| 发布 | NOT RELEASED |

## 6. 第一轮独立交互复核与修订

**已在原型层修正：未保存编辑丢弃后，字段必须恢复到当前实际已保存的 LinkBinding revision，而不是保留被放弃的草稿值。** 否则返回配置页面时会继续展示未经保存的历史 Binding 选择，让用户误以为它是当前有效版本。

新增专用浏览器负例：先保存版本，再修改终点 Binding revision，确认离开并丢弃，重新打开配置，断言终点恢复为真正已保存的 revision；未保存标记应消失。该检查不替代真实服务端 CAS/idempotency。

保留待 UX-High 再细化：字段帮助提示、当前保存修订与工作草稿的视觉隔离、来源结构漂移引导、真实响应延迟与竞态保护、多对象根集合筛选（M8.1 UX-Low 首轮仅演示单 ObjectRef 根），以及验证范围超过预算时清晰的拒绝和缩小来源建议。
