# M8.2 BridgeTable UX-Low · 独立深度产品与交互 Review（2026-10-19）

> **裁决状态：INDEPENDENT REVIEW COMPLETE / UX-Low 技术层建议有条件签收，仍待产品 Owner 正式签收。**  
> **功能状态：仅原型/评审/机器合同、未提交 M8.2 Java/Flyway/React 生产实现、未合并/未发布。**  
> 单一纵向 [PR #103](https://github.com/gitfortian/ontoforge/pull/103)，基于已经合并到 main 的 M8.1 [#93](https://github.com/gitfortian/ontoforge/pull/93)。事实依据：[R1](../design/M8-2-BridgeTable-R1-产品范围与LinkFact证据评审.md)、[R2](../design/M8-2-BridgeTable-R2-API版本持久化查询与三方证据合同评审.md)、[UX-Low 原型](../prototypes/m8-2-bridge-table-lowfi.html)、[Playwright Chromium 交互集](../prototypes/tests/test_m8_2_bridge_table_lowfi_browser.py)、[UX-B01–B12 签收矩阵](M8-2-BridgeTable-UX-Low评审说明.md)。
>
> **本次审核基准**：静态 HTML/JS 状态机、真实 Playwright Chromium 交互、GitHub CI、R1/R2 合同的逐路径对照；并非真实 PG17 / API / MetaDB 证据。每次修订均保留在同一个 Draft PR，最终绿灯必须以当前 PR 最新 SHA 为准；见 PR review 评论中的 live CI 链接。技术 Reviewer 评论不是产品 Owner 投票，不能自行宣称 UX-Low 已正式签收。

## 1. 审查方法和结论

**审查不是看“能否点击”就通过**，而是沿以下六个场景交叉检验：

1. **入口与业务决策**：既有 Native 关系类型 / ObjectSet 自然衔接；M:N LinkType 的唯一 Bridge mapping；不为桥表额外制造 ObjectType，也不插入无关菜单。
2. **建模身份与方向**：两端 M5 Binding 精确 revision + distinct identityDomain；Bridge 两列分别引用 SOURCE/TARGET 真实 sourceKey；OUT/IN 不能翻转 LinkFact，方向变化后 root/输出端过滤/数值属性类型同步切换。
3. **FULL 质量可信性**：只读模拟 Full、物理行/去重 pair、SOURCE/TARGET/both orphan、三类 NULL 等互斥桶；超预算及失权不得假装有效，核验输入一变立即失效资格。
4. **版本化写入与接续**：CAS 冲突不覆盖 Head、UNKNOWN 使用原幂等操作回执、成功提交后禁二次点击；未保存编辑从任意跨阶段入口离开都需要确认，丢弃须回到已保存映射。
5. **查询、聚合、冻结与失败**：根/输出对象字段类型化；筛选后重复行计数遵从当前可见关系集合；COUNT_DISTINCT/SUM 按去重对象计算；根局部 orphan PARTIAL 与 true-empty COMPLETE 区分；查询条件变化不能沿用旧 result/evidence。
6. **授权、三方来源证据与窄屏**：SOURCE/TARGET/BRIDGE 三角色读取、Link revision 和 schemaFingerprint 清晰；授权失败/结构漂移移除旧对象及签名 Cursor 的展示，且在后续 FULL 重验中检测到撤权也应立即退休旧结果；375px 无横向溢出；无旧 JavaScript 静默启动失败。

**技术判断**：产品流程方向合理，不应退回 R1/R2 重画；本轮发现的 UX-Low 真实 P1 缺口必须修完且当前 CI 全绿，才适合提交产品签收。高保真仍需分别签收，当前不能写生产代码。

## 2. 在绿测试基础上新发现并修复的 P1 缺口

| ID | 级别 | 原缺陷及业务危害 | 本次修复与可证伪回归 |
| --- | --- | --- | --- |
| UX-D01 | P1 | 已验证 FULL 后切换校验场景，不重新核验仍允许继续保存旧结论 | `validation-scenario.change` 立即取消 FULL 准入并禁保存；Chromium 先验证正常、换 orphan 再验证 |
| UX-D02 | P1 | 待保存映射从“核验”或“保存”直接跳查询/证据，绕开只在配置页存在的丢弃确认 | 跨阶段离开配置工作流按 `state.dirty` 统一守卫；取消保留编辑、确认恢复已保存映射；Chromium 两条绕过反例 |
| UX-D03 | P1 | 失败/撤权后证据面板仍展示完整三个来源角色卡，不能清楚表示当前资格已失效 | 只有当前成功执行才可见 SOURCE/TARGET/BRIDGE 角色及版本锁；失败仅留安全状态和修复入口 |
| UX-D04 | P1 | root/filter/SUM 选择变化，查询对象虽隐藏，证据区仍继续显示上一执行为当前成功 | 参数变化将当前活动执行从原型状态取消、清除结果与证据；Chrome 验证旧 executionId 不被继承 |
| UX-D05 | P1 | OUT→IN 后筛选 option 文案改成工单，真实 value 却仍是设备字段；用户会得错误过滤含义 | 切方向同步重置筛选并变更 option value；IN E02 仅筛工单 MO-002；Chrome 断言物理 pair |
| UX-D06 | P1 | 故障时仅 `hidden=true`，已渲染旧对象/聚合/结果快照仍留 DOM | `purgeRenderedResult` 清除 rows/summary/snapshot/aggregate/pageNumber，失败不保留可被误读旧载荷；Chromium 检查 DOM 为空 |
| UX-D07 | P1 | 来源身份不可核验时，FULL 数据面板仍可能暴露之前的静态三方绑定/结构信息 | 除有权的预算诊断外，失败隐藏 FULL Ledger；授权撤销反例断言证据容器不可见 |
| UX-D08 | P1 | 已有成功结果后，下一次 FULL 检测 Bridge 撤权/DDL 漂移，旧成功结果不主动失效 | 重新校验发现撤权/漂移即 retire 旧快照、清 DOM、禁用三方执行证据；两条 Playwright 反例 |
| UX-D09 | P1 | SUM 仅显示数值，不解释对设备或工单哪个数值字段聚合；易导致错误业务解释 | 在 SUM 选择显式数值字段，随 OUT/IN 切换至各自输出端的已映射数值属性；浏览器检验 OUT=30、IN=300 |

上述 P1 是**原型层发现和修复**，并不宣称真实后端已经实现 corresponding policy。真实 B0/source authorization、数据一致性、CAS 及源 SQL 均是后续生产门禁。

## 3. 继承自首次 UX-Low 交付、需要保留的关键不变量

- **提交一次**：原型完成一次修订后禁重复保存；UNKNOWN HTTP 回包丢失必须先用同 operationId 恢复，不能直接在未确认状态跑 query。
- **真实无关系与不完整结果**：没有边就 COMPLETE/0；本次根范围有授权确定的孤儿才 PARTIAL/KNOWN_SUBSET；无权/超预算/DDL 不允许 PARTIAL 假成功。
- **Bridge 重复并非平行事件**：同一 SOURCE/TARGET pair 只代表存在性一条 LinkFact，物理重复独立计数；过滤后的结果不借用被排除 pair 的重复量。
- **FULL 台账**：`nullBothRows + nullSourceOnlyRows + nullTargetOnlyRows + sourceOrphanOnlyRows + targetOrphanOnlyRows + bothOrphanRows + matchedPhysicalRows = bridgePhysicalRows`。`distinctMatchedPairs` 独立于 matched physical rows，不能把重复行当逻辑关系。
- **一跳和三方证据**：模型 Lock 仍是 2 OBJECT + 1 LINK，Bridge 自身是第三份只读来源证据，绝不偷偷生成 ObjectBinding 或 Graph 运行时。
- **简单工业示例但非硬编码规格**：原型的工单/设备和数值属性都是固定**合成样例**，交付的生产代码须支持任意符合 M5/Native Schema 的用户自定义类型。

## 4. 仍需在 UX-High/研发合同前面对的 P2 与真实风险

| 编号 | 级别 | 待办与准入约束 | 解决阶段 |
| --- | --- | --- | --- |
| UX-H01 | P2 | 单页静态场景尚不覆盖真实请求 loading、乱序响应、Abort/cancel 与保存未知请求竞态；不能让旧回包覆盖新 root/版本 | 全新 UX-High + 生产 React E2E |
| UX-H02 | P2 | 两个 Endpoint Binding 的相应已授权读取、当前 source schema/SELECT 需由服务端证明；原型只通过合成拒绝模拟，不能把静态 HTML 隐藏当作权限控制 | M8.2 Java/PG17 授权与 session E2E |
| UX-H03 | P2 | 低保真演示 375px 无溢出不等于完整可访问性：tabpanel 关联、键盘焦点恢复、错误焦点提示、窄屏复杂质量台账仍需要设计 | UX-High 独立 Review |
| UX-H04 | P2 | `BridgeDuplicatePairRowCount` 过滤前后作用域、聚合对 PARTIAL 已知子集的解释、SUM/COUNT_DISTINCT 的输出属性权限应形成前后端合同回归 | R3 细节/生产合同 + UX-High |
| UX-H05 | P2 | 没有稳定桥表行 PK 时，不能展示真实逐物理行可永久定位的证据；当前提供的是 source-role/读取范围/重复计数 | UX-High 证据文案 + PG17 来源测试 |
| UX-H06 | P2 | 全屏是演示工作台而非现有产品真实路由。实际 React 必须复用 OntoForge 导航、组件、主题、回到原 LinkType / root 等上下文，不可直接移植原型 HTML/CSS | 正式 React 交付 |
| UX-H07 | P2 | 故障代码/路径当前为用户可见模拟值，真实 API 不得把桥表表名、对象键值或授权推断通过 403/404/日志错误泄漏 | R3 Error Schema + Security E2E |

**不能误报**：目前 M8.2 的源端 PostgreSQL 三表 FULL、跨事务一致性、最新 ACL、物理行预算/SQL 计划、真实 React 和 Windows 11 UAT 均未做。M8.1 已合并不自动替 M8.2 通过 C01–C12 / B01–B18。

## 5. 自动化验收矩阵与签收建议

- [专属 Chromium 交互集](../prototypes/tests/test_m8_2_bridge_table_lowfi_browser.py) 原先 32 项，本轮新增 stale-FULL / dirty-navigation / revoked provenance / filter type / DOM purge / revalidate revoke/drift / explicit SUM 等反例，目标 **44 项**，应以本 PR 最新 Head 的 [M8.2 UX-Low CI](../../.github/workflows/m8-2-bridge-lowfi-browser.yml) 实际结果为准。
- R1/R2 Schema 机器候选/旧 FK 回归、Docs Gate、项目 Required Quality 和相关旧原型必须在**同一个最新 head** 核实 PASS，不能组合不同 SHA 的绿灯作最终证据。
- UX-B01–B12：技术检查覆盖入口/三方来源/失败/复杂身份/去重/保存/分页/移动端；**产品 Owner 签收状态全部仍为“待正式确认”**，不伪造他人签字。
- **判定建议**：若最新 Head 的全部 Required Checks 通过，且产品 Owner 认可上述新交互，则**建议 UX-Low 通过 → 在 PR #103 开始全新 UX-High**。高保真再次独立产品签收且 OFIP-0010 M8.2 维护者授权之前不得实现生产 Java/Flyway/React。
- 若产品对桥表概念引导、断链 PARTIAL、SUM 属性选择、错误恢复还有异议，则仍按原 PR 修订 UX-Low，**不得**新增第 2 个 M8.2 PR 或直接合并未实现功能的 Draft。

**最终审查边界**：产品交互可签收候选 ≠ 完成 M8.2 生产能力；Draft #103 仍需要承载后续 UX-High、正式 OFIP、纵向全栈与 PG17 证据后才能转换 Ready/merge。
