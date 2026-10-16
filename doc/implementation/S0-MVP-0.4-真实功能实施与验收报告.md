# OntoForge S0-MVP-0.4｜多对象与 LinkType 真实功能实施及验收报告

> **实施 PR**：[PR #13（Stacked Draft）](https://github.com/gitfortian/ontoforge/pull/13)，base 是 [PR #11 的 0.3 开发分支](https://github.com/gitfortian/ontoforge/pull/11)，而不是 main。**0.3 尚未合并、Windows 11 原生 PostgreSQL 停启复验尚未执行、0.4 尚未发布**。  
> **产品方案和交互**：[PR #12（OFIP-0004 四轮设计）](https://github.com/gitfortian/ontoforge/pull/12)。DG-1 / TC-2 / UX-Low / UX-High 文档已提交，但正式 Accepted 记录尚未写入。  
> **报告性质**：当前为工程集成候选，不以某次 Docs CI、原型或某个后端函数的存在来宣称完整功能通过。

## 1. 30 秒看本次有什么真实变化

此前只能完整编辑一个对象类型，无法在真正的 React/Java/PostgreSQL 工作流中定义 LinkType 并将关系收入本地历史。0.4 将这部分纳入**真实代码候选**，目标演示为：

**打开一个模型包 → 定义生产批次、生产工单、销售订单三个对象 → 创建两条业务关系 → 完整保存/重开 → 服务端检查 → 冻结全部对象和关系的本地历史 → 修改关系基数或证据要求 → 比较风险 → 发现冲突可恢复**。

这是**关系类型定义**，不是生产批次实例数据、工单数据、LinkFact、ObjectSet 查询、正式发布、Agent 或可执行 Action。

## 2. 与整体 F01–F29 产品功能的关系

| 总账功能 | 此前可用能力 | 0.4 本 PR 的新实现候选 | 本轮必须拿到的独立真实证据 | 尚缺什么 |
| --- | --- | --- | --- | --- |
| F02 本体可视化建模 | 单个 ObjectType 的字段编辑 | 在原工作台切换多个对象和 LinkType，编辑来源/目标、基数与证据声明；**整包保存** | 真 React+Chromium 3 对象 2 关系、刷新重开 | 完整专业语义工作空间、跨包链接、生产可靠授权 |
| F03 结构与关系验证 | ObjectType/Property 基础校验 | 非空 LinkType 的结构安全 WIP、同包精确引用、重复身份、枚举基数、必填证据 | 真 PostgreSQL+HTTP+JUnit：VALID/INCOMPLETE/INVALID/422/409 | 广义规则系统和跨包/下游兼容 |
| F04 历史快照与变化风险 | 0.3 已编码本地只读 Snapshot/对象 Diff，尚未合并 | 冻结整个多对象+关系 Candidate；LinkType 新增/删除、端点、基数和证据要求变化的结构差异 | 真实 PG17 两份快照、JCS/SHA 复核、旧历史字节保持、HTTP Diff | **仍无审核、正式 Release、Activation、回滚** |
| F05 模型包结构 | 基础同包草稿 | 同包多个对象和关系共享 revision/CAS | 保存重开、未知响应重查、跨包拒绝 | 跨包依赖锁、分布式治理 |
| F28 语义质量 | 0.3 Type/Property 结构风险提醒 | LinkType 端点/基数潜在破坏提示，证据要求放宽触发治理复核 | JSON API 和 React 历史 Diff 对比 | 下游消费者依赖实际验证和影响预测 |

**成熟度计法**：功能代码已提交但若对应门禁未通过，仍是**实施候选**，不是“产品基础可用”；CI 真 PG/HTTP/Chromium 通过后可认为 0.4 关系子链路达到可验收候选，仍不能推断 F02/F03/F04/F05/F28 整个功能均完善，更不能声称生产可用。

## 3. 真正修改了哪些功能

1. **后端草稿合同** `DraftValidation`：支持非空 `linkTypes[]`，最多 200 项，属性白名单/文本/类型/布尔值安全校验。完整检查校验 LinkType 的 ID/API 唯一、同包对象端点、合法 cardinality、必填 evidenceRequired。未填写的安全 WIP 可保存但不能冻结，非法结构或伪造未知属性明确拒绝。
2. **完整 Native 候选**：全部 `objectTypes[]` + `linkTypes[]` 在一次 revision 中进入 Candidate，绝不将 `linkTypes` 固定清空；仍拒绝本期不支持的 imports、bindings、actions 等资产。
3. **历史与差异** `NativeSnapshotService`：在保留 0.3 JCS RFC8785、SHA-256、单 revision 幂等/不可变和只读完整性检查的基础上，允许完整的多对象关系定义冻结；差异按 LinkType 稳定 ID 对齐；降低证据要求提示治理复核。
4. **真实 React 工作台** `NativeModelWorkspace`：同一个模型包内编辑多对象和多关系，保留原有字段配置、主键、保存/验证/异常恢复/本地历史入口；不会只提交当前选中对象而丢掉其他模型资产。
5. **真实历史 React 展示**：`NativeSnapshotWorkspace` 对 LINK_TYPE 结构风险显示关系身份而不是空 ObjectType 名称。
6. **真实自动化验收**：复用 0.3 Java/PG17/HTTP 与 Chromium 运行环境，新增三个集成测试覆盖多对象+关系合法冻结、非法引用/重复/未知字段、仅重排关系不产生结构 Diff；新增两个真实 Chromium 故事走完全量 UI 保存→刷新→两版快照以及不完整关系 WIP。

## 4. 必须处理的阻断，不许偷换状态

| 门槛 | 当前计划 | 不允许的捷径 |
| --- | --- | --- |
| B1 后端真实 PostgreSQL/JUnit/API | 每次对准最终 SHA 检查 Required Quality 和 Real Browser E2E | 以纯 Java 单元测试或文档 CI 冒充真实持久化 |
| B2 React 实际浏览器 | 正常与异常故事通过，真正从 UI 创建两个 LinkType | 用 0.4 UX-High 内存 Mock 的 12/12 作为生产 UI 测试 |
| B3 快照历史一致性 | 原版只含单 ObjectType 的 0.3 旧数据继续完整可核验；不改旧 Flyway 和摘要 | 修改 V3、重算历史、静默裁剪关系 |
| B4 接口安全性 | 未支持定义、未知字段、非法关系证据输入必须拒绝 | 将 LinkType 当作实际业务外键或生产执行 |
| B5 PR #11 集成 | 0.4 在明确基于 0.3 已验证 Head 的 stacked branch 实施；合并 0.3 前 **0.4 不合并 main** | 将未合并 0.3 Snapshot 当成 main 上已经可用 |
| B6 产品批准与 Windows 实机 | 四轮 DG/TC/UX 正式签收与 Windows native PG 真停启/持久化证据独立记录 | 自审意见当作正式 Accepted、Linux CI 冒充 Windows |
| B7 企业生产化 | Workspaces/ACL/Review/Release/Activation 另立正式产品阶段 | 显示伪“已审批/已发布/生产就绪” |

## 5. 可供维护者验证的手动黄金故事（限隔离测试库）

- 启动真实 PostgreSQL + Spring Boot + React 环境，创建 `manufacturing.production` 的**隔离测试包**（避免重复 ID，测试应使用独立包）。
- 定义 `ProductionBatch`、`WorkOrder`、`CustomerOrder` 三种 ObjectType，每种对象都有主键和属性；同包新建两条 LinkType，来源与目标从已有对象中选。
- 保存后刷新页面，确保三个对象、两条关系均真实来自后端；检查应为 `VALID`；冻结本地历史并看见完整 Candidate。
- 修改「生产批次归属工单」的基数，或把 `evidenceRequired` 从 true 改 false；重新保存→检查→冻结→历史比较应出现不同类别的结构风险；旧版不可变，摘要不变。
- 删除被引用的工单对象，保留 LinkType 的悬空引用；保存 WIP 后检查 `INVALID`，修复前拒绝冻结；发生两个标签页 revision 冲突时不能覆盖原编辑。

**当前为开发验收清单，不表示上述步骤已由 Windows 本机实测。**

## 6. 本轮交付边界与下一步

这是依赖 #11 的**独立真实功能 PR**，不是另一个设计 PR，也不拆成几个只有按钮/脚本的零碎功能；完成标准是整体真实链路都通过，所有例外有可运行证据并通过维护者评审后才能解除 Draft。

**当前总体结论**：0.4 已转向实现候选，面向 F02/F03/F04/F05/F28 的关系子链路在逐步落实。P0 的可信授权/正式发布、P1 工业事实投影/查询、P2 Agent 和 P3 治理 Action 均不在此阶段宣称完成。
