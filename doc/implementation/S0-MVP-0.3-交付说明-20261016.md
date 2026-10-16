# OntoForge S0-MVP-0.3 · 本轮交付说明与进度交接（2026-10-16）

> **状态：PR #11 Draft / 已提交功能与加严测试代码 / 最新 Head CI 以 Actions 实际执行为准 / Windows 11 原生 PostgreSQL 重启验收 NOT RUN / Not Released。**
>
> 这是一次**交付进度与证据说明**，不以测试文件数量或 CI 绿色代替可用产品。产品底层定位保持 LOCAL_SNAPSHOT_ONLY，不能称为正式发布版本。

## 1. 本轮交付目标与已实现用户能力

对应 [PR #11](https://github.com/gitfortian/ontoforge/pull/11)、[OFIP-0003 冻结决策](../proposals/OFIP-0003-S0-MVP-0.3-模型快照与发布边界.md)。

| 功能 | 用户如何操作 | 本次实际可得到什么 | 性质 |
| --- | --- | --- | --- |
| Native ObjectType 草稿 | 在现有建模工作台创建、保存、校验 | 真实 PG 草稿及独立 revision/VALID 证据 | 已实现 / Linux E2E 曾通过 |
| 冻结模型快照 | 校验当前 revision 后点击冻结 | 独立不可变本地快照、JCS UTF-8、SHA-256、幂等回执 | 已实现 / Linux E2E 曾通过 |
| 历史追溯 | 进入同包历史并跨页选择版本 | 实际 PostgreSQL 的旧快照仍可重开 | 已实现 / 22 快照浏览器用例曾通过 |
| 结构差异 | 选择 From、To | Type/Property 对照与潜在风险；**不是兼容认证** | 已实现 / Linux E2E 曾通过 |
| 故障安全 | 摘要或 JSONB 镜像被修改后再按 ID/列表/Diff 读取 | 期望返回 500 SNAPSHOT_INTEGRITY_FAILURE，不提供“已验证”假结果 | **本轮新增真实 PG + HTTP 故障注入测试，最新 CI 待确认** |
| 风险细分 | 比较 ENUM 顺序/成员、classification、required、主键、类型 | 期望顺序变化忽略，其他变更按风险级别报告 | **本轮新增真实 HTTP 测试，最新 CI 待确认** |
| 不支持资产防丢失 | 草稿携带 LinkType/未支持 actions 字段后尝试保存 | 期望 422 编辑合同拒绝、revision 不变、未产生快照 | **本轮新增真实 HTTP 负例，最新 CI 待确认** |
| JCS 交叉验证 | 对同一组黄金向量运行 Java 与 Node.js | 期望 canonical UTF-8 和 SHA-256 完全一致 | **本轮新增 Node 独立实现及 E2E 工作流，最新 CI 待确认** |

本期**不提供**身份授权、空间隔离、正式审核、SchemaRelease、ReleaseDigest、激活、数据绑定运行或工业 Action；无真实生产准入能力。

## 2. 本轮具体提交了什么

- `ontoforge-boot/src/test/java/io/ontoforge/boot/NativeSnapshotTest.java`：新增长期回归的故障注入、Diff 及禁止资产测试。故障注入只针对**隔离测试数据库**；临时禁用 trigger 后严格 `finally` 恢复，读取时必须 fail closed。
- `scripts/ci/check_jcs_vectors.mjs`：独立 JavaScript/ECMAScript 规范化及摘要对照，共用 `doc/design/fixtures/ofip-0003-jcs-fixtures.json`。
- `.github/workflows/native-draft-e2e.yml`：加入 JCS 验证步骤与 fixture/脚本路径触发。
- `doc/implementation/交付说明模板.md`：规定此后**每轮功能交付**必须报告用户可操作功能、证据、状态、剩余风险、下个 PR、责任和 Gate，不允许模糊进度。

**提醒：提交代码 ≠ CI 验证已成功；本文件应在最新 Head 全套 CI 完成后再补充准确 SHA/链接/结果。**

## 3. 质量证据与未验证范围

| 项目 | 本轮开始前的可信证据 | 当前加严代码状态 |
| --- | --- | --- |
| PG17 + Java JUnit | 14/14 PASS（原 Head `ba0cb7063b2450af197a0d0fd670a23b1e41df8c`） | 待最新 Head CI |
| 真实 React + Chromium | 10/10 PASS（同原 Head） | 待最新 Head CI |
| Required Quality | [#38870992289](https://github.com/gitfortian/ontoforge/actions/runs/38870992289) PASS（原 Head） | 待最新 Head CI |
| 真实 E2E | [#38870992283](https://github.com/gitfortian/ontoforge/actions/runs/38870992283) PASS（原 Head） | 待最新 Head CI |
| Windows PowerShell 5.1 | 语法 PASS（原 Head） | **不等于 Windows 本机运行** |
| Windows 11 原生 PG → Spring Boot 真重启 → Recheck → Edge | NOT RUN | **NOT RUN / 独立用户验收 Gate** |

## 4. 按严重程度管理的遗留任务

| 优先级 | 下一任务 | 完成定义 | Gate |
| --- | --- | --- | --- |
| P0 | 解决本轮加严测试暴露的任何 Java/PG/Node/Chromium CI 缺陷 | 最新 Head 全部必需门禁 PASS；禁止沿用旧 Head | 合并前 |
| P0 | Windows 原生持久化验收 | 在隔离测试库运行 `scripts/verify-snapshot.ps1 -Mode Exercise -AllowCreateTestDraft`，**真重启** Spring Boot 后执行输出的 `-Mode Recheck -EvidencePath ...`，记录 installationId、snapshotId/digest；Edge 抽检页面 | 独立签收，不由 Linux CI 代替 |
| P1 | 扩充 RFC 8785 官方数字/转义/恶意 Unicode 边界 | 跨 Java/JS 共享黄金样例全部通过，包括拒绝非法输入 | 0.3 加严或专门合同测试 |
| P1 | 数据库故障及极端 keyset 竞态复核 | 有真实错误恢复证据；缺失不伪报 PASS | 后续稳定性验收 |
| P1 | 审查 0.4 能力边界 | 先进行用户故事与合同评审，再实施；不得借本地 Snapshot 跳过审批/授权设计 | 0.3 收口后 |

## 5. 阶段进度控制

| 阶段 | 当前状态 | 产品意义 | 下个动作 |
| --- | --- | --- | --- |
| S0-MVP-0.1 | 基座已实现，非生产发布 | 本机 API/PG/React 贯通 | 保持回归 |
| S0-MVP-0.2 | PR #7 已合并，Windows 实机待签收 | 草稿编辑/校验/CAS | 与 0.3 原生验收统一抽检 |
| **S0-MVP-0.3** | **PR #11 Draft，加严代码已提交，最终门禁待核对** | 不可变快照/历史/Diff | 收口测试、独立 Windows 验收、再做合并评审 |
| S0-MVP-0.4 | **NOT STARTED** | 待产品评审确定下一可见功能 | 不抢跑权限/审批/工业执行大框架 |

**合并建议**：在本轮新代码 CI 通过、遗留 P0 风险有明确决定前，保持 Draft。即使代码将来合并，也不得把 Windows NOT RUN 或 Not Released 改写为 PASS/Released。

## 6. 用户需要如何验收

无需重新手工逐条执行所有自动化用例。对 0.3，可先审阅最新 [PR #11](https://github.com/gitfortian/ontoforge/pull/11) 与 [完整验收矩阵](./S0-MVP-0.3-功能说明与全面Review验收矩阵.md)。若要签收 Windows 原生持久化，只在**隔离测试数据库**运行双阶段脚本；Exercise 会永久留下草稿和两份 Snapshot，**不得使用生产或重要数据库**。

**以后每个 PR 的交付说明**统一采用 [交付说明模板](./交付说明模板.md)，列出新增的真实用户能力、未实现边界、可复现证据、状态和下轮明确任务。
