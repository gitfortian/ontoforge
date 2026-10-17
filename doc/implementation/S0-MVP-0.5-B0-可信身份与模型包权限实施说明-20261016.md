# S0-MVP-0.5 · B0 可信身份与模型包权限实施说明

> 对应：OFIP-0005 P0-B
> 状态：PR #17 的 Draft 实现候选；OFIP-0005 的显式决策、人工安全评审及产品签收独立记录，CI 通过不代表这些审批完成。
> 本阶段：真实身份认证 + 模型包访问授权的第一条可运行垂直切片
> 本阶段不实现：模型变更审核、正式版本、生效、回退。

## 1. 产品目标

这一轮只回答一个问题：**谁在操作砺元，以及这个人能不能操作这个模型包？**

服务端必须从受信身份得到当前用户、身份来源、模型包所属工作空间、当前用户角色和本次请求允许的动作。

前端可以根据权限隐藏或禁用按钮，但最终授权必须由后端执行。

## 2. 本轮用户效果

### 有权限

模型设计者可以查看授权模型包、创建草稿、保存草稿、校验草稿、创建本地模型快照、查看授权模型历史。

### 无权限

查看者不能保存或创建草稿，也不能校验草稿或创建快照。服务端返回未认证 401；已认证但没有权限 403。

客户端传入的 userId、role、owner 等字段不参与授权。

## 3. 角色最小集合

| 角色 | 允许 |
|---|---|
| Viewer | 阅读已授权模型包的公开内容 |
| Editor | Viewer + 草稿编辑、校验、本地快照 |
| Reviewer | 阅读受限草稿/快照；后续 B1 审核 |
| Publisher | 阅读；后续 B1 正式版本 |
| Activator | 阅读；后续 B2 生效/回退 |
| Owner | 对已分配模型包拥有本阶段 Editor 操作；本 PR 尚无成员/权限管理 API |

本轮真正落地 Viewer、Editor、Owner；Reviewer / Publisher / Activator 先进入权限模型。

## 4. 身份信任

生产：OIDC issuer → JWT 签名验证 → issuer / audience / exp / nbf → subject → 内部 principal。

开发/CI：使用独立的、仅用于测试的签名 JWT fixture。运行应用必须提供 `OIDC_ISSUER_URI`、`OIDC_JWK_SET_URI`、`OIDC_AUDIENCE`；无配置启动失败，不提供匿名开发后门。当前前端尚无 OIDC 登录/BFF；浏览器自动化在浏览器上下文中注入短期测试 Bearer Token，不代表已交付登录体验。

浏览器工作流 `native-draft-e2e.yml` 使用 test classpath 下的 `B0BrowserFixture`，仅监听 `127.0.0.1:18765` 并提供临时 RSA 公钥 JWKS；真实 Boot jar 使用生产 `JwtDecoder`。夹具先生成 64 个随机模型包，再通过 JDBC 向独立 `ontoforge_test_*` 库显式写入 Editor/Viewer 成员及包归属，不自动认领历史包。测试私钥只在夹具进程内，临时 Token 文件不得上传为验收附件。

手动运行 `verify-objecttype.ps1`、`verify-snapshot.ps1`、`verify-linktype.ps1` 时，在当前会话外部设置 `ONTOFORGE_BEARER_TOKEN`，并通过 `-PackageId` 指定**预先分配给该身份且尚无 Draft**的测试包；`Recheck` 读取原证据包，不创建新归属。脚本仍只允许 loopback，失败不会绕过授权。

明确禁止 X-User-Id、X-Role、请求体 reviewerName、前端 role 下拉框、固定 service account 冒充用户。

## 5. 资源边界

新增 Workspace、Principal、Workspace Membership、Package Assignment。

现有 native_package_draft、native_schema_snapshot 全部纳入同一授权边界。

不修改 V1–V3。

## 6. 旧数据迁移

P0-B 不自动把历史 package 授权给任意新用户。旧 package 必须先进入 Workspace 的 Package Assignment。

CI 使用隔离测试数据验证 editor-A、viewer-A、outsider-A 三种访问边界。

## 7. B0 验收矩阵

| 编号 | 场景 | 期望 |
|---|---|---|
| B01 | 无 Token 读取草稿列表 | 401 |
| B02 | 无 Token 创建草稿 | 401 |
| B03 | 有效 Editor Token 读取授权包 | 200 |
| B04 | 有效 Editor Token 创建草稿 | 201 |
| B05 | 有效 Editor Token 保存草稿 | 200 |
| B06 | Viewer Token 读取受限 Draft | 403 |
| B07 | Viewer Token 保存 Draft | 403 |
| B08 | Outsider Token 读取授权包 | 403/404，不泄露内容 |
| B09 | Editor Token 读取另一个未授权包 | 403/404 |
| B10 | Token issuer 错误 | 401 |
| B11 | Token audience 错误 | 401 |
| B12 | Token 过期 | 401 |
| B13 | 请求体伪造 subject/role | 不影响授权 |
| B14 | 用户被撤销包权限后再次访问 | 403 |
| B15 | /api/me 返回可信身份和当前角色 | 200 |
| B16 | /api/system/status 不泄露权限数据 | 200 |

## 8. 本阶段完成定义

