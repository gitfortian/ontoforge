# OntoForge（砺元）产品功能说明书｜三级功能目录与实现状态 V1.0

> **文档类型**：产品功能说明 / 功能分解结构（FBS）/ AS-IS 与 TO-BE 差距基线。不是新的总体架构、不是产品 Release Notes、不是软件发布批准书。  
> **梳理日期**：2026-10-19；**代码基准**：`main` = [`c9de0336a022ed33bca08f39029b5ac58d3265e3`](https://github.com/gitfortian/ontoforge/commit/c9de0336a022ed33bca08f39029b5ac58d3265e3)，包含已合并 PR #75。  
> **范围**：OntoForge 独立本体产品，不包含 data-ops；**正式产品发布**：Not Released；用户既有 Windows 11 本机 PostgreSQL 完整人工 UAT：NOT RUN/未取得签收证据。

## 0. 使用说明与状态判定

此文档按照**一级产品模块 → 二级功能组 → 三级可验收功能点**编目。三级项为业务功能，不把 Java 类、数据库表或一个按钮拆成虚假业务模块。每一行都给出最小可观察行为、状态与一处直接代码或批准范围的依据。

- **已实现·限定环境**：`main` 中存在对应前端/API/PG/脚本的具体能力，限定于已描述的本机隔离、单来源或合成数据范围。不是正式发行，也不表示已完成所有人工 UAT。
- **部分实现／待加固**：有真实代码，但目标的完整安全、统一工作流、企业场景或用户签收尚有关键缺口。
- **规划中／未开发**：可能有 OFIP、原型、Schema 或研究 POC；不能据此将生产用户功能判作完成。
- **范围分离**：架构规划看 [唯一 V1.3 现行总体架构](../本体产品总体功能与技术架构设计_v1.3.md)；已实现的代码以本文件引用的 `main` 为准；[256 术语表](../砺元本体产品统一术语词汇表.md)规范名称；[F01–F29 历史 MVP 成熟度总账](./OntoForge-MVP-产品功能全景与成熟度矩阵.md)继续承担旧编号与里程碑历史，不与本文件的 `F01.xx.xx` 功能分解编号混用。

**重要**：本页的 `F01–F12` 是本次三级功能树的**局部新编号**，不是历史 V1.3 的 `F01–F29` 能力编号；跨文档引用应使用全称“L3-F01.01.01”等避免冲突。后续正式治理时建议统一为 `OF-F01.01.01`。

## 1. 产品定位、用户与范围

**定位**：企业/工业制造运营本体（Operational Ontology）平台，以受治理的对象类型、关系类型、来源绑定、对象读取与集合查询表达可溯源业务上下文。现阶段是**模型定义与本机有限只读查询的可运行 MVP**，不是工业生产数据全链路平台、图推理引擎或 Agent/Action 执行产品。

**主要角色**：业务/领域建模人员负责对象和关系定义；数据工程人员负责来源表、属性、主键映射；业务分析人员负责对象查询及质量追溯；研发与 QA 维护隔离测试环境及证据；可信审批主体、生产安全管理员与 Agent 应用属于尚未交付的扩大范围。

**当前主要业务故事**：
1. 用户建模型包 → 定义 ObjectType、Property、LinkType → 安全保存 WIP → 校验当前修订。
2. 用户冻结本地快照 → 只读历史与结构 Diff → 从已核验快照选定运行时类型。
3. 用户将自定义 ObjectType 绑定到受控只读 PostgreSQL 测试表 → 预览、校验、保存 Binding 修订。
4. 用户按 ObjectRef 读取对象 → 用 local-query-v1 对 ObjectSet 筛选、排序、投影、分页与有限聚合 → 查看版本及来源证据。
5. 用户另可在制造质量专用 MVP 对批次、工单、订单进行两跳追溯和影响集合查询；此路径不得冒充 M8 通用实例关系查询。

## 2. 一级/二级/三级功能统计

本轮盘点：**12 个一级模块、30 个二级功能组、125 个三级功能点**（已实现·限定环境 96；部分实现／待加固 5；规划中／未开发 24）。

| 一级编号 | 一级模块 | 二级功能组 | 三级功能点 |
|---|---|---:|---:|
| F01 | 产品门户与工作区 | 2 | 7 |
| F02 | Native 本体建模 | 4 | 17 |
| F03 | 草稿校验与编辑控制 | 2 | 9 |
| F04 | 模型快照与版本历史 | 2 | 10 |
| F05 | 运行时类型目录（M4） | 2 | 6 |
| F06 | 通用 Data Binding（M5） | 3 | 13 |
| F07 | 对象实例运行时（M6） | 2 | 9 |
| F08 | 通用 ObjectSet 查询（M7） | 3 | 16 |
| F09 | 工业制造质量追溯（MVP 专用） | 3 | 12 |
| F10 | 身份边界与本地运行 | 2 | 8 |
| F11 | 数据准备、诊断与交付质量 | 2 | 9 |
| F12 | 后续核心与扩展能力（设计范围） | 3 | 9 |

> **计数解释**：这是功能点的盘点分布，**不能把已实现条数除以总条数当作产品完成率**：功能点权重、安全风险、外部依赖、人工签收与正式 Release 均未纳入算术比例。

## 3. 三级功能明细（逐项可追溯）

## F01 产品门户与工作区

### F01.01 系统概览

| 三级编号 | 功能点 | 业务行为与最小验收 | 当前状态 | 代码或需求证据 |
|---|---|---|---|---|
| F01.01.01 | **服务与数据库状态** | 展示 API 与 PostgreSQL 实际连接状态；失败不能展示为已连接 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/controller/SystemStatusController.java) |
| F01.01.02 | **安装身份查看** | 显示 installationId 及环境标识；重启后可用于核对同一安装 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/controller/SystemStatusController.java) |
| F01.01.03 | **异常重试入口** | 系统状态读取失败可主动重试，不代替全平台监控 | 已实现·限定环境 | [查看依据](../../ontoforge-ui/src/main.tsx) |

