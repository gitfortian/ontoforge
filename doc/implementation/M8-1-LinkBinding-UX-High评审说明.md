# M8.1 · LinkBinding / OUT-IN 一跳查询 UX-High 高保真产品评审

**状态：UX-High Review Candidate · 待独立产品签收（非实施授权）**  
**日期：2026-10-19**  
**设计基线**：[R1 #77](https://github.com/gitfortian/ontoforge/pull/77) / [R2 #79](https://github.com/gitfortian/ontoforge/pull/79) 已签收；[UX-Low #84](https://github.com/gitfortian/ontoforge/pull/84) 已产品签收并 squash 合并（`5945585d`）；[OFIP-0010](../proposals/OFIP-0010-M8-LinkFact与有界遍历.md) **Decision Pending**。

- [打开本轮全新 UX-High 可交互原型](../prototypes/m8-1-linkbinding-highfi.html)
- [真实 Chromium 高保真回归](../prototypes/tests/test_m8_1_linkbinding_highfi_browser.py)
- [专用 CI 工作流](../../.github/workflows/m8-1-linkbinding-highfi-browser.yml)
- [UX-Low 已签收的源原型](../prototypes/m8-1-linkbinding-lowfi.html)，保留不覆盖

## 1. 本轮原型的实际用户价值

本次**不是生产 React 交付**；以现有 M7 UX-High 的蓝灰调、左侧建模/查询导航、清晰的面板边界为视觉基线，制作独立 HTML 静态交互设计。没有复制任意未实现的正式路由，也没有新建“图谱工具”独立菜单。端到端故事为：

1. 从本体建模“关系类型”进入 LinkBinding 工作台，查看固定 `equipment-inspections` 的 Equipment SOURCE → InspectionLot TARGET 及 `ONE_TO_MANY`。
2. 工作区顶部同时展示**草稿编辑状态 / 已保存不可变修订 / 当前 FULL 核验资格**，杜绝“UI 里有一个版本”被误认为“当前可查询且被授权”。
3. 有指导的 FK owner 选择；只选择两端已保存 M5 revision 与受控来源列，禁止任意 SQL 和目标 PK。
4. 单独触发 FULL、预算内的只读校验；VALID / VALID_WITH_ORPHANS 可以前往 CAS 保存；超预算 SAMPLE、类型错、基数冲突和来源撤权均不可保存。
5. 展示版本保存独立于查询、CAS 409 对照与返回复核、未知响应下通过幂等键查结果，不盲目二次新建。
6. 通过单 ObjectRef 或有界 root ObjectSet 查询 OUT/IN 的一跳，结果 EXACT zero、PARTIAL/KNOWN_SUBSET、FAILED 三态严格区分；后续页稳定在同一 result snapshot。
7. 证据区展示执行标识、LinkBinding/对象两端修订、双端来源与质量；TTL、翻页撤权/漂移清除对象及关系载荷并保留安全失败证据。
8. 未保存离开使用自定义可聚焦的原生 dialog；延迟核验、延迟翻页演练利用请求代次和输入冻结拒绝过期响应。

## 2. 高保真视觉与交互细化

| 设计界面 | 结构决策 | 必须被真实 Chromium 检验 |
| --- | --- | --- |
| 本体关系入口 | 跟随项目建模导航；「关系类型」作为声明、「实例关系绑定」作为业务操作 | 入口返回、刷新后稳定选择；不误生成假的业务边 |
| 工作区上下文 | 顶部关系 SOURCE/TARGET 小图仅表达定义；卡片区显式区分草稿、持久版本和 FULL 资格 | 修改字段只污染草稿，已存修订不跟随变化 |
| 映射配置 | FK OWNER 帮助 + 只读 PK + 受控列；减少首次决策负担 | SOURCE 错端、跨 Snapshot、缺列、类型错 FAIL CLOSED |
| 验证 | 显示 FULL、版本/范围/受控来源与校验 ID | 超预算 429 / 来源 503 不可保存；旧异步验证不可复活资格 |
| 保存 | CAS expectedRevision、已存版本、冲突对照/保留草稿、幂等未知响应恢复 | 409 不覆盖；未知先查；结构漂移重验 |
| 关联查询 | 左侧方向/root/故障，右侧查询结果与分页；保留原始 SOURCE→TARGET LinkFact | 单根 / 根集合、精准 0、受影响 root PARTIAL；旧分页响应不显示、撤权后受限来源信息不显示 |
| 证据与恢复 | 独立可重入的证据区，失败只保留安全证据、禁止重用旧 cursor | 撤权、Schema 漂移、TTL、SOURCE_TIMEOUT 负例 |
| 窄屏与无障碍 | 900px 折叠侧栏为横向导航；600px 单列；自定义 dialog 和焦点可见 | 375px 无横向溢出；模态取消与丢弃双分支 |

## 3. 独立 UX-High 签收问题（UH1–UH10）

| 编号 | 待签收问题 | 此 PR 状态 |
| --- | --- | --- |
| UH1 | 与 OntoForge / M7 现有页面视觉一致、没有平行专用工具导航 | 待签收 |
| UH2 | 双端版本、FK owner、PK/外键、定义图示是否首次易懂 | 待签收 |
| UH3 | 草稿、已保存修订、FULL 资格是否能一眼区分 | 待签收 |
| UH4 | FULL / SAMPLE / orphan 的合格/拒绝与下一步是否清楚 | 待签收 |
| UH5 | CAS 冲突、保存结果 UNKNOWN、结构漂移和重核验是否足够安全 | 待签收 |
| UH6 | OUT / IN / 单根 / 有界集合的质量与精确计数是否正确 | 待签收 |
| UH7 | FAILED 不泄漏旧载荷、失效 cursor 不可恢复；证据最小安全可读 | 待签收 |
| UH8 | 375px 小屏、焦点、未保存离开确认是否完整 | 待签收 |
| UH9 | 延迟核验响应、延迟翻页响应与新配置不会串用 | 待签收 |
| UH10 | 高保真与 R2 v2 独立合同匹配，且不误称生产能力 | 待签收 |

此 PR 进行的是**视觉与产品交互正式评审**，不是签收行为本身。签收必须在真实回归、独立 Review 与缺陷修订完成后通过显式记录完成。

## 4. 设计证据和真实研发边界

预期专用 Chromium 设计回归 **27 项**：延续 UX-Low 的主业务语义，同时验证自定义 dialog、修订状态隔离、CAS 对比、延迟响应代次、撤权后的来源/版本证据遮蔽和移动端。CI 以实际运行结果为准；本页文档的数量是计划，不代表 PASS。设计页面全是固定合成数据与模拟事件；JS 计时器仅验证静态模型中的竞态防护，不模拟真实多 Worker 或 API。

禁止因为 UX 原型看起来“已完整”而声明以下任何项完成：PG17 在 REPEATABLE_READ 的两端来源核验、Spring Boot 签名 v2 查询、真实 CAS 持久化、授权吊销或安全不泄漏、React 路由和真实分页、实际 SourceReadRef、Flyway 迁移、性能/压力/安全 E2E。以上均为 **NOT STARTED / NOT RUN**。

**下一门禁顺序**：UX-High 独立 Review → 缺陷修正与同 head Chromium/Docs/Required Quality 核验 → 产品明确签收 UX-High → 维护者对 OFIP-0010 的正式决策（须可追溯理由/链接，不能代投票）→ 仅在两者都满足时另开 M8.1 纵向生产功能 PR。M8.2 桥表及 M8.3 两跳保持独立阶段，不纳入当前实施。

## 5. 首轮独立代码/交互审查修正（2026-10-19）

本轮额外发现并修正 **P1 UX-SEC**：原合成证据面板在翻页授权撤销（403）后虽隐藏结果对象与 LinkFact，却继续显示固定的来源表名与版本闭包；这与 R2“无当前读取资格不得泄露受限来源存在性”冲突。修订后撤权状态保留最小 executionId 与错误恢复提示，同时**遮蔽源表名、身份和不可查看的版本闭包**；验证场景下无读取资格亦遮蔽受控来源名。增加 2 项真实 Chromium 负例。演练页即使展示了模拟访问拒绝，也不能视作真实 JWT/PG 数据隔离测试。

此审查记录只是修订依据；27 项测试及其他检查需在最终 head 重新核验，不能复用先前 22/22 的绿灯。

此外修复 **P1 UX-VERSION**：顶部双端版本摘要不可始终写死 B-11/r2、B-15/r3。高保真现在明确标注“当前工作选择”，随草稿变化；独立保存版本与查询执行证据只采用冻结后的 `state.saved.source/target`，不将未经保存的历史版本冒充执行版本。新增 1 项历史版本/草稿混用浏览器测试，总计 25 项。

## 6. 第二轮截图视觉复核（2026-10-19）

从最终提交 `bd7921ec` 的专用 [Chromium 截图 artifact](https://github.com/gitfortian/ontoforge/actions/runs/39193317944) 下载并人工打开多张 **1440px 桌面全页**、**375px 移动端全页**截图，与 M7 UX-High 原型进行了视觉对照：桌面工作台布局、灰蓝色层次与业务区分可接受；但发现新的 **P1 UX-MOBILE**：375px 虽无横向溢出，上方关系定义、三状态、四个版本摘要和五阶段 Tab 在手机上全部单列堆叠，主要配置动作过于靠下，无法快速进入用户任务。

修订策略：保留关系定义/证据/状态语义，不删关键事实；375px 手机端改为压缩关系两端概览、三状态并列摘要、两列 Binding 上下文、三列步骤导航，并在页头提供「前往当前工作区」可用按钮。此按钮仅滚动至当前阶段，不保存、验证、执行，也不绕过未保存离开防护。

截图证据升级：原有 teardown 全页图外，新增未保存离开确认弹窗**打开期间**的专门截图，新增移动端任务跳转和移动端查询结果两个真实 Chromium 用例；因此本阶段专用回归改为 **27 项**，须以本次最新 head 的 CI 复验结果为准。人工视觉签收需要查看新的 artifact，而不是复用此前出现问题的 375px 旧图。
