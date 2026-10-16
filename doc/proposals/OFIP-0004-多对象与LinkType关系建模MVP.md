# OFIP-0004｜P0-A 下一阶段：多对象与 LinkType 关系建模的真实闭环

**Status:** Accepted

**Review Phase:** DG-1 / TC-2 / UX-Low / UX-High 已完成受托产品决议；独立第三方代码审核及 Windows 实机验收仍各自有独立门禁

**Discussion:** [PR #12 — OFIP-0004 四轮设计与原型](https://github.com/gitfortian/ontoforge/pull/12)；产品设计决议 Accepted，不等于功能发布

**Decision:** [VOTE] Accepted — 受托产品阶段决议 https://github.com/gitfortian/ontoforge/pull/12#issuecomment-6214014668 （由 ChatGPT 按本次会话产品负责人连续指示继续实施受托记录；并非声称第三方 GitHub 审核人亲自批准）。本次仅批准 DG1–DG6 推荐 A、TC1–TC14 和 UX-Low/High 的既定 0.4 范围；不授权真实工业数据连接、Release、权限或 Action。

**Implementation:** Code Merged — [真实功能 PR #13](https://github.com/gitfortian/ontoforge/pull/13) 已合并（`605b9976`），依赖 PR #11 已合并。当前成熟度和环境验收统一见[产品进度总账](../implementation/OntoForge-MVP-产品功能全景与成熟度矩阵.md)。

**Release:** Not Released

**Related:** [唯一现行 V1.3 总体架构](../本体产品总体功能与技术架构设计_v1.3.md) · [Native DSL/AST V0.2](../design/核心本体元模型与Native-DSL-AST契约_v0.2.md) · [OFIP-0002 草稿建模](./OFIP-0002-ObjectType-草稿建模MVP.md) · [OFIP-0003 快照与发布边界](./OFIP-0003-S0-MVP-0.3-模型快照与发布边界.md) · [PR #11（0.3 代码候选）](https://github.com/gitfortian/ontoforge/pull/11)

> 本轮对产品负责人的一句话解释：**不仅能定义“生产批次有哪些字段”，还可以定义“生产批次与工单、订单存在什么类型的业务关系”，并像对象模型一样保存、检查、留存历史和比较变更。**
>
> **只定义关系类型，不生成真实业务关系**。例如设定「生产批次关联生产工单」不代表系统已经知道任何一条真实工单，也不能声称“查出了订单”。真实关系必须等 P1 Data Binding、身份解析与投影索引另行实现。

## 受托设计决议记录｜2026-10-16

> 以下保留设计评审时的历史记录；各节“Draft/未合并/未实现”仅描述该轮评审基线，后续实现状态见上方元数据及产品总账，不作为另一份当前进度。

- **依据**：产品负责人在四轮设计与低/高保真验证后，连续指示“继续实施”“继续下一阶段”，授权进入完整真实功能开发及集成收口。本条是**ChatGPT 受托产品阶段记录**，不是伪称某位第三方审核人提交 GitHub Approve Review。
- **批准范围**：DG1–DG6 全部推荐 A；TC1–TC14 的同包 typed identity、WIP/VALID、关系端点/基数/证据、CAS、不丢资产、原 0.3 快照不变量和结构风险合同；UX-Low/High 的同一 Studio 工作台、对象/关系切换和恢复路径。
- **依据证据**：低保真 17 项 Mock 状态逻辑；高保真 12/12 独立 Chromium；真实功能候选见 [PR #13](https://github.com/gitfortian/ontoforge/pull/13) 的隔离 PostgreSQL/Java/React/Chromium；功能验收与设计签收不互相替代。
- **禁止范围**：跨包 LinkType、数据绑定/真实 LinkFact、企业安全授权、正式审查/发布/激活、Agent、Action 或任何面向实际工厂生产数据的操作。
- **独立未完成门禁**：先合并 0.3 PR #11 的可信基线、正确处理 PR #12 / PR #13 的主线集成冲突、完成正式代码评审；Windows 11 本机 PostgreSQL 的真重启与持久化证据仍 NOT RUN，不能由 Linux CI 代替。

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


### TC-2：多对象与关系的详细功能合同（第二轮评审候选）

> **状态：Contract-Gate-2 Discussion / NOT ACCEPTED / Implementation NOT STARTED。** 本章根据 main 的 DraftValidation、NativeDraftService、NativeDraftController、Native V0.2 Schema，以及 PR #11 尚未合并的 NativeSnapshotService、SnapshotController 和 Flyway V3 核对而来。以下所有新增行为均为**推荐合同**，不是已经运行的功能。

**产品层面的目标**：一个模型包可以完整描述“生产批次—生产工单—销售订单”，关系与对象一起保存、校验、追溯和比较；失败时用户看到具体原因，不会丢关系、误认为完成发布或凭空产生真实业务对象。

#### TC1｜稳定身份、名称与引用规则

| 资产 | 身份作用域 | 当前推荐的产品/校验约束 |
| --- | --- | --- |
| 模型包 | packageId | 沿用域式名称，一个包只有一个可写 Draft；本期无可信多空间 |
| 对象类型 | packageId + ObjectType.id | 同包 id 和 apiName 分别唯一；改 label 不改变身份，改 id 属于新身份 |
| 属性 | packageId + ObjectType.id + Property.id | 字段 ID、API 名在所属对象内部唯一，允许不同对象的属性同名 |
| 关系类型 | packageId + LinkType.id | 同包关系 ID 和 apiName 分别唯一；同字符串的 ObjectType ID 与 LinkType ID 可并存，但必须按 kind 区分 |
| 关系源和目标 | sourceTypeRef、targetTypeRef | **仅按同包 ObjectType.id 精确匹配**，不按显示名称或数组位置猜测，不跨包隐式引用 |

允许对象自关联（工单关联上级工单）；更改对象 ID 后关联不会被暗中改写或迁移。Native 定义 ID 正则沿用 ^[a-z][a-z0-9-]{1,63}$，API 名正则沿用 ^[A-Za-z][A-Za-z0-9]{1,80}$。本轮不新造第二套关系身份。

#### TC2｜LinkType 的最小字段合同与基数方向

| 字段 | 类型 / 必须性 | 产品显示与推荐校验 |
| --- | --- | --- |
| id | string；完整候选必填 | 稳定标识，同包关系列表不得重复 |
| apiName | string；完整候选必填 | 程序名称，同包关系列表唯一 |
| label | 可选 string | 业务显示名；如未填不强制，存在则必须满足 Native 长度 |
| sourceTypeRef | string；完整候选必填 | 从当前包选择**来源对象**，必须精确存在 |
| targetTypeRef | string；完整候选必填 | 从当前包选择**目标对象**，必须精确存在 |
| cardinality | enum；完整候选必填 | ONE_TO_ONE / ONE_TO_MANY / MANY_TO_ONE / MANY_TO_MANY |
| evidenceRequired | boolean；完整候选必填 | 表示**未来真实关系**是否必须具备来源证据，不代表当前已有证据 |

- 基数方向统一解释为**来源对象 → 目标对象**，ONE_TO_MANY 为每个来源可有多个目标、每个目标至多一个来源；MANY_TO_ONE 反之；ONE_TO_ONE 双向至多一个；MANY_TO_MANY 双向可多个。
- “至多”**不保证实际存在关联**，也不代表最小基数为 1。未来真实 LinkFact 及数据绑定另行实现。
- 创建关系时建议 evidenceRequired 默认 true，但用户必须能明确更改；布尔字段不接受字符串 true/false、数字 0/1 代替。
- 本期不扩展 inverseOf、数据库外键、实例关系、周期生效、跨包链接。Native V0.2 linkType Schema 不随 UI 设计改版本。

#### TC3｜编辑草稿：暂存未完成 ≠ 静默丢资产

沿用 EditorDoc 的 editorSchemaVersion = 0.1.0；objectTypes 与 linkTypes 都为**数组**。PUT 整体 editingContent，不新增另一套“仅更新关系”的权威接口。

- WIP 保存允许**结构安全但业务信息未填写完毕**：比如新 LinkType 未选目标、未选基数；校验应返回 INCOMPLETE，而不是阻止合法编辑进度。
- 根对象及 LinkType 深层必须拒绝未知字段、错误 JSON 类型和超限内容。若已经存在未支持资产，前端不能只提交可见部分“修好”它；需要只读告警或显式受控转换。
- 保留 256 KiB 编辑文档上限、最多 50 个 ObjectType、每类型 100 属性、总属性 1000。推荐最多 200 个 LinkType，并且最终 Candidate 仍不得超过 256 KiB；越界拒绝整个保存，而不是截断。
- 每次提交是完整聚合，必须包含**全部对象和关系**；原子写入一个 revision，成功后旧校验报告失效。不得仅复制当前 Tab 选中对象而覆盖其他资产。
- 现代码对非空 linkTypes 返回 UNSUPPORTED_LINK_EDITOR；**只删除这一检查仍不够**，因为当前完整 Candidate 生成时固定 linkTypes 为空。两个环节必须一起实施，并有“关系未丢失”回归。

#### TC4｜校验：有效候选必须完整保留支持的对象和关系

| 结果 | 用户真实含义 | 示例 |
| --- | --- | --- |
| INCOMPLETE | 结构安全、业务字段缺失，可继续编辑 | 新关系的 source/target/cardinality 未选 |
| INVALID | 存在语义冲突或不合法值，必须修复 | 重复 Link ID、未知对象端点、无效基数 |
| VALID | 当前**已保存 revision** 的全部对象、字段和关系通过服务端校验 | 3 个对象、2 条关系的 Native Candidate 完整可用 |

- POST validate 使用 expectedRevision；过期修订须返回 409，不得对未保存页面内容错误宣称已校验。
- 推荐错误结构沿用 {path,code,message}，路径为 /linkTypes/0/sourceTypeRef、/linkTypes/1/cardinality 等；后端 path 对应当次 revision 的数组顺序，前端需要把问题定位到稳定 Link.id 的编辑卡片。
- 缺失与冲突混合出现时，按 INVALID 返回并提供完整可用的问题列表，不能只显示第一项。
- 构造 Candidate 时必须包含**全部**合法 objectTypes 与 linkTypes；只允许消除约定的可选空 label/description，绝不删关系。
- 上述完整校验仍不等于 Review/Release/Activation。imports、bindings、linkBindings、objectSets、functions、rules、decisionStrategies、policies、actions 本阶段一律不假装实现或静默纳入。

#### TC5｜删除与变更：关系引用不得被偷偷级联

| 场景 | 草稿保存 | 校验/冻结 | 用户看到的行为 |
| --- | --- | --- | --- |
| 修改对象 label | 允许 | 关系仍按 id 有效 | 关系显示名称更新 |
| 修改对象 id | 显式确认后可保存 | 旧 Link 仍引用旧 id 时 INVALID，不能冻结 | 标出受影响关系，并提示逐条重连 |
| 删除被关系引用的对象 | 允许保存不完整 WIP | 未修复悬空关系则 INVALID | 保留原关系并提示“无法找到来源/目标”，**不自动级联删除** |
| 修改关系 source/target 或基数 | 允许 | 重新保存和校验后才能冻结 | 明确显示这是结构性修改 |
| 删除某关系 | 显式确认后允许 | 新版本可 VALID | 已冻结旧版本仍完整保留该关系 |

对象 ID 的修改不能被当成只改业务名称；Link ID 修改在 Diff 中视为“删除旧 Link + 新增 Link”，不猜测重命名。若业务希望删除对象时自动删除关系，必须另行产品评审，不能让前端和 API 采用不同规则。

#### TC6｜真实接口复用与错误矩阵

**复用既有 0.2 API，0.3 快照 API 仍须先完成独立合并/集成**，新合同不创建 /links 平行写入接口：

| 业务操作 | 公共接口 | 期望与关键错误 |
| --- | --- | --- |
| 读取完整草稿 | GET /api/model/drafts/{draftId} | editingContent 必含对象/关系集合 |
| 保存 WIP | PUT /api/model/drafts/{draftId} | 200 新 revision；409 DRAFT_REVISION_CONFLICT；422 EDITOR_DOCUMENT_INVALID |
| 完整检查 | POST /api/model/drafts/{draftId}/validate | 200 VALID / INCOMPLETE / INVALID（检查报告，不表示发布）；过期 revision 409 |
| 本地冻结 | POST /api/model/drafts/{draftId}/snapshots | 新建 201；同 key 找回 200；未校验 409 VALIDATION_NOT_CURRENT；不合法 Candidate 422 |
| 结果未知按原 key 核对 | GET /api/model/drafts/{draftId}/snapshots/by-key/{idempotencyKey} | 已存在返回原快照；不存在 404；禁止盲目新 key 重试 |
| 查看历史及详情 | GET /api/model/packages/{packageId}/snapshots；GET /api/model/snapshots/{snapshotId} | 旧版只读，完整性坏必须 500 SNAPSHOT_INTEGRITY_FAILURE |
| 历史差异 | GET /api/model/snapshot-diffs?from=...&to=... | 旧版零关系与新版有关系仍可比；跨包 422 PACKAGE_MISMATCH |

统一分类：400 为格式错误；404 为不存在；409 为修订或冻结资格冲突；422 为结构/合同错误；503 为持久化暂不可用；500 为无法核验的历史完整性异常。前端不应泄漏堆栈/SQL，也不把 200+INVALID 的检查报告当作保存或冻结成功。

**建议新增的 Issue code（当前后端尚不存在）**：LINK_ID_REQUIRED、DUPLICATE_LINK_ID、DUPLICATE_LINK_API_NAME、LINK_SOURCE_REQUIRED、LINK_SOURCE_UNKNOWN、LINK_TARGET_REQUIRED、LINK_TARGET_UNKNOWN、LINK_CARDINALITY_REQUIRED、LINK_CARDINALITY_INVALID、LINK_EVIDENCE_REQUIRED。结构错误继续复用 UNKNOWN_FIELD、INVALID_STRING、EXPECTED_BOOLEAN 等现有 code。实现阶段必须把码表与 UI 定位一起测试，不能只写文档。

#### TC7｜0.3 旧快照兼容与不可变合同

- PR #11 的 freeze 仍限制**恰好一个 ObjectType、零 LinkType**；实施关系建模时必须扩展为“当前 revision 所有受支持对象和关系完整 VALID”，同时继续拒绝本期未支持资产。
- 沿用 V3 表、JCS RFC8785、SHA-256、canonical UTF-8、一个草稿修订一份快照、相同 key 幂等找回、不可变保护及数据镜像完整性检查；**绝不修改 V1–V3 旧迁移或重写历史快照字节和摘要**。
- 现有 V2 editing_document JSONB、V3 canonical_payload BYTEA 和 snapshot_document JSONB 结构上可以容纳对象/关系数组；暂不计划额外 V4，实施阶段须以真实“旧安装升级并写新快照、旧快照摘要不变”的 PostgreSQL 集成证据最终确认。
- 关系数量和稳定 ID 集合必须在 Candidate 与新快照中保持一致。若有一个 Link 丢失，冻结必须拒绝而不是生成“成功但缺关系”的历史。
- 数组顺序在结构 Diff 中不构成业务变化，但整份 JSON 的 JCS **不会自动把数组元素重排**：即使 Diff 结果为空，重排仍可能产生不同快照字节与 digest，不可混为一谈。
- 快照仍标示 LOCAL_SNAPSHOT_ONLY；无可信批准、正式发行版本、激活、运行对象或工业执行。

#### TC8｜关系结构差异与风险级别

以 LinkType.id 对齐两版关系，不依赖位置。新增 entityKind = LINK_TYPE，path 采用 /linkTypes/{stableId} 或 /linkTypes/{stableId}/{field}。建议新增可选 linkTypeId 字段，objectTypeId/propertyId 对关系行保持 null；**新增字段的向后兼容必须由当前前端和真实 HTTP 回归确认，不能假装绝对无影响**。若旧消费方拒绝未知字段，必须选择显式版本化合同。若新客户端依赖 linkTypeId，服务器必须总为 LINK_TYPE 行提供准确值，并为旧 OBJECT_TYPE/PROPERTY 行显式保留 null，不得复用 objectTypeId 假装关系身份。

| 变化 | changeKind | riskLevel | 用户理解 |
| --- | --- | --- | --- |
| 新增关系 | ADDED | ADDITIVE | 新增关系定义，**不等于生成真实关联数据** |
| 删除关系 | REMOVED | POTENTIALLY_BREAKING | 未来引用该关系的消费者可能受影响 |
| 仅修改 label | MODIFIED | DOC_ONLY | 仅显示名称修改 |
| 修改 apiName | MODIFIED | POTENTIALLY_BREAKING | 程序引用可能受影响 |
| 修改 sourceTypeRef/targetTypeRef | MODIFIED | POTENTIALLY_BREAKING | 关系方向/语义端点改变 |
| 修改 cardinality | MODIFIED | POTENTIALLY_BREAKING | 关联基数约束发生变化 |
| evidenceRequired 从 false 改 true | MODIFIED | POTENTIALLY_BREAKING | 将来关系需要更严格证据 |
| evidenceRequired 从 true 改 false | MODIFIED | POLICY_REVIEW_REQUIRED | 降低证据要求，需要治理复核 |
| 仅交换关系数组顺序 | 不应产生结构变更 | — | 不改变关系定义的结构语义 |

ID 改名 = REMOVED + ADDED；相同快照比较 = sameSnapshot true 和空 changes。按稳定路径排序，差异值可复核。全程保持 compatibilityStatus 为 STRUCTURAL_RISK_ONLY_NOT_COMPATIBILITY_CERTIFICATION，不对真实消费者兼容性做认证。

#### TC9｜前端与失败恢复合同

| 用户看到的问题 | 正确行为 |
| --- | --- |
| 修改了关系未保存 | 整个 EditorDoc 标 dirty；切换对象/关系 Tab 仍保留未保存输入；不能直接冻结 |
| 另一标签页改了同包草稿 | 409；保留本地完整聚合，展示服务器最新版本供人工决策，禁止自动覆盖 |
| 保存超时不确定是否成功 | 重新读取服务器 revision + 内容核对；不宣称成功，也不丢本地草稿 |
| 冻结回执不明 | 使用**相同 idempotencyKey** 查询原结果；不得直接新建另一个 key 重试 |
| 删除了被引用对象 | 提示受影响 LinkTypes；允许保留 INVALID WIP，不自作主张删除所有关系 |
| 加载旧的多个对象或未知资产 | 只能完整编辑或只读告警；**绝不显示第一个对象后整包提交覆盖** |
| 数据库不可用 | 保留可复制的本地编辑、报告未确认状态，恢复后重新核验 |
| 快照内容损坏 | 拒绝展示为可信历史或提供伪 Diff |

编辑器状态归属是**整个包**，不只单个表单。校验问题的路径要和对应 revision / 稳定对象 ID 一起使用，避免重排卡片后跳错问题行。

#### TC10｜规模、安全和明确非目标

保持最多 50 个 ObjectType、单个对象 100 个属性、包内 1000 属性、256 KiB 原始编辑文档与快照上限；本轮建议新增最多 200 个 LinkType 的上限。非法深层 JSON、未知字段、超大输入必须拒绝，不静默裁剪。

本阶段仍只面向隔离本地开发；没有企业可信身份/工作空间，不能连接生产 MES/ERP/QMS 数据。LinkType 是类型声明，不是 LinkFact 实例，也不是 ObjectSet 查询、审批发布、Agent 上下文或 Action。

#### TC11｜完整工程实现范围与依赖

未来**一个真实功能 PR**必须一起交付：多对象编辑 → LinkType 新增/修改/删除 → 保存/刷新重开 → 端点/基数检查 → 两个历史快照 → LinkType Diff → 并发和异常恢复。不能分拆成只有前端表单或只有校验函数的“已完成”PR。

代码影响：React NativeModelWorkspace 必须由单一 objectTypes[0] 状态改为完整 EditorDoc；DraftValidation 的编辑安全与 Candidate 生成必须一起改；PR #11 的 NativeSnapshotService.freeze 与 diff 必须扩展；PG17、真实 HTTP 和 Chromium 必须跑正反例。**关系代码只能在已确认集成 0.3 快照基线后开发**，不直接把 PR #11 未合并代码当 main 能力。

#### TC12｜可执行验收矩阵（设计阶段 NOT RUN）

| 编号 | 场景 | 实施时通过标准 |
| --- | --- | --- |
| C01 | 3 ObjectType + 2 LinkType 保存、读取、校验 | 同 revision VALID，Candidate/重开保留全部 3+2 |
| C02 | 关系目标未选择 | 200 保存 WIP；校验 INCOMPLETE+定位 path；冻结拒绝 |
| C03 | Link 引用不存在对象 | INVALID + LINK_SOURCE_UNKNOWN 或 TARGET_UNKNOWN，不裁剪 |
| C04 | 重复 Link id/apiName、重复对象 ID | INVALID，定位每个重复错误，原数据保留 |
| C05 | cardinality 为未知枚举字符串、evidenceRequired 错写为字符串、未知审批字段、201 条关系 | 枚举未知值：WIP 可暂存但完整校验返回 200 INVALID；伪 boolean / 未知字段 / 超限：保存直接 422；均不能产生快照 |
| C06 | 删除/重命名被关系引用的对象 | 保留关联、提示修复，未修复不 VALID |
| C07 | 两次冻结包含关系的完整历史 | 摘要复核，旧版本完整且不可变，幂等不重复 |
| C08 | 旧版一个对象零 Link vs 新版多对象多 Link | 可正常查看与比较新增差异，不触碰旧 SHA |
| C09 | 增删 Link、改方向/基数/证据要求、只重排顺序 | 风险级别符合 TC8，顺序变化无结构 Diff |
| C10 | 两标签冲突、回执超时、重复 key | 409 或同 key 恢复，页面不丢编辑 |
| C11 | 摘要损坏、数据库断开、伪造发布属性 | 500/503/422 与安全提示，不伪称发布成功 |
| C12 | Windows 原生 PG 后端真重启、Edge 抽检 | 需要**Windows 用户机器独立证据**，当前 NOT RUN |

#### TC13｜本轮审查发现的真实代码缺口

| 文件/模块 | 当前已存在 | 需要在实施 PR 中改变 |
| --- | --- | --- |
| NativeDraftController | PUT 整体草稿、POST validate、CAS 修订 | 尽量不改公共 URL，保持旧消费者可用 |
| DraftValidation.checkEditor | 拒绝非空 linkTypes；对对象/属性做安全检查 | 新增 LinkType 已知字段、类型和规模安全检查 |
| DraftValidation.validate | 候选一律填空 linkTypes | 全量保留关系、检查同包端点和重复身份 |
| Native V0.2 Schema / Python 语义测试 | 已定义 LinkType 和两端引用约束 | Java/前端和现行 Schema 对齐，不创建第二套字段规范 |
| NativeSnapshotService（PR #11） | 恰好一对象、零关系才能冻结；Diff 不比较 LinkType | 扩为合法多对象/关系冻结，新增 LINK_TYPE 风险对比 |
| Flyway V2/V3 | JSONB 草稿 + canonical BYTEA 快照 | 暂不改结构，真实迁移回归后再判断是否需要增量迁移 |
| React 工作台 | 编辑第一个对象，复杂草稿只读 | 以整个草稿为编辑状态，错误/冲突保护全包数据 |

#### TC14｜Contract-Gate-2 六项待签收决策（推荐 A）

| 决策 | 推荐 A | 不推荐的替代方案 |
| --- | --- | --- |
| CT1 身份规则 | 同包 typed identity、严格引用，关系 ID/API 同类唯一 | 用名称/数组下标作为真实身份 |
| CT2 保存与校验 | 安全 WIP 可存、全部对象/关系 VALID 才能冻结 | 保存时删掉不认识/不完整的关系 |
| CT3 基数与证据 | 关系基数统一来源→目标；证据字段只是未来声明 | 用当前关系定义当成已发现真实关系 |
| CT4 历史兼容 | 0.3 原快照字节/摘要不变；新 Candidate 全资产冻结 | 改旧 Flyway、历史或只保存首对象 |
| CT5 关系 Diff | 稳定 ID、结构风险与审核提示，旧消费合同验证 | 空 Diff 或声称兼容性已认证 |
| CT6 交付边界 | 一个完整真实功能 PR + 独立 Windows 验收 | 只交独立关系表单就宣布 0.4 完成 |

**本轮独立合同审核结论：建议 CT1–CT6 采用 A，进入下一轮 UX-Low 设计评审；合同仍为 Discussion，需维护者显式签收，不能把本文件提交或 Docs CI 成功当成产品批准、功能运行或正式发布。**

## Proposed Changes｜推荐实施切片与产品交互

### UX-Low：第三轮可操作低保真交互原型与独立检查

> **阶段状态：UX-GATE-LOW 已提交、待正式审阅和签收；不是 UX-High，更不是前端/后端功能实现。** [静态交互原型 HTML](../prototypes/ofip-0004-linktype-lowfi.html) · [完整 UX-Low 交互评审与 16 项矩阵](../implementation/S0-MVP-0.4-UX-Low-交互设计与评审矩阵.md) · [可复现 Node Mock 状态检查脚本](../prototypes/tests/ofip-0004-lowfi-state-check.mjs)。

这轮用一个工作台、四个 Tab 把原先相互割裂的入口串成完整故事：

1. **对象类型**：一个模型包里选择、创建多个 ObjectType，保留稳定 ID、属性、主键；切换对象或关系不自动丢掉本地编辑。
2. **关系类型**：来源对象 → 目标对象选择，四种基数中文解释，显示“未来关系是否要求证据”；只管理 LinkType，不创建实际关系记录。
3. **保存与检查**：整包保存修订、未填完的模型仍可暂存、校验返回 VALID / INCOMPLETE / INVALID，关联对象被删除时跳转修复；模拟两个标签页修订冲突与保存回执未知。
4. **历史与对比**：通过当前修订的完整性检查才可以留存 Mock 历史；第二次修改关系基数并留存后，可选 From/To，看到风险等级；模拟冻结回执未知后使用原请求核对。

**本轮独立检查**：对提交的原型脚本完成 JavaScript 语法解析和合计 17/17 内存 Mock 状态逻辑检查；原型只使用浏览器内存，**未进行真实 Chromium 浏览器 UI 验收**，Node 仓库脚本也待独立命令复验。所有用户行为仍是 Mock，不能声明 PostgreSQL、Spring Boot 或 Native Snapshot 合同在 0.4 已实现。

**UX-Low 风险审查**：当前示意性属性表单并未覆盖 0.2 的全部专业字段（例如完整枚举、分类），高保真必须继承既有能力；被引用对象删除后需更醒目的影响提示；技术 JSON Pointer 应转为业务路径；无真实身份下不可展示“审批通过/正式发布/已生效”。

**推荐评审意见**：UX-Low 的“一个工作台 + 对象与关系并行编辑 + 保存校验独立 + 历史差异”方案方向建议保留，且不建议新增独立「关系专业工具」一级菜单。正式签收前需对低保真主线与异常恢复做一次可见页面检查；后续再做 UX-High 视觉与完整交互合同。

### UX-High：第四轮高保真交互评审候选（不等于功能实现）

> **阶段说明**：已创建 [可操作高保真 HTML 原型](../prototypes/ofip-0004-linktype-highfi.html) 和 [12 条独立 Chromium 浏览器评审用例](../prototypes/tests/test_ofip0004_linktype_highfi.py)，并提交 [高保真交互审核矩阵](../implementation/S0-MVP-0.4-UX-High-交互设计与验收矩阵.md)。**原型仍然是浏览器内存 Mock**，并不持久化 PostgreSQL 或调用任何正式模型 API。

本轮以 OntoForge 当前 Studio 的蓝灰色视觉基线和原有菜单结构为约束，重点修复 UX-Low 四个不足：

1. **专业字段不倒退**：完整恢复对象属性的 STRING / INTEGER / DECIMAL / BOOLEAN / DATETIME / ENUM，ENUM 取值、数据分类、必填、可搜索与主键语义，不将“简化原型的字段缺省”固化为真实产品功能削减。
2. **关系引用影响可见**：对象列表显示被引用关系数，详情区标明受影响 LinkType 并有直接跳转；删除后保留悬空关系而非自动级联。
3. **校验问题业务化**：优先显示「关系类型或对象类型 › 对象名 › 字段名」及修复入口，技术 JSON Pointer 作为次要追溯；校验严格基于已保存的当前 revision。
4. **状态分步与版本确认**：四步流程提示；保存 WIP、VALID 检查、本地只读历史与正式发布不同；执行本地历史留存前出现明确的模拟确认弹窗。失败、并发与未知结果仍在原工作台恢复，不生成独立专业工具菜单。

**交付验收边界**：高保真原型的 Browser/JS 结果必须按受测 SHA 和实际 CI 记录，不可拿 0.2/0.3 的既有 Chromium 结果代替 0.4；即使 12 个浏览器故事均 PASS，也只证明内存 Mock 可操作，**不证明生产 Java/PG/React LinkType 能力已经实施**。正式代码实现必须沿用已经集成的 0.3 Snapshot 不变量，并在一个功能 PR 交付全闭环。

**独立审核建议**：优先确认完整专业字段没有回退、关系引用修复入口、校验/历史区分、移动端关键任务与明确 Mock 安全边界。UX-High 仍为 Discussion/待正式签收。没有显式 `[VOTE] Accepted`，不跳过标准流程直接开发生产功能。

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
| **DG-1 产品/领域（上轮建议已提交）** | 用户故事、功能范围、关系语义、不可丢失不变量与替代方案 | 经正式签收后锁定范围 |
| **TC-2 合同（本轮候选已提交）** | TC1–TC14：WIP、关系引用、旧历史兼容、关系 Diff、错误和验收矩阵 | 经正式签收后进入 UX-Low |
| **UX-Low（本轮原型及矩阵已提交）** | [可操作内存原型](../prototypes/ofip-0004-linktype-lowfi.html)、[16 项 UX 评审](../implementation/S0-MVP-0.4-UX-Low-交互设计与评审矩阵.md)、可复现 Mock 状态检查 | 正式审阅并签收后允许高保真原型 |
| **UX-High（本轮高保真原型已提交）** | [高保真交互 HTML](../prototypes/ofip-0004-linktype-highfi.html)、[Chromium 真实 UI 用例](../prototypes/tests/test_ofip0004_linktype_highfi.py)、[审核矩阵](../implementation/S0-MVP-0.4-UX-High-交互设计与验收矩阵.md) | 完整高保真 + DG/TC 正式签收后允许真实功能 PR |
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

**DG-1 已提出推荐 A 并进入第二轮合同候选设计；现已补齐 TC1–TC14，但两轮仍需维护者显式签收。** 未签收前可以审阅 UX 方向草图，但不得创建有实现授权含义的正式功能 PR。

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

**Decision Gate：DG-1、TC-2、UX-Low、UX-High 四轮方案已依据会话授权形成受托 Accepted 决议；本设计 PR 仍只交付 Mock 原型及合同，不包含正式功能代码；实现已在 PR #13 Draft 通过隔离环境真实验收，PR #11 依赖未合并，Windows 11 原生 PostgreSQL 重启 NOT RUN，**不得称为正式发布或生产可用**。**