### F01.02 导航与工作续接

| 三级编号 | 功能点 | 业务行为与最小验收 | 当前状态 | 代码或需求证据 |
|---|---|---|---|---|
| F01.02.01 | **主功能导航** | 进入建模、质量追溯、数据绑定、对象读取与对象集合查询入口 | 已实现·限定环境 | [查看依据](../../ontoforge-ui/src/main.tsx) |
| F01.02.02 | **浏览器前后退接续** | Hash 路由切换、浏览器返回及前进使用统一导航规则 | 已实现·限定环境 | [查看依据](../../ontoforge-ui/src/main.tsx) |
| F01.02.03 | **未保存编辑保护** | 离开编辑页或退出登录前提醒确认，拒绝时保留当前编辑上下文 | 已实现·限定环境 | [查看依据](../../ontoforge-ui/src/main.tsx) |
| F01.02.04 | **跨模块统一工作流** | 从模型到快照、绑定、对象、查询的无重复选择连续向导 | 部分实现／待加固 | [查看依据](https://github.com/gitfortian/ontoforge/issues/50) |

## F02 Native 本体建模

### F02.01 模型包管理

| 三级编号 | 功能点 | 业务行为与最小验收 | 当前状态 | 代码或需求证据 |
|---|---|---|---|---|
| F02.01.01 | **新建模型包** | 使用 packageId 建立持久草稿模型包并返回实际草稿身份 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/controller/NativeDraftController.java) |
| F02.01.02 | **草稿列表与打开** | 分页查询模型包草稿并以 draftId 重开 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/controller/NativeDraftController.java) |
| F02.01.03 | **工作中编辑恢复** | 符合编辑合同的不完整 WIP 保存后刷新可恢复 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/model/NativeDraftService.java) |
| F02.01.04 | **跨包引用与依赖** | 跨模型包 Import、依赖锁及冲突控制尚无生产功能 | 规划中／未开发 | [查看依据](https://github.com/gitfortian/ontoforge/issues/49) |

### F02.02 对象类型定义

| 三级编号 | 功能点 | 业务行为与最小验收 | 当前状态 | 代码或需求证据 |
|---|---|---|---|---|
| F02.02.01 | **多 ObjectType 定义** | 在同一模型包新增、切换和编辑多个对象类型 | 已实现·限定环境 | [查看依据](../../ontoforge-ui/src/NativeModelWorkspace.tsx) |
| F02.02.02 | **对象标识与 API 命名** | 编辑稳定 ID、API 名、展示名及描述 | 已实现·限定环境 | [查看依据](../../ontoforge-ui/src/NativeModelWorkspace.tsx) |
| F02.02.03 | **对象主键设置** | 从属性中设置 primaryKeyRef；缺少有效主键不能通过完整性校验 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/model/DraftValidation.java) |
| F02.02.04 | **对象类型结构移除** | 在当前模型编辑稿内调整和移除类型，并由校验阻断悬空关系引用 | 已实现·限定环境 | [查看依据](../../ontoforge-ui/src/NativeModelWorkspace.tsx) |

### F02.03 属性与类型

| 三级编号 | 功能点 | 业务行为与最小验收 | 当前状态 | 代码或需求证据 |
|---|---|---|---|---|
| F02.03.01 | **属性基础信息** | 定义稳定属性 ID、API 名、展示名、说明 | 已实现·限定环境 | [查看依据](../../ontoforge-ui/src/NativeModelWorkspace.tsx) |
| F02.03.02 | **属性值类型** | 编辑 STRING、INTEGER、DECIMAL、BOOLEAN、DATETIME、ENUM 等已支持类型 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/model/DraftValidation.java) |
| F02.03.03 | **属性约束** | 设置 required、searchable、classification、枚举取值及主键引用 | 已实现·限定环境 | [查看依据](../../ontoforge-ui/src/NativeModelWorkspace.tsx) |
| F02.03.04 | **属性合法性校验** | 拒绝重复命名、错误枚举或无效主键引用，返回路径 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/model/DraftValidation.java) |
| F02.03.05 | **复杂业务语义类型** | 单位量纲、复杂约束、计算属性与完整规则语言尚未进入生产闭环 | 规划中／未开发 | [查看依据](../../doc/implementation/OntoForge-MVP-产品功能全景与成熟度矩阵.md) |

### F02.04 关系类型定义

