# OntoForge（砺元）｜V1.0 三级功能说明书正式产品评审报告

> **评审日期**：2026-10-19；**评审对象**：[PR #78](https://github.com/gitfortian/ontoforge/pull/78) `doc/implementation/OntoForge-产品功能说明书-三级功能-V1.0.md`  
> **审查基准**：2026-10-19 `main=c9de0336a022ed33bca08f39029b5ac58d3265e3`，包括 M7 已合并 [#75](https://github.com/gitfortian/ontoforge/pull/75)；只读 GitHub 代码/合同/Issue/文档静态审查，不是本机真实运行 UAT。  
> **角色**：受托独立产品功能审核意见；不替代产品所有者正式签收、GitHub maintainer 授权、代码审查或企业环境验收。  
> **结论：`REVISE / NOT SIGNED OFF`（暂不签收全产品完整三级功能目录）**。原目录可作为当前 AS-IS 功能盘点底稿，但全局功能覆盖、可测验收合同和现行文档可信性不足，不应直接标记正式产品基线通过。

## 1. 评审问题和范围

**目标**：检查实际研发成果是否完整体现为可理解、可操作、可验收的产品功能说明；确保未来 F01–F29 与 M8–M14 没有因“只写已开发模块”而漏掉的产品能力。**不是**直接实施 M8、替产品负责人签收、上线或修改安全规则。

评审采用五个维度：

1. **全局定位与范围**：V1.3 八域/29 个 F 编号与当前目录是否双向覆盖；本机 MVP、Core 与 Extended 分离。
2. **用户故事与业务正确性**：从新建模型到冻结、映射、对象读取、集合查询及来源证据，跨模块术语、前后置依赖和用户下一步是否可理解。
3. **功能真实成熟度**：代码已合并/真实 UI / API / PG / 隔离自动测试/Windows 人工 UAT/正式 Release 严格分层。
4. **专业功能与安全语义**：ObjectType≠ObjectRef；LinkType≠LinkFact；Local Snapshot≠Release；M7 游标结果快照≠源历史；NULL/MISSING/UNKNOWN/部分结果不冒充成功。
5. **研发可执行性**：每三级点能否独立定义输入、校验规则、错误、权限、证据、测试、验收门禁。

## 2. 评审结果总览

| 检查维度 | 结论 | 主要证据与限定 |
|---|---|---|
| 当前 M0–M7 实施枚举 | **基本覆盖，但应补合同边界** | 模型草稿/快照、M4 类型、M5 Binding、M6 Object、M7 ObjectSet 实际 Java/React 入口可对照，旧 M2/M3 另有专用质量追溯 |
| V1.3 全产品范围完整 | **不通过** | 125 行只列 12 个实现视角模块，缺 8 项 V1.3 功能的独立三级分解（F10/F13/F14/F17/F24/F26/F27/F29） |
| 一级/二级分类权威一致性 | **不通过** | V1.3 的 8 大功能域与本文 12 个模块未映射；F12 是把所有未实施能力放进“后续”抽屉 |
| 三级分解可直接验收 | **不通过** | 多数行仅有一句描述及源文件链接，缺用户触发、输出、负例、角色/权限和测试用例 |
| 实施状态真实性 | **有条件通过** | M5–M7 代码已合并，文档使用“限定环境”合理；但 96 项覆盖不等于 96 项已独立测过或产品完成率 |
| ObjectSet / 关系语义 | **通过（严格限定）** | M7 Typed AST、ObjectRef 去重、COUNT_DISTINCT/SUM、游标重核验均有服务端实现；M8 LinkFact/Traversal 未实现 |
| 模型/绑定/发布身份 | **通过（严格限定）** | 局部草稿/快照/绑定 revision 真实存在；正式 SchemaRelease/Activation/业务写入尚未完成 |
| 用户工作流与可用性 | **部分通过** | 各页面有深链接，但没有证明自定义领域完整连续任务、失败恢复和上下文保存的正式用户验收 |
| 安全与生产状态 | **谨慎通过** | mvp-local 对本地运行限制明确；B0 JWT/OIDC 为部分能力、生产 IAM 未验收，不能扩展到多租户/对象级 Policy |
| 文档权威及状态一致 | **待修** | README/doc/README、2026-10-17 AS-IS、M5–M7 Issue 仍有历史阶段句子，需单一状态来源明确优先级 |

## 3. P1 级整改项：阻断“全产品三级功能说明书”签收

### P1-01｜全产品能力覆盖存在实质缺项

**发现**：V1.3 定义了八个产品功能域、F01–F29。当前 12 模块中，F10 状态与事件、F13 文档知识与证据、F14 规则与推理、F17 Capability Registry、F24 业务术语与共识、F26 本体对齐与融合、F27 知识图谱工程、F29 AI 辅助建模与反馈没有独立三级条目；剩余多个能力只以“规划中”的一行代替整套专业功能。

**为何重要**：后续 M8–M14 仅能解释近期里程碑，不能代表整个 OntoForge 产品范围。尤其“事件时间/状态”、“知识证据”、“协同决策”、“能力注册”等能力会在技术架构中出现却没有产品责任和用户故事，易形成后台堆砌。

**整改**：以 V1.3 8 域为产品分类权威，建立 29 个 F → 二级能力组 → 可验收三级能力映射；补全 8 个缺项，长远非 MVP 的仍标“未规划到版本/未实现”，不得凭不在 M8–M14 而删除。当前文档补充[全量映射](./OntoForge-产品功能说明书-三级功能-V1.0.md#8-正式产品评审补充v13-f01f29-覆盖矩阵)，但映射不是完成三级拆分。

**验收**：V1.3 的 29 个 F 各至少有一个明确可追溯的产品功能组与三级功能；从任意目录 L3 行能回溯一个唯一主归属 F 编号；未交付的内容有独立状态。

### P1-02｜“范围排除语句”混入功能点，形成虚假覆盖与重复计数

**发现**：例如 `OF-F06.01.03`“不开放任意连接”、`OF-F07.02.06`“没有永久 Object Store”、`OF-F08.03.05`“M7 不执行 LinkFact”、`OF-F09.03.04`“本模块不取代 M5–M8”、`OF-F11.01.04`“生产数据未交付”。它们是重要**范围边界**，不都是独立可验收的正向用户功能；`OF-F11.02.04` Windows UAT 和 `OF-F12.03.03` 多领域验收属于质量活动，不应计作与对象读取等价的产品业务能力。

**影响**：125 个三级“功能点”中掺杂边界、质量门、重复规划，容易把“未做 X”的陈述错误统计为产品功能，并因重复记录 LinkFact/Connector 抬高“规划条数”。

**整改**：分成 **产品功能树（唯一主归属）/ 能力边界清单 / 非功能与质量门禁 / 研发里程碑映射** 四层，原编号保留迁移表避免引用失效。禁止因删重而误判缺能力。

**验收**：每个业务 L3 均有明确用户行为/可观察输出或服务于核心行为的独立产品合同；边界与 CI/UAT 不计入产品 L3 业务数。

### P1-03｜125 行不是完整的标准功能规格与测试合同

**发现**：当前每行只有“名称、行为一句话、单个证据链接、粗状态”。不足以做 PRD/功能说明验收；大多数没有用户角色、场景、前置、输入、输出、拒绝条件、权限、数据范围、来源/时间/版本、不确定结果、刷新/重试、前后端证据及明确测试编号。

**典型样例**：`OF-F08.02.05` SUM 必须按去重 ObjectRef 聚合，若命中同一 ObjectRef 的矛盾值或 NULL/MISSING 应拒绝；不仅需要“支持 SUM”的一句话。 `OF-F06.02.05` 校验必须区别 schema 不匹配、身份重复、部分样本无法证明全量数据有效。

**整改**：两层文档——主目录维持简洁，一线业务关键 L3 另建功能合同（输入/结果/异常/权限/证据/测试编号）。可将 M5、M6、M7、M8 优先建设为标准示范模板。

**验收**：每个 Core MVP 用户可操作功能都可直接生成正例、负例、异常恢复、权限/漂移和版本竞争用例，无需凭开发者口头猜测。

### P1-04｜研发视角模块组织不等于完整用户任务体系

**发现**：侧边栏分别平铺 Native 建模、质量追溯、M5 Binding、M6 对象读取、M7 ObjectSet；代码 `main.tsx` 仍有禁用的“通用数据连接与 ObjectSet”规划项，与已存在的 M7 导航语义冲突。M4 类型页能带参数进入绑定，绑定又能跳到对象页，**但有深链接 ≠ 已完成连续业务向导**。

**新增代码核查（M7 前端查询意图）**：[ObjectSetWorkspace.tsx](../../ontoforge-ui/src/features/analysis/objectsets/ObjectSetWorkspace.tsx) 的 `buildSteps()` 使用 `filters.filter(f => f.operator==='IS_NULL' || f.value.trim()!=='')`；已在界面添加但尚未填写值的筛选条件会**被静默丢弃**，导致用户以为带筛选执行，实际发出更宽的查询。该行为需明确拒绝执行或在提交前要求补齐，不能将未填写条件直接当作无条件查询。属于正确性/误解风险，建议在 M7 后续修复优先处理，并补 Chromium 负例。

**整改**：把一级产品信息架构改回 V1.3“工作台、本体设计、连接与映射、业务对象、查询与分析、决策与行动、语义协同、平台管理”或评审后的同等级任务组织。近期 M11 的统一 Studio 不应等于重复造一套页面；先定义当前 M4–M7 的上下文保留/返回/错误接续验收矩阵，优先修复 M7 未完成筛选静默忽略。

**验收**：从自定义 Supplier/采购领域新建模型到筛选结果，对用户只展示每一步必需的下一步行动；无需猜“应该进入 M6 还是 M7”，包、快照、Binding、类型身份和工作上下文不丢失。

### P1-05｜实现、自动测试、人工验收、发布四种状态混用风险

**发现**：96 行标“已实现·限定环境”，若用户把这一数字理解为商业可用，容易误导。M7 已合并 #75，`main` 有 Java/React，但是否全部功能经过同一 SHA 的真实 UAT 与企业安全验收是另一个问题。B0 的 API/角色授权已存在但无真正企业 IAM 管理；`mvp-local` 是明示弱安全本机模式。

**整改**：每条功能至少并列记录 `implementationStatus`、`evidenceStatus`、`userAcceptanceStatus`、`releaseStatus` 四轴，产物视图可显示一句综合状态，但不能只凭单个服务类链接判断测试 PASS。引用实际 CI job URL 与测试名，不复制粘贴“所有门禁通过”。

**验收**：代码合并、测试通过、Windows 用户机器签收、商业 Release 之间永不自动推导。主目录“96 已实现”只能解释为“按代码存在核查的受限子项”，不能计算完成率。

### P1-06｜历史实施文档和 Issue 对当前事实表达不一致

**发现**：`doc/README.md` 的旧状态仍写 M5 Draft/M6 Decision Pending/M7 Discussion；M7 Issue #46 末尾仍写“Implementation Not Started”，但 #75 已合并主线。本次 PR 插入的“当前入口优先于历史记录”缓解了阅读顺序，**没有消除机器和读者检索到相反状态**的问题。

**整改**：保留历史叙述但必须添加日期、状态废止标签；每条 Issue 维护当前状态区，更新反向链接；只维护一份可机读的最新功能状态矩阵（可以是 Markdown 表，非必要不新建 JSON Schema）。不得覆盖已有正式审查投票记录。

**验收**：从 README、doc/README、功能说明书、Issue #44–#53 任意入口都能看到一致的最新实现结论和当前未发布事实。

### P1-07｜“整个产品”的核心非查询价值链尚未产品化分解

**发现**：V1.3 强调 `Rule → Function/Decision → Policy → Action → Outcome`、分层领域 Pack 和专业语义协作。目录却将 M12 Action 和 M13 Agent 各合为一项，Rule/Function、可解释决策、Capability Registry、业务状态/事件、知识引用没有独立功能范围。仅 M8–M14 的 issue 并不足以支撑产品完整价值蓝图。

**整改**：建立产品全景 TO-BE，不表示 MVP 要立刻开发。至少明确规则定义与验证、确定性函数/决策、只读模拟/解释、可信 Policy/审批、Action 计划/执行/回执/未知态/Outcome、Agent 能力发现/查询/引用及人机反馈链，并注明长期依赖和准入门槛。

**验收**：即使不进入当前 MVP，也能给出清晰分层、用户价值、接口合同和为何推迟的依据。

## 4. P2 级专业优化项

| 编号 | 关注点 | 核实后的问题与建议 |
|---|---|---|
| P2-01 | 建模表达及 UI/API 一致性 | F02“可视化本体建模”的当前成果是表单编辑，尚无专业关系图；而且服务端 DraftValidation 允许 UUID 与 UUID 主键，但前端 NativeModelWorkspace 只提供六种非 UUID 属性类型、仅 STRING/INTEGER 主键选择。需修补前端表单、错误提示与跨层合同测试，不能声称 UUID 已可在界面配置 |
| P2-02 | 模型生命周期 | Snapshot/JCS/Diff 已实现，但兼容性评估、消费者依赖锁、发布审批、Activation/回滚四者生命周期需要进一步拆分；M7 query versionLock 不是 M10 正式发行或跨消费者兼容判断 |
| P2-03 | 数据映射语义 | 现有 Binding 仅 `AUTHORITATIVE` + 单注册 PG；后续必须分别设计链接映射、事件映射、来源权威冲突、跨源主键审定、映射失效修复，不宜笼统称为“连接器扩展” |
| P2-04 | 对象值精度 | M6 `PRESENT/NULL/MISSING` 不能和 INVALID/CONFLICT 混用；身份域和版本 provenance 必须在单对象、ObjectSet 与 Agent 返回中保持一致 |
| P2-05 | ObjectSet 语义 | M7 FILTER 当前是受限 **AND** 条件组合，不存在通用 OR/NOT 布尔组合；COUNT_DISTINCT 按 ObjectRef 而非物理行；SUM NULL/MISSING 不强行置零 |
| P2-06 | 结果语义 | M7 resultSnapshot 是短 TTL 进程内分页载荷，分页时仍会重核验授权和来源 Schema；不等于历史数据快照、指数级索引或多次执行间的结果一致性证明 |
| P2-07 | 制造质量用例 | M2/M3 的专用两跳及对象浏览必须继续作为回归样本，但长期不应形成与 M8 通用 LinkBinding 并列的第二套产品执行体系；制定替换/保留边界 |
| P2-08 | 工业领域模板 | 单一内置 `manufacturing.quality` 和脱敏 CSV 是快速演示材料，不等于可复用领域包：需独立提出版本、依赖、安装、裁剪、升级与租户扩展的功能点 |
| P2-09 | 支撑性工程项 | `Flyway`、CI、Windows UAT、质量门禁是重要交付治理证据；放进产品能力树附录而非当成一线业务功能同权重计数 |
| P2-10 | 状态/编号治理 | 当前 `OF-Fxx.xx.xx` 编码已与老 F01–F29 区分，是改进；但必须有跨历史编号/Issue/PR 的一对多映射和改号记录 |
| P2-11 | 安全路线 | B0 已合并 OIDC/JWT，未来优先复用 yak-ops HttpSession；复用决定不意味着已迁移代码或可以绕过撤权、来源查询的服务端 PEP |
| P2-12 | 页面视觉一致性 | 当前代码能证实导航和业务交互入口，**未做浏览器截图/人工实测**，不能把“交互体验达商业标准”写入正式签收；M11 仍要走低保真→高保真→实现门禁 |

## 5. 代码与产品事实抽查

| 核对对象 | 当前代码级事实 | 对产品评审的约束 |
|---|---|---|
| Native 建模与关系 | [NativeModelWorkspace](../../ontoforge-ui/src/features/design/modeling/NativeModelWorkspace.tsx) 支持删除对象时保留悬空 Link 引用，由校验阻止冻结；[DraftValidation](../../ontoforge-core/src/main/java/io/ontoforge/core/compiler/DraftValidation.java) 服务端允许 `UUID` 与 UUID 主键，但 NativeModelWorkspace 的属性下拉只暴露六类（无 UUID），主键候选逻辑也仅放行 STRING/INTEGER | 产品合同与表单能力存在落差：不能把后台允许 UUID 写成用户已可配置 UUID；后续应补前端 UI 与跨层用例 |
| 本地快照 | [NativeSnapshotService](../../ontoforge-business/src/main/java/io/ontoforge/business/model/NativeSnapshotService.java) 提供真实事务、不可变快照、摘要和 Diff | 明确非 SchemaRelease |
| M5 Binding | [DataBindingController](../../ontoforge-boot/src/main/java/io/ontoforge/boot/controller/DataBindingController.java) 具备 source tables、list、preview、validate、create、update、revisions、detail；[DataBindingService](../../ontoforge-business/src/main/java/io/ontoforge/business/connection/DataBindingService.java) 限受控只读 PG | 可计为主线已实现的纵向有限闭环，不计为企业连接器 |
| M6 Object | [ObjectRuntimeController](../../ontoforge-boot/src/main/java/io/ontoforge/boot/controller/ObjectRuntimeController.java) 有 `POST /api/runtime/objects/read`，返回单次读取证据和类型化值 | 不存在全量对象写存储/多源统一身份 |
| M7 ObjectSet | [ObjectSetQueryController](../../ontoforge-boot/src/main/java/io/ontoforge/boot/controller/ObjectSetQueryController.java) 有 `query/pages`；[ObjectSetQueryService](../../ontoforge-business/src/main/java/io/ontoforge/business/query/ObjectSetQueryService.java) 对 FILTER/SORT/PROJECT/PAGE/AGGREGATE、TTL/预算/凭据复核设显式约束 | 不把通用 Link Traverse 或永久 Index 记为已开发；短期查询完成不等于生产规模可用 |
| 固定质量追溯 | [QualityMvpController](../../ontoforge-boot/src/main/java/io/ontoforge/boot/controller/QualityMvpController.java) 实现 `trace`、`trace-set`、`object-set` | 属于固定模型+限定本机数据子域，不等于 M8 通用关联 |
| 当前导航 | [main.tsx](../../ontoforge-ui/src/app/App.tsx) 既有 M7 可点击菜单，同时保留禁用“通用数据连接与 ObjectSet”规划项 | 同一概念的已实现/规划冲突应纳入 M11 UI 修订 |
| 安全边界 | [LocalMvpSecurityConfig](../../ontoforge-boot/src/main/java/io/ontoforge/boot/security/LocalMvpSecurityConfig.java) 显式限制本地地址/数据库；[B0AuthorizationService](../../ontoforge-platform/src/main/java/io/ontoforge/platform/identity/B0AuthorizationService.java) 实施包授权 | 企业 IAM / 全域行列策略 / 审计未验收 |

## 6. 用户故事黄金验收矩阵（建议正式签收前执行）

**U01 自定义领域从零到多对象查询（Core P0/P1 必过）**：使用全新 `supplier`（非固定质量领域）创建包→创建 Supplier ObjectType 和必填/可检索属性→保存 WIP→验证 VALID→冻结快照→类型列表→选择合法来源表→主键/属性映射→预览/校验→保存 Binding→读取单对象→设置 EQ/PREFIX/IN、SORT、PROJECT、PAGE、COUNT_DISTINCT/SUM→查看来源版本和每个结果。**验收**：每一步显示正确对象/版本/来源，下游不误用旧 draft 或 binding；页间结果固定；新领域无需修改后端 switch。

**U02 改模、改绑与版本竞争**：两窗口并发保存触发 409→保留未提交编辑→核对服务器最新修订；变更 ObjectType/Binding 后旧 VALID 失效、旧 cursor 不得默默查询新版本；重新校验后创建新快照和 Binding revision。**验收**：没有跨版本混用、无未授权自动合并、没有伪造成功。

**U03 来源改变与权限撤销**：保存 Binding → 来源列改名/类型变化、SELECT 撤销、包权限撤销→分别尝试 M6 Read 和 M7 新查询/后续页。**验收**：漂移/撤权/不可用/结果过期状态区分；不返回部分精确聚合；权限恢复不自动复活有争议旧结果。

**U04 值与去重精度**：同一 ObjectRef 在来源中重复但内容一致时去重；冲突时禁止任取行；NULL/MISSING 与 0 分离；金额重复关系路径不能多算；decimal 精度无 JS Number 丢失。**验收**：COUNT_DISTINCT/SUM 真实精确或明确失败，能定位冲突原因。

**U05 制造专用旧功能防回归**：批次多条影响路径、断链计数、质量状态冲突、M2/M3 单类对象筛选分页继续可用。**验收**：M8 接入后仍不误报为通用 LinkFact，原功能不回退。

**U06 完整 UI/支持性验收**：标准屏/窄屏、跨模块跳转、返回来源、页面刷新、未保存阻拦、重复点击、晚到请求、错误证据显示。**验收**：由真正浏览器、截图和用户操作记录支持；测试环境为隔离数据库，用户 Windows 11 人工 UAT 单独留痕。

**U07 M8 准入**（未实现，不计当前通过）：用户自建两 ObjectType + LinkType，配置外键来源，先验证双向一跳及孤儿/基数，再桥表和两跳分切片。**验收**：均需 OFIP 决策、UX-Low/UX-High 签收与真实业务正反例后才允许生产实现。

## 7. 建议修订步骤与明确签收门槛

| 次序 | 工作项 | 依赖 | 正式 Review Gate |
|---|---|---|---|
| A | 保留现有 125 行代码梳理作为 AS-IS 底稿，同时注明“尚未全量签收” | 本轮完成 | 状态清晰，历史与现行区分 |
| B | 8 域 F01–F29 对齐；补足缺失的 8 个功能和 Rule/Decision/Policy/Action/Agent 等长期第三级 | 产品方案评审 | 29/29 可双向映射，无“后续功能大杂烩” |
| C | 排除伪功能、归并重复、分离质量门与非目标；每 L3 唯一主归属 | B | 产品功能数量可审计，不靠边界描述充数 |
| D | 按优先级补关键功能合同、验收用例和真实 CI/浏览器证据 | B/C | 核心故事 U01–U06 具有可重复证据，Windows 用户 UAT 明确 NOT RUN 或已签收 |
| E | README/doc/README/旧 AS-IS/Issue #44–#53 状态同步，不重写历史 | B | 读者从任一入口查到同一最新状态 |
| F | 第二次正式产品审查 | A–E | **产品所有者明确签收**，审查通过后才考虑把 PR #78 转为 Ready for review/合并 |

**当前建议**：PR #78 保持 Draft；不应批准“整个产品完整三级目录”签收。以上 P1 是文档/产品规划整改，不代表已有 M5–M7 代码必须回滚。M8 标准评审可按既有许可并行进行，不能被本次“目录未签收”解释为允许跳过其独立决策与原型流程。

## 8. CI/核验记录与限制

- 已直接读取 `main` 上的 V1.3、Native/Draft/Snapshot、Runtime Type、M5 Binding、M6 Object、M7 Query、Quality MVP、Local Security、B0 Authorization、前端主导航及相关工作台。
- 已核对 [PR #75](https://github.com/gitfortian/ontoforge/pull/75) 已合并，M8 [Issue #47](https://github.com/gitfortian/ontoforge/issues/47) 仍属未实施设计范围；[PR #78](https://github.com/gitfortian/ontoforge/pull/78) 是纯文档 Draft。
- PR #78 首次文档提交的 GitHub Actions 曾显示 `Required Quality`、`S0 MVP foundation`、`Architecture Docs Gates` 成功；**本报告后续修改提交需对各自同一 head 重新核验**，不沿用旧 SHA 作为最新通过证据。
- 未在用户 Windows 11 已安装 PostgreSQL 环境中启动服务、执行真实浏览器、截图或性能/安全 UAT。对未亲自跑过的负例，只描述代码合同和建议测试，不宣称本轮测试 PASS。

**签收判定：REVISE / NOT SIGNED OFF**。待 B–E 完成后重新审议，而非自动审批。

## 9. 2026-10-19 整改落实记录（本轮不提前变更签收结论）

- **P1-01 全局覆盖**：新增 [V1.1 完整产品三级功能树](../product/OntoForge-产品完整功能树-三级-V1.1.md)：8 大功能域、原 F01–F29 29 个模块与 216 条**正向产品能力**。此前缺失的 F10/F13/F14/F17/F24/F26/F27/F29 均有独立 L3，完成了本项的**目录结构整改**；216 条语义正确性尚待复核。
- **P1-02 混合计数**：在 V1.1 全产品视图中分离边界、质量门和研发 PR；保留原 125 项作为 AS-IS 静态账，不直接合并计数。新增[125→216 追溯账](../product/OntoForge-ASIS到全产品功能树-追溯与分类-V1.1.md)，71 条直接、26 条合并细节、16 条未来规划、6 条边界、6 条质量活动全部有归档。**分类已完成初稿，正式审核和功能归属签收仍待进行**。
- **P1-03 核心合同**：新增 [15 项 Core 功能验收合同与 8 个黄金故事](../product/OntoForge-Core功能验收合同与黄金故事-V1.1.md)；其余长期功能仍为低粒度合同，Core 测试证据尚未实测。
- **P1-04 工作流程**：已定义 U01–U08 验收路径和跨模块身份追溯；**未实际完成 Windows Chromium/PG UAT**。静默漏掉筛选的问题已经建立 [#80](https://github.com/gitfortian/ontoforge/issues/80)。
- **P1-05 四轴状态**：已定义实现/CI/UAT/Release 四轴模板；**尚未对全部 216 点取得具体 CI 和 Windows 实测**，禁止提升为 Signed。
- **P1-06 入口与 Issue 状态同步**：README/doc/README 链接 V1.1 与旧 AS-IS 的真实职责；M5–M14 计划建立当前状态头，保留历史记录。
- **P1-07 全产品价值链**：F14 Rule/Function/Decision、F17 Registry、F18 Action、F16 Agent、F19 Policy、F20 Evidence 各有独立三级分解；它们属于明确规划，**不是当前可执行产品功能**。
- **产品研发问题**：[M7 条件误放大 #80](https://github.com/gitfortian/ontoforge/issues/80)、[Native UUID 合同不一 #81](https://github.com/gitfortian/ontoforge/issues/81)；[全域产品签收与证据治理 #82](https://github.com/gitfortian/ontoforge/issues/82)。

**本轮状态**：P1-01 的结构性缺口已整改，其他项部分推进；PR #78 仍为 Draft / **REVISE**，不得写成全部整改关闭或正式产品签收。

> **源码路径同步**：2026-10-19 主线工程经历 yak-ops 风格模块化重组（至 `main=c157d228`）；早期 `ontoforge-boot/.../model`、`ontoforge-ui/src/*Workspace` 位置已经变更。本文相应代码链接更新为当前 `ontoforge-business`/`ontoforge-core`/`ontoforge-ui/src/features` 路径。第一轮静态功能判断仍以当时 `c9de0336` 为研究基准，**文件迁移本身不证明最新代码回归通过**。

## 10. 第二轮独立复核的最新判定（2026-10-19）

本文件记录**第一轮问题**及整改起点，不应覆盖[第二轮正式产品复核报告](../product/OntoForge-V1.1-第二轮独立产品功能复核-20261019.md)。第二轮发现跨产品域重复认领相同实现、将 Flyway/脚本当成运营功能、黄金故事白名单/单列唯一键前提缺失，已将 **24 条 L3** 的范围/成熟度纠偏，并更新迁移账为 **71 直接 / 26 归并 / 16 未来 / 6 边界 / 6 质量**。全功能树现为 **216 条（57 已实现·限定 / 5 局部待验证 / 154 规划）**。

**签收状态**：功能覆盖结构可作为产品规划候选有条件通过；真实 Core UAT、长期功能合同完整性、产品所有者最终签收和企业 Release 均尚未完成；PR #78 **仍是 Draft，未合并**。
