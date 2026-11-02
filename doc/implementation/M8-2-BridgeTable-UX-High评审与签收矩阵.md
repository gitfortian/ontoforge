# OntoForge M8.2 · BridgeTable UX-High 高保真交互原型评审与签收矩阵

> **状态：UX-HIGH REVIEW CANDIDATE / 待独立评审与产品签收 / 非生产实现。** 日期：2026-10-19。  
> 唯一纵向 [Draft PR #103](https://github.com/gitfortian/ontoforge/pull/103)，M8.1 [#93](https://github.com/gitfortian/ontoforge/pull/93) 已在 main，M8.3 仍预留未来单一功能 PR。**本期不得新开任何 M8.2 原型/修复 PR。**
>
> [UX-Low 产品 Owner 明确签收记录](https://github.com/gitfortian/ontoforge/pull/103#issuecomment-6268995562) 批准“开始 UX-High”，**不代表 UX-High 已签收或 OFIP-0010 M8.2 获得生产研发授权**。在 UX-High 产品明确签收和 M8.2 独立 OFIP 决策前，不允许新生产 Java/Flyway/React 代码。

## 0. 本轮真实交付索引

- [**独立 M8.2 UX-High 高保真交互原型 HTML**](../prototypes/m8-2-bridge-table-highfi.html)：采用 OntoForge 的亮色工作台、主侧导航、分步确认、关系方向与桥表概览、卡片状态和响应式布局；**与已签收 UX-Low 是两个独立 HTML 设计物**，不覆盖原型存档。
- [**High 专用真实 Chromium 浏览器 E2E**](../prototypes/tests/test_m8_2_bridge_table_highfi_browser.py)：复用 UX-Low 44 项基线，再验证高保真加载/取消/慢回包竞态、键盘 Tab 可访问性、部分结果 SUM、三方证据、375/768/1024/1440 和重置。
- [High 专用 GitHub Actions + Chromium 截图](../../.github/workflows/m8-2-bridge-highfi-browser.yml)。所有用例必须基于**相同最新 PR Head**独立通过。
- [UX-Low 正式深度评审与 P1 修复](M8-2-BridgeTable-UX-Low独立深度Review-20261019.md) / [R2 API/Schema/三方证据与预算合同](../design/M8-2-BridgeTable-R2-API版本持久化查询与三方证据合同评审.md)。

**实现性质**：独立单文件静态 HTML + 合成 Source/Link/Object 数据、本地 JavaScript 定时器模拟慢请求及操作状态。不访问真实服务端、不调用 SQL、不写入元数据、没有 JWT/Session ACL 安全能力、不是现有 React 页部署、不包含真实 CAS 或幂等机制。原型中“成功保存/真实 FULL/证据”都是**用户认知及状态模拟**。

## 1. 产品视觉与交互架构的新增设计

### 1.1 信息架构：先说明关系是什么，再让用户决定如何绑定

将“模型关系概览”和“当前 Local Snapshot / Endpoint Binding”放到工作台首屏：SOURCE MaintenanceOrder → **BridgeTable**（关系来源，不是第三 ObjectType）→ TARGET Equipment。对用户只暴露一个主操作“配置桥表来源”，而不是展示所有后台对象、指纹/事务配置或另建主菜单。

新高保真保留现有四个业务导航入口，并用 5 个标准阶段容纳过程：①配置桥表 → ②完整核验 → ③保存修订 → ④双向查询 → ⑤证据恢复。与 M8.1 已有用户路径保持关系类型和查询工作区一致，页面不直接复刻旧低保真 CSS。一个阶段一个主按钮，其他次级动作用弱按钮；不会因“保存成功”自动触发查询。

### 1.2 配置前的来源信任语义

- 两端已锁 M5 Object Binding revision/identityDomain/sourceKey 为只读技术证据，Bridge 仅一表两 FK 显式选择；不提供自由输入 SQL、选择任意 PK 或跨 sourceId。
- 关系物理行只能证明 SOURCE/TARGET pair 的存在性，一对 pair 一条 LinkFact；可读的重复 pair 独立计数，不扩展为“多个事件”或时态关系。
- 元数据列表筛选与真实 SELECT ACL 在高保真仅展示状态，不等于生产权限实现。不得用原型中的“隐藏卡片”冒充源数据安全保证。

### 1.3 FULL 校验反馈与下一步引导

- 以三方来源的一次只读 FULL 为必要准入；按 `nullBoth/nullSourceOnly/nullTargetOnly/sourceOrphanOnly/targetOrphanOnly/bothOrphan/matchedPhysical` 七个互斥桶解释物理行账本，额外报告唯一 pair 与 duplicatePairRows。
- VALID_WITH_ORPHANS 和 VALID 均允许签发本次验证资格；403/409/429、权限未知、SAMPLE/UNKNOWN_TOTAL、三表不一致不得保存，不把失败显示成“真实 0”。
- 校验情境或 Bridge mapping 任一变化，即取消旧 FULL 资格。模拟慢校验在旧请求返回前，用户换场景/修改表列，旧 completion 不得再恢复 validated。

### 1.4 CAS 保存、UNKNOWN 恢复与查询分离

- 保存明确是一份不可变修订；成功后不可二次点击同一提交生成第二 revision，存在 Head CAS 冲突时提示重新打开 Head。UNKNOWN 的恢复只使用**原 Operation UUID**，得到回执之前不得绕去执行查询。
- 高保真展示 Head expectedRevision、操作回执和版本冻结说明，但并未连接真正的元数据数据库，不能当作持久化证明。

### 1.5 双向遍历、PARTIAL 语义和异步竞态

- OUT 从工单到设备、IN 从设备到工单；反向仅改变 root 和输出类型，**LinkFact 自身永远保留 MaintenanceOrder→Equipment 的定义方向**。
- 输出端筛选和 SUM 目标数值字段随 OUT/IN 切换，不能复用上次的 ObjectType 属性；物理桥表重复行不增加输出 ObjectRef 数量或 SUM 值。
- 针对 root 相关 orphan，正确标 `PARTIAL / KNOWN_SUBSET`。若选择 SUM，结果仅标为**“已知子集小计（非全量）”**，不输出暗示所有来源已完整核验的业务总数；无边/合法 NULL 才是真零 COMPLETE。
- 新增 *request generation token* 的异步模拟：慢查询 LOADING、手动取消、root/方向/筛选变化、被后来的快查询抢占时，旧响应不得覆盖当前页面；慢 FULL 在输入变化时也必须 fail-closed。测试要求明确模拟前后状态，不能靠定时等待猜测。
- 进入证据前应能看到结果 executionId、冻结 snapshot、quality 和 SourceReadRef 三方角色；失败/失权应移除旧已渲染对象 DOM、隐藏原始来源卡片，并明确须重新授权及重新执行才有新 cursor。

### 1.6 移动端与可访问性

- 正式高保真复用 OntoForge 工作台的侧栏/阶段与表格视觉结构，针对 375px、768px、1024px 设计自动换列、滚动安全的表格容器，不应产生水平溢出。
- `tablist/tab/tabpanel` 建立 aria-controls/labelledby；箭头键切换五阶段，聚焦的反馈与 role=status 进行键盘/读屏审核；禁用按钮不能通过页签绕过 FULL/CAS 授权。
- 生产 React 必须复用正式设计系统、权限与错误处理，不准直接把这个 HTML 复制进生产 DOM 当成完成 UI 改造。

## 2. 主要 UX-High 产品签收问题

| 编号 | 用户看到/操作的具体故事 | 验收准则 | 当前状态 |
| --- | --- | --- | --- |
| H01 | Native 关系类型进入桥表配置 | 对象、关系和当前项目上下文首次可见，不新增不必要的顶层菜单 | 待产品签收 |
| H02 | 首次配置 2 个 Object Binding + Bridge | 显示 2 个权威 PK 与 Bridge 双列方向，首次用户能识别下一动作 | 待产品签收 |
| H03 | 只读 FULL 核验 | FULL vs SAMPLE/UNKNOWN、互斥 NULL/orphan/duplicate 报告可理解 | 待产品签收 |
| H04 | 保存及未知回包恢复 | CAS 与幂等回执清晰且操作不可重复；保存不会自动查询 | 待产品签收 |
| H05 | OUT/IN 与结果计数 | 根方向/输出端类型/LinkFact SOURCE→TARGET、COUNT_DISTINCT/SUM 对齐 | 待产品签收 |
| H06 | 局部 orphan PARTIAL 下 SUM | “已知子集小计”与真零 COMPLETE/FAILED 不混淆 | 待产品签收 |
| H07 | 慢查询/乱序回包/取消 | LOAD/取消状态真实可点击，旧请求回包不得覆盖新条件 | 待产品签收 |
| H08 | 慢 FULL/编辑漂移 | 旧校验资格立刻失效，未保存修改返回受控 | 待产品签收 |
| H09 | 查询证据与页面恢复 | SOURCE/TARGET/BRIDGE 各自来源角色，当前/失败证据区分明确 | 待产品签收 |
| H10 | 撤权、Bridge 漂移、过期 Cursor | 旧结果和 DOM 清空；修复后重新执行，新冻结结果 | 待产品签收 |
| H11 | 正式样式与同产品导航 | 组件颜色/间距、主要 CTA、表格状态与 M7/M8.1 视觉体系协调 | 待产品签收 |
| H12 | 375/768/1024 及键盘 | 无横向溢出，关键动作/状态可见，箭头键与 tabpanel 关联工作 | 待产品签收 |

## 3. 独立 Review 核查点（不得只看截图）

| 风险 | 必须审查的反例 | 下一步 |
| --- | --- | --- |
| P1 - 异步竞态 | slow 回包发生在 root/方向变更、用户取消或新快请求成功之后 | 运行实际 Chromium 断言旧结果永不复活 |
| P1 - 不完整聚合 | PARTIAL 下仍向用户展示看似完整的 SUM 合计 | 必须标“非全量”，并显示被授权当前根子集 |
| P1 - 失权泄漏 | 仅 CSS hide 旧对象，DOM 中仍包含旧 ObjectRef/LinkFact | 检查 DOM 节点清空，未授权错误无敏感表键 |
| P1 - 提交一致性 | 同一 operation 重放又创建 revision，或保存 UNKNOWN 不确认回执直接查询 | 必须保存一次、恢复同一 ID |
| P2 - 首次判断负担 | 首屏术语过多，无法识别 Bridge 对应的两列映射 | 优化帮助文案和信息密度 |
| P2 - 本体与物理身份 | Bridge 误当第三 Object Binding，或 IN 翻转 SOURCE/TARGET 逻辑边 | 检查引用角色及身份 |
| P2 - 验收样本过窄 | 只用固定工业例子，无法支持真实任意对象模型 | 此 High 仅场景表达，正式 Java/React 必须有通用模型与真实 PG17 测试 |

## 4. 阶段门禁与全局功能状态

**本轮 UX-High 可推荐签收的必要条件**：

1. 专属 Chromium 交互测试与截图在同一最新 head **completed/success**，所有原低保真合同、R2 Schema/架构文档、Required Quality 同 head PASS。
2. 独立 UX-High 深度 Review 能证明 H01–H12 在已演示范围内无未关闭 P1；如有问题只能在 Draft #103 修复重测，不能以 Review COMMENT 代替产品确认。
3. 产品 Owner 单独明确签收 UX-High；该签收决定必须留 PR / 总 Issue，不能由 GitHub Actions 绿灯自动产生。
4. 取得后仍须通过 **OFIP-0010 M8.2 独立生产实施决策**；未经正式 Accepted 不能动 Java/Flyway/React。
5. 正式生产阶段必须跑实际 PostgreSQL 17 同事务三表 FULL、真实来源授权/撤权、Head CAS/idempotency、JWT/Session、React+Chromium 端到端验收 B01–B18；当前这些均 **NOT IMPLEMENTED/NOT RUN**。

**不可混淆**：M8.1 #93 MERGED ≠ M8.2 完成；本 PR 原型/合同质量检查 PASS ≠ M8.2 生产 E2E PASS；UX-Low 的用户签收 ≠ UX-High 的用户签收。M8.3 仅计划未来一份纵向 PR，不能搭车开发。


## 5. 高保真可访问性修订（同 PR 内追补）

独立交互复核补充：原型对可见错误状态使用 `role=alert`、可程序化聚焦与焦点移动，保证键盘用户不会停留在已失效提交按钮。专属 Chromium 新增一项“无效版本映射触发验证 → 焦点落到验证错误 + 读屏告警”反例，**总计划为 58 个浏览器用例**。此项仍只验证静态原型交互，并不证明生产 React 或真实浏览器辅助技术的完整兼容性。


### UX-High 独立审查中的补充（与低保真签收严格区分）

- 高保真实际新增 Reset 确认：当存在尚未保存映射草稿、UNKNOWN 保存回执、慢查询或未完成 FULL 时，重置前必须明确提示将丢弃哪些工作；取消保留当前状态。
- 当用户离开查询或 FULL 步骤，成功导航后应取消该步骤尚未完成的模拟请求，迟到回包不能回填旧对象/证据。
- 上述交互分别增加 3 个 Chromium 反例，**最新完整浏览器矩阵为 58 项**。真实修复和曾经失败的初始化日志集中记录在 [独立高保真 Review](M8-2-BridgeTable-UX-High独立交互Review-20261019.md)，不得删除不通过记录或用旧 head 的 PASS 冒充最新状态。