| 三级编号 | 功能点 | 业务行为与最小验收 | 当前状态 | 代码或需求证据 |
|---|---|---|---|---|
| F02.04.01 | **LinkType 结构建模** | 设置关系稳定 ID、API 名与含义 | 已实现·限定环境 | [查看依据](../../ontoforge-ui/src/NativeModelWorkspace.tsx) |
| F02.04.02 | **关系端点与基数** | 选择源/目标 ObjectType、cardinality 和 evidenceRequired | 已实现·限定环境 | [查看依据](../../ontoforge-ui/src/NativeModelWorkspace.tsx) |
| F02.04.03 | **关系完整性检查** | 识别非法端点引用、非法基数、重复关系定义等错误 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/model/DraftValidation.java) |
| F02.04.04 | **实例关联事实** | LinkType 定义不自动生成通用 LinkFact；实例级关联由后续 M8 负责 | 规划中／未开发 | [查看依据](https://github.com/gitfortian/ontoforge/issues/47) |

## F03 草稿校验与编辑控制

### F03.01 编辑与并发

| 三级编号 | 功能点 | 业务行为与最小验收 | 当前状态 | 代码或需求证据 |
|---|---|---|---|---|
| F03.01.01 | **草稿乐观版本保存** | 保存携带 expectedRevision，成功后 revision 递增 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/model/NativeDraftService.java) |
| F03.01.02 | **并发冲突保护** | 过期修订返回 409，避免旧编辑覆盖新内容 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/model/NativeDraftService.java) |
| F03.01.03 | **未完成结构安全保存** | 不完整业务模型允许 WIP 持久化，但非法编辑 JSON 拒绝 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/model/NativeDraftService.java) |
| F03.01.04 | **保存结果真实性** | 网络/服务端失败不显示虚假成功；保留未保存内容 | 已实现·限定环境 | [查看依据](../../ontoforge-ui/src/NativeModelWorkspace.tsx) |

### F03.02 模型有效性

| 三级编号 | 功能点 | 业务行为与最小验收 | 当前状态 | 代码或需求证据 |
|---|---|---|---|---|
| F03.02.01 | **执行完整性校验** | 按当前 revision 生成 NOT_RUN / INCOMPLETE / INVALID / VALID 等状态 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/model/DraftValidation.java) |
| F03.02.02 | **属性主键与关系验证** | 验证必填属性、主键、枚举、关系端点和 ID 唯一性 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/model/DraftValidation.java) |
| F03.02.03 | **Native Schema 合同核验** | 从 editor 文档转 Native 候选并执行 V0.2 JSON Schema 校验 | 已实现·限定环境 | [查看依据](../../doc/design/schema/ontology-package-v0.2.schema.json) |
| F03.02.04 | **校验修订失效** | 编辑保存后旧 VALID 不可沿用，冻结前必须重新校验当前 revision | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/model/NativeDraftService.java) |
| F03.02.05 | **跨模块完整业务规则验证** | 关联来源、发布消费者、规则与 Action 的联合治理校验尚未实现 | 规划中／未开发 | [查看依据](https://github.com/gitfortian/ontoforge/issues/49) |

## F04 模型快照与版本历史

### F04.01 本地不可变快照

| 三级编号 | 功能点 | 业务行为与最小验收 | 当前状态 | 代码或需求证据 |
|---|---|---|---|---|
| F04.01.01 | **冻结已校验模型** | 当前修订 VALID 才可冻结 LOCAL_SNAPSHOT，只读历史与正式发行分离 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/controller/NativeSnapshotController.java) |
| F04.01.02 | **快照内容摘要** | 以 RFC8785 JCS 规范化字节及 SHA-256 固定模型内容 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/model/NativeSnapshotService.java) |
| F04.01.03 | **快照原子性与保护** | PG 事务、来源修订唯一约束、数据库触发器限制普通更新删除 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/model/NativeSnapshotService.java) |
| F04.01.04 | **幂等冻结与未知结果核对** | 同 idempotencyKey 返回同一结果，可按键或修订查回 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/controller/NativeSnapshotController.java) |

### F04.02 版本浏览与差异

| 三级编号 | 功能点 | 业务行为与最小验收 | 当前状态 | 代码或需求证据 |
|---|---|---|---|---|
| F04.02.01 | **快照历史列表** | 按模型包浏览并分页读取历史快照 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/controller/NativeSnapshotController.java) |
| F04.02.02 | **快照只读详情** | 查询快照 ID、模型版本、摘要与结构内容 | 已实现·限定环境 | [查看依据](../../ontoforge-ui/src/NativeSnapshotWorkspace.tsx) |
| F04.02.03 | **快照完整性重核验** | 读取时核对原始字节/JSON 镜像/摘要/Schema，异常拒绝 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/model/NativeSnapshotService.java) |
| F04.02.04 | **两版本结构 Diff** | 同一包比较 ObjectType/Property/LinkType 增删及变更 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/model/NativeSnapshotService.java) |
| F04.02.05 | **潜在破坏风险提示** | 提示主键、类型、关系基数等结构兼容风险；并非消费者兼容性证明 | 已实现·限定环境 | [查看依据](../../ontoforge-ui/src/NativeSnapshotWorkspace.tsx) |
| F04.02.06 | **正式发布与版本激活** | SchemaRelease、审批、Activation 与真实回退独立规划，当前无此闭环 | 规划中／未开发 | [查看依据](https://github.com/gitfortian/ontoforge/issues/49) |

## F05 运行时类型目录（M4）

### F05.01 类型来源解析

| 三级编号 | 功能点 | 业务行为与最小验收 | 当前状态 | 代码或需求证据 |
|---|---|---|---|---|
| F05.01.01 | **有效草稿类型解析** | 只允许当前已 VALID 且 revision 精确匹配的草稿作为类型来源 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/model/RuntimeTypeResolver.java) |
| F05.01.02 | **本地快照类型解析** | 从校验过完整性的 snapshotId 读取不可变类型合同 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/model/RuntimeTypeResolver.java) |
| F05.01.03 | **版本来源显式区分** | 草稿与快照类型来源不能静默替换；失效需重新选择 | 已实现·限定环境 | [查看依据](../../ontoforge-ui/src/RuntimeTypeWorkspace.tsx) |

### F05.02 类型合同与读取

