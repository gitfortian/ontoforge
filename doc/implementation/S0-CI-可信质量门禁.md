# S0 CI 可信质量门禁：范围、证据、失败恢复

> 适用：OntoForge S0 / OFIP-0001 Phase 2。[治理决策](../proposals/OFIP-0001-需求提案文档治理与CI证据门禁.md) · [AI/贡献者规范](../../AGENTS.md)。这是**自动化工程质量**检查，不是产品用户验收或生产发行授权。

## 1. 固定入口与事实边界

- 主入口：`.github/workflows/quality-gate.yml`（**所有 PR、push main、手动运行**；没有 `paths:` 工作流过滤）。
- 最终状态名：**Required Quality Gate**。它总会对选择结果、被选 job 成功、未选 job 真正 skipped 逐项做严格判定。
- **仓库 Ruleset/Branch Protection 尚需维护者单独配置**：YAML 里有名字不代表 GitHub 已禁止带失败检查合并。请在 PR 实际出现稳定状态后，将精确的 `Required Quality Gate` 加为 main 的 required status；若需要，先检查现有 required checks 是否引用旧 job，避免永久 Pending。不要自动修改仓库设置。
- `scripts/ci/quality_ci.py` 使用完整 `base...head` PR diff（包括 rename/copy 双侧路径），push 使用 before..current；workflow_dispatch 和未知/错误 diff 改为全选，防止空路径静默跳过。
- `scripts/ci/test_quality_ci.py` 专门模拟类别、重命名、错误路径、意外跳过、缺少测试报告、失败/取消等负例。**CI 选择器自身也是必跑测试**。
- 测试完成状态只接受执行成功的 job；没选的检查显示 **NOT SELECTED**，不是 PASS。无法定位正确范围时扩大测试，不是报通过。

## 2. 测试选择策略

| 本 PR 改动 | 自动选择的最小套件 | 实际执行的检查 | 明确不代表 |
| --- | --- | --- | --- |
| README/AGENTS/提案/研究/普通文档 | Docs | 文档链接、架构单一权威、OFIP 决策结构 | 后端/浏览器通过 |
| `doc/design/**` | Docs + Native | V0.2 正反例；运行时 Package Schema/共享 JCS 向量加 Backend + E2E，Native 检查器变更保留 E2E | 正式 Ontology Compiler |
| `ontoforge-boot/**`、根 pom、`compose.yaml`、Windows S0 验收脚本 | Backend + E2E | Java21、独立 TEST_DB_* 的 PG17、Flyway V1–V3、API JUnit、真实 Chromium 和重启复查 | 用户 Windows 本地数据库通过 |
| `ontoforge-ui/**`（非 Markdown） | Frontend + E2E | Node22、npm ci、TypeScript/Vite、依赖审计、真实 Chromium | 未覆盖的浏览器、人工易用性或生产验收 |
| `experiments/pg-runtime/**`（非 Markdown） | POC | 一次性 PG17 `ontopoc`、20k 研究实验与原始证据 | 生产 RLS/ERP/Action 性能或可靠性 |
| `.github/workflows/**`、`scripts/ci/**`、未知新目录/共用配置 | **六套全部（含真实 E2E）** | 保守回归 + 选择器自测 | 正式 Release Gate 或用户人工验收 |

例：重命名 `ontoforge-boot/**` 到 `ontoforge-ui/**` 会同时选择 Backend 和 Frontend；删除代码也要按旧路径判定影响。

研究 POC 的大规模 `100000×8`/`250000×24` 和其他独立专项工作流仍保留在原 workflow，**不作为每一个日常 PR 的自动 required 套件**。更新实验代码时原本的专项 PR 触发仍可能额外执行，这些结果应分别记录，不可由 20k smoke 冒充大规模验收。

## 3. 如何识别虚假的绿灯

`Required Quality Gate` 汇总以下状态：

- **PASS (selected)**：需要执行且对应 job 确实为 `success`。
- **NOT SELECTED**：该 PR 的完整 diff 不触及此套件，对应 job 必须为 `skipped`。
- **FAIL**：需要执行却失败、取消、跳过，或不需要执行却意外成功，以及范围识别 job 出错。最终门禁失败。
- **NOT RUN**：本工作流没有实际执行的用户 Windows 既有安装验收、真实生产授权/Action 及正式发行；这些不能因 `Required Quality Gate` PASS 改称成功。

Backend 除 Maven 返回码外，**必须检查本次产生的 Surefire XML**，并且包含 `FoundationTest.realPostgresMigrationAndApiStatus` 与 `FoundationTest.migrationVersionOneExists`，任一 missing/skipped/error/failure 均拒绝。旧报告在测试前删除。

