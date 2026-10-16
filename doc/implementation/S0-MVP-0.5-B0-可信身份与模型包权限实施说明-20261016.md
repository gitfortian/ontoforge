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