| 三级编号 | 功能点 | 业务行为与最小验收 | 当前状态 | 代码或需求证据 |
|---|---|---|---|---|
| F05.02.01 | **对象类型列表** | 提供草稿/快照各自的类型列表与类型详情四个只读接口 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/controller/RuntimeTypeController.java) |
| F05.02.02 | **属性合同展示** | 读取主键、类型、检索性、必填、枚举、分类等合同 | 已实现·限定环境 | [查看依据](../../ontoforge-ui/src/RuntimeTypeWorkspace.tsx) |
| F05.02.03 | **类型目录作为下游依据** | M5/M6/M7 可使用解析结果；不表示正式 Release Registry 已完成 | 部分实现／待加固 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/model/RuntimeTypeResolver.java) |

## F06 通用 Data Binding（M5）

### F06.01 受控来源识别

| 三级编号 | 功能点 | 业务行为与最小验收 | 当前状态 | 代码或需求证据 |
|---|---|---|---|---|
| F06.01.01 | **已登记来源发现** | 仅列出单个受控只读 PostgreSQL 来源的允许表与列、字段类型 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/controller/DataBindingController.java) |
| F06.01.02 | **来源结构指纹** | 展示表/列 Schema Fingerprint 作为后续绑定与漂移核验依据 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/model/DataBindingService.java) |
| F06.01.03 | **外部任意连接配置** | 任意 JDBC URL、跨库/跨系统 MES/ERP/QMS 连接器不开放 | 规划中／未开发 | [查看依据](https://github.com/gitfortian/ontoforge/issues/48) |

### F06.02 类型到数据绑定

| 三级编号 | 功能点 | 业务行为与最小验收 | 当前状态 | 代码或需求证据 |
|---|---|---|---|---|
| F06.02.01 | **精确类型版本选择** | 使用有效类型快照与 ObjectType 作为持久 Binding 来源 | 已实现·限定环境 | [查看依据](../../ontoforge-ui/src/DataBindingWorkspace.tsx) |
| F06.02.02 | **对象主键映射** | 把对象 identity/sourceKey 对应到受控表的列 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/model/DataBindingService.java) |
| F06.02.03 | **属性列映射** | 为 ObjectType Property 选择来源列和 AUTHORITATIVE 语义 | 已实现·限定环境 | [查看依据](../../ontoforge-ui/src/DataBindingWorkspace.tsx) |
| F06.02.04 | **绑定前真实预览** | 以只读 SQL 展示有限原始样本和映射样本，不在预览时持久化 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/controller/DataBindingController.java) |
| F06.02.05 | **绑定有效性校验** | 检查列类型/缺项/身份重复/结构指纹及有限数据质量 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/model/DataBindingService.java) |

### F06.03 绑定生命期

| 三级编号 | 功能点 | 业务行为与最小验收 | 当前状态 | 代码或需求证据 |
|---|---|---|---|---|
| F06.03.01 | **绑定创建与修订** | 创建 Binding 身份、CAS 更新 revision、保留源和模型引用 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/controller/DataBindingController.java) |
| F06.03.02 | **绑定历史与重开** | 读取已有 Binding 及 revisions，页面刷新后重现真实记录 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/controller/DataBindingController.java) |
| F06.03.03 | **绑定内容摘要** | 记录 digest、来源 fingerprint、绑定/模型修订与身份域 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/resources/db/migration/V7__generic_data_binding.sql) |
| F06.03.04 | **来源只读安全边界** | 不允许自由 SQL 和生产写入；验证读取角色/白名单 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/model/DataBindingService.java) |
| F06.03.05 | **多连接器和批量同步** | 企业级连接器、CDC、投影物化、写回及分布式源管理尚未实现 | 规划中／未开发 | [查看依据](https://github.com/gitfortian/ontoforge/issues/48) |

## F07 对象实例运行时（M6）

### F07.01 虚拟对象身份

| 三级编号 | 功能点 | 业务行为与最小验收 | 当前状态 | 代码或需求证据 |
|---|---|---|---|---|
| F07.01.01 | **按对象键读取** | 根据 bindingId/revision、规范对象键和属性请求读取单个虚拟对象 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/controller/ObjectRuntimeController.java) |
| F07.01.02 | **稳定 ObjectRef** | 返回 packageId、objectTypeId、identityDomainId、带类型主键 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/model/ObjectRuntimeService.java) |
| F07.01.03 | **身份域变更隔离** | 模型/绑定版本变化时明确身份边界，不把不同对象误并 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/model/ObjectRuntimeService.java) |

### F07.02 属性值与数据证据