- JWT 由服务端验证；
- issuer、audience、时间有效性验证；
- principal 使用 issuer + subject 稳定映射；
- package access 从 PostgreSQL 授权表判断；
- 旧 Draft/Snapshot API 全部受保护；
- Viewer/Editor/Owner 至少三种角色真实生效；
- 401/403 有稳定错误语义；
- 伪造身份字段不能越权；
- 真实 PostgreSQL 集成测试覆盖；
- 真实签名 JWT 测试覆盖；
- 不提交生产私钥；
- Required Quality 全部通过。

## 9. 下一阶段

B0 完成后进入 B1：模型变更与真实审核。

B1 完成：提交模型变更、固定候选内容、质量检查、审核、退回、拒绝、批准。

再进入 B2：正式版本、生效与安全回退。

## 10. B0 OIDC 登录接续（开发候选，不是正式产品发布）

已新增 Authorization Code + PKCE 公共 SPA 登录路径：Vite 构建时设置
`VITE_OIDC_CLIENT_ID`、`VITE_OIDC_AUTHORIZATION_ENDPOINT`、`VITE_OIDC_TOKEN_ENDPOINT`，
可选 `VITE_OIDC_SCOPE`（默认 `openid`）。在企业 IdP 注册 Redirect URI 为
前端实际 origin + pathname；Windows 本地示例为 `http://127.0.0.1:5173/`。
IdP 必须明确允许公共 SPA Client、PKCE S256、授权码模式、浏览器 Token Endpoint CORS，
并签发包含 OntoForge API audience 的短期 Bearer **访问令牌**（而不是拿 ID Token 访问 API）。
后端以受信 issuer / JWKS / audience 独立验证，页面仅消费 `/api/me` 的服务端身份/权限。

前端强制校验 OAuth state、五分钟回调时限、PKCE SHA-256；访问令牌只存于页面内存，
不写 localStorage，不保存长期刷新令牌。刷新页面后需要重新登录；未配置 IdP 时阻止进入
模型工作台，401 发生时清除令牌、重新要求认证。不可用 `X-User-Id` 或 `X-Role` 冒充。
企业真实 IdP 联调、Token 生命周期、CSP、安全威胁建模及生产 TLS/会话设计
**尚未验收，不得标为生产 SSO 已完成**。CI 浏览器通过 Playwright 注入真实签名 Bearer
验证页面/API/PG 路径，与企业 IdP 的完整重定向测试是两件事。

## 11. 既有模型包管理员显式归属与初始化

Flyway V4 只创建 Workspace / Principal / Membership / Package Assignment 表，
不会自动认领任何旧 Draft/Snapshot。数据库管理员可在独立测试库使用
`scripts/b0-provision.psql` 明确授予指定主体某个工作空间角色，并显式分配一个模型包：

```powershell
psql -X -v ON_ERROR_STOP=1 -h 127.0.0.1 -U postgres -d ontoforge_test_b0 `
  -v workspace_key=quality -v workspace_name="质量模型组" `
  -v issuer="https://your-idp.example/real-issuer" -v subject="exact-oidc-subject" `
  -v display_name="测试建模用户" -v role=EDITOR `
  -v package_id=manufacturing.quality -f scripts/b0-provision.psql
```

这条命令是测试库示例，不应复制到现有生产库。脚本单一事务、参数校验、原有归属不变：
已绑定其他 Workspace 的同名包会抛错并回滚；同工作空间重试是幂等的；没有删表、
迁移旧快照、禁用 ACL 或匿名后门。执行者必须拥有足够的数据库管理员权限。
将旧 packageId 输入上例即可显式归属，不修改既有 draftId、revision、snapshotId 和摘要。

当前授权颗粒度为 **Workspace Membership × Workspace Package Assignment**，
不是用户对单个包的独立授权。加入工作空间将按角色继承该空间全部已分配模型包；
因此每个 Workspace 的成员和包都必须由管理员核实，跨部门应使用不同工作空间。
命令行主体 subject、目标库、审批单、运行时间及输出回执应记录在运维审计中，
真实用户权限管理 UI/撤权工作流留待后续治理阶段。

## 12. 本轮门槛与功能映射

- F01/F19：真实 JWT 验签、/api/me、Workspace/Package 操作授权已有候选实现；前端 OIDC PKCE 登录候选接续。
- F02/F04：现有草稿、校验、快照、Diff API 接入包级鉴权；不将本地快照说成正式发行。
- F20：仅有操作前的当前权限判断与既有快照证据；正式审计轨迹尚未完成。
- F25：ChangeSet/ReviewDecision 未实现；B1 仍未开始。
- 生产 IdP 联调、操作者本地安装 UAT、权限迁移审批、人工安全签收和正式发布均为 **NOT RUN**。

## 13. B0 登录后模型包授权接续

已授权用户在模型包页面可查看来自 /api/me 的可信工作空间、角色及分配包数量。
创建新草稿前，从已授权 EDITOR/OWNER 模型包中选择；前端对未分配模型包
禁用创建并提示管理员授权，所有 API 仍以 PostgreSQL 中的实时权限校验为准。
VIEWER/REVIEWER/PUBLISHER 等不具备草稿编辑权的角色不展示创建按钮。
实际细粒度包级独立授权、成员管理 UI、企业 IdP 安全签收及生产发布仍未完成。
