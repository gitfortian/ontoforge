# OFIP-0017：复用 Yak 本地账号、Session 与工作空间

**Status:** Accepted

**Discussion:** [PR #94](https://github.com/gitfortian/ontoforge/pull/94)；提案与生产代码分别提交。

**Decision:** [VOTE] Accepted — 2026-10-19，维护者明确接受本合同并授权 B1 → B2 → C1 实施；[永久决定记录](https://github.com/gitfortian/ontoforge/pull/94#issuecomment-6261109442)。

**Implementation:** In Progress — [B1 Session / PR #96](https://github.com/gitfortian/ontoforge/pull/96)、[B2 账号管理 / PR #97](https://github.com/gitfortian/ontoforge/pull/97)、[C1 空间与成员 / PR #98](https://github.com/gitfortian/ontoforge/pull/98) 已分别提交开发实现与独立验证证据；尚待审阅、合并和用户既有安装验收。本提案 PR 不包含生产代码，不据 CI 推定生产签收或发布。

**Release:** Not Released

**Related:** [OFIP-0001 治理](./OFIP-0001-需求提案文档治理与CI证据门禁.md) · [统一术语](../砺元本体产品统一术语词汇表.md) · [工程模块归属](../implementation/S0-工程模块规划与命名规范.md) · [现有 B0 实施边界](../implementation/S0-MVP-0.5-B0-可信身份与模型包权限实施说明-20261016.md) · [MVP 认证复用方向](../implementation/MVP-核心优先与yak-ops认证复用决策-20261017.md)

## Motivation

普通部署的管理员需要在本机创建账号，用户用账号密码登录后，在获授权的工作空间内完成现有本体建模、绑定和查询，再真实退出会话。无需先部署企业 IdP；已有 OIDC 安装仍可显式选择兼容模式。

核验基线为 OntoForge main `c157d228eaa38b12bd99012c713817fbb2ab4378` 与 Yak main [`85338827bdbb745eaa6a017031640674a298a4f9`](https://github.com/weifuwan/yak-ops/tree/85338827bdbb745eaa6a017031640674a298a4f9)。当前 OntoForge 有 V5 的可信主体、工作空间、成员与唯一包归属，及 JWT 包授权；没有本地账号、Session 登录和管理页面。显式 `mvp-local` 是单人开发路径，不能充当多用户权限。

Yak 已有 BCrypt、HttpSession、登录限制、用户管理、空间与成员界面，可按固定版本复用。但源码中的用户 CRUD/重置密码没有完整系统管理员授权，空间 OWNER/ADMIN/MEMBER 也不是 OntoForge 已存的六种角色；不能整套搬入后宣称安全隔离完成。

黄金故事：管理员初始化 → 建立账号与空间并明确授予模型包 → 普通用户登录 → 仅看到获授权资源 → 编辑并保存真实草稿、执行现有只读查询 → 退出 → 原会话再请求得到 401。另一用户即使拿到该用户的 ObjectSet cursor 也不能读取结果。

本提案不扩展 Native/AST、正式发行、Action、组织租户、完整 IAM、SSO 自动账号合并、消息中心、字典、文件服务或凭据保险库。后续连接管理涉及来源注册和秘密处理时另立合同；Yak 的密码遮罩不等于凭据加密。

## Public Interfaces

### 认证模式与配置

新增 `ontoforge.security.authentication-mode`（环境变量 `ONTOFORGE_SECURITY_AUTHENTICATION_MODE`）：`local-session` 为普通部署默认；`oidc-jwt` 显式启用当前 JWT/OIDC 兼容链。显式 `mvp-local` 仍只允许既有 loopback/本机数据库约束，不因模式默认改变而自动开启。

三条链互斥，非法配置启动失败。Session 模式不能通过传 Bearer 或自报 issuer/subject/roles 获得身份；OIDC 模式不从本地 Cookie 接受身份，不提供本地密码登录。OIDC 原有 issuer、audience、签名、时效校验保留。没有匿名回退。

Session 使用服务端 HttpSession，默认闲置 30 分钟，Cookie HttpOnly、SameSite=Lax、Path=/api；生产 HTTPS 必须 Secure。仅 loopback 开发允许 HTTP；跨域部署不在本切片内，默认不开放 credentialed CORS。首次接入先支持单实例，重启需重新登录，不宣称分布式会话。

### HTTP 与前端可见行为

沿用项目现有成功 JSON 和 HTTP 错误合同，不引入 Yak 的通用 Result 包装破坏现有 API。所有敏感响应 `Cache-Control: no-store`，密码与会话标识不进入日志/响应/浏览器持久存储。

| 接口 | 请求/响应与权限 |
| --- | --- |
| `GET /api/auth/context` | 公开返回 `mode`、`authenticated`；Session 模式返回 CSRF `token/headerName`，不返回账号目录或任何密码。Token 对当前会话有效；登录/退出后重新获取 |
| `POST /api/auth/login` | 仅 Session 模式；`{username,password}` + CSRF；成功建立新会话并返回现有 Identity 结构；统一 `401 INVALID_CREDENTIALS`，不区分账号不存在/停用/密码错误；受限时 `429 LOGIN_RATE_LIMITED` |
| `POST /api/auth/logout` | Session 模式 + CSRF，服务端失效会话并清除 Cookie，幂等 204；不是只清空前端状态 |
| `GET /api/me` | 继续返回 `principalId/issuer/subject/displayName/workspaces`，无需有成员资格即可返回空列表；所有资源仍单独授权。Session/JWT 均由服务端可信身份解析 |
| `POST /api/auth/password` | 当前本地账号；`{currentPassword,newPassword}` + CSRF；成功使全部旧会话失效，要求重新登录；不得由普通用户指定他人 ID |
| `GET/POST /api/platform/accounts` | 系统管理员分页查询/建立本地账号；创建输入 `username/displayName/password/systemAdmin`；输出仅 `accountId/principalId/username/displayName/enabled/systemAdmin/version` |
| `PUT /api/platform/accounts/{id}` | 系统管理员更新名称、启停/管理员属性，携带 `expectedVersion`；保护自己及最后一个可用管理员，冲突 409；不接受修改身份主键/密码摘要 |
| `POST /api/platform/accounts/{id}/password` | 系统管理员显式重置新密码；重置后失效目标所有旧会话；没有默认密码或返回旧密码的接口 |
| `GET/POST /api/platform/workspaces` | 已认证用户列出自己的空间、创建空间；创建者事务内成为 OWNER。选择空间只是前端筛选，不构成授权上下文 |
| `PUT /api/platform/workspaces/{id}` | 该空间 OWNER 改显示名称，使用 `expectedVersion`；空间 key/UUID 不变；本批无删除空间接口 |
| `GET/PUT/DELETE /api/platform/workspaces/{id}/members/{principalId}` | OWNER 管理成员角色；列表使用同资源集合路由；PUT 接受角色集合与 `expectedVersion`，DELETE 使用版本前置条件；空角色/未知主体拒绝；不得移除最后一个可用 OWNER |
| `POST /api/platform/workspaces/{id}/packages` | 系统管理员显式分配 packageId，已有同空间归属幂等，其他空间归属 409；OWNER 可为自己空间登记尚不存在、未分配的新包；历史未归属包仅管理员可显式接管 |

账号管理只在 Session 模式开放；空间操作在两种可信模式下均执行同一成员授权。系统管理员可查看/管理空间成员和显式包分配以恢复失管空间，但**不自动取得模型包业务读写权**，必须明确赋予既有成员角色。操作由服务端识别管理员，客户端传 `systemAdmin/roles` 不能自行提权。

首次启动不生成演示管理员、不开放匿名注册。仅在本地账号表为空且显式配置 `ONTOFORGE_BOOTSTRAP_ADMIN_ENABLED=true`、外部 `ONTOFORGE_BOOTSTRAP_ADMIN_USERNAME` / `ONTOFORGE_BOOTSTRAP_ADMIN_PASSWORD` 时，持锁事务创建首个管理员及其可信主体。空库未配置时启动失败并给出无秘密的操作提示；非空库不重新设置管理员。初始化后关闭开关并撤除环境中的初始化密码。账号初始无包权限；B 切片用可审阅的显式授权 SQL，C 切片用空间 UI，均不得自动接管历史模型包。

密码按 Yak BCrypt 路径复用，强度至少 10；新密码长度 12 字符至 72 UTF-8 字节，不裁剪密码空格，超过 BCrypt 有效字节长度明确拒绝。用户名规范为 trim 后小写 ASCII，3–64 字符 `[a-z0-9._-]`；创建/登录一致，数据库唯一约束兜底。失败登录按规范用户名及来源 IP 双重限速，连续 5 次失败在 15 分钟窗口内限制 15 分钟，未知账号也执行等价摘要核对；限流容器有容量与过期清理，不接收任意 X-Forwarded-For 为可信 IP。

### 持久化与权限

V1–V7 保持原字节。B 切片新增前向迁移 V8（实施时如版本已占用，使用下一个空闲版本）：`platform_local_account` 保存 `account_id` UUID、`principal_id` 唯一外键、规范用户名唯一键、`password_hash`、`enabled`、`system_admin`、`credential_version`、`version`、创建/更新时间。启停/改密/重置递增 credential_version；Session 仅持稳定 ID 与登录版本，每次请求重新核对启用状态和版本。首个管理员创建及最后管理员保护使用同一事务锁；不物理删除账号或主体。

本地可信主体为 `(issuer="urn:ontoforge:local", subject=accountId UUID)`，用户名不是身份主键。现有 OIDC `(issuer,subject)`、principalId、workspaceId 和包归属不变；不按同名/邮箱合并账号。预留的本地 issuer 不接受外部 JWT 冒充。

C 切片新增空间/成员并发版本所需前向迁移，保留现有 V5 表和键；继续使用已有 `OWNER/EDITOR/REVIEWER/PUBLISHER/ACTIVATOR/VIEWER`。READ_DRAFT/READ_SNAPSHOT 仍允许 EDITOR/REVIEWER/PUBLISHER/OWNER；WRITE_DRAFT/VALIDATE_DRAFT/CREATE_SNAPSHOT 仍允许 EDITOR/OWNER。VIEWER/ACTIVATOR 不因此获得历史上没有的操作，正式审核/发布/激活仍未实现。成员可有多个角色，角色集合变更事务化；权限每次请求查真实成员及归属，撤权不等待 Session 过期。

`/api/me`、草稿列表、Draft/Snapshot/Runtime/Binding/对象读取/质量追溯/ObjectSet 均使用同一可信主体解析。只允许显式 mvp-local 返回 `LOCAL_ISOLATED`；Session 的查询证据标为 `SESSION_AUTHENTICATED`，游标按真实稳定主体绑定，不能沿用所有非 JWT 共享的本机 actorKey。`accessMode` 新值同步现有 TS/JSON 消费者，不改 EXACT/COMPLETE/UNKNOWN 等质量语义。

## Proposed Changes

直接复用 Yak 的 BCrypt、Session 生命周期、登录交互、用户和空间管理中可用的实现，并保留 Apache-2.0、固定提交及来源清单。依赖 MySQL/MyBatis 的 DAO 改为本项目 PostgreSQL/JdbcTemplate；Spring Security Context 由可信 Session 适配器构造。借鉴实现不等于绕过当前 Spring Security 或创建第二套权限旁路。

`ontoforge-boot` 负责模式互斥、HTTP/CSRF/Cookie 和 Security 适配；`platform` 负责账号、工作空间及授权用例/事务；`dao` 负责 SQL/迁移；`business` 继续拥有建模和查询，只消费可信主体，不依赖 Boot。前端在单一 `app/features/shared` 工程中新增真实登录和管理页面；公共组件消费第一批 Yak UI，不创建空模块或占位设置页。

Session 全部变更方法（含登录、退出、密码）要求 CSRF，登录成功更换会话 ID 并更新 CSRF，拒绝固定会话攻击。前端按 context 选择 `credentials: same-origin` 与 CSRF Header；OIDC 保持内存 Bearer/PKCE，避免双身份。401 显示登录入口并保留可恢复的未保存编辑，不自动提交；403 明确无权。退出若有未保存编辑先使用既有离开确认，确认后再执行服务端 logout。

启停账号时检查其 OWNER 成员关系；不能停用导致任何空间失去最后可用 OWNER 的账号。系统管理员先转交空间所有权，再停用。管理员身份变更、账号状态、角色/包归属变化都在服务端重新检查；禁止只有隐藏按钮而无后端检查。

按依赖顺序，实施 PR 每个交付一条可操作链路：

| 切片 | 用户可验收结果 |
| --- | --- |
| B1 | 初始化管理员 → 真实 Session 登录 → 读取身份/显式授权的旧模型包 → 编辑保存/查询 → 服务端退出；同时保留 JWT 签名负例和显式 mvp-local |
| B2 | 管理员建账号、改资料、启停、重置；用户改自己的密码；普通用户直接调用管理 API 仍被拒绝 |
| C1 | 建空间、成员及角色、明确登记/分配包 → 在所选空间建模；另一用户/空间无权访问，撤权后旧游标不可再读 |
| C2 | 仅在实际有消费者时复用收藏/最近访问/偏好；浏览器主题已有本地存储，不为偏好中心预建通用数据库 |

## Compatibility, Deprecation, and Migration Plan

普通默认从 OIDC 变为 Session 属显式行为变更，README/启动脚本/测试夹具/CI 必须同步。已有 IdP 部署升级前设置 `ONTOFORGE_SECURITY_AUTHENTICATION_MODE=oidc-jwt`，保留原 OIDC 配置即可延续原路径；未配置空账号库不能悄悄退到匿名。用户既有 installationId、Native Draft、Snapshot 摘要/修订、V6/V7 Binding、来源元数据不改变。

已有 SQL 分配的 JWT 主体和包继续有效；本地账号与其独立，跨模式迁移必须逐项明确转授，禁止同名合并或自动授予。空间角色管理不是组织租户隔离。原 `/api/me` 结构向后兼容；新增 context/账号/成员接口不更名现有业务路由。

新增数据库版本回滚不通过删除表/降级迁移/repair。部署回退仅在已测试兼容的旧构建或显式 JWT 模式进行；保留新增表但禁用本地登录，先保存迁移与权限证据。重启使 Session 和内存查询结果失效，旧 cursor 返回既有过期/失效语义，不制造持久结果保证。

现有 B0 集成/浏览器/脚本/工作流必须明确选择 `oidc-jwt`，继续使用真实签名 Token 负例；新增 Session 用例使用另一独立 `ontoforge_test_*` 数据库。不把 TEST_DB_* 指向开发库，不输出 Cookie、CSRF、密码或来源秘密。

## Test Plan

下表是后续实现验收要求，**本提案均 NOT RUN**；提案 PR 只执行文档治理与链接检查，并记录实际 base/head SHA。

| 类型 | 成功故事 | 必需负例与实际证据 |
| --- | --- | --- |
| 模式 | 默认 Session、显式 JWT、显式 mvp-local 分别启动 | 非法/冲突配置拒绝；缺初始化密码拒绝；Session 不接 Bearer 身份、JWT 不接本地 Cookie；mvp-local loopback 约束保留 |
| PG/Flyway | 从真实 V1–V7 夹具升级、重启后账号/模型/归属仍在 | V1–V7 checksum、UUID/摘要不变；并发用户名/初始化只有一个结果；旧 expectedVersion 409；最后管理员/OWNER 并发保护 |
| 登录与 Session | 正确密码登录、换会话、刷新可读身份、闲置到期、服务端退出 | 匿名 401；错误/未知/停用相同错误；限流及容量；CSRF 缺失/错误/跨会话拒绝；旧 Session ID/密码版本失效；Cookie flags 与生产 Secure |
| 管理与撤权 | 管理员创建/重置/停用，用户只改自己密码 | 普通用户直接 HTTP 管理与伪造 roles/systemAdmin 均 403；重置/启停使旧会话立即失效；任何变更不泄漏摘要 |
| 空间/包 | 所选空间内真实建模、角色授予和撤销 | 跨空间 ID、未分配包、重复归属、最后 OWNER、伪造客户端空间授权均拒绝；管理员没有隐式模型权限 |
| 查询 | Session 用户执行 M5/M6/M7/质量追溯 | 两个 Session 用户不能共用 cursor；JWT/Session 游标不可混用；撤权再授权不能复活旧 cursor；来源撤权/版本漂移/过期负例保留 |
| 浏览器 | 登录→建模保存→刷新→退出→重新登录；空间/成员管理 | 错误恢复、无权、过期与未保存编辑确认；密码不进 local/sessionStorage；无假按钮；第一批主题与原有 31 条业务故事继续回归 |

实现时运行隔离 TEST_DB_* 下 `mvn -pl ontoforge-boot -am verify`（故障注入另显式启用）、前端 `npm run build`/`npm run audit:dependencies`、真实 PostgreSQL + Chromium，以及 `python -m unittest discover -s doc/tests -p 'test_*.py' -v` 与模块边界检查。每个 PR 给出确切测试文件/命令、commit SHA 和 PASS/FAIL/NOT SELECTED/NOT RUN；自动化不替代用户既有安装人工验收或生产安全签收。

## Rejected Alternatives

- 继续要求所有普通用户先接 IdP：不符合维护者已确认的默认方式和 Yak 复用方向；保留显式兼容路径即可。
- 直接复制 Yak 全部用户/权限代码并认为安全完成：原用户 CRUD 缺系统管理员限制、空间角色与 V5 不同，且使用 MySQL/MyBatis；必须适配现有消费者和 PG，并补负例。
- 同时隐式接受 Cookie 与 Bearer，或把非 JWT 都视为本机身份：会产生身份混淆及共用 cursor，拒绝这类回退。
- 新建第二套 workspace/permission 表或修改 V5：已有明确键和包唯一归属；这会破坏历史与授权权威，选择前向扩展。
- 登录即授予所有历史模型包、按用户名合并 OIDC 主体：违背明确授予及稳定身份，不采用。
- 一次生成消息、字典、文件、完整 RBAC/组织/连接管理的空模块：Yak 并未完整提供全部能力，也没有实际调用者；后续围绕真实故事逐项交付。

---

**Decision Gate:** 已取得上方明确接受决定；按 B1 → B2 → C1 顺序实施。合并或绿色 CI 不自动形成实现/发布签收；重大改变本合同需重新审议。