每个专项 job 在 Summary 中写入 `git rev-parse HEAD`；汇总写入当前 Actions checkout `GITHUB_SHA` 和源 PR head SHA。PR 采用 GitHub 合成 merge commit 运行时，二者可能不同；代码审查应分别记录**来源 head、目标 base、实际测试 checkout**，不能把前一个提交的 PASS 当作后一个的结果。

Frontend 提交 `package-lock.json`，统一以 Node 22.12+ 和 `npm ci` 安装；依赖更新必须同时提交锁文件、构建及 `npm run audit:dependencies` 结果。Dependabot 为 npm/Maven/Actions 提交每周更新建议，不能替代实际回归。

## 4. 本机验证（不连接业务数据）

从仓库根目录：

```powershell
# 无需数据库，仅测试分类器、负例与状态逻辑
python -m unittest discover -s scripts/ci -p "test_*.py" -v
# 文档与治理链接
python -m unittest discover -s doc/tests -p "test_*.py" -v
# Native 合同（先安装 jsonschema==4.25.1）
python -m unittest discover -s doc/design/tests -p "test_native_v02.py" -v
```

Backend 的 PostgreSQL/Flyway 实测依赖 **隔离 PostgreSQL 测试数据库**；不要把研究 POC 的 `DROP SCHEMA poc CASCADE` 指向 Windows `ontoforge` 或真实生产数据库。本机启动和持久化验收仍按照 [Windows 11 手册](./S0-Windows11-本机PostgreSQL-启动与验收.md) 执行，与 CI 各自记录证据。

## 5. 真实验收清单

1. Docs-only PR：范围选择 Docs=true，其余 false；Summary 中其余为 NOT SELECTED；最终 `Required Quality Gate` PASS。
2. 改 Backend 或 Flyway：必须真实启动 PG17、执行 Maven 并产生两项正向 JUnit XML 证据。
3. 改 Native Schema/AST：Docs 和 Native 真实运行；其中运行时 Package Schema 必须再执行 Backend 与 E2E。
4. 改前端：锁定安装、依赖审计、TypeScript/生产构建及真实产品 E2E 必须通过。
5. 改 CI/未知路径：六套检查全部被选择；POC 必须使用一次性 CI 数据库，不能碰本机/生产数据库。
6. 任何选中 job 失败、取消、无报告或未执行：最终 `Required Quality Gate` 必须失败，不得降级为“其它检查都绿”。
7. 保护开关为另一项**仓库设置决策**：本 PR 只提供稳定状态、脚本和测试，不宣称已经开启 main 强制保护。

已存在的 `s0-mvp.yml`、`architecture-docs-v13.yml`、`native-contract-v02.yml` 及 PostgreSQL POC 专项检查暂时并行保留，防止迁移期间丢失门禁；待稳定门禁实际验证并确认 branch rules 后，可在单独 PR 评审是否将重复工作流改为仅手动调用。

## 6. 真实产品 E2E 与保护配置

质量门通过 `workflow_call` 复用 `native-draft-e2e.yml`，仅由统一选择器决定 PR 范围；后端、前端、验收脚本、运行时 Package Schema 或共享 JCS 向量变化均触发真实用户故事。E2E 失败、取消、被意外跳过或缺失时聚合失败。原型 HTML 的 UX 检查仍是独立范围。

`scripts/configure-main-protection.ps1` 默认只预览 `.github/main-ruleset.json`。待本轮门禁合并并在远程 main 成功后，具备管理权限的维护者可显式执行 `-Apply`；脚本绑定 main、要求 PR 和 GitHub Actions 的 Required Quality Gate、禁止删除及强推，并回读配置。不会在 CI 自动改变仓库设置。仍须以失败测试 PR 证明合并确被阻止。

模板采用单维护者可执行的 PR + 必需检查基线（批准人数为 0），不会伪称存在独立人工批准。建立实际审核者团队后，再评审将批准人数提升为 1 或使用 CODEOWNERS；高风险合同的复核责任仍按贡献规范执行。当前启用状态只能以 GitHub 实际查询为准。

数据库测试在 Flyway 建立连接前校验 `TEST_DB_URL`：只接受 loopback、独立 `ontoforge_test_*` 库名、无 JDBC 选项；TEST_DB_USERNAME/PASSWORD 不回退到 DB_*。故障注入还要求 `TEST_DB_ALLOW_FAULT_INJECTION=true`。Windows 操作示例见现有[本机手册](./S0-Windows11-本机PostgreSQL-启动与验收.md)。