| 三级编号 | 功能点 | 业务行为与最小验收 | 当前状态 | 代码或需求证据 |
|---|---|---|---|---|
| F07.02.01 | **类型化属性值** | 按当前映射及 Property 合同返回已请求字段的值类型和内容 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/model/ObjectRuntimeService.java) |
| F07.02.02 | **空值与未映射区分** | 准确表达 PRESENT、NULL、MISSING，缺失不冒充空字符串/零 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/model/ObjectRuntimeService.java) |
| F07.02.03 | **物理重复折叠与冲突拒绝** | 相同 ObjectRef 一致重复行可折叠；冲突必须失败而非任取一行 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/model/ObjectRuntimeService.java) |
| F07.02.04 | **运行时来源证据** | 返回来源读引用、列级 provenance、Binding 版本与读取范围等证据 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/model/ObjectRuntimeService.java) |
| F07.02.05 | **失败与超预算恢复** | 区分无对象、权限撤销、结构漂移、类型冲突及查询/输出预算失败 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/model/ObjectRuntimeService.java) |
| F07.02.06 | **通用对象存储与编辑** | 没有永久 Object Store、对象修改/回写、跨源合并或完整分布式 Runtime | 规划中／未开发 | [查看依据](https://github.com/gitfortian/ontoforge/issues/51) |

## F08 通用 ObjectSet 查询（M7）

### F08.01 类型化查询配置

| 三级编号 | 功能点 | 业务行为与最小验收 | 当前状态 | 代码或需求证据 |
|---|---|---|---|---|
| F08.01.01 | **版本化查询合同** | 使用 local-query-v1 AST，固定 rootTypeRef 与 Binding revision，拒绝额外字段 | 已实现·限定环境 | [查看依据](../../doc/design/schema/local-query-v1.schema.json) |
| F08.01.02 | **类型安全筛选** | 支持 EQ/IN/GT/GTE/LT/LTE/PREFIX/IS_NULL 的适用类型与可检索性校验 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/model/ObjectSetQueryService.java) |
| F08.01.03 | **组合条件约束** | 支持有限 FILTER 节点与条件；不支持任意布尔表达式/自由 SQL | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/model/ObjectSetQueryService.java) |
| F08.01.04 | **多字段稳定排序** | 至多三字段 ASC/DESC，主键稳定兜底，重复排序字段拒绝 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/model/ObjectSetQueryService.java) |
| F08.01.05 | **结果字段投影** | 选择 1–100 个合法 Property；未映射值保持 MISSING | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/model/ObjectSetQueryService.java) |

### F08.02 集合执行与读取

| 三级编号 | 功能点 | 业务行为与最小验收 | 当前状态 | 代码或需求证据 |
|---|---|---|---|---|
| F08.02.01 | **通用集合查询入口** | POST /api/runtime/object-sets/query 对自定义类型读取多对象集合 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/controller/ObjectSetQueryController.java) |
| F08.02.02 | **有界精确分页** | PAGE 大小 1–50，结果快照和不透明 nextCursor 接续 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/model/ObjectSetQueryService.java) |
| F08.02.03 | **游标时效与复核** | 默认 TTL 最多 300 秒，每页重验包授权、绑定版本与来源权限/Schema | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/model/ObjectSetQueryService.java) |
| F08.02.04 | **去重对象计数** | COUNT_DISTINCT 基于 ObjectRef 去重，不能把重复物理行算两次 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/model/ObjectSetQueryService.java) |
| F08.02.05 | **精确数值求和** | SUM 仅 INTEGER/DECIMAL；NULL/MISSING 不得默认为零，失败不返回部分总和 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/model/ObjectSetQueryService.java) |
| F08.02.06 | **空结果与失败区分** | 无命中、失效 cursor、未知执行结果或来源故障返回不同状态/证据 | 已实现·限定环境 | [查看依据](../../ontoforge-ui/src/ObjectSetWorkspace.tsx) |

### F08.03 执行可信约束

| 三级编号 | 功能点 | 业务行为与最小验收 | 当前状态 | 代码或需求证据 |
|---|---|---|---|---|
| F08.03.01 | **单事务一致读取** | 对允许的单 PostgreSQL 来源执行有界只读 REPEATABLE READ | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/model/ObjectSetQueryService.java) |
| F08.03.02 | **执行预算** | 最大 10000 物理行、8MiB 来源/结果及 3 秒查询上限，超限失败关闭 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/model/ObjectSetQueryService.java) |
| F08.03.03 | **短时结果快照配额** | 内存快照最大 10 份/合计 80MiB；进程重启不承诺恢复旧游标 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/model/ObjectSetQueryService.java) |
| F08.03.04 | **查询摘要与版本锁证据** | 返回 queryDigest、versionLock、sourceReadRef、质量范围及预算证据 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/model/ObjectSetQueryService.java) |
| F08.03.05 | **通用 Link 遍历与跨源 Join** | M7 不执行 LinkFact、多跳图遍历、跨源 JOIN 或持久化索引 | 规划中／未开发 | [查看依据](https://github.com/gitfortian/ontoforge/issues/47) |

## F09 工业制造质量追溯（MVP 专用）

### F09.01 质量模型与来源

| 三级编号 | 功能点 | 业务行为与最小验收 | 当前状态 | 代码或需求证据 |
|---|---|---|---|---|
| F09.01.01 | **内置三对象建模** | 一键创建生产批次、工单和客户订单及两段 LinkType 的示例模型 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/controller/QualityMvpController.java) |
| F09.01.02 | **测试表/字段选择** | 仅 public.demo_* 与 mvp_source_* 受控表；完成固定 10 项配置 | 已实现·限定环境 | [查看依据](../../ontoforge-ui/src/QualityMvpWorkspace.tsx) |
| F09.01.03 | **样本及关系预览** | 分别预览有限三表行与一条两跳关联示例 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/controller/QualityMvpController.java) |
| F09.01.04 | **场景绑定持久化** | 绑定当前 VALID 示例模型的 revision，防止旧绑定误用 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/controller/QualityMvpController.java) |

### F09.02 批次与影响追溯

| 三级编号 | 功能点 | 业务行为与最小验收 | 当前状态 | 代码或需求证据 |
|---|---|---|---|---|
| F09.02.01 | **单批次链路追溯** | 按 batchId 返回批次→工单→订单及源列信息，拒绝歧义单路径 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/controller/QualityMvpController.java) |
| F09.02.02 | **多路径影响集合** | 返回受影响工单/去重订单/完整关系路径计数 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/controller/QualityMvpController.java) |
| F09.02.03 | **影响路径分页** | 按有界路径稳定前后翻页，展示来源与外键证据 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/controller/QualityMvpController.java) |
| F09.02.04 | **断链与冲突解释** | 区分批次缺失、缺失工单/订单、数据冲突和修订漂移 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/controller/QualityMvpController.java) |

