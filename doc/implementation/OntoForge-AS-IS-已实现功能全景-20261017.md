# OntoForge 砺元｜已实现产品功能全景（AS-IS）

> **文档性质**：对“当前代码真正能够做什么”的事实说明，**不是新一版总体规划**、销售承诺或生产发行说明。总体目标仍以 [V1.3 总体架构](../本体产品总体功能与技术架构设计_v1.3.md) 为准；术语仍以[统一词汇表](../砺元本体产品统一术语词汇表.md)为准。
>
> **冻结基线**：2026-10-17，仓库 [gitfortian/ontoforge](https://github.com/gitfortian/ontoforge)，main = `763fbfe792b45a84db8d3c2c423ca001a05682ed`（已合并 #32、#33）。本文新增文档 PR 不新增业务运行时代码。
>
> **最重要的边界**：下面的“已实现”指 **main 有真实 Java/React/PostgreSQL 代码，在隔离 CI 中经过相应自动验收**；不表示在用户 Windows 11 既有安装中全部手动签收，更不表示已部署工厂、可公网访问或正式发布。研究实验、架构合同、产品原型、未合并 PR 单列，不混算。

## 1. 一页概览：现在是一款什么产品

**当前可体验的最小产品**：本机单用户、隔离 PostgreSQL 环境中的本体建模与制造质量追溯工作台。

- **建模**：定义模型包、多个业务对象类型和属性、对象间的 LinkType，保存并校验。
- **留痕**：冻结只读本地模型快照，以修订号/幂等键重查，比较对象、属性和关系差异风险。
- **业务价值演示**：给生产批次、生产工单、客户订单建立本体模型；绑定本机 PostgreSQL 中允许的测试表；预览实际来源行和一条关联证据；按批次号查询到关联工单、客户订单与来源列。

**尚不是**：多租户受控工业平台、MES/ERP 连接器、通用 ObjectSet、对象索引平台、Agent 工作引擎、规则决策引擎或 Action 执行平台。

### 用户故事全景图

```text
进入本机 Studio
  ├─ A. 建模：模型包 → 多 ObjectType/Property/LinkType → WIP 保存 → VALID 校验
  │                └─ 冻结本地快照 → 历史列表 → 只读快照 → 同包 Diff/风险
  └─ B. 质量追溯：创建/打开示例本体 → 选 PostgreSQL 测试表/字段
                         → 预览 3 表样本和一条两跳关联
                         → 保存当前模型 revision 的来源绑定
                         → 输入批次 → 批次/工单/客户订单 + 来源证据
```

## 2. 完整产品功能清单：实际操作、实现程度、代码证据

状态用语：**主线已实现·限定场景**／**仅研究或合同**／**候选 PR 未合并**／**未实现**。不按“模块大类”笼统打勾。

| 编号 / 用户能力 | 用户能做什么 | 精确实现程度 | 直接代码/页面证据 |
| --- | --- | --- | --- |
| S01 平台状态 | 查看 API、PostgreSQL 和安装标识 | 主线已实现，本机真实状态；不是运维监控大屏 | [SystemStatusController](../../ontoforge-boot/src/main/java/io/ontoforge/boot/controller/SystemStatusController.java)、[首页](../../ontoforge-ui/src/main.tsx) |
| S02 模型包 | 创建模型包、分页浏览/打开已有草稿 | 主线已实现；一个 packageId 对应一个可编辑草稿，不含跨包依赖 | [NativeDraftController](../../ontoforge-boot/src/main/java/io/ontoforge/boot/controller/NativeDraftController.java)、[V2](../../ontoforge-boot/src/main/resources/db/migration/V2__native_package_drafts.sql) |
| S03 多 ObjectType | 在同一包创建、选择、编辑多个对象类型 | 主线已实现；无跨包继承/专业业务词汇管理 | [NativeModelWorkspace](../../ontoforge-ui/src/NativeModelWorkspace.tsx) |
| S04 Property | 配置稳定 ID、API 名、显示名、数据类型、必填、可检索、分类、枚举值和主键引用 | 主线已实现**定义**；不等于业务数据列自动绑定 | [DraftValidation](../../ontoforge-boot/src/main/java/io/ontoforge/boot/model/DraftValidation.java) |
| S05 LinkType | 配置关系 ID、API 名、起止对象、基数与 evidenceRequired | 主线已实现**关系类型声明**；无普遍的实例级 LinkFact 持久化 | [NativeModelWorkspace](../../ontoforge-ui/src/NativeModelWorkspace.tsx)、[DraftValidation](../../ontoforge-boot/src/main/java/io/ontoforge/boot/model/DraftValidation.java) |
| S06 草稿保存 | WIP 不完整模型可安全保存、刷新重开 | 主线已实现；草稿未通过校验也允许符合编辑结构的保存 | [NativeDraftService](../../ontoforge-boot/src/main/java/io/ontoforge/boot/model/NativeDraftService.java) |
| S07 乐观并发 | 按 expectedRevision 保存；两处编辑冲突返回 409 | 主线已实现；不是多人工作空间权限控制 | [NativeDraftService](../../ontoforge-boot/src/main/java/io/ontoforge/boot/model/NativeDraftService.java) |
| S08 完整性校验 | 对当前修订做完整性/引用/Native 合同检查，返回问题路径与状态 | 主线已实现；NOT_RUN/INCOMPLETE/INVALID/VALID 不等于已审核 | [DraftValidation](../../ontoforge-boot/src/main/java/io/ontoforge/boot/model/DraftValidation.java)、[NativePackageSchema](../../ontoforge-boot/src/main/java/io/ontoforge/boot/model/NativePackageSchema.java) |
| S09 本地快照 | 只有当前修订 VALID 后才可冻结只读快照 | 主线已实现，**不等于 SchemaRelease / Activation** | [NativeSnapshotService](../../ontoforge-boot/src/main/java/io/ontoforge/boot/model/NativeSnapshotService.java)、[V3](../../ontoforge-boot/src/main/resources/db/migration/V3__native_schema_snapshot.sql) |
| S10 快照验证/找回 | RFC 8785 JCS、SHA-256 核验，按幂等键/修订查询响应不明的请求 | 主线已实现；无企业级防篡改审计或跨地域恢复 | [NativeSnapshotService](../../ontoforge-boot/src/main/java/io/ontoforge/boot/model/NativeSnapshotService.java) |
| S11 快照历史与 Diff | 只读浏览历史，两份同包快照对比对象/属性/LinkType 变化及破坏风险 | 主线已实现；提示潜在风险，不是下游兼容证明 | [NativeSnapshotWorkspace](../../ontoforge-ui/src/NativeSnapshotWorkspace.tsx) |
| S12 内置制造场景 | 三对象（生产批次/工单/客户订单）、两关系的演示本体自动创建与校验 | 主线已实现，**固定 `manufacturing.quality` 包** | [QualityMvpController](../../ontoforge-boot/src/main/java/io/ontoforge/boot/controller/QualityMvpController.java) |
| S13 本机测试表发现 | 从当前 PG `public` 元信息中选择 `demo_*` / `mvp_source_*` 测试表与支持的标量列 | 主线已实现；不支持其他 Schema、远程连接参数、真实 MES/ERP | [QualityMvpController](../../ontoforge-boot/src/main/java/io/ontoforge/boot/controller/QualityMvpController.java) |
| S14 可配置来源绑定 | 三表和 7 个字段，共 10 项配置，绑定当前 VALID 草稿 revision、持久保存 | 主线已实现，**仅单一预定义两跳场景** | [QualityMvpWorkspace](../../ontoforge-ui/src/QualityMvpWorkspace.tsx)、[V4](../../ontoforge-boot/src/main/resources/db/migration/V4__mvp_quality_trace_sandbox.sql) |
| S15 绑定前预览 | 每表最多 3 条真实样本；找一条批次→工单→订单路径；预览不写入 | 主线已实现；不保证全表质量/关联覆盖率 | [QualityMvpController](../../ontoforge-boot/src/main/java/io/ontoforge/boot/controller/QualityMvpController.java) |
| S16 业务追溯 | 输入批次号，真实 JDBC 查询两跳关联，展示对象 ID/标签、来源表/外键证据 | 主线已实现；只读固定 Join、单路径，**不是通用 ObjectSet** | [QualityMvpController](../../ontoforge-boot/src/main/java/io/ontoforge/boot/controller/QualityMvpController.java) |
| S17 失效/错误保护 | 模型 revision 变化、数据源列不匹配、非法标识符、无批次、多路径明确拒绝 | 主线已实现；没有完整运行时质量保证 | [QualityMvpController](../../ontoforge-boot/src/main/java/io/ontoforge/boot/controller/QualityMvpController.java) |
| S18 Windows/CI 可验收性 | 真 PG17 + Boot + Vite/Chromium 用户故事、重启复核与 PS 验收脚本 | 自动化证据已实现；用户既有 Win11 本机验收需另签收 | [E2E](../../ontoforge-ui/e2e/)、[质量门禁](../../.github/workflows/quality-gate.yml) |

### 三个关键概念不能混淆

1. **ObjectType / LinkType** 定义业务语义结构，不代表已有真实实例或通用图谱投影。
2. **本地快照**是把合法模型结构固定留存，并核验其字节摘要；**不是**审核、正式发布、激活或回退。
3. **绑定与追溯**目前只是特定 `manufacturing.quality` 包和三张本机白名单测试表：查询结果是真实 SQL 返回的测试事实，**不是工业生产系统已打通**。

## 3. 具体用户操作与可预期结果

### 3.1 从零建模及留存历史（S02–S11）

1. 启动本机 PG、Spring Boot 和 Vite，进入 `/#/model/packages`。
2. 创建一个包（如 `manufacturing.production`）；在该包创建多个 ObjectType、Property、主键以及 LinkType。
3. 对不完整的编辑保存 WIP，刷新仍可取回；校验报告应区分 `INCOMPLETE` 等状态。
4. 修订完整后保存并再次校验为 `VALID`；旧校验在任何保存后失效。
5. 以当前 revision、UUID idempotencyKey 冻结，只生成 **`LOCAL_SNAPSHOT_ONLY`**；刷新仍可按 ID/修订/幂等键恢复。
6. 再修改草稿并冻结另一修订；打开历史快照、对比增删/变更和 `POTENTIALLY_BREAKING` 等风险。
7. 两标签并发保存冲突返回 409；同幂等键重试不会产生不同快照，校验与保存需要最新 revision。

**用户获得**：可重复打开、可比较、有来源修订的*本地*模型版本历史，不获得对外已批准模型的证明。

### 3.2 工业质量追溯的最短闭环（S12–S17）

进入 `/#/mvp/quality`，依次完成：

| 步骤 | 操作 | 前端展示/后端事实 | 不能声称 |
| --- | --- | --- | --- |
| 01 本体定义 | 创建示例本体，或打开真实模型工作台 | `ProductionBatch → WorkOrder → CustomerOrder` 的 3 个 ObjectType + 2 个 LinkType，服务端创建草稿并 VALID 校验；不覆盖已有编辑 | 不代表全平台自动建模 |
| 02 来源绑定 | 展开 PostgreSQL 表与字段；选择批次表/状态/关联外键、工单表/关联订单字段、订单表/客户展示字段 | `information_schema` 的表与列；固定 10 项映射；支持保存到 PG JSONB，锁定草稿 revision | 不代表通用 Connector |
| 02 检查证据 | 预览真实样本与关联，再明确保存 | 三表各最多 3 行；至少找到一条可能连通路径时展示；预览不改绑定；字段变化后旧预览失效 | 一条匹配不代表全部匹配 |
| 03 查询 | 输入本机表中的批次 ID | 参数化 JDBC 双 JOIN，返回批次质量状态、工单、客户订单、表名/关联列 | 不代表通用 ObjectSet、全量影响分析 |

**内置合成记录**：`B2026-001 (BLOCKED) → WO-1001 → ORD-9001`。另有 `B2026-002 → WO-1002 → ORD-9002`。这是 Flyway V4 明确插入的合成数据。PR #33 还在 CI 隔离库创建了不同字段名的 `demo_*` 三表，验证 `LOT-701 → JOB-77 → SO-88`。后者并非生产数据。

**严格查询限制**：批次号需匹配 `[A-Za-z0-9-]{1,64}`；未知批次 404；未绑定、绑定 revision 已过时、来源结构漂移以及一次查询发现多条路径等情况返回可核对错误；`LIMIT 2` 仅用于检测多路径并拒绝，不会截断结果后谎称“全部订单”。

## 4. API 覆盖面（当前 main 可运行）

| 功能 | 方法与路径 | 主要返回/作用 |
| --- | --- | --- |
| 服务状态 | `GET /api/system/status` | 安装 UUID、连接状况、模型/快照标记 |
| 草稿列表/新建 | `GET /api/model/drafts`、`POST /api/model/drafts` | 草稿分页、创建 packageId 唯一草稿 |
| 草稿读取/保存/校验 | `GET /api/model/drafts/{draftId}`、`PUT /api/model/drafts/{draftId}`、`POST /api/model/drafts/{draftId}/validate` | 编辑文档、CAS revision、问题报告 |
| 快照冻结/重查 | `POST /api/model/drafts/{draftId}/snapshots`、`GET .../by-key/{idempotencyKey}`、`GET .../by-revision/{revision}` | 幂等本地快照，非发行 |
| 快照列表/读取/差异 | `GET /api/model/packages/{packageId}/snapshots`、`GET /api/model/snapshots/{snapshotId}`、`GET /api/model/snapshot-diffs?from=&to=` | 只读历史与同包差异 |
| MVP 状态/初始化 | `GET /api/mvp/quality/status`、`POST /api/mvp/quality/model` | 固定制造质量示例模型 |
| 来源目录/预览 | `GET /api/mvp/quality/sources`、`POST /api/mvp/quality/preview` | 测试表目录、限量样本及一条关系 |
| 来源绑定 | `POST /api/mvp/quality/bind`、`GET /api/mvp/quality/binding` | 10 项 PG 来源映射，版本受限 |
| 追溯 | `GET /api/mvp/quality/trace?batchId=...` | 一条两跳关联路径及来源 |

上述不是 OpenAPI/SDK 的正式对外承诺；**不存在通用实例 CRUD、Action 执行、正式发布、MCP/Agent 查询接口**。

## 5. 面向全局功能 F01–F29 的成熟度映射

采用 [总体架构 V1.3](../本体产品总体功能与技术架构设计_v1.3.md) 固定功能编号；“覆盖子项”不等于功能域交付完成。

| 功能域 | 现有已实现子项 | 未实现或仅验证 |
| --- | --- | --- |
| F1 本体设计与发布（F01–F05） | F02 多类型/关系/属性；F03 部分结构验证；F04 **本地历史子项**；F05 基础模型包 | F01 工作空间、正式 F04 审核/发行/激活/回滚、F05 跨包依赖 |
| F2 连接与语义映射（F06–F08、F26） | F08 **单 PG 测试表 10 项显式映射子项** | F06 生产连接器目录、F07 实体消歧、F26 对齐/融合、通用 F08 |
| F3 对象运行时（F09–F12） | F09 测试行按对象类型输出子项、F11 固定只读两跳子项、F12 有限追溯示例 | 通用 Object Store、LinkFact、事件状态、ObjectSet AST 执行、完整影响面 |
| F4 知识与分析（F13–F15、F27） | 无生产产品功能 | 知识证据、规则推理、专业诊断、图谱工程 |
| F5 Agent 与行动（F16–F18） | 无生产产品功能 | Context Engine、Capability Registry、Action Runtime |
| F6 语义安全与治理（F19–F20） | 快照摘要、修订/幂等键等局部**完整性**证据 | 服务端用户/对象级权限、可信治理、操作全链路审计 |
| F7 Studio/生态（F21–F23） | F21 本地 React Studio 子项、F23 本机状态子项 | SDK/MCP、正式制造模板市场、生产运维 |
| F8 语义协同工程（F24–F25、F28–F29） | F28 结构校验子项 | 术语协同、可信审核工作流、AI 生成与反馈 |

**不要把 F01–F29 的“已设计”当成实现比例**；产品质量与覆盖面是两根独立坐标轴。

## 6. 尚未合并的真实候选与纯研究资产

| 项目 | 在仓库的状态（截至该基线） | 实际内容 | 不应误写成 |
| --- | --- | --- | --- |
| [PR #17](https://github.com/gitfortian/ontoforge/pull/17) | **Open / Draft / 未合并**；最近一次已消除冲突且独立 CI 成功 | B0 可信 OIDC/JWT 身份与模型包授权候选，拟以 **V5** 叠加 main 的 V4；包含测试 IdP 与真实 E2E | main 已登录/授权、企业级多租户、正式已发行 |
| [PR #34](https://github.com/gitfortian/ontoforge/pull/34) | **Open / Ready for review / 未合并**；最近一次自动化检查成功 | Windows 本机 `ontoforge_test_*` 隔离库的脱敏 CSV 导入脚本、合成示例及重入校验 | main 已内置导入 UI、已连接真实工业源 |
| [Native V0.2 JSON Schema/AST](../design/核心本体元模型与Native-DSL-AST契约_v0.2.md) | 设计合同和测试代码存在；已实现在**有限的草稿→候选→快照**链路中使用其包 Schema | ObjectSet 查询/结果、语义执行记录等 schema 合同 | 所有合同都有运行时执行器 |
| [PG ObjectSet/Outbox POC](../../experiments/pg-runtime/README.md) | `experiments/` 下独立可运行实验，独立 CI | 高扇出查询、RLS/字段可见性、Mock ERP、Outbox 幂等竞态研究 | 产品 ObjectSet/Action 已上线 |
| OFIP-0005、治理交互原型 | 规划/原型/候选 | ChangeSet、Review、Release、Activation 的产品与合同设计 | 已存在可靠的服务端审核发行流程 |

## 7. 功能验收证据与局限

| 功能群 | 实际测试入口 | 最近关联交付证据 | 不能替代的验收 |
| --- | --- | --- | --- |
| Native 对象与关系草稿 | `NativeDraftTest`、`NativePackageSchemaTest`、`test_real_native_draft.py` | 已合并 #7/#13 及当前 `main` CI | 用户真实建模可用性、专业领域一致性 |
| 快照、完整性、差异 | `NativeSnapshotTest`、`test_real_native_snapshot.py`、`verify-snapshot.ps1` | 已合并 #11 及现有真 PG17 E2E | 生产审计、灾备、发布审批 |
| 制造质量测试追溯 | `test_real_mvp_quality.py`、Flyway V4、真实 Chromium | [#32](https://github.com/gitfortian/ontoforge/pull/32)、[#33](https://github.com/gitfortian/ontoforge/pull/33)，其中 #33 的 [12 项 CI](https://github.com/gitfortian/ontoforge/actions/runs/39007977105) 通过 | 真实工厂数据的质量、血缘完整性、权限和场景准确性 |
| 导入（仅 PR 候选） | `scripts/import-quality-test-data.ps1` 与对应独立 PostgreSQL CI | [#34](https://github.com/gitfortian/ontoforge/pull/34) 自动检查通过但未合并 | 用户实际脱敏工业数据接入与 Win11 使用验收 |
| 安全（仅 PR 候选） | B0 JwtDecoder/真实浏览器模拟 IdP/授权反例 | [#17](https://github.com/gitfortian/ontoforge/pull/17) 独立 12 项 CI 成功但仍 Draft | 企业 IdP、安全架构评审和正式生产身份管理 |

## 8. 已知缺口、风险和最小下一步

1. **当前 main 无生产身份/权限控制**：只能在本机隔离环境展示；不得绑定真实生产秘密或把 8080/5173 公开到外网。B0 PR 的绿色 CI 不改变 main 此事实。
2. **MVP 模型与绑定并非一套通用语义编译器**：模型包固定、字段映射固定为三表七列角色；没有实体去重、动态查询计划、全量源事实/投影、跨数据源 Join。
3. **样本预览不等于数据验证**：每表取最多 3 行、只试一条成功关联；不报告全量匹配率。当前查询遇到多个匹配结果拒绝以免误报，无法回答“一批影响多个订单”的生产真实需求。
4. **来源证据有限**：返回表名/列名/对象 ID，未提供一致性快照时间、权限证据、稳定源版本或可重建的 SourceFact/LinkFact 存储。
5. **进度事实老旧文案仍存在**：旧 README/历史矩阵中的 “P1 未实现”需按日期理解，不能覆盖本文当期 main 的**有限测试场景**。本文不改旧规划 ID 与接口合同。
6. **Windows 11 用户现有安装现场验收未签收**；已有 Linux CI + Windows 脚本解析不等于现场功能体验完成。

**建议的最短下阶段**：先审核/合并独立 [PR #34](https://github.com/gitfortian/ontoforge/pull/34)，用一份获得授权的**脱敏**制造测试数据完整跑“导入 → 映射预览 → 保存 → 两跳查询”，观察实际关系是否一对多及返回数量；再决定是否要扩展 ObjectSet 的“多受影响订单分页结果”。B0/#17 可独立继续评审，但不应成为验证这个基础工业故事的借口或阻塞项。

---

**维护规则**：本文件只描述对应 main SHA 的 AS-IS。每次功能合并后在后续 PR 中更新“已合并/候选/未实现”及证据链接，不反向修改 V1.3 的概念架构，也不将单项 CI 通过等价为功能发布。
