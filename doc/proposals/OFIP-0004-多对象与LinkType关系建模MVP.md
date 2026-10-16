# OFIP-0004｜P0-A 下一阶段：多对象与 LinkType 关系建模的真实闭环

**Status:** Discussion（第一轮产品/领域评审稿；非 Accepted）

**Discussion:** [PR #12 — OFIP-0004 第一轮产品/领域设计评审](https://github.com/gitfortian/ontoforge/pull/12)（Draft）

**Decision:** Pending。本文的推荐方案不是维护者已签收合同；没有 `[VOTE] Accepted` 不得进入正式功能实现 PR。

**Implementation:** Not Started。此 PR 只提交设计和评审证据，不修改生产模型/数据库/API/前端。

**Release:** Not Released

**Related:** [唯一现行 V1.3 总体架构](../本体产品总体功能与技术架构设计_v1.3.md) · [Native DSL/AST V0.2](../design/核心本体元模型与Native-DSL-AST契约_v0.2.md) · [OFIP-0002 草稿建模](./OFIP-0002-ObjectType-草稿建模MVP.md) · [OFIP-0003 快照与发布边界](./OFIP-0003-S0-MVP-0.3-模型快照与发布边界.md) · [PR #11（0.3 代码候选）](https://github.com/gitfortian/ontoforge/pull/11)

> 本轮对产品负责人的一句话解释：**不仅能定义“生产批次有哪些字段”，还可以定义“生产批次与工单、订单存在什么类型的业务关系”，并像对象模型一样保存、检查、留存历史和比较变更。**
>
> **只定义关系类型，不生成真实业务关系**。例如设定「生产批次关联生产工单」不代表系统已经知道任何一条真实工单，也不能声称“查出了订单”。真实关系必须等 P1 Data Binding、身份解析与投影索引另行实现。

## Motivation｜为什么是下一阶段

### 一、全局定位（产品能力，而非代码数量）

| 全局功能 | 当前能做的子范围 | 本次希望新增的用户能力 | 本轮完成后整体成熟度 |
| --- | --- | --- | --- |
| F02 可视化本体建模 | 单个 ObjectType 与 Property 编辑的基础体验；模型包存储结构允许数组 | 在同一模型包创建多个对象，选择来源/目标对象定义 LinkType 关系 | **F02 的关系定义子项基础可用**；远非完整可视化/专业建模 |
| F03 约束与验证 | ObjectType/Property/主键的基础校验 | 校验两端引用、同包作用域、基数、证据要求、重复身份与不完整关系 | **F03 增加关系校验子项**；跨包等专业校验仍未完成 |
| F04 版本发布 | PR #11 已开发本地 Snapshot、历史/结构 Diff；尚未合并、未发布 | 新模型候选能连同关系一起冻结；历史对比反映关系的变更，不丢弃资产 | **F04 历史子项扩展到关系**；审核/正式发布/激活仍未实现 |
| F05 模型包与依赖 | 本地 packageId + 当前可写草稿 | 在同一包下引用对象，关系与对象共享草稿修订 | **包内组合能力提升**；跨包导入/依赖锁未实现 |
| F28 语义质量门禁 | 基础结构风险提示 | 跨版本关系引用/基数/证据要求变化提醒 | **仅局部质量子项**；不做下游消费者兼容认证 |

**阶段候选名**：`S0-MVP-0.4 / P0-A LinkType 关系建模`。编号只用于讨论，不是发布承诺。P0 专业建模 MVP 仍需要可信工作空间、审查发布、依赖与更多治理能力。

### 二、发现的真实阻断（现有代码证据）

1. **前端只有一个对象编辑态**：`ontoforge-ui/src/NativeModelWorkspace.tsx` 将 `objectTypes[0]` 映射到唯一编辑表单；发现多个 ObjectType 或已有 LinkType 时将草稿切只读，不能安全编辑。
2. **草稿接口允许对象数组，但明确拒绝任何 LinkType**：`DraftValidation.checkEditor` 对 `linkTypes` 非空报 `UNSUPPORTED_LINK_EDITOR`；`validate` 当前生成候选时直接写空 `linkTypes`，若只放开保存可能造成**静默丢失关系资产**。
3. **0.3 快照当前只允许一个对象、零关系**：`NativeSnapshotService.freeze` 限制 `objectTypes.size()==1` 且 `linkTypes` 为空；即使新增关系编辑器，也不能直接完成「关系留存与版本追溯」用户故事。
4. **Native V0.2 Schema 已明确定义 LinkType 形状**：`id`、`apiName`、`sourceTypeRef`、`targetTypeRef`、`cardinality`、`evidenceRequired`，不应另造第二套关系语言。
5. **依赖 PR 状态**：[PR #11](https://github.com/gitfortian/ontoforge/pull/11) 为 Draft / Not Merged；最新代码候选 Linux PG17/React/Chromium CI 通过，但 Windows 11 Native PG 真重启仍未验收。设计可并行评审，**关系代码实现必须基于已合并或已确认的 0.3 Snapshot 合同**，绝不合并未核对版本的两个开发分支。

### 三、用户和真实业务问题

角色：制造领域架构师、质量/生产工程师、本体设计者。用户需要为 `ProductionBatch`（生产批次）、`WorkOrder`（生产工单）、`CustomerOrder`（销售订单）分别定义对象属性与主键，再说明「生产批次关联生产工单」「生产工单关联销售订单」是什么类型、方向、基数以及后续实际关系是否必须有来源证据。

当前只能编辑一个对象类型，无法正确建立该模型结构，后续源绑定/查询/Agent 都缺少可依赖的关系元模型。

### 四、成功定义：一条可被浏览器验收的黄金故事

1. 用户打开已有测试模型包 `manufacturing.production`；在「对象类型」区域能保留并编辑原来的生产批次。
2. 点击**新增对象类型**，创建 `WorkOrder` 与 `CustomerOrder`，分别定义主键和至少一个属性。对象与属性的稳定 ID 不能由页面排序决定。
3. 切换到**关系类型**区域，新增 `batch-to-workorder`，填写业务名称，**选择已存在的来源对象 `ProductionBatch`、目标对象 `WorkOrder`**，并明确选择基数（例：MANY_TO_ONE）、是否要求来源证据。
4. 再定义 `workorder-to-order`，目标是 `CustomerOrder`。保存草稿后刷新/重新打开，三个对象和两条关系定义都在。
5. 点击**检查模型完整性**。如果两个端点存在、ID 不重复、基数合法、所有 ObjectType 完整，应收到当前修订 `VALID`；此时仍**不是审批或正式发布**。
6. 通过确认框冻结一份**本地历史快照**。随后修改某关系的基数，保存→重新校验→冻结第二份；在历史列表选择两版比较，明确提示关系发生了可能影响未来消费者的结构变化。
7. 即使关系指向不存在的对象、用户尝试删除被引用的对象或发生并发保存冲突，也必须保留本地编辑、给出明确错误/风险说明，且**不能出现“通过但关系消失”或覆盖旧历史**。

**用户最终能看到的效果**：对象列表、关系列表、关联对象选择、字段级校验定位、保存后重新打开、完整历史、关系变化风险提醒。**用户此时还不能看到任何真实生产批次记录或订单影响查询。**

## Public Interfaces｜领域模型和公共合同（讨论候选）

### 一、保持现有权威，避免引入第二套模型

- `NativePackage.objectTypes: ObjectType[]`、`NativePackage.linkTypes: LinkType[]` 保持 Native V0.2 定义；本轮不发明「关系实例」表、graph service、ObjectSet 或 LinkFact。
- `Draft.editingContent` 可以表达**未完成的 WIP**，但必须进行类型、结构、长度、未知字段保护；**保存 WIP ≠ 校验通过**。完整候选由服务端从整个草稿构建，绝不删掉未知资产。
- 一包可含多种 ObjectType 和 LinkType；单对象包、零关系仍必须合法并可继续冻结快照；当前独立快照与未来正式 Release 生命周期保持分离。
- `Property.id` 以所属 ObjectType 为身份作用域；`ObjectType.id` 在包内唯一；`LinkType.id` 在 LinkType 集合内唯一，跨类型冲突的命名空间规则应在 Contract-Gate 冻结。Link `sourceTypeRef/targetTypeRef` 当前只引用**同包 ObjectType.id**。
- 关系类型本身**不等于事实**：`evidenceRequired` 是未来 LinkFact/Binding 的证据约束声明，并不是现在已经发现、认证或建立了生产事实。任何 UI 均不能显示伪关系实例数。

### 二、LinkType 合同字段（沿用 Native V0.2）

| 字段 | 产品语言 | 输入/约束与 UI 反馈 |
| --- | --- | --- |
| `id` | 关系标识 | 包内 LinkType 唯一且稳定，修改标识要提示潜在破坏性变更，不自动把删除+新增猜成重命名 |
| `apiName` | 关系接口名 | 显示为清晰的机器可调用名称；必须符合现有 Native 命名规则 |
| `label`（可选） | 显示名称 | 如「生产批次对应工单」；仅是显示用语 |
| `sourceTypeRef` | 来源对象类型 | 从当前包对象清单选择，不输入随意自由文本造成悬空引用 |
| `targetTypeRef` | 目标对象类型 | 与来源一样选择已定义对象；允许端点相同的自关联，必须显式显示 |
| `cardinality` | 业务关联基数 | `ONE_TO_ONE` / `ONE_TO_MANY` / `MANY_TO_ONE` / `MANY_TO_MANY`；UI 用「一对一／一对多／多对一／多对多」解释，方向从来源→目标理解 |
| `evidenceRequired` | 未来关系是否必须有来源证据 | 必填布尔值；建议创建默认“需要证据”，但不把默认值解释为已生成证据 |

### 三、编辑器 / 完整性 / 版本 / 失败的预期合同

| 用户操作 | 真实服务端权威/结果 | 用户不能被误导的点 |
| --- | --- | --- |
| 保存不完整关系草稿 | 可以保存合同安全的 WIP；revision 增长，旧 `VALID` 失效 | 不完整不等于已通过校验 |
| 检查完整模型 | 所有对象/关系先验检查、每个端点引用、重复 ID 与枚举语义；返回错误位置 | 前端绿色不代表服务端校验通过 |
| 删除被关系引用的 ObjectType | 提示受影响的 LinkType；用户可先清理关系再删除，或显式保留未完成 WIP，但不得暗中改/删关系 | **不得自动级联删除 LinkType 并保存为“有效”** |
| 冻结历史 | 必须是同修订、已通过服务端校验的完整 Candidate；快照包含所有对象和 LinkType | 不裁剪额外对象/关系、不显示伪“已发布” |
| 关系历史对比 | 以稳定 `LinkType.id` 对齐，显示新增/删除、端点变化、基数和证据要求变化 | 结构风险提示不是对消费者的完整兼容认证 |
| 保存冲突 / 网络回执未知 | 409 不覆盖服务器版本，客户端保留本地编辑；冻结沿用原 key 找回 | 不假装保存成功、不重复生成历史 |
| 超出当前支持范围的定义 | 清楚拒绝未实现的 `Binding/Action/ObjectSet` 等资产，不允许静默丢失 | 不因为图谱画出来就声称关系事实已生成 |

**版本与边界**：0.4 不引入独立「正式发行号」、审批人、签名或激活；继续显示 `LOCAL_SNAPSHOT_ONLY`。旧 Snapshot 的 JCS bytes 与 digest 必须完全保持不变，不能为了支持 LinkType 修改 V3 历史行或将旧快照“升级”。

## Proposed Changes｜推荐实施切片与产品交互

### 一、页面信息架构（与现有工作台融合，不造孤立菜单）

```text
本体设计 > 模型包 > manufacturing.production
  ├─ 对象类型（3）    [生产批次] [生产工单] [销售订单]  [+ 新增对象类型]
  ├─ 关系类型（2）    [批次 → 工单] [工单 → 订单]       [+ 新增关系]
  ├─ 完整性检查      先保存 → 检查当前修订 → 错误定位
  └─ 历史版本        冻结本地快照 → 查看 → 选择两版比较
```

- **同一建模工作台中的分区或 Tab**，而不是额外创建「专业工具」一级菜单；减少跨页跳转。
- 创建关系时，先选两个对象，再选择基数和是否必须有来源证据。端点未建立时提供「去新增对象」指引，不让用户填写无法验证的引用。
- 默认列表优先展示业务名称、端点、基数、校验状态；详细 ID/JSON/摘要放在展开区，产品体验不被技术字段淹没。
- 对象删除、关系重连、基数变化须提示潜在破坏；用户主动确认后保存草稿、重新校验。
- 继续继承 0.2 的 dirty 提醒、保存/校验分步、并发冲突恢复和 0.3 的版本历史；不在当前 PR 伪造已开发页面。

### 二、设计建议的一个真实功能 PR（批准之后才开始）

**功能 PR 的完整交付定义**：用户能在实际 React + Spring Boot + PostgreSQL 上，创建多个 ObjectType、两条 LinkType，保存/刷新/重开、校验、冻结、查看历史并看到关系差异，且关键负例可复现。**不能只交付 LinkType 表单或单独补一个 SQL/Java 适配器，然后声称“关系建模完成”。**

拟需要同时核对并修改（供 Contract-Gate 决策，不是已实施变更）：
1. `ontoforge-ui/src/NativeModelWorkspace.tsx`：从单 ObjectType 局部编辑态转为整个 `EditorDoc` 草稿建模、对象/关系选择和字段级错误定位；旧的多类型草稿不再因加载而被悄悄覆盖。
2. `DraftValidation`：区分 WIP 的安全结构、完整 Native Candidate 的语义校验；关系引用端点须是同包确实存在的 ObjectType；完整候选保留**全部**对象与 LinkTypes。
3. `NativeSnapshotService`：放开当前“一对象、零关系”的冻结限制，但仍拒绝 Binding/ObjectSet/Function/Action 等其他未支持资产；关系历史 Diff 不被丢弃；保护旧历史与摘要。
4. Java/真实 PostgreSQL/HTTP/Chromium：多对象+关系的黄金故事、并发冲突、脏编辑、删除关联对象、未知字段、结构差异的真实回归。
5. 检查 `NativePackageSchema`、Native V0.2 机器合同及 `doc/design/tests/` 的语义是否一致；**不因 UI 需要擅自改 V0.2 现行 Schema 定义**。

### 三、版本依赖和兼容性

- **必须基于已验证的 0.3 代码并完成 Git 集成**：PR #11 处于 Draft，故本设计 PR 与其独立；未来实施前重新读取 main/head，确认 Snapshot 现有接口和 0.3 的不可变性/异常合同。
- 旧版只含一个 ObjectType 的草稿可继续编辑、校验与冻结；多对象/关系 WIP 应原样读取，不自动降级成只读的第一对象/丢弃资产。
- 如果现有 `native_package_draft.editing_document JSONB` 与 `native_schema_snapshot.canonical_payload` 已足以存储数组，**优先不增加新表**。是否真无 Flyway V4 需在合同评审中以代码、约束和迁移测试证明，不能无根据宣称。
- 旧快照不修改、不再计算新的“替代摘要”；新快照按相同 JCS/SHA-256 规范化。对比历史中旧版零关系与新版有关系的情况须给出新增差异，不能 500 或空白。
- 修改旧关系的主键/ID/方向/端点不自动迁移实例（当前压根没有实例）或猜测语义重命名。
- 正式审核、发布、激活、跨包依赖、可信权限、绑定、ObjectSet、Agent 和 Action 均为**非目标**，不得以此文档偷渡进本阶段。

## Compatibility, Deprecation, and Migration Plan

| 范围 | 计划与强制兼容门禁 |
| --- | --- |
| Draft / 对象 ID | 保留旧版本包、revision、草稿 API；旧单对象用户故事不得回归 |
| Native Schema | 原有 `linkTypes` 契约字段与基数枚举保留，正式变更 V0.2 需独立证据/评审 |
| PostgreSQL / Flyway | 现有 V1/V2/V3 迁移只增不改，已有 installationId 与历史快照永远不重建；是否新增 V4 由实际证据决定 |
| 快照摘要 / 历史 | 旧 canonical bytes、SHA 不得发生变更；新模型关系必须完整收进快照与 Diff |
| 版本与发布 | 仅 LOCAL_SNAPSHOT_ONLY；SchemaRelease/Approval/Activation 不在本次 |
| 安全和外部事实 | 无真实工作空间/授权，必须保持 loopback + 测试库限制；Binding/ObjectSet/Action 仍不存在 |
| 外部执行 / 业务回滚 | None：本阶段不读写 MES/ERP/QMS，也没有 Action 实际效果 |

## Test Plan｜实施后必须证明（本提案 PR 全部 NOT RUN）

| 验收编号 | 用户真实故事/正常结果 | 关键负例或风险 | 实施证据要求 |
| --- | --- | --- | --- |
| R01 | 同一包创建并重开 3 个完整对象 | 旧草稿对象不被覆盖、对象 ID 重复 | PG17+真实 HTTP+Chromium |
| R02 | 创建 2 条不同方向/基数的 LinkType | 两端引用同包对象、重名 ID、未知端点、非法枚举 | Native 合同+PG/HTTP |
| R03 | 保存不完整 WIP，校验提示问题 | 保存后不可假 VALID、所有问题可定位 | HTTP+Chromium |
| R04 | 修改被 LinkType 引用的对象并重检 | 不能静默级联删关系，UI 告知受影响引用 | HTTP+Chromium |
| R05 | 关系候选通过完整校验 | 未支持 Binding/Action/额外未知字段拒绝 | Java+PG17 |
| R06 | 将完整多对象+关系冻结为第一份快照 | 原本一对象快照仍正常、历史 digest 不变 | PG17+JCS+HTTP |
| R07 | 第二份快照修改基数/端点，Diff 正确 | 删除/新增关系、字段不同、旧版零关系 Diff、跨包拒绝 | PG17+HTTP+Chromium |
| R08 | 确认并发/未知响应不会丢编辑或重复冻结 | 409、同 key 重试、网络中断、revision 过期 | PG17+HTTP+Chromium |
| R09 | 重新打开历史 22+ 版本并跨页对比 | 分页漏选/重复、坏摘要必须 fail closed | 真实浏览器 E2E |
| R10 | Windows 原生 PG 真重启后找回关系定义和快照 | 不允许把 Linux CI 当做用户本机验收 | 实机单独 Gate：NOT RUN |

**所有表中内容目前是未来验收标准，不是本设计 PR 的“测试通过”。** 实施时必须逐项提供运行 SHA、CI 链接与负例证据。维护者未提供 Windows 本机证据之前，仍记 NOT RUN。

## 评审分轮与签收

| 关口 | 本轮输出 | 通过后才允许什么 |
| --- | --- | --- |
| **DG-1 产品/领域（本轮）** | 上面的用户故事、功能范围、关系语义、不可丢失不变量与替代方案 | 允许进入详细技术合同设计 |
| TC-2 接口/持久化合同 | DTO、WIP/VALID 约束、包内身份、Snapshot/Link Diff、版本/迁移与异常矩阵 | 允许低保真交互设计 |
| UX-Low | 一个模型包内的对象/关系/校验/历史用户路径，正常和错误状态 | 允许高保真原型 |
| UX-High | 与 OntoForge 统一视觉一致，含复杂状态、错误恢复及页面验收矩阵 | 允许独立真实功能 PR |
| Implementation | 真 Spring Boot/PG17/React、真实浏览器和合同负例、Windows 实机独立复验 | 允许最终合并/发布评审（仍不代表生产准入） |

### DG-1 请优先审核的六个决策点（推荐 A，待签收）

| 编号 | 推荐 A | 另一备选及权衡 |
| --- | --- | --- |
| DG1 产品范围 | **A**：多对象定义 + LinkType + 真实校验 + 快照 + Diff 一次闭环 | B：只做关系表单；会留下无法追溯关系的半成品 |
| DG2 类型作用域 | **A**：只允许引用同包对象类型 | B：跨包引用需要 Import/DependencyLock/ACL，当前无可信支持 |
| DG3 用户交互 | **A**：原建模工作台中整合对象/关系分区 | B：独立专业工具/新一级菜单使流程割裂 |
| DG4 WIP 保存 | **A**：安全结构允许不完整 WIP，完整校验后才冻结 | B：每次保存必须 VALID，妨碍逐步建模 |
| DG5 关系证据 | **A**：evidenceRequired 只是未来关系事实证据要求声明 | B：误认为已生成真实 LinkFact，混淆 Schema 与业务事实 |
| DG6 版本兼容 | **A**：0.3 Snapshot 不变性完全继承，关系必须被完整收录和对比 | B：只留存对象不留关系，产生静默资产丢失 |

**本次建议的通过条件**：确认以上产品方向后，再细化基数、引用、删除、空值及错误码，进入 TC-2。未经显式签收不自动创建正式开发 PR。

## Rejected Alternatives

| 替代思路 | 为什么当前不采用 |
| --- | --- |
| 只在前端画关系线，不保存真实数据 | Demo 风险：刷新后无真实关系，不可验证 |
| 把 LinkType 直接当成数据库外键或具体对象实例 | 混淆模型定义与生产数据、误导后续业务查询 |
| 每个 LinkType 单独建一个任意关系表，脱离 Native Package | 制造第二套身份与版本权威，不符合已定 Native V0.2 |
| 沿用单 ObjectType 保存后直接拼关系 JSON | 0.2/0.3 的保护机制会拒绝或在 Candidate 中清空关系，数据丢失风险 |
| 立刻一并实现跨包引用、Binding、查询、Agent | 扩大边界，无法形成一期可验收用户故事 |
| 先造通用图数据库服务 | 当前只需关系类型定义，不存在强制图存储的业务事实证据 |

---

**Decision Gate：本文为讨论稿；第一轮产品/领域审查优先，无正式实施授权、无生产代码更改、无可宣布的新功能。0.3 Draft/Windows NOT RUN 需在未来实施依赖前明确收口。**