### F09.03 专用对象浏览

| 三级编号 | 功能点 | 业务行为与最小验收 | 当前状态 | 代码或需求证据 |
|---|---|---|---|---|
| F09.03.01 | **三类对象集合浏览** | 在固定示例类型中浏览批次、工单、订单集合 | 已实现·限定环境 | [查看依据](../../ontoforge-ui/src/QualityMvpWorkspace.tsx) |
| F09.03.02 | **标量字段查找** | 支持精确匹配与前缀查询，显示真实去重对象和来源 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/controller/QualityMvpController.java) |
| F09.03.03 | **查询结果分页** | 专用 ObjectSet 限定页大小/游标与修订指纹 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/controller/QualityMvpController.java) |
| F09.03.04 | **通用多领域关系建模** | 本模块不取代 M5–M8 的任意领域/通用关系链路 | 规划中／未开发 | [查看依据](https://github.com/gitfortian/ontoforge/issues/47) |

## F10 身份边界与本地运行

### F10.01 可信身份代码路径

| 三级编号 | 功能点 | 业务行为与最小验收 | 当前状态 | 代码或需求证据 |
|---|---|---|---|---|
| F10.01.01 | **B0 JWT/OIDC 验证** | 非本地模式要求受信 issuer/JWKS/audience；代码已合并但企业环境未签收 | 部分实现／待加固 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/security/SecurityConfig.java) |
| F10.01.02 | **模型包授权** | B0 提供显式包授权与服务端操作校验，不能据此宣称完整多租户/企业 IAM | 部分实现／待加固 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/security/B0AuthorizationService.java) |
| F10.01.03 | **身份信息读取** | GET /api/me 返回当前受信主体的身份摘要 | 部分实现／待加固 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/security/B0AuthorizationService.java) |
| F10.01.04 | **统一企业登录管理** | 正式会话/组织角色/权限管理页与 yak-ops HttpSession 复用尚未交付 | 规划中／未开发 | [查看依据](https://github.com/gitfortian/ontoforge/issues/49) |

### F10.02 隔离开发安全

| 三级编号 | 功能点 | 业务行为与最小验收 | 当前状态 | 代码或需求证据 |
|---|---|---|---|---|
| F10.02.01 | **mvp-local 显式本地模式** | 通过 Spring Profile 启用无登录本机体验，仅限 loopback/测试库 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/security/LocalMvpSecurityConfig.java) |
| F10.02.02 | **普通模式失败关闭** | 未配置可信 OIDC 的非本地安全端点不得默认为匿名开放 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/security/SecurityConfig.java) |
| F10.02.03 | **来源只读账户** | M5 使用限制来源权限的独立只读连接，而非应用任意执行 SQL | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/model/DataBindingService.java) |
| F10.02.04 | **企业策略与审计** | 对象行/列级策略、可信审批、访问审计、生产密钥治理尚未建立 | 规划中／未开发 | [查看依据](../../doc/proposals/OFIP-0005-P0B模型治理审核发布与激活MVP.md) |

## F11 数据准备、诊断与交付质量

### F11.01 测试数据准备

