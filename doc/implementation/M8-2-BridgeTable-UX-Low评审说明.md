# M8.2 · BridgeTable 多对多关联 UX-Low 交互评审与签收矩阵

**Status: UX-LOW REVIEW CANDIDATE / NOT SIGNED / PRODUCTION NOT STARTED**  
日期 2026-10-19 · 唯一交付 [Draft PR #103](https://github.com/gitfortian/ontoforge/pull/103) · 基于已合并的 M8.1 #93 与 [M8.2 R2 合同评审](../design/M8-2-BridgeTable-R2-API版本持久化查询与三方证据合同评审.md)。

> 产品 Owner 已在对话明确批准**启动** M8.2 UX-Low，**尚未签收此具体原型**。M8.2 没有正式 OFIP-0010 独立开发授权；UX-High 及 Java/Flyway/React 正式实现均不得提前展开。当前 HTML 是**本地合成交互**，不是实际 PG17/Session/JWT/生产 API 证明。

## 1. 可点击原型与自动化入口

- [M8.2 全新可交互 UX-Low HTML](../prototypes/m8-2-bridge-table-lowfi.html)
- [Playwright 真实 Chromium 交互回归](../prototypes/tests/test_m8_2_bridge_table_lowfi_browser.py)
- [专属浏览器 CI 与 375px 截图](../../.github/workflows/m8-2-bridge-lowfi-browser.yml)
- [R1 产品边界](../design/M8-2-BridgeTable-R1-产品范围与LinkFact证据评审.md) / [R2 API、版本、三方证据与预算](../design/M8-2-BridgeTable-R2-API版本持久化查询与三方证据合同评审.md)
- 机器 Draft Schema：[`local-link-binding-v2` Bridge Request](../design/candidates/m8-2/local-link-binding-v2-bridge.candidate.schema.json) / [Bridge 成功结果三方证据](../design/candidates/m8-2/local-result-v2-bridge.candidate.schema.json)。

## 2. 用户故事：配置并查询“维修工单 ↔ 设备”

原型同时保留“本体建模 → 关系类型”“对象集合查询”既有入口，没有为 M8.2 新增顶层专业菜单。真实用户不应在业务路径中理解内部 Java 模块或 PG 表名生成 SQL 的细节。简单工单/设备示例用于说明；正式功能必须与任意用户自定义 ObjectType/LinkType 工作。

| 阶段 | 用户操作、状态转移 | 必需真实合同 |
| --- | --- | --- |
| 1. 桥表配置 | 查看只读 LinkType 与双方已保存 Binding revision；明确桥表 + sourceFk / targetFk 两列；旧版外键 FK 流程不被遮挡或合并成危险默认 | 必须同 LOCAL_SNAPSHOT + 同 sourceId、两列精确匹配冻结 sourceKey；不接受隐式 CAST、任意 SQL |
| 2. FULL 校验 | 以明确“只读”预演；VALID/VALID_WITH_ORPHANS、物理重复 pair、合法 NULL、孤儿统计分开；超 10k/授权未知/指纹漂移不能保存 | SOURCE、TARGET、BRIDGE 同一受控 READ ONLY REPEATABLE_READ，FULL 未完成不得发有效资格 |
| 3. 保存版本 | expectedRevision CAS + 新 revision/identity; HTTP 响应 UNKNOWN 以相同 operation UUID 恢复，绝不静默重建；来源漂移不修改现有 Head | 元数据 Operation/Revision/Head 同事务；不改旧 FK revision、contentDigest 或 Flyway V10 |
| 4. OUT/IN 单跳 | OUT MO-001 → E01/E02，IN E02 → MO-001/MO-002；可选择根对象或小型受控根集合，输出端过滤/COUNT_DISTINCT/SUM；列表按 ObjectRef 去重，LinkFact 保留原端点方向 | 仅 local-query-v2 单 TRAVERSE；Bridge pair 物理重复行不重复产生业务边和对象 SUM |
| 5. 来源证据和恢复 | SOURCE/TARGET/BRIDGE 三方证据，失败隐藏对象与 LinkFact；桥表 SELECT 撤销/DDL 漂移/过期 Cursor 不复活；修好授权后重新执行 | 每页当前三方授权与 fingerprint 复核；无任何 source history/replay 假承诺 |

**重要交互取舍**：不同于 M8.1 的“FK 所在端”选择，M8.2 的主要决策是**桥表名称、两列分别映射谁**。只需一处关系来源配置，不出现多个平行工作台。保存按钮必须落在 FULL 通过之后；保存不自动发起查询。UI 显示第三份来源证据，但**不**强制生成第三个 ObjectType。

## 3. 合成数据与期望的可观察结果

| 评审注入 | 桥表物理事实 | 允许的业务语义 |
| --- | --- | --- |
| clean | (MO-001,E01)、(MO-001,E02)、(MO-002,E02) | OUT(MO-001) = E01,E02；IN(E02) = MO-001,MO-002；质量 COMPLETE |
| duplicate | clean + 第二条 (MO-001,E01) | 4 行、3 对逻辑 pair；OUT(MO-001) 仍只给 2 条 LinkFact，duplicatePairRows=1；对象 SUM E01=10+E02=20=30 |
| orphan | clean + (MO-001,E99) + (MO-999,E02) | FULL VALID_WITH_ORPHANS；OUT(MO-001) 和 IN(E02) 各自 PARTIAL，其余不受影响 root COMPLETE |
| null | clean + (MO-001,NULL)+(NULL,E02)+(NULL,NULL) | FULL VALID；NULL 不算孤儿、不造 LinkFact |
| budget | 10,001 physical rows（合成故障） | SAMPLE/UNKNOWN_TOTAL/429，不能保存 |
| revoke | BRIDGE SELECT 撤销（合成故障） | FAILED，无敏感 orphan/pair 泄漏 |
| drift | BRIDGE 来源指纹变更（合成故障） | FAILED，必须重验 |
| cas / unknown / drift（保存阶段） | 分别为修订竞争 / 回包丢失 / 保存前指纹漂移 | 旧修订不覆盖；原幂等键恢复；漂移不保存 |
| timeout / budget / revoke-bridge / drift-bridge / expired（执行/翻页） | 三表超时、预算、撤权、指纹漂移、旧 Cursor 过期 | FAILED，不展示旧对象、关系和聚合；再次执行才签发新快照 |

原型在演示页明确标注“模拟”，不会联网执行生产 API/真 PG，不提供真实 Session 授权功能，也不对固定样例外做业务实现承诺。

## 4. UX-Low 产品签收问题（每项待验收）

| 编号 | 用户验收问题 | 期望结果 | 状态 |
| --- | --- | --- | --- |
| UX-B01 | 能从 Native 关系类型自然进入，不增加“专业工具”菜单吗？ | 路径闭合，回到现有导航上下文 | 待签收 |
| UX-B02 | 第一次配置能准确识别 SOURCE/TARGET 主键及 Bridge 两列吗？ | 自动只读呈现权威 sourceKey，无自由输入 SQL | 待签收 |
| UX-B03 | 非白名单 Bridge / 跨 sourceId / 反向列能明确拒绝吗？ | 422/403/409，不能继续保存 | 待签收 |
| UX-B04 | FULL、SAMPLE、VALID_WITH_ORPHANS、FAILED 是否不混淆？ | 物理行、逻辑 pair、重复/NULL/孤儿计数分离 | 待签收 |
| UX-B05 | CAS 和 UNKNOWN 恢复是否可理解且不重复保存？ | 原 operation UUID 回读修订，保留未保存编辑 | 待签收 |
| UX-B06 | OUT/IN 原始 LinkFact 方向能被理解吗？ | IN E02 仍展示 MO-001 → E02 | 待签收 |
| UX-B07 | 重复桥表行、多工单共享设备的 COUNT/SUM 能理解吗？ | 三种 count 区分，最终对象属性只累加一次 | 待签收 |
| UX-B08 | 当前根孤儿 PARTIAL、合法零关联 COMPLETE 与 FAILED 是否明确？ | 避免成功零伪装授权/预算失败 | 待签收 |
| UX-B09 | 三方证据含 Bridge 但不生成第三 Object Binding 吗？ | 三个 SourceReadRefs + 两 OBJECT、一 LINK revision | 待签收 |
| UX-B10 | 旧 cursor 撤权后不可复活，修复后只能新查询吗？ | 失败隐藏载荷，旧快照永久退休 | 待签收 |
| UX-B11 | 未保存返回、撤销编辑、重开旧修订是否正确？ | 取消离开保留、丢弃恢复已保存值 | 待签收 |
| UX-B12 | 375px 小屏排版、标签、操作按钮能完成任务吗？ | 无水平溢出，关键信息可见 | 待签收 |

## 5. 浏览器检查矩阵与证据门禁

专属 Python/Playwright suite 为真实 Chromium 操作（触发 `change/click/confirm`），覆盖入口、无数据、Bridge 双 FK、错误来源/错方向、FULL 质量桶、CAS/UNKNOWN、OUT/IN、稳定冻结分页、重复 pair SUM/COUNT、局部孤儿、授权撤销/DDL 漂移、超时/429、未保存编辑和 375px 断点。

**测试承诺**：截图由 GitHub Actions artifact 留存，所有用例必须在**同一最新 PR Head** 实跑通过；测试失败必须在 #103 原分支修复，不能修改断言掩盖业务缺陷。首次 CI 结果以工具实查为准，未得到 success 前标 **RUNNING/NOT YET PASS**；独立 UX Review 与产品签收不能被“绿 CI”替代。

**实机真实能力现状**：B01–B18 PostgreSQL 真实 Bridge Join、Spring Boot/JWT、生产 React Chromium 均 **NOT RUN**；Windows 原生 PostgreSQL 和发布证据属于后续相应门禁，不能称发布成功。

## 6. 签收后标准顺序与 PR 预算

M8.1：#93 已合并；M8.2：**唯一 Draft #103** 承载 UX-Low → UX-High → OFIP-0010 M8.2 授权 → 纵向生产实现 → PG17/JWT/Session/React E2E → Required Quality 最新 Head 全绿 → 独立终审与合并；M8.3 未来仅 1 PR。

**当前不得开始 UX-High 或正式代码**。产品 Owner 独立审核并明确签收 UX-Low 及要求的修订之后，才允许在 #103 中制作新 UX-High。
