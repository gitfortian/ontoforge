# OntoForge · 砺元｜V1.1 第二轮独立产品功能复核报告

> **评审日期**：2026-10-19。**评审对象**：[PR #78](https://github.com/gitfortian/ontoforge/pull/78) 的[全产品三级功能树](./OntoForge-产品完整功能树-三级-V1.1.md)、[旧 125 条迁移账](./OntoForge-ASIS到全产品功能树-追溯与分类-V1.1.md)、[Core 合同/黄金故事](./OntoForge-Core功能验收合同与黄金故事-V1.1.md)。
> **代码基线**：2026-10-19 main `c9de0336`，审查 Native 草稿/快照、M4–M7、QualityMVP、Spring/React 真实源码。**未运行用户 Windows 11 PostgreSQL/Chromium 实机 UAT，也不等同于生产安全/发布评审**。
> **结论**：**产品功能范围结构有条件通过；实现成熟度限定认可；真实用户验收 NOT RUN；商业发布 NOT RELEASED；PR #78 仍为 Draft / 未获最终签收。**

> **审查后新增工程变化提醒**：最新主线至 `c157d228` 已进行了多模块代码迁移。本文关于 M5/M7 的 Java 源码链接已指向重组后的 `ontoforge-business` 等路径；必须以本次 PR 合并目标 head 实际测试确认行为，不从旧源码目录路径推断最新 main 的接口或 CI 状态。

## 1. 数量复核与唯一权威

| 检查对象 | 结果 | 评审判定 |
|---|---|---|
| 一级产品功能域 | 8 个，沿用 V1.3 | 结构齐全 |
| 二级 F01–F29 | 29 个且编号唯一 | 结构齐全 |
| 三级能力 ID | 216 个且编号唯一 | 产品范围已编目，不是交付数量 |
| 修订后代码状态分类 | **57 已实现·限定环境、5 局部实现／待验证、154 规划／未实施** | 静态保守分级，不是完成率 |
| 原 AS-IS 125 条映射 | 71 直接、26 细节合并、16 未来、6 边界、6 质量 | 全部有去向，保留原证据账 |
| 标准验收 | 15 Core 合同、8 条跨模块黄金故事 | 规范已建立，仍无本次实跑签收 |

**重要变更**：上一轮公布的 70/9/137 是粗粒度初始标记。本轮发现同一 Java/React 实现被多个产品域重复标成“已实现”，以及内部迁移脚本被当成运营产品能力，于是按独立用户价值重新修正为 **57/5/154**。这不是业务代码回退，也不能解释为本轮删除了 13 个已交付 PR。

## 2. P1 发现与本轮整改

### R2-P1-01 同一个能力跨域重复认领（文档已整改）

原 F01/F23 的健康页、F03/F28 的草稿校验、F04/F20 的快照摘要、F08/F28 的 Binding 校验、F11/F19/F20/F23/F28 的 M7 查询预算/证据/游标，以及 F12/F15 的固定质量影响查询，是**共用已有实现**而不是每个域都有独立工作台与操作结果。

**处理**：各基础能力由单一主域承担实现状态，衍生的统一质量门、正式审计、运维预算策略、跨层撤权、根因分析、M11 连续 Studio 等独立产品目标标记“规划中”。本次调整了 **24 条 L3 的语义或状态**，并在功能树记录依赖与非目标边界。

### R2-P1-02 研发机制和产品运营能力混淆（文档已整改）

Flyway 迁移不等于可操作的升级/回滚产品；PowerShell 脱敏数据脚本不等于演示数据环境管理；内置导航不等于连续 Studio；“多领域黄金测试”是 QA Gate 而非可安装领域模板。已将 F23.02/F23.03/F23.08/F22.02/F22.06 等改为独立的正向产品目标，并保持规划状态。现有底层工程机制仍保留在 AS-IS 与自动质量证据中。

### R2-P1-03 采购黄金故事的受控来源前提不足（合同已整改）

[DataBindingService](../../ontoforge-business/src/main/java/io/ontoforge/business/connection/DataBindingService.java) 使用来源 ID `local-pg-sandbox`，只允许 `public` 下由服务端登记的表。默认允许表配置见 [application.yml](../../ontoforge-boot/src/main/resources/application.yml)：`m5_supplier_source`，且要求独立最小权限只读账号。任意新建 Supplier/PurchaseOrder 类型不意味着自动出现相应来源数据。

**处理**：U01 现在明确创建 Supplier/PurchaseOrder 的 Native 定义，但在当前 Core 仅对实际准备好的 `public.m5_supplier_source` 验证 Supplier Binding、Object Runtime、ObjectSet；PurchaseOrder 的实体 LinkFact/双向遍历仍归未实现的 M8。

### R2-P1-04 “重复主键”验收与 M5 唯一键校验矛盾（合同已整改）

M5 要求所绑定的 `sourceKey` 是**单列主键或单列 UNIQUE**。因此在正常受约束 PostgreSQL 来源表里，不可能随意插入相同 sourceKey 的两条物理记录。M6/M7 的防御性 ObjectRef 去重与冲突检查可以做**受控异常/服务层注入测试**，但不可作为用户常规合法表的 UAT 路径来宣称验证成功。

**处理**：C06/C09/U04 分离“合法唯一键 + 精确 COUNT/SUM/NULL/MISSING”与“异常物理重复/冲突”两类测试，并要求隔离夹具和独立证据。

### R2-P1-05 全域完成定义仍不足以最终签收（未解决）

216 条功能树现在是完整产品范围索引，**不是 216 份包含角色、前置、输入、输出、权限、失败恢复、证据和测试编号的独立规格**。15 项合同已覆盖近期重点 Native/M5–M8，但长期规则、事件、知识、Decision/Action、Agent/语义协同仍需依获准里程碑逐项精化。

### R2-P1-06 用户现场 UAT 和产品安全签收仍缺（未解决）

本次仅在 GitHub 中静态核对代码与 PR，未在用户 Windows 11 本机执行 PG 数据准备、Spring API、Chromium 全路径、撤权/漂移、失败恢复等验收。即使本 PR 文档 CI 通过，也**不能**写“全产品 UAT 通过”“企业 IAM 已完成”或“SchemaRelease 已正式发布”。

## 3. P2 专业语义重点

| 核心边界 | 审核判断 |
|---|---|
| Native Draft vs Package Registry | `NativeDraftService.create(packageId)` 真实落地 `native_package_draft`；F05.01 已改成“创建单包工作草稿”，不冒充完整包注册发布 |
| M4 Type Catalog | 仅 VALID 草稿或本地快照来源，不是正式 SchemaRelease Registry；F05.09 已排除原部分实现条目的成熟度外溢 |
| M7 前端查询意图 | [#80](https://github.com/gitfortian/ontoforge/issues/80) 仍未修复：用户添加的未填值条件可能被前端静默丢弃，导致执行范围扩大 |
| UUID UI/API 合同 | [#81](https://github.com/gitfortian/ontoforge/issues/81) 仍未修复：后台接受 UUID，而 Native 编辑页无 UUID 选项及主键候选 |
| 结果快照与业务新鲜度 | M7 `resultSnapshotRef` 只冻结短时分页结果，不是来源历史/永久索引；`freshness=UNKNOWN` 不等于实时业务事实 |
| 固定制造 vs 通用 Link | M2/M3 的批次→工单→订单链路不能当作 M8 LinkBinding/LinkFact/通用遍历已交付 |
| 图谱引擎与 OWL | F27 是可选物化与查询适配能力，不强制把 Native CWA 的运行时变成 OWL/RDF 或图数据库 |
| Rule/Action 安全 | Rule/Decision 的候选不等于 Action 授权；HTTP 回执成功不等于业务 Outcome Verified |

## 4. 正式验收 Gate

| Gate | 结论 |
|---|---|
| G1：8 域 / 29 F / 216 L3 产品范围 | **通过（结构级）** |
| G2：单一主归属与非产品项剥离 | **有条件通过（第二轮已修订）** |
| G3：Core 用户可操作的合同和故障路径 | **部分通过：合同已编制，尚未真实执行** |
| G4：真实 PostgreSQL/HTTP/Chromium/Windows 用户 UAT | **NOT RUN** |
| G5：企业安全、正式 Release、M8+ 全部核心闭环 | **NOT RELEASED / 未签收** |
| G6：PR #78 正式审批及合并 | **保持 Draft**；独立于 Markdown CI 通过 |

## 5. 接下来必须完成的实际工作

1. **代码修复**：[#80 M7 未完成筛选](https://github.com/gitfortian/ontoforge/issues/80) 和 [#81 Native UUID](https://github.com/gitfortian/ontoforge/issues/81) 分独立纵向 PR，走正反例/权限/版本/Chromium 真实测试，禁止偷偷混进本纯文档 PR。
2. **当前 Core 实测**：按调整后的 C01–C15、U01–U06 准备 `m5_supplier_source`、只读账号、白名单、唯一键和预期数据，获得同 SHA PG/HTTP/Chromium 证据；用户既有 Windows UAT 继续独立记账。
3. **治理签收**：在 [#82](https://github.com/gitfortian/ontoforge/issues/82) 对长期 F01–F29 主归属及版次边界做产品负责人最终确认，再经真正 PR 分支保护与维护者门禁判断是否合并。
4. **M8 仍走标准流程**：M8.1 外键正反向一跳、M8.2 桥表、M8.3 两跳不提前计完成，继续独立 OFIP、UX-Low、UX-High、生产实现及 PG/React 验收。

**第二轮总体判断**：范围和目录归属已达到可继续规划的候选质量，但未达到全产品实际功能正式验收、用户 UAT 或发布状态。保持 PR #78 Draft。