| 三级编号 | 功能点 | 业务行为与最小验收 | 当前状态 | 代码或需求证据 |
|---|---|---|---|---|
| F11.01.01 | **合成制造来源表** | Flyway 提供独立测试数据与演示链路，禁止视为真实工厂数据 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/java/io/ontoforge/boot/controller/QualityMvpController.java) |
| F11.01.02 | **脱敏 CSV 本地导入** | PowerShell 限定导入三张 demo_* 测试表及固定列合同 | 已实现·限定环境 | [查看依据](../../scripts/import-quality-test-data.ps1) |
| F11.01.03 | **导入校验与防误操作** | 检查表头/外键/重复/行数和测试库身份，错误拒绝 | 已实现·限定环境 | [查看依据](../../scripts/import-quality-test-data.ps1) |
| F11.01.04 | **生产数据接入** | 真实 MES/ERP/QMS 连接器与生产同步任务没有交付 | 规划中／未开发 | [查看依据](https://github.com/gitfortian/ontoforge/issues/48) |

### F11.02 研发与验收支撑

| 三级编号 | 功能点 | 业务行为与最小验收 | 当前状态 | 代码或需求证据 |
|---|---|---|---|---|
| F11.02.01 | **数据迁移与持久化** | Flyway V1–V7 等已提交迁移记录服务于本机 PG，而非生产数据库运维服务 | 已实现·限定环境 | [查看依据](../../ontoforge-boot/src/main/resources/db/migration/V7__generic_data_binding.sql) |
| F11.02.02 | **后端与前端集成检查** | 仓库有 Maven/PG17/React/Chromium 实测工作流，结果以对应 head CI 为准 | 已实现·限定环境 | [查看依据](../../.github/workflows/quality-gate.yml) |
| F11.02.03 | **规范与文档治理** | OFIP、术语表、Schema 合同、PR Gate 和验收说明可追溯 | 已实现·限定环境 | [查看依据](../../.github/workflows/quality-gate.yml) |
| F11.02.04 | **Windows 用户实机验收** | 用户既有 Windows 11 + PG 环境的完整人工 UAT 未有可确认签收 | 规划中／未开发 | [查看依据](https://github.com/gitfortian/ontoforge/issues/53) |
| F11.02.05 | **高可用与生产观测** | 备份恢复、HA、容量压测、部署升级与生产审计未交付 | 规划中／未开发 | [查看依据](https://github.com/gitfortian/ontoforge/issues/53) |

## F12 后续核心与扩展能力（设计范围）

### F12.01 通用关系与证据治理

| 三级编号 | 功能点 | 业务行为与最小验收 | 当前状态 | 代码或需求证据 |
|---|---|---|---|---|
| F12.01.01 | **Link 受控映射** | M8.1：自定义 LinkType 外键映射、正反一跳与关系证据 | 规划中／未开发 | [查看依据](https://github.com/gitfortian/ontoforge/issues/47) |
| F12.01.02 | **桥表与两跳遍历** | M8.2/M8.3：桥表 M:N、多路径及显式两跳，不做无界图搜索 | 规划中／未开发 | [查看依据](https://github.com/gitfortian/ontoforge/issues/47) |
| F12.01.03 | **来源证据统一治理** | M9：质量四维、checkpoint UNKNOWN、结构漂移审阅与修复工作流 | 规划中／未开发 | [查看依据](https://github.com/gitfortian/ontoforge/issues/48) |

### F12.02 发布与通用 Studio

| 三级编号 | 功能点 | 业务行为与最小验收 | 当前状态 | 代码或需求证据 |
|---|---|---|---|---|
| F12.02.01 | **运行时本地版本锁** | M10-A：消费者锁定本地不可变模型和绑定版本并做兼容检查 | 规划中／未开发 | [查看依据](https://github.com/gitfortian/ontoforge/issues/49) |
| F12.02.02 | **审核/正式发行/激活** | M10-B/OFIP-0005：审批、SchemaRelease、Activation 和回退；尚需产品决策 | 规划中／未开发 | [查看依据](https://github.com/gitfortian/ontoforge/issues/49) |
| F12.02.03 | **连续 Ontology Studio** | M11：将模型、绑定、对象、集合、证据形成完整一体化任务工作台 | 规划中／未开发 | [查看依据](https://github.com/gitfortian/ontoforge/issues/50) |

### F12.03 智能能力与产品验收

| 三级编号 | 功能点 | 业务行为与最小验收 | 当前状态 | 代码或需求证据 |
|---|---|---|---|---|
| F12.03.01 | **受控 Action 模拟写入** | M12：预演、授权、持久幂等、外部回执、UNKNOWN 对账 | 规划中／未开发 | [查看依据](https://github.com/gitfortian/ontoforge/issues/51) |
| F12.03.02 | **Agent 只读上下文** | M13：优先 REST 类型发现、对象集合与来源证据，MCP 后续薄适配 | 规划中／未开发 | [查看依据](https://github.com/gitfortian/ontoforge/issues/52) |
| F12.03.03 | **跨领域综合验收** | M14：质量/采购多领域同引擎、第三类型挑战、UAT 与风险总账 | 规划中／未开发 | [查看依据](https://github.com/gitfortian/ontoforge/issues/53) |
## 4. 跨功能业务路径与依赖

```text
本机平台状态/工作台
  → 模型包及 ObjectType/Property/LinkType（建模）
  → 保存修订 → VALID 校验
  → 冻结 LOCAL_SNAPSHOT（只读；不是 SchemaRelease）
  → M4 Type Catalog（按快照解析）
  → M5 Data Binding（受控单 PG 表/列、预览校验、修订）
  → M6 Object Runtime（ObjectRef、类型化值、来源证据）
  → M7 ObjectSet（Filter/Sort/Project/Page/COUNT_DISTINCT/SUM）
  → M8 通用 LinkFact + 多跳遍历（未实现）
  → M9 来源治理与恢复（未实现；最低来源核验在 M5–M7 已执行）
  → M10 正式发行 / 激活（未实现） → M11 连续 Studio（未实现）
  → M12 受控 Action / M13 Agent 只读上下文（未实现）
```

**依赖解释**：M2/M3 的制造固定场景具有双跳与对象集合，但不满足通用 LinkType→LinkFact→任意类型遍历的 M8 合同；M7 的查询 resultSnapshot 是**短时分页结果缓存**，不是 Native 模型 Snapshot，也不是正式 Release 或可审计历史数据仓。

## 5. 已实现范围的关键非功能约束

| 范围 | 真实限定及验收判断 | 证据 |
|---|---|---|
| 数据来源 | M5–M7 限单登记只读 PostgreSQL 来源及受控表；不允许任意 SQL、生产凭据、跨源 JOIN | [M5 实现](../../ontoforge-boot/src/main/java/io/ontoforge/boot/model/DataBindingService.java) |
| M6 对象读取 | 按需虚拟对象、不落全量 Object Store；重复一致行折叠，所请求属性存在冲突拒绝 | [M6 实现](../../ontoforge-boot/src/main/java/io/ontoforge/boot/model/ObjectRuntimeService.java) |
| M7 过滤算子 | EQ/IN/GT/GTE/LT/LTE/PREFIX/IS_NULL 限类型适配；不支持任意复杂表达式 | [M7 实现](../../ontoforge-boot/src/main/java/io/ontoforge/boot/model/ObjectSetQueryService.java) |
| M7 预算 | 物理行最多 10000，单次来源/结果各 8MiB，超时最多 3s；页大小 1–50 | [M7 实现](../../ontoforge-boot/src/main/java/io/ontoforge/boot/model/ObjectSetQueryService.java) |
| M7 结果快照 | TTL 默认最多 300 秒，内存不超过 10 份和合计 80MiB；不保证跨服务重启继续分页 | [M7 实现](../../ontoforge-boot/src/main/java/io/ontoforge/boot/model/ObjectSetQueryService.java) |
| 安全 | 独立 `mvp-local` 仅用于本机隔离开发；非本地 JWT/OIDC 实现不等于生产认证体系已经验收 | [安全配置](../../ontoforge-boot/src/main/java/io/ontoforge/boot/security/SecurityConfig.java) |
| 模型版本 | Draft revision、Local Snapshot、Binding revision、Query result snapshot、SchemaRelease 是不同身份 | [版本历史](../../ontoforge-boot/src/main/java/io/ontoforge/boot/model/NativeSnapshotService.java) |

## 6. 待完成能力与研发优先顺序

| 建议顺序 | 研发里程碑 | 当前缺口 / 用户应看到的最终结果 | 放行条件 |
|---|---|---|---|
| 1 | M7 合并后整体验收收口 | 对当前 M7 已合并功能以同一 main SHA 补齐代码级证据/人工验收；修正文档的历史状态 | 功能点真实跑通，断言回归与错误路径，不把 CI 当 Windows 人工验收 |
| 2 | M8 通用关系 | 用户自建 LinkType 配置关系来源后完成双向一跳；随后桥表 M:N、显式两跳分切片交付 | 每个切片先 OFIP 决策、UX-Low/UX-High 签收、真实 PG/API/React 测试 |
| 3 | M9 来源证据治理 | 统一结构漂移、字段证据、Checkpoint UNKNOWN、质量四维与修复重试 | 抗误报、权限撤权、来源失效、预算与漂移反例 |
| 4 | M10-A/M10-B 与 M11 | 完成精确版本锁/兼容评估，确定 Core 是否含正式发行，连续 Studio 可从建模走到查询证据 | OFIP-0005 与 M10 定位正式决策；正式发行不得用本机假身份审批 |
| 5 | M14 Core 验收 | 质量与采购两个独立领域复用同一引擎，独立第三类型挑战 | Windows 既有安装人工 UAT、真实质量证据与风险清单 |
| 6 | M12 / M13 Extended | 先模拟 Action 的审批/回执/UNKNOWN；Agent REST 只读上下文，MCP 后续薄适配 | 必要的可信身份/发行/策略与外部结果核验；不可触达生产工业控制 |

以上排期顺序是**建议**，不是授权跳过提案、原型或门禁。参见 [M8](https://github.com/gitfortian/ontoforge/issues/47) / [M9](https://github.com/gitfortian/ontoforge/issues/48) / [M10](https://github.com/gitfortian/ontoforge/issues/49) / [M11](https://github.com/gitfortian/ontoforge/issues/50) / [M14](https://github.com/gitfortian/ontoforge/issues/53)。

## 7. 验收清单与文档维护机制

**对每个三级功能点**，后续维护至少记录：状态、首次交付 PR、最近代码校验 commit、用户操作入口/API、正例、关键负例、是否覆盖 Windows UAT、未覆盖的环境/规模/安全约束。出现 PR 合并时只更新受到影响的 L3 行，必要时补详细测试报告链接，**不自动提升模块全域成熟度**。

**当前核对依据**：

- 2026-10-19 仓库 `main` [`c9de0336`](https://github.com/gitfortian/ontoforge/commit/c9de0336a022ed33bca08f39029b5ac58d3265e3)；M4 [#56](https://github.com/gitfortian/ontoforge/pull/56)、M5 [#63](https://github.com/gitfortian/ontoforge/pull/63)、M6 [#68](https://github.com/gitfortian/ontoforge/pull/68) / [#71](https://github.com/gitfortian/ontoforge/pull/71)、M7 [#75](https://github.com/gitfortian/ontoforge/pull/75) 已合并。
- 草稿/快照 [#7](https://github.com/gitfortian/ontoforge/pull/7) / [#11](https://github.com/gitfortian/ontoforge/pull/11) / [#13](https://github.com/gitfortian/ontoforge/pull/13)；固定追溯 [#32](https://github.com/gitfortian/ontoforge/pull/32) / [#33](https://github.com/gitfortian/ontoforge/pull/33) / [#38](https://github.com/gitfortian/ontoforge/pull/38) / [#40](https://github.com/gitfortian/ontoforge/pull/40)；CSV [#34](https://github.com/gitfortian/ontoforge/pull/34)。
- 每项能力的服务端与前端入口已在第 3 章逐条关联。历史 [2026-10-17 AS-IS](./OntoForge-AS-IS-已实现功能全景-20261017.md) 是旧快照，不能覆盖 M4–M7 的现行状态。
- **证据局限**：本次执行了 GitHub 文件级/PR 合并状态静态核验，**未在用户 Windows 11 本机运行实际 PostgreSQL、浏览器和生产安全验收**；CI 的具体最新执行结果须另以合并 commit 的工作流页面为准，本文不凭文档审查自行宣称“所有测试全绿”。

## 8. 术语与版本治理

- **Native Schema**：描述模型包对象/关系定义；**ObjectRef**：对象实例身份；**ObjectSet**：对象集合查询语义；**Data Binding**：绑定模型/属性与来源事实；**LinkFact**：具体实例关联事实；**Action**：可能产生外部写副作用的受治理执行。
- 这份三级功能说明书是**唯一按当前实际能力编制的可维护三级目录**；它不替换总体架构 V1.3、不回写历史 F01–F29，也不将研究 POC 编入已实现能力。
- 当实现变化时更新本文件的代码 SHA、状态及证据，保持单一最新入口，不为每个 PR 再复制一份新“完整功能说明书”。
