# Yak B1/B2：本地账号、Session 与账号管理

设计依据：[OFIP-0017 / PR #94](https://github.com/gitfortian/ontoforge/pull/94)，[维护者 2026-10-19 明确接受](https://github.com/gitfortian/ontoforge/pull/94#issuecomment-6261109442)。B1/B2 是开发实现候选，尚未生产发布、安全签收或用户既有安装 UAT。

## 默认本机启动（无需编辑配置）

Windows 已安装并运行 PostgreSQL、Java 21 和 Maven 后，在仓库根运行 `.\scripts\run-local.ps1`。首次只输入已有 PostgreSQL 密码和新管理员密码（用户名默认 `admin`）；脚本自动发现 psql、检查连接、创建缺失的本机 OntoForge 数据库并生成随机签名密钥。第一次启动执行正常 Flyway 和受控管理员 bootstrap，成功后删除一次性初始化密码。已有账号不重建或重置。

第二次及以后同一命令直接启动，已有 jar 可加 `-SkipBuild`。IDEA 先运行脚本完成首次初始化，此后直接启动主类，工作目录选择仓库根即可；不需要在每个 IDEA 运行配置里复制秘密。

本机文件 `config/application.properties` 使用当前用户专属 Windows ACL，已加入 Git ignore。文件含数据库密码和稳定签名密钥，属于本机凭据配置，内容不是加密格式；不要移到受版本控制的文件中。名称/包归属等业务状态继续保存在 PostgreSQL。迁移版本、账号密码、授权和会话校验没有默认绕过。

`-Reconfigure` 可重新输入数据库密码，不会重置已有账号。`-NonInteractive` 禁止提示，首次无人值守部署须明确提供外部参数；自动准备只适用于 loopback + 默认本地 Session。显式 mvp-local/JWT 保留原路径，不因初始化脚本自动切换。服务器远程部署仍使用下面的明确环境变量配置。自有 `config/application.properties` 若不是脚本生成，脚本拒绝覆盖。

## 启动与登录

普通默认 `local-session`；已有 OIDC 安装升级前设置 `ONTOFORGE_SECURITY_AUTHENTICATION_MODE=oidc-jwt` 并保留原 OIDC/VITE_OIDC 配置。显式 `mvp-local` 仍是原有单人隔离开发方式。非法模式启动失败，没有匿名回退。

首次且仅账号表为空时，在已配置 DB_* 的启动终端显式设置：

```powershell
$env:ONTOFORGE_BOOTSTRAP_ADMIN_ENABLED = 'true'
$env:ONTOFORGE_BOOTSTRAP_ADMIN_USERNAME = 'your-admin-name'
$env:ONTOFORGE_BOOTSTRAP_ADMIN_PASSWORD = [System.Net.NetworkCredential]::new('', (Read-Host 'Initial account password' -AsSecureString)).Password
# 非 mvp-local 还需外部提供至少 32 字符的稳定私有 ONTOFORGE_OBJECTSET_CURSOR_SIGNING_SECRET。
.\scripts\run-local.ps1
```

Java 21、Node 22、PostgreSQL；数据库密码沿用外部 `DB_PASSWORD`。本机验收用独立 `ontoforge_test_*` 库，保留用户既有数据库。Flyway 只新增 V8，不改 V1–V7。空账号库缺少初始化配置时拒绝启动；没有固定初始密码或匿名注册。

用户名 3–64 位 ASCII 字母、数字、点、下划线或连字符，统一 trim 后小写。新密码至少 12 字符、最多 72 UTF-8 字节，不裁剪密码空格。初始化成功后关闭初始化开关并移除终端中的初始化密码；非空安装不会重置旧账号。

第二终端 `cd ontoforge-ui; npm ci; npm run dev`，打开 `http://127.0.0.1:5173/#/model/packages`。Session Cookie 为 HttpOnly、SameSite=Lax、Path=/api，闲置 30 分钟到期；重启需重新登录。密码/Token 不进 localStorage/sessionStorage。

## B1 显式授权

系统管理员不自动获得任何模型包。B2 已提供账号管理；C1 已提供 [空间/成员/包归属界面](./Yak-C1-工作空间与模型包归属.md)，正常使用优先通过 UI 明确授权。以下保留 B1 的数据库操作员示例，直接 SQL 变更须另行协调应用并发并刷新页面。本机测试库示例须替换真实用户名和目标 packageId；已有其他空间归属会使整个事务失败，不转移旧包：

```sql
DO $$
DECLARE owner_principal uuid; target_workspace uuid;
BEGIN
  SELECT principal_id INTO STRICT owner_principal
    FROM platform_local_account WHERE username='your-admin-name' AND enabled;
  INSERT INTO b0_workspace(workspace_id,workspace_key,display_name)
    VALUES(gen_random_uuid(),'local-workbench','本机工作台')
    ON CONFLICT(workspace_key) DO UPDATE SET workspace_key=EXCLUDED.workspace_key
    RETURNING workspace_id INTO target_workspace;
  INSERT INTO b0_workspace_membership(workspace_id,principal_id,role)
    VALUES(target_workspace,owner_principal,'OWNER') ON CONFLICT DO NOTHING;
  INSERT INTO b0_package_assignment(workspace_id,package_id)
    VALUES(target_workspace,'manufacturing.quality')
    ON CONFLICT(workspace_id,package_id) DO NOTHING;
END $$;
```

刷新后只显示获授权包；可为新的包 ID 登记后再从 UI 创建草稿。安装 UUID、草稿修订、快照摘要、旧 OIDC 主体/成员/包归属保留；不按同名合并账号，不自动接管历史未归属包。

## 会话和失败

登录先失效旧会话，并持久化 Spring Security Context；Session 只保存不含秘密的身份/版本，逐请求重新检查启用、凭据版本、成员与包归属。登录前获取 CSRF，登录后重新获取，所有变更方法含登录/退出均校验。错误密码、未知或停用账号统一 401，账号/IP 失败尝试受限。

退出调用真实服务端 logout，有未保存编辑先确认。会话失效时保留已挂载编辑并显示重新登录层；同一账号可继续，切换账号会关闭原账号编辑，不自动重放失败操作。ObjectSet cursor 按真实主体绑定；同空间其他用户也不能读取。撤权后失效 cursor 不因重新授权复活。Session 查询证据为 `SESSION_AUTHENTICATED`、对象读取为 `SESSION_AUTHORIZED`，原质量状态不变。

本地 HTTP 只供 loopback 开发。非 loopback Session 部署须配置 HTTPS 和 `ONTOFORGE_SESSION_COOKIE_SECURE=true`；启动检查不代表代理/网络/生产安全签收。Session 模式不以 Bearer 登录，OIDC 模式不以本地 Cookie 认证。分布式 Session、组织、消息、字典、文件、正式发行与 Action 未在 B1 实现。

## B2 账号管理与自行改密

系统管理员登录后在左侧「账号管理」查询、分页、新增本地账号、编辑显示名称、设置启用/停用和管理员属性，以及明确重置密码。新建只返回公开账号资料，不返回密码或摘要，也不赋予任何模型包。用户名固定，主体仍使用 accountId UUID；停用保留账号/成员/历史引用，不做物理删除。

编辑和重置携带读取时的版本，冲突返回 409，需刷新核对后再提交；不会自动覆盖并发修改或重放网络结果未知的请求。当前管理员不能停用/降级自己；变更在同一事务锁内重新复核操作员是否仍为可用管理员，因此并发互相降级不能移除所有管理员。停用最后一个可用本地空间 OWNER 前必须转交所有权。

启停、管理员变更和密码重置递增凭据版本，使该账号全部旧会话失效。被降级用户重新登录后不再显示账号入口，也不会保留先前管理列表。普通用户直接调用相同 API 仍被服务端拒绝。

所有本地用户可在右上角「修改密码」输入当前密码和两次新密码。成功后当前及其他会话全部失效，使用新密码重新登录；同一身份仍可继续未提交的本体编辑。客户端不能指定改密目标账号。密码对话框关闭或提交后清除密码字段，不保存到浏览器持久存储。

B2 复用固定 Yak 源码的用户列表/表单/密码重置流程（`apps/web/app/management/users`、`UserServiceImpl`），适配 PostgreSQL、明确管理员检查、CAS 和软停用；保留 B1 已分发的 Apache 许可证。没有引入完整 RBAC、组织或空间管理接口。

## 来源与验证

固定 Yak 提交 `85338827bdbb745eaa6a017031640674a298a4f9`：复用 [DefaultPasswordEncoder](https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-platform/src/main/java/io/yak/ops/security/extend/impl/DefaultPasswordEncoder.java) 的 BCrypt/非法摘要拒绝；复用 [HttpSessionAuthenticationManager](https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-platform/src/main/java/io/yak/ops/security/authentication/HttpSessionAuthenticationManager.java) 的会话轮换/超时，适配显式 request 和数据库版本复核。去除 Yak utils/SPI/MySQL 依赖。登录交互及 PasswordInput 复用 Yak，API 沿用本项目合同。Apache LICENSE/NOTICE 随 Boot/Platform 的 `META-INF/yak-*` 分发。

隔离 TEST_DB_* 下运行 `mvn -pl ontoforge-boot -am verify`，故障注入需显式开关；新增 `LocalSessionIntegrationTest` 有 8 条真实 HTTP/PG 检查，包括 Cookie/CSRF/撤权/两用户 cursor。真实默认 Session API + Vite + 私有 `SESSION_E2E_AUTH_FILE` 下运行 `python -m unittest discover -s ontoforge-ui/e2e -p session_stories.py -v`；现有 JWT 36 条故事仍独立执行。确切 base/head、实际结果与未跑范围在实现 PR 记录；命令清单本身不表示测试通过。
