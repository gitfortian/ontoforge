# M3 · Native 对象集合浏览与字段筛选：功能完成说明与验收计划

> **实施状态**：PR [#40](https://github.com/gitfortian/ontoforge/pull/40) 开发候选，基于 #38 ObjectSet 关系影响集合。仅对应本机隔离测试数据库；真实主线成熟度只在合并及 CI 验收后更新。产品任务 [#39](https://github.com/gitfortian/ontoforge/issues/39)；长期唯一本体架构继续参照 [V1.3](../本体产品总体功能与技术架构设计_v1.3.md)。

## 1. 一眼看清本轮用户价值

从 **“知道一批次影响了哪些工单/订单”（M2 关系集合）** 进一步到 **“可按对象类型查看业务对象集合，再按业务属性过滤”（M3 对象集合）**。用户在工业质量追溯工作台新增的第 04 步选择以下已 VALID Native ObjectType，再用当前已保存的 PostgreSQL Binding 返回真实事实：

| Native ObjectType | 数据源来自当前 Mapping | 可筛选的标量属性 | 预期 UI |
| --- | --- | --- | --- |
| `production-batch`（生产批次） | `batchTable/batchId/batchStatus` | `id`、`qualityStatus` | 按质量状态找到批次、显示源表/主键/状态字段 |
| `work-order`（生产工单） | `workTable/workId` | `id`、`workOrderId`（同一主键） | 物理重复工单 ID 只显示**一个对象**；避免把 N:M 订单关联列误写成单值属性 |
| `customer-order`（客户订单） | `orderTable/orderId/orderName` | `id`、`customerName` | 客户名称前缀筛选，按对象 ID 翻页 |

界面提供受控操作：**选择类型 → 选择属性 → 精确等于/以文字开头 → 查询对象集合 → 查看来源证据 → 下一页/上一页**。筛选值留空可浏览全部；保留已有 01–03 本体建模、映射预览、双跳追溯。

## 2. 真实接口与查询机制

`GET /api/mvp/quality/object-set?objectTypeId=customer-order&filterField=customerName&operator=prefix&filterValue=华&pageSize=10&cursor=...`

**返回 DTO `ObjectSetPage`**：对象类型、筛选字段/运算符/值、所用 Native 草稿与 Mapping revision、来源指纹、准确匹配去重对象总数、当前页对象数组、nextCursor。每个对象返回：

- `id`：按已映射主键列聚合的唯一对象 ID。
- `attributeName/attributeValue`：仅允许的单值标量属性与值。
- `sourceTable`、`idSourceColumn`、`attributeSourceColumn`：可定位的 PostgreSQL 来源表/列；**并非独立的有签名版本化 SourceFact**。
- `physicalRowCount`：该对象聚合前的真实物理行数，供用户识别去重情况。

**执行链**：

```text
Native VALID 的 manufacturing.quality 模型 + 当前 boundRevision
    → 当前 Binding 中三种语义角色的受控表和列
    → information_schema 白名单来源校验
    → 按对象 ID 聚合物理行（去重；检查标量属性一致性）
    → 参数化 eq / starts_with 过滤
    → 对匹配总数做有界计数
    → 按对象 ID 排序、keyset 游标取一页
    → 返回对象及来源证据
```

### 安全与可靠性

- 服务端强制三种 Native ObjectType、两个受支持运算符及**当前类型**白名单属性；不会接受用户自定义 SQL 或任意数据库列。
- 查询 SQL 标识符来自已检验的 Binding，只用双引号引用；筛选值和游标通过 JDBC 参数传入。
- 一个对象 ID 对应多条来源事实但标量属性冲突或为空时返回 409 `OBJECTSET_ATTRIBUTE_CONFLICT`，不选取一个值冒充事实。**M3 不试图把多目标关系压成单值属性。**
- 单请求 `REPEATABLE_READ`、查询 statement timeout 3 秒、每页 1–50 条、最多 10000 个匹配对象，超预算明确拒绝。
- 游标绑定 Native 对象类型、筛选字段、运算符、值、模型 revision、映射 SHA-256 指纹及上一对象 ID；参数变化时返回 400 `INVALID_OBJECTSET_CURSOR`，避免串查询翻页。
- **仅保证单次 HTTP 请求内 PG 一致性**；跨页请求的来源数据水位尚无冻结、游标不是授权凭证、用户不应将其用作安全 Token。
- 未创建通用 ObjectSet AST Planner、ObjectIndex、LinkFact、数据同步 Worker 或跨源权限体系。

## 3. 测试样例与可观察结果

| 实际调用或 UI 操作 | 基于已有 Flyway V6 合成事实的预期证据 |
| --- | --- |
| 客户订单全部，pageSize=2 | 对象 `SO-91`、`SO-92`，下一页 `SO-93`；总数一直为 3 |
| `customerName` 前缀“华” | 仅 `SO-91`（华南装备）和 `SO-92`（华东制造），2 个对象 |
| 批次质量 `qualityStatus = PASSED` | `LOT-901`；错误事实 `LOT-904` 同时含 BLOCKED/PASSED，不被误算为一个可信单值状态 |
| 工单 `id = JOB-70` | 1 个工单对象，`physicalRowCount=3`；三行代表重复或多条关系事实，不是三个工单 |
| 查询 `LOT-904` 的对象状态 | 409 `OBJECTSET_ATTRIBUTE_CONFLICT` |
| 非法 Native 类型、业务关系字段、运算符、页大小 | 返回明确 400 / 422 错误，不放开任意表/SQL |
| 把订单分页游标拿去查批次/不同过滤条件 | 400 `INVALID_OBJECTSET_CURSOR` |
| 模型/绑定 revision 不匹配、来源字段漂移 | 原已有 409 `BINDING_STALE` / `BINDING_SCHEMA_DRIFT` 继续生效 |

**验收证据入口**：`QualityImpactObjectSetTest` 的真实 PostgreSQL HTTP 集成；`test_real_mvp_quality.py` 的真实 React/Chromium 对象类型选择、过滤、前后页和来源证据；原 M2 多订单黄金故事、Native 草稿/快照/B0 兼容测试。CI 成功仅表示相应代码提交通过自动门禁，不等于 Win11 实际安装/工厂数据已通过 UAT。

## 4. 全局 MVP 成熟度与下一步

| 功能域 | 本轮后成熟度 | 完成后仍缺什么 |
| --- | --- | --- |
| Native Ontology Language | 已有有限类型/关系/属性编辑与校验 | 完整 Schema 发布与跨包类型管理 |
| Data Binding | 当前单本机 PG 白名单测试表 + 有效修订绑定 | 工业 Connector、来源水位/版本、实体消歧 |
| ObjectSet | M2 固定双跳关系集合；**M3 三对象类型读集合 + 属性过滤/分页**（待 #40 合并） | 通用 AST/Planner、任意对象类型映射、跨来源、对象索引/聚合 |
| Action/Agent | 暂不投入 MVP 研发 | 后续安全策略下的执行/只读 Agent Context |
| 登录与角色 | 精简 MVP 本机无需认证；通用实现未来优先复用 yak-ops | yak-ops HttpSession 真实移植、会话/CSRF/许可验收 |

**下一建议的核心功能**：把当前固定三类对象的读取形态抽为可重用的最小 **ObjectSet 查询语言/执行 AST**，能够将任意已绑定 ObjectType 的字段过滤、排序和结果证据贯通，而不是继续堆砌固定 SQL 入口。先明确用户可配置的 Binding 模型如何映射多值属性与关系事实，再做对照式真实 PG 小 POC；不得用 Agent 代替语义执行正确性。
