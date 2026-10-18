# M7 ObjectSet · UX-Low 原型评审说明

**状态：待产品所有者评审；未签收。**  
**设计依据：** [M7 Issue #46](https://github.com/gitfortian/ontoforge/issues/46)、[OFIP-0009](../proposals/OFIP-0009-M7-通用ObjectSet查询.md)、已合并的 [M6 PR #68](https://github.com/gitfortian/ontoforge/pull/68) 与 [来源失败语义硬化 PR #71](https://github.com/gitfortian/ontoforge/pull/71)。

## 本轮目的

先评审用户如何构建一个类型安全、范围明确、可分页或可聚合的 ObjectSet 查询。当前提交只有静态交互原型和 Chromium 原型测试，不包含生产 Java/React 查询执行代码，也不改变 OFIP-0009 的 Decision Pending 状态。

原型文件：[`m7-objectset-lowfi.html`](../prototypes/m7-objectset-lowfi.html)。

## 目标用户故事

采购分析人员使用自己定义的 `Supplier` 类型和明确的 Binding revision：

1. 选择对象类型、不可变 Snapshot 与绑定版本。
2. 通过类型化字段选择筛选条件，例如 `rating > 4.0 AND active = true`；不输入 SQL。
3. 配置最多 3 个稳定排序字段，系统追加 ObjectRef 平局排序。
4. 选择结果列；过滤/排序所需属性不必被投影到结果中。
5. 选择分页结果或有限聚合，两种模式互斥。
6. 执行成功后查看同一个有界结果快照、精确计数、版本锁和来源证据；下一页不重新查询一份新结果。
7. 发生预算超限、来源断线/撤权、结构漂移或快照过期时，显示明确错误，不返回部分对象或伪造精确计数。

## 信息架构与交互决定

- 左侧“查询设计”按固定阶段组织：对象与版本 → 筛选 → 排序 → 投影 → 分页/聚合 → 执行。
- 右侧“执行结果与证据”将对象结果、执行状态、结果快照和来源质量信封分开呈现。
- 查询模式切换、绑定 revision 或条件/排序/投影变化后，旧结果失效并要求重新执行，避免把旧结果误认为新查询的结果。
- 过滤、排序和聚合属性由服务端合同按真实属性类型限制；不支持的类型/操作符不自动降级。
- 成功结果只显示请求范围；`freshness/checkpoint=UNKNOWN` 不伪装成“实时”。
- 翻页时若服务端复核发现来源权限已撤销，则返回失败并隐藏结果页；游标不授予权限。
- 静态合成数据仅用于交互走查，页面明确标注“不调用后端 API”。

## 与 M6 实际合同的衔接

M6 的 `POST /api/runtime/objects/read` 是单个 ObjectRef 的按需读取，不是集合扫描 API。M7 不应循环调用该端点逐个取对象，也不应扩展 M3 的固定制造类型 switch。生产设计须：
- 对一次查询使用 `filter ∪ sort ∪ aggregate ∪ project ∪ primaryKey` 的完整“查询使用属性”集合进行类型转换与冲突核验；只把 Project 集合输出给用户。不能让筛选条件在去重之前隐藏同身份属性冲突。
- 为整次查询设置独立的来源行数、扫描字节数、执行时间与结果大小预算；M6 单对象的 8 MiB 上限不能被乘以对象数。
- 成功后冻结有界结果，游标绑定 query digest、精确版本锁、服务端访问范围与 TTL；每页重新核验包权限、来源授权和 schema fingerprint。
- 保持 ObjectRef、`PRESENT/NULL/MISSING`、精确数值字符串和 freshness UNKNOWN 的 M6 语义。

## 原型自动化覆盖

专用 Chromium 测试验证：
- 查询步骤和 versionLock / resultSnapshot 证据显示。
- 筛选条件最多 8 个。
- PageSize 变化、下一页/上一页和结果失效行为。
- `COUNT_DISTINCT` 与 `SUM` 聚合模式。
- 非法 AST、预算超限、来源不可用、结构漂移、过期游标均无结果载荷。
- 已执行第一页后发生来源撤权，下一页被拒绝。
- 切换 Binding revision 更新版本锁；重置清空结果。
- 窄屏布局不让页面整体横向溢出。

**注意：**这些测试仅证明静态原型交互，没有验证生产 AST 编译、SQL、数据库一致性、权限或查询预算。

## 待产品所有者签收

1. 查询构建器是否让用户理解固定执行顺序与筛选/排序/投影的差别？
2. “分页结果 / 聚合统计”分开是否清楚，是否接受首版不支持 Group By？
3. 右侧的结果快照、准确范围、UNKNOWN 新鲜度和来源证据是否足够清楚？
4. 权限撤销、来源漂移、超预算、过期游标的恢复路径是否合理？
5. 当前工作台的信息密度是否适合进入高保真设计？

## 不可越过的门禁

- UX-Low 尚未签收，不能直接宣布进入 UX-High。
- UX-High 需要单独设计、真实 Chromium 回归和产品所有者签收。
- OFIP-0009 仍为 Decision Pending；本 PR 不授权生产运行时代码实施。正式实现前必须取得维护者依仓库治理流程的显式 `[VOTE] Accepted`。
