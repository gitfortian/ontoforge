# OntoForge S0-MVP-0.2 · Windows 11 原生 PostgreSQL 验收记录

**工程基线**：[PR #7（已合并）](https://github.com/gitfortian/ontoforge/pull/7)，真实 Spring Boot + PostgreSQL 17 + React ObjectType 建模。**发布状态**：Not Released；**Windows 实机测试状态**：NOT RUN（等待操作者在自己的 Windows 11 上执行）。此文档是验收步骤与结果记录模板，不是事后补造的通过报告。

## 本次要证明什么

用户能在本机创建一个真实的 Native 模型包草稿，保存不完整 WIP 并读取、执行结构校验、补齐属性和主键使 Native V0.2 校验通过、保存新修订后旧校验失效，并正确拒绝并发旧版本写入（409）。停止并重启 Spring Boot **但不重置原生 PostgreSQL**，保存的模型与 installationId 仍完全一致。页面打开、刷新重开及异常提示还应与系统实际状态一致。

## 运行前置条件

- Windows 11、Java 21、Node.js 22、Maven；本机已安装 PostgreSQL（建议 PG17）。默认 `127.0.0.1:5432/ontoforge`，不得把本版本服务开放到公共网络。
- 已执行 `git pull` 获取 PR #7 的 main 代码，并按 [README](../../README.md) 在不同 PowerShell 窗口启动 Spring Boot、React/Vite。后端环境变量 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD` 已正确设置，不向 ChatGPT/GitHub 提交密码。
- **使用测试/开发数据库**。验收脚本会创建一个前缀为 `acceptance.p<随机UUID>` 的新草稿；数据库没有删除功能，因此这个草稿会保留。脚本不会修改或清除其他模型，也不会执行迁移或对已有表写 SQL。
- 仅允许 loopback `http://127.0.0.1:8080` 或 `http://localhost:8080`（也可通过 `-ApiBase` 选择其他本机端口）。

## 两阶段实际验收

```powershell
# 1. 核查数据库与服务身份，只读；需要 DB_PASSWORD 和 psql
.\scripts\verify-s0.ps1

# 2. 明确同意创建一个独立测试草稿，完成真实 HTTP 黄金故事
.\scripts\verify-objecttype.ps1 -Mode Exercise -AllowCreateTestDraft
```

**Exercise** 必须在屏幕上逐一输出 PASS：创建模型包、revision 2 WIP、INCOMPLETE、revision 3 VALID、revision 4 NOT_RUN、过期 revision 409。首次执行结束后会打印证据 JSON 的本机路径与 Recheck 命令。证据文件只包含安装 UUID、草稿 UUID、版本号和测试描述，无密码。

然后**仅重启 Spring Boot**，保持 PG 服务和数据原样不动：

```powershell
# 使用上一步输出的实际文件路径（示例占位路径不能原样执行）
.\scripts\verify-objecttype.ps1 -Mode Recheck -EvidencePath "C:\Users\<user>\AppData\Local\Temp\ontoforge-acceptance-<uuid>.json"
```

**Recheck** 验证当前 installationId 与创建时完全一致、同一 draftId 存在、revision=4、ObjectType 描述未变且最后校验状态为 NOT_RUN。任何失败不得改为 PASS。若操作期间第三方编辑了同一验收草稿，须记录差异并重新执行专门验收，不得简单修改预期 revision。

最后在 Edge / Chrome 打开 `http://127.0.0.1:5173/#/model/packages`，核实模型包列表可以找到草稿、点击能够打开，修改未保存内容时 Back 应询问是否离开，错误字段能定位，数据与同一 PostgreSQL 后端保持一致。此项为 UI 实机人工抽检（不由 Linux CI 自动替代）。

## 可填写的实际结果

| 证据 | 验收目标 | 当前状态 |
| --- | --- | --- |
| Linux CI PG17 + Java/Flyway + React/Chromium（合并前） | 功能和异常路径有自动证据 | PASS（PR #7，独立 Linux CI） |
| Windows 服务/数据库身份验证 | `verify-s0.ps1` 只读通过，Flyway V1/V2 | NOT RUN |
| Windows Exercise 黄金故事 | 独立草稿创建、WIP、VALID、409 | NOT RUN |
| Windows 后端重启后 Recheck | 安装 UUID、草稿 UUID、修订与内容不变 | NOT RUN |
| Windows Edge/Chrome 人工抽检 | 视觉、刷新、导航离开保护与错误定位 | NOT RUN |
| 认证授权 / 发布 / ObjectSet / Action | 本次范围外 | NOT IMPLEMENTED |

记录时建议附上：实际代码 commit SHA、Java/Node/PostgreSQL 版本、运行日期与时区、经过脱敏的控制台 PASS 文本和截图、验收人员结论。**不得上传 DB_PASSWORD、连接凭据或包含工业真实业务数据的文件。**

**签收规则**：以上 Windows 相关四项全部完成才能给出 S0-MVP-0.2「Windows 本机验收 PASS」。代码合并不等于发布；S0 当前没有授权控制，仍禁止生产部署。

## 问题排查与恢复

- `DB_PASSWORD` 缺失、PG 未启动、Flyway V2 不存在 → 先检查 README 配置与 `verify-s0.ps1`，不要删除库重建。
- API 返回 503、超时或不同安装 UUID → 检查是否连接了另一数据库。**保留验收文件与现有数据**，不要盲目重试创建。
- Recheck 失败 → 先保存日志，核对 PG 实例/端口、Spring Boot 日志和 draftId，不得修改证据文件以强制通过。
- 测试产生的草稿无法删除 → 这是当前 MVP 的功能边界，在测试数据库留存，不应在生产库执行此脚本。
