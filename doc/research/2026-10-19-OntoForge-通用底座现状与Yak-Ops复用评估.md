# OntoForge 通用底座现状与 Yak Ops 复用评估

调研日期：2026-10-19（Asia/Shanghai）。性质：源码盘点与复用建议，不是安全验收、已批准产品需求或运行测试报告。

## 1. Yak Ops 研究基线与判定方法

- 远端 `https://github.com/weifuwan/yak-ops.git` 的 `main`，本次通过 `git ls-remote` 核对为 `85338827bdbb745eaa6a017031640674a298a4f9`，与 2026-10-17 研究基线相同。以下 Yak 链接全部固定到此提交。
- 只读检查 Controller、Service、Repository/Mapper、Flyway、前端路由/调用及测试源码；没有启动 Yak、安装依赖、运行其测试、检查线上部署或核验本次远端 CI。
- “已实现”仅表示发现实际代码与调用/持久化；“部分实现”表示具体子能力成立但不能覆盖完整功能；“未发现”表示在本次完整入口及持久化清单中没有对应实现，不能据此断言其他私有项目也没有。
- 判定通用能力时，以独立的账户、空间、平台体验、运维基础设施为对象。数据同步、CDC、任务执行事实不能直接当成本体领域的能力或 Action 授权。

## 2. Yak Ops 全部可见通用能力矩阵

| 能力 | 源码判定 | 可复用内容与边界 | 固定提交证据 |
| --- | --- | --- | --- |
| 本地账号密码登录、当前用户、退出 | 已实现 | 登录 UI、登录态加载、失败反馈、登录后回跳；服务端使用 Servlet HttpSession/JSESSIONID，不是 JWT/OIDC | [LoginController][yak-login-controller]、[LoginServiceImpl][yak-login-service]、[HttpSessionAuthenticationManager][yak-session]、[AppRouter][yak-router] |
| 密码存储、会话失效与首用户初始化 | 已实现基础机制 | BCrypt（默认 strength 10）、重新登录销毁旧会话、30 分钟默认闲置过期、改密使本进程已索引会话失效、显式开启的首用户初始化；不能据此声称具备集群会话/企业 IAM | [密码编码器][yak-password]、[会话管理][yak-session]、[安全配置][yak-security-properties]、[首用户初始化][yak-bootstrap] |
| 登录失败保护 | 已实现进程内机制 | 用户名/IP 两个维度、默认 5 次失败锁 15 分钟、默认隐藏账号不存在差异；状态存于 ConcurrentHashMap，未发现持久/分布式登录限流 | [LoginAttemptGuard][yak-login-guard]、[默认策略][yak-security-properties] |
| 用户列表、搜索、新建、编辑、删除、重置密码 | 已实现管理页面/API/数据库 | 表单、字段检查、分页、手机号展示遮罩、禁止删除当前用户、密码哈希等可参考；**当前未发现这些管理写操作的管理员授权检查**，因此不宜直接引入为安全完成的账户管理 | [UserController][yak-user-controller]、[UserServiceImpl][yak-user-service]、[UserEntity][yak-user-entity]、[用户管理 UI][yak-user-ui] |
| 全局管理员、动态角色/权限/RBAC、菜单权限 | 未发现完整实现 | 用户没有系统 role 字段，认证拦截器明确不负责角色/权限/项目授权；导航是静态常量。管理菜单存在不能作为管理员边界证据 | [UserEntity][yak-user-entity]、[认证拦截器][yak-auth-interceptor]、[静态导航][yak-navigation]、[全部 Controller][yak-controllers] |
| 工作空间创建、发现、选择、成员管理 | 已实现最小闭环 | 空间/成员表、创建者 OWNER、查询我的空间、成员增删/角色修改、OWNER 操作约束与最后一个 OWNER 保留；前端选择/创建/成员表单可复用。未发现空间改名/删除、组织树、空间配额、邀请邮件闭环 | [WorkspaceController][yak-workspace-controller]、[WorkspaceServiceImpl][yak-workspace-service]、[Workspace UI][yak-workspace-ui]、[基线 DDL][yak-v1] |
| 工作空间角色与资源隔离 | 部分实现 | 角色只有 **OWNER / ADMIN / MEMBER**，不是 EDITOR/VIEWER 或通用 RBAC。请求 X-Workspace-Id 先核对成员再绑定上下文，业务仓储按空间查询；app_name 自动过滤只针对用户表，不是全业务多租户 | [WorkspaceRole][yak-workspace-role]、[WorkspaceContextInterceptor][yak-workspace-interceptor]、[MybatisPlusConfiguration][yak-mybatis]、[空间服务][yak-workspace-service] |
| 组织、部门、租户、用户组 | 未发现完整实现 | 用户 deptId 仅资料字段，DDL 明确没有部门外键；没有可见组织/租户/用户组管理 API、表或管理路由。app_name 与 Workspace 不能直接等价于企业租户 | [UserEntity][yak-user-entity]、[基线 DDL][yak-v1]、[路由][yak-router] |
| 单点登录、OIDC/OAuth/SAML、MFA、找回密码 | 未发现实现 | 当前登录是本地密码 + Cookie 会话；没有现成企业 IdP 联邦供直接搬用。已有服务接口可作为适配点，不能据此宣称多种身份后端已经实现 | [LoginServiceImpl][yak-login-service]、[SecurityConfiguration][yak-security-assembly]、[平台源码目录][yak-platform] |
| 用户偏好、收藏、最近/常用信号 | 已实现限定场景 | 当前仅 PRODUCT_MENU 收藏与 DATASOURCE_CREATE_TYPE 使用次数/最近时间，按当前认证用户持久化；可扩展到 OntoForge 真实产品菜单，不能称为任意系统配置中心 | [UserPreferenceController][yak-preference-controller]、[UserPreferenceServiceImpl][yak-preference-service]、[场景枚举][yak-preference-scene]、[ProductLauncher][yak-launcher] |
| 系统设置、字典、参数中心、功能开关 | 未发现通用管理中心 | application.yml 有环境配置，用户偏好有固定场景；未发现全局系统设置/字典 CRUD API、表、页面。管理中心目前仅用户和工作空间 | [application.yml][yak-yaml]、[管理导航][yak-navigation]、[Flyway 目录][yak-migrations] |
| 站内消息、消息收件箱、已读未读、公告 | 未发现实现 | 当前 TopBar 没有消息中心入口；notifyOnce 是 Toast 的约 1.2 秒进程内去重，既不持久化，也没有收件人/已读 API。可以复用提示组件，消息中心需要另做 | [TopBar][yak-topbar]、[notifyOnce][yak-toast-dedupe]、[Controller 清单][yak-controllers]、[DDL 清单][yak-migrations] |
| 邮件/短信/Webhook、订阅、消息投递任务 | 未发现通用实现 | 调度触发历史是任务领域记录，不是通知投递/送达/重试基础设施；不能把用户 email 字段视为邮件服务 | [平台目录][yak-platform]、[Controller 清单][yak-controllers]、[ScheduleController][yak-schedule-controller] |
| 应用壳、产品切换、侧栏、受保护路由、工作空间切换 | 已实现 | AppLayout/TopBar/ProductLauncher、登录态 Loading、合法站内回跳、空间切换；需要将数据集成/同步菜单换成本体产品真实菜单，不复制无消费者的多产品入口 | [AppRouter][yak-router]、[导航][yak-navigation]、[TopBar][yak-topbar]、[WorkspaceProvider][yak-workspace-context] |
| UI 组件、表格、分页、表单、弹层、提示、空状态 | 已实现源码组件库 | @yak-ops/yak-ui 内有真实 Button/Input/Field/Table/Pagination/Dialog/Drawer/Select/Toast 等消费者；组件源码可择需复用，不要求 OntoForge 先变成 npm 多应用仓库 | [组件导出][yak-ui-index]、[Table][yak-ui-table]、[组件依赖][yak-ui-package] |
| 主题、设计 Token、国际化 | 部分实现 | light/dark/system Provider 与主题 CSS 存在，偏好存 localStorage；不能据此声称全部屏幕深色验收或后台同步。数据源模块存在局部 i18n，未发现全站语言切换/完整翻译 | [ThemeProvider][yak-theme]、[主题目录][yak-themes]、[局部 i18n][yak-i18n] |
| HTTP 客户端、错误展示、会话过期、请求上下文 | 已实现 | fetch 包装、Cookie credentials、空间头、BizError、401 重定向、HTML 错误页过滤与长度限制、Toast 去重。需适配 OntoForge 现有 API/错误/JWT 契约，不能全量覆盖响应 | [request.ts][yak-request]、[response.ts][yak-response] |
| API 响应、统一异常、参数校验、OpenAPI、分页 | 已实现基础设施 | Result/ErrorCode、HTTP 状态映射、Bean Validation、Swagger、分页通用类型；GlobalExceptionHandler 同时依赖 datasource/task/schedule 错误码，复制前需拆出通用部分 | [异常边界][yak-errors]、[Result][yak-result]、[OpenAPI 装配][yak-openapi]、[公共分页目录][yak-page] |
| 审计字段、业务日志、敏感信息遮罩 | 部分实现 | BaseEntity 创建/更新时间与操作人、SLF4J/Logback 滚动、凭证遮罩、Task 独立日志；没有完整安全审计事件查询、不可篡改审计、留存策略控制台 | [BaseEntity][yak-base-entity]、[Logback][yak-logback]、[SensitiveDataConverter][yak-sensitive]、[日志测试][yak-sensitive-test] |
| 通用文件中心、上传附件、对象存储、导入导出 | 未发现完整实现 | request 支持 blob/FormData 只说明传输包装可用；Task 日志文件/Checkpoint 是领域资源，不是上传附件服务。未发现 File Controller/附件表/对象存储管理 | [request.ts][yak-request]、[Controller 清单][yak-controllers]、[迁移清单][yak-migrations] |
| 数据源连接信息、连通性测试、Schema 目录、驱动 SPI | 已实现但与数据集成有关 | 表单、连接测试、JDBC 元数据、插件类型/能力声明、敏感字段遮罩和更新保留逻辑有复用价值；需要接入 OntoForge 的受控来源、凭证引用、版本化 Binding、查询预算，不能改写本体语义契约 | [数据源 Controller][yak-datasource-controller]、[目录 Controller][yak-catalog-controller]、[凭证处理][yak-credentials]、[数据源插件][yak-datasource-plugins] |
| 通用凭证库、KMS/Vault、密钥轮换 | 未发现实现 | DDL 的 connection_params/original_json 仍包含连接敏感数据，maskForDisplay 是返回时遮罩；不能将脱敏等同于静态加密或密钥托管 | [基线 DDL][yak-v1]、[DataSourceCredentialUtils][yak-credentials] |
| SPI、插件发现、配置规则 | 已实现基础设施 | SpiRegistry 封装 ServiceLoader、ID 去重与不可变索引；Configuration 有真实选项/规则校验与测试。选取真实扩展点时复用；不是插件市场、动态热装载或沙箱 | [SpiRegistry][yak-spi]、[SPI 测试][yak-spi-test]、[配置模块][yak-configuration]、[配置测试][yak-configuration-tests] |
| Cron、任务调度、执行、进度、取消、日志 | 已实现真实领域系统 | Quartz 插件、调度定义/触发历史、版本/并发控制、Task dispatch/观察/取消、执行日志等可作为以后后台作业能力的来源；现在主要消费者为数据同步，并非可直接套用的本体 Action/Function/Decision 运行时 | [ScheduleController][yak-schedule-controller]、[ScheduleServiceImpl][yak-schedule-service]、[调度目标枚举][yak-schedule-target]、[Task execution][yak-task-execution] |
| 监控、健康、指标、告警中心 | 部分领域运维 | operations dashboard、实例/吞吐/进度等属于数据同步运维；未发现独立通用主机/JVM 监控、Prometheus/Micrometer 接入或告警策略中心，不能把 dashboard 直接称为平台监控体系 | [Operations 页面][yak-operations-ui]、[DataSyncOperationsController][yak-operations-controller]、[Boot POM][yak-boot-pom] |
| 构建分发、部署、质量门禁、测试资源管理 | 已有工程实现 | Maven reactor/BOM、ArchUnit、日志规则、前端架构/契约检查、测试套件选择、JUnit 数据库资源、dist tar、nginx、Compose smoke、release gate 值得复用流程与边界；版本名/模块名/数据库及证据机制需适配 | [工作流目录][yak-workflows]、[dist 装配][yak-dist]、[测试工具][yak-test-utils]、[前端工程检查][yak-ui-root-package] |

## 3. 必须先处理的适配差异

### 3.1 账户管理不能直接标记为“权限系统已具备”

`YakAuthenticationInterceptor` 只做登录态校验。`UserController` 的 add/edit/resetPassword/delete 将当前用户名传给 Service 作为操作人；对应 Service 里没有系统管理员角色检查，UserEntity 也没有系统角色字段。尤其 `/{id}/password` 虽名为“管理员重置”，名称不是授权实现证据。本次没有启动 Yak 发起越权攻击，因此这里的结论是明确的**静态授权边界缺口**，不是渗透测试结果。[Controller][yak-user-controller]、[Service][yak-user-service]、[拦截器][yak-auth-interceptor]

Workspace 的 OWNER/ADMIN 约束真实存在于成员管理，但 MEMBER 不等价于只读 Viewer；全局账户管理与空间成员管理是两条不同边界。移植时应保留 OntoForge 已有服务端身份/模型包授权，将管理权限明确落到服务端，而不是只隐藏管理菜单。[空间角色][yak-workspace-role]、[空间服务][yak-workspace-service]

### 3.2 本地 Session 与已有 OIDC/JWT 需要统一身份策略

Yak 以本地用户 ID/用户名及 HttpSession 为依据，进程内保存用户会话索引和失败次数。它不提供现成 OIDC/JWT。复用登录页面、账户体验、密码编码等并不要求替换 OntoForge 现有身份边界。如果将本地身份作为另一种部署模式，应显式设计身份映射、会话/CSRF、Cookie 策略和服务端授权，并避免让客户端选择自己的授权上下文。没有独立 CSRF 过滤器或 Cookie 安全策略的源码证据，不能作安全完备断言。[会话实现][yak-session]、[安全装配][yak-security-assembly]、[默认配置][yak-yaml]

### 3.3 SQL、依赖与 API 契约不能直接覆盖

| 项目面 | Yak 固定提交中的实际技术 | 复用影响 |
| --- | --- | --- |
| Java/后端 | Java 21、Spring Boot 3.3.13 | Java 层相近；Boot 3 与 OntoForge 当前 Boot 4 的自动装配、starter、Jackson/测试兼容须独立验证，不引入 Yak 父 POM 覆盖现有版本 |
| DAO | MyBatis-Plus 3.5.16、spring-jdbc、MySQL Connector/J、flyway-mysql | OntoForge 当前 PostgreSQL/JdbcTemplate 仓储应保留；迁移 SQL 使用 MySQL ENGINE/UNSIGNED/TINYINT/DATETIME/生成列语法，不能原样执行 |
| 数据身份 | VARCHAR(64) 雪花字符串 ID；app_name 只过滤用户表 | 需要映射 OntoForge 的现有身份与 UUID，不能全局替换既有主键、issuer/subject 或历史迁移 |
| 前端 | React ^18.3.1、React Router ^7.18.4、Vite ^6、TS ^5.9、Tailwind ^4.1；Node >=22.13 | 这些是 package.json 声明范围，不是本次验证的安装版本；先择需移植壳与组件，再适配现有路由/样式/API |
| UI 组件 | @base-ui/react ^1.8.0、class-variance-authority ^0.7.1 | 组件有真实实现，可按消费者逐步采用，不必同时导入原项目所有主题/字体/品牌素材 |
| API | Result(code,data,msg/message)、Cookie、X-Workspace-Id | 适配 OntoForge 现有成功/错误 JSON 与 Bearer，而非批量修改已公开接口 |

版本与依赖来源：[根 POM][yak-root-pom]、[BOM][yak-bom]、[DAO POM][yak-dao-pom]、[前端根 package.json][yak-ui-root-package]、[Web package.json][yak-web-package]、[UI package.json][yak-ui-package]；SQL/身份来源：[V1][yak-v1]、[IdUtils][yak-id]、[MyBatis 装配][yak-mybatis]。

### 3.4 许可证与复用来源

Yak 仓库根 `LICENSE` 声明 Apache License 2.0。本次只核验到仓库许可文件及源码来源，没有做全部第三方依赖、字体、图片、视频、内置驱动包的许可审计。实际复制代码应记录来源提交，按项目流程保留适用声明并审查被选资产；不要因为两者由同一用户维护就省略来源记录。[LICENSE][yak-license]、[内置驱动目录][yak-drivers]

## 4. 本次证据与未验证项

- **已做：**远端 main SHA 核对；源码与实际调用/数据库链路检查；平台 Controller/表/路由入口盘点；已有测试文件和工作流范围检查。
- **未运行：**Yak Maven、数据库迁移、HTTP/安全测试、前端构建、Playwright、Compose/release。没有本次“通过”数量。
- **已有测试源码：**SPI、配置校验、凭证处理、日志遮罩、Backend Architecture、Task/调度等存在单元/集成测试。平台模块未发现自己的 `src/test`；不能用 Task 测试替代账户管理授权验收。[SPI 测试][yak-spi-test]、[日志测试][yak-sensitive-test]、[Backend E2E][yak-e2e]
- **UI 测试边界：**Yak 的测试说明明确区分 mock 浏览器交互与 Compose 实际登录，只有 full/release 才验证后者；存在 `login.py` 或绿色 mock 不能推出平台后端安全已验收。[Playwright 说明][yak-ui-tests]
- **建议新增的移植验收：**未登录/过期会话，普通用户不能管理账户，空间跨界拒绝，模型包 Viewer 不可写，OIDC 与本地用户映射，消息/系统设置真实持久化与权限，已有 Native/Binding/ObjectSet API 回归。需要按实际选定功能追加，不能只做 UI 展示。

## 5. OntoForge 当前通用能力：已有底层机制，管理产品尚不完整

本次核对的最新远端 main 为 `c157d228eaa38b12bd99012c713817fbb2ab4378`，已包含工程模块化 PR #83。实际读取的本地提交为 `e5b5c2f4a474cedbb118c94664fe19ee06116dbc`；逐目录比对确认，二者的生产源码、UI、POM、脚本与 README 无差异，新增差异为 M8 R2 候选设计材料。以下结论针对此源码基线，不根据历史文档中的 S0 描述推断当前代码。

“未发现”以全部 Controller、生产 Java 包、Flyway V1–V7、UI 入口/功能目录及依赖为检索范围。`NativeModelWorkspace` 等文件名中的 Workspace 是功能工作台，不是空间管理模块。[HTTP 入口][of-controllers]、[平台实现][of-platform]、[迁移][of-migrations]、[前端入口][of-app]

| 通用能力 | OntoForge 实际实现 | 缺口与 Yak 复用判断 |
| --- | --- | --- |
| 浏览器登录认证 | 默认分支为 OIDC/JWT Resource Server；SPA 已有 Authorization Code + PKCE、组织账号登录入口、签名/issuer/audience 校验、可信 `/api/me` | **已有外部身份接入，尚无本地账号密码登录**。优先复用 Yak 账户/登录实现，接入已有资源授权；不是从“完全没有认证”开始。[身份配置][of-security]、[浏览器身份][of-auth] |
| 本机无登录模式 | 显式 `mvp-local`、loopback/数据库约束、固定且明确标记的本机身份 | 继续用于隔离开发，不能迁移为普通用户或多用户空间。它与 Yak 本地账号登录是不同形态。[本机模式][of-local-security]、[本机授权适配][of-local-authz] |
| 会话、退出、改密后会话失效 | Bearer 只存 JS 内存，客户端 signOut 清理本地状态，未见本项目服务端账号会话/强制下线；未见 SPA refresh-token 或 IdP 全局退出实现 | 复用 Yak Session 生命周期与账户级失效逻辑，保留其单进程范围；Cookie/CSRF 与服务端主体映射一并接通。[浏览器身份][of-auth] |
| 用户管理、账号生命周期 | V5 的 principal 是 issuer/subject 到内部身份的映射，由显式 psql 配置；没有本地密码表、用户 CRUD/停用/重置密码 UI/API | 迁入 Yak 的实际表单、校验、密码与管理用例；同时补管理员服务端授权。[V5][of-v5]、[显式授权脚本][of-provision] |
| 平台角色、通用 RBAC、菜单权限 | 固定六种包角色枚举和五种模型操作；授权角色来自数据库，客户端自填身份/角色不生效；没有动态角色/权限管理中心 | **已有领域授权子集**。Yak 三种空间角色只能作为成员管理基础，不能自动映射为模型发布/激活权限。[包授权][of-authz]、[授权仓储][of-authz-dao] |
| 工作空间、项目空间 | 有 workspace/member/package-assignment 表，`/api/me` 返回可访问空间；模型包只能显式归属一个空间 | 没有创建/切换/成员管理的用户闭环。复用 Yak 空间 UI/成员规则，适配现有 UUID 和包归属；空间、模型包、未来 Project 不是同一个标识。[V5][of-v5]、[授权仓储][of-authz-dao] |
| 组织、部门、租户、用户组 | 未发现独立表、用例与管理入口 | Yak 当前也没有完整产品实现，不能列为现成迁入项；保留为有实际组织需求时的增量能力。[平台实现][of-platform]、[迁移][of-migrations] |
| 个人资料、偏好、收藏、最近使用 | 当前只显示可信主体名称；没有用户级偏好存储、收藏/常用/最近访问模块 | Yak 收藏与使用信号可优先复用，按本体菜单/资源设定场景；主题偏好与业务资源权限分开。[前端入口][of-app] |
| 主题、国际化、设计 Token | UI 以中文和现有 CSS 为主，未见主题切换 Provider、语言切换与翻译体系 | 可复用 Yak 主题和设计 Token；局部 i18n 只是基础，完整多语言仍需逐屏词条与实际验收。[UI 目录][of-ui]、[UI 依赖][of-ui-package] |
| 系统设置、参数/字典中心 | 配置来自 YAML/环境变量，已有数据库与来源校验；没有配置读写管理 API、版本/审计或设置页面 | Yak 的配置规则可复用；**用户偏好、部署参数、系统设置应分别记录所有者和修改权限**。通用设置中心两边都需要增量交付。[应用配置][of-yaml]、[配置模块][of-config] |
| 站内消息、公告、订阅与送达 | 页面内有 error/notice/status 提示；未发现消息、收件人、已读状态、订阅或通知投递表/API | Toast 可以复用，消息中心没有现成完整实现。后续从“审批待办/绑定漂移等真实事件→持久消息→收件箱→已读”交付，消息正文仍由服务端授权读取。[UI 目录][of-ui]、[迁移][of-migrations] |
| 通用审计、操作历史 | 快照/绑定有业务历史与摘要，部分记录有创建时间；没有通用 actor/action/target/result 审计查询模块 | 复用 Yak 审计字段和日志脱敏机制；Native 历史不等于安全审计，审计事件、留存和查询权限另需实现。[V3][of-v3]、[迁移][of-migrations] |
| 应用壳、导航、通用表单/表格/弹层 | 有 App 与 hash 路由、功能页，许多交互由各页 HTML/CSS 和浏览器 confirm 实现；无现成通用组件库依赖 | **高复用价值**：择需迁入 Yak UI、布局、空状态、分页、表单/弹层，沿用本体菜单和真实功能路径，不需要先改成多应用仓库。[前端入口][of-app]、[UI 依赖][of-ui-package] |
| HTTP 客户端、异常、分页、参数校验 | shared/api 只有身份与 authorizedFetch；各功能页有请求/错误转换；Controller Advice 按功能组织，既有错误码和页/游标合同实际被 UI 消费 | 可复用 Yak 请求与异常基础机制；通过适配保留 Native、Binding、ObjectSet 已有响应与失败恢复，不全局套 Result 包装改变旧接口。[浏览器身份][of-auth]、[HTTP 入口][of-controllers] |
| 数据源连接管理、凭据、元数据发现 | 独立只读 PG 来源由环境变量登记，允许表发现、列/键检查、结构指纹和查询预算已实现；无连接 CRUD/凭据管理界面 | 复用 Yak 连接表单、测试、目录、字段遮罩；维持受控来源与只读、版本化 Binding 合同。**Yak 返回脱敏不等于数据库静态加密**。[来源装配][of-source-config]、[PG 来源读取][of-source-dao] |
| 文件、附件、对象存储、通用导入导出 | 有领域数据导入脚本/快照内容操作，未见文件中心或附件持久化/授权下载模块 | Yak 也无完整文件中心；网络 blob/FormData 工具可复用，文件归属、存储、下载授权不能凭工具推定完成。[脚本目录][of-scripts]、[HTTP 入口][of-controllers] |
| 后台作业、Cron、任务日志、持久重试 | 未建通用 task-execution；当前有同步 HTTP 用例和 M7 进程内有界结果快照 | Yak 调度/任务生命周期存在，可为后续导入、索引、对账提供来源；须提取实际通用执行部分，不把数据同步任务或 Cron 直接等价为 Action 执行。[模块规划][of-module-plan]、[M7 实现][of-objectset] |
| 通用缓存、分布式锁、消息队列、Outbox | M7 仅有特定查询的内存 TTL/容量控制；生产模块未见通用 Redis/锁/MQ/Outbox。研究目录中的 PG POC 不属于部署实现 | 优先保留已有 CAS/事务等真实机制。发现具体异步/多节点消费者后再引入对应基础设施，不能将某个缓存/演示 POC 扩大为平台能力。[M7 实现][of-objectset]、[Boot 依赖][of-boot-pom] |
| 插件发现、配置规则、技术公共工具 | Common 只有复用摘要，Configuration 主要为环境校验；Core 是 Native 校验；SPI/插件仍为规划 | 需要实际插件时复用 Yak SpiRegistry/Typed Configuration 与测试。Yak Core 是数据流合同，OntoForge Core 继续拥有本体语义。[模块规划][of-module-plan]、[配置模块][of-config] |
| 运维状态、日志、指标、告警 | `/api/system/status` 返回安装 UUID、数据库查询结果和功能模式；存在 Spring 常规日志；无完整监控/告警中心 | Yak Logback/遮罩/日志结构可复用；数据同步 dashboard 需换成本体实际指标，不能直接成为通用运维验收。[平台状态][of-status]、[Boot 依赖][of-boot-pom] |
| 启动、分发、部署、升级和质量工具 | 已有 Reactor、Flyway、run-local、真实 PG/HTTP/浏览器质量门禁；未形成正式 dist/安装升级发行闭环 | 借鉴 Yak dist、nginx/Compose、release/check tooling；已有门禁与测试资产继续保留，独立发行以真实部署结果验收。[脚本][of-scripts]、[CI][of-workflows]、[模块规划][of-module-plan] |

因此，OntoForge 当前通用能力适合描述为：**面向本体 MVP 的身份/资源授权和运行基础已存在；面向多用户的完整平台管理与通用体验尚未形成。** 仓库若干历史文档中的“没有认证”不能覆盖当前默认 JWT 分支；UI 的“本机开发模式”也不能当作真实用户系统。[身份配置][of-security]、[本机授权适配][of-local-authz]

## 6. 复用结论：把可运行实现作为默认来源

本研究建议将 **“通用能力优先复用 Yak 已有实现”** 作为后续选型原则。复用范围覆盖前后端、持久化与工程工具，不局限于本次列举的登录、权限、消息、空间和设置。按以下四类处理：

1. **源码优先迁入，主要做工程适配**：UI 组件、主题 Token、布局结构、Toast 去重、表单/分页、公共校验和技术工具、日志遮罩、适用的工程检查。按真实消费者选择代码；不是只参考截图重新实现，也不是先导入整个应用。
2. **迁入完整切片，同时适配产品规则**：登录/账号/会话、工作空间/成员、偏好收藏、连接信息/元数据等。迁移 UI、Controller、用例、DAO 和必要测试；保留源提交及本地适配点。
3. **通用但来源中还没有完整实现**：站内消息中心、系统设置/字典中心、完整 RBAC/组织租户、通用文件与审计、企业 IdP 联邦。此类不能标记“直接搬用即可”；可复用现成组件/基础工具，新增真实产品用例。
4. **本体产品继续拥有**：Native 模型/编译/快照/Release、Data Binding 身份与 Authority、ObjectSet 质量与证据、Function/Decision/Action、语义协作。借用存储、日志、调度和 UI 工具，不替换领域合同。[总体模块规划][of-module-plan]

账号和空间的目标调用关系可以保持很小：

```text
Yak 账号校验 / Session（或保留的外部 OIDC 适配）
  → 服务端验证的稳定主体
  → OntoForge 空间成员 / 模型包及业务操作授权
  → 现有 Native / Binding / Object / ObjectSet 用例
```

**最重要的接入事实**：当前 `B0AuthorizationService` 直接要求 `JwtAuthenticationToken`，而前端 `authorizedFetch` 明确 `credentials: 'omit'`。复制 SessionManager/登录页还不能让本体现有接口使用该身份。应在真实出现的两种身份来源之间设置可信主体的适配位置，让密码、会话等实现留在 Platform，并使业务用例不需要了解会话存储细节；Cookie 请求、Spring Security 过滤链、CSRF 与 `/api/me` 也要形成同一条真实链路。[现有授权][of-authz]、[请求身份][of-auth]

空间成员管理权、账号管理员权、模型包读写权、发布/激活权分别处理。不能把 Yak MEMBER 直接解释为 OntoForge EDITOR，也不能把 Workspace OWNER 自动变为全部模型/来源/Action 的全能主体；既有 Viewer 读限制、撤权即时生效及游标失效测试需要继续成立。[现有授权][of-authz]、[授权负例测试][of-auth-tests]

## 7. 与现有模块结构的落点

| 模块 | 复用代码的实际归属 |
| --- | --- |
| `ontoforge-platform` | 账户、密码/会话、空间成员、偏好与通用管理用例；将来有实际实现的通知、审计、平台设置 |
| `ontoforge-dao` | 本项目账户/偏好等仓储、PostgreSQL 前向迁移、空间与身份映射；已应用 V1–V7 保持不变 |
| `ontoforge-boot` | 登录与管理 HTTP 入口、Cookie/CSRF/安全过滤链及模块装配 |
| `ontoforge-configuration` / `common` | 被实际复用的类型化配置/校验与技术工具，不集中堆放全部 DTO 或领域规则 |
| `ontoforge-ui/src/app` / `shared` | 应用壳、主题与共享组件、HTTP 适配；继续保持单 npm 根 |
| `ontoforge-ui/src/features/administration` 等真实功能 | 账号、空间、偏好/设置等有后端支撑的页面；目录按模块规划与实际交付创建 |
| `ontoforge-business` / Core | 本体差异化用例与规则继续留在本项目；数据源管理中跨本体绑定的编排归 Business |
| 后续 `task-execution` / SPI / dist | 有真实索引/导入/扩展/发行消费者时，择需迁入 Yak 的通用部分，不提前创建空工程 |

优先采用**可追溯的源码迁入 + 少量适配**，先在 OntoForge 完成实际消费者。共同代码出现稳定的双项目维护需求后，再将其提取为独立共享库/脚手架发行；目前无需先建设另一个平台产品，也不把整个 Yak DAO/Core/业务工程作为 OntoForge 的运行依赖。

## 8. 建议的实施批次与真实交付出口

以下是研究建议顺序，不是已批准的完整安全架构，也不修改本体 M0–M14 的成熟度。每一批按可操作故事交付；基础复用不阻断当前单人本机核心功能。

| 批次 | 复用与适配内容 | 用户可验证的结果 |
| --- | --- | --- |
| A：公共体验 | Yak UI、主题/布局、通用表单/表格/错误交互，保持现有本体路由与合同 | 原有建模→快照→绑定→对象/查询流程在新公共组件下可操作；刷新、慢响应、失败恢复保持有效 |
| B：账号与可信身份 | Yak 账号/密码/Session/登录页；明确部署认证模式、主体映射、账号管理员边界；接通现有资源授权 | 本地账号登录→进入被授权空间和模型→退出；未登录/过期、普通用户越权管理、停用/改密会话失效有真实 HTTP/浏览器负例 |
| C：空间与偏好 | 创建/选择空间、成员变更、角色映射、模型包归属、收藏/最近使用；按需与 B 合成一个完整故事 | 用户只能看见自己有权的资源；跨空间拒绝；撤权后的页面、查询游标与直接接口同步失效；收藏/最近使用真实持久化 |
| D：连接与平台运维 | 复用连接表单/测试/目录、凭据遮罩、日志结构、部署分发；密码保存方式和来源权限由本项目明确 | 管理员登记受控只读来源→预览→版本化绑定；日志不输出秘密；重启/升级不破坏已存绑定/快照；实际安装/升级可验证 |
| E：通用缺口 | 从真实事件建设消息、设置/字典、审计、文件；按具体需求使用调度/SPI | 例如绑定漂移事件→消息持久化→仅授权收件人可看→标记已读；系统参数变更有范围、校验、审计与生效规则 |

B/C 涉及认证方式、身份/空间映射和持久合同，实施时按现有 OFIP 流程记录明确决定；A 中复用组件、日志等例行内部改造按普通聚焦 PR 交付。**本次仅做完整盘点与建议，不变更默认认证分支、不回填 Accepted 状态、不实施新增管理接口。**

## 9. OntoForge 本次核验范围

- 读取当前 HTTP、安全过滤链、Identity/Authorization、DAO/迁移、配置、UI 身份与路由、依赖及授权负例；核对远端 main 与本地实际源码差异。
- 没有重新执行后端或浏览器测试，没有启动外部 Yak 代码；前次 PR #83 的运行结果不是本研究新增能力的验收。
- 本轮产物为此单一研究文件及文档索引链接；是可追溯的选型依据，不替代唯一总体架构、当前模块规划或后续实现 PR。

[of-controllers]: https://github.com/gitfortian/ontoforge/tree/c157d228eaa38b12bd99012c713817fbb2ab4378/ontoforge-boot/src/main/java/io/ontoforge/boot/controller
[of-platform]: https://github.com/gitfortian/ontoforge/tree/c157d228eaa38b12bd99012c713817fbb2ab4378/ontoforge-platform/src/main/java/io/ontoforge/platform
[of-migrations]: https://github.com/gitfortian/ontoforge/tree/c157d228eaa38b12bd99012c713817fbb2ab4378/ontoforge-dao/src/main/resources/db/migration
[of-app]: ../../ontoforge-ui/src/app/App.tsx
[of-ui]: https://github.com/gitfortian/ontoforge/tree/c157d228eaa38b12bd99012c713817fbb2ab4378/ontoforge-ui/src
[of-ui-package]: ../../ontoforge-ui/package.json
[of-security]: ../../ontoforge-boot/src/main/java/io/ontoforge/boot/security/SecurityConfig.java
[of-auth]: ../../ontoforge-ui/src/shared/api/auth.ts
[of-local-security]: ../../ontoforge-boot/src/main/java/io/ontoforge/boot/security/LocalMvpSecurityConfig.java
[of-local-authz]: ../../ontoforge-platform/src/main/java/io/ontoforge/platform/identity/LocalMvpAuthorizationService.java
[of-v5]: ../../ontoforge-dao/src/main/resources/db/migration/V5__b0_identity_authorization.sql
[of-provision]: ../../scripts/b0-provision.psql
[of-authz]: ../../ontoforge-platform/src/main/java/io/ontoforge/platform/identity/B0AuthorizationService.java
[of-authz-dao]: ../../ontoforge-dao/src/main/java/io/ontoforge/dao/platform/AuthorizationRepository.java
[of-yaml]: ../../ontoforge-boot/src/main/resources/application.yml
[of-config]: https://github.com/gitfortian/ontoforge/tree/c157d228eaa38b12bd99012c713817fbb2ab4378/ontoforge-configuration/src/main/java/io/ontoforge/configuration
[of-v3]: ../../ontoforge-dao/src/main/resources/db/migration/V3__native_schema_snapshot.sql
[of-source-config]: ../../ontoforge-boot/src/main/java/io/ontoforge/boot/config/BindingSourceConfiguration.java
[of-source-dao]: ../../ontoforge-dao/src/main/java/io/ontoforge/dao/query/PostgresSourceRepository.java
[of-scripts]: https://github.com/gitfortian/ontoforge/tree/c157d228eaa38b12bd99012c713817fbb2ab4378/scripts
[of-module-plan]: ../implementation/S0-工程模块规划与命名规范.md
[of-objectset]: ../../ontoforge-business/src/main/java/io/ontoforge/business/query/ObjectSetQueryService.java
[of-boot-pom]: ../../ontoforge-boot/pom.xml
[of-status]: ../../ontoforge-platform/src/main/java/io/ontoforge/platform/operations/PlatformStatusService.java
[of-workflows]: https://github.com/gitfortian/ontoforge/tree/c157d228eaa38b12bd99012c713817fbb2ab4378/.github/workflows
[of-auth-tests]: ../../ontoforge-boot/src/test/java/io/ontoforge/boot/B0IdentityAuthorizationTest.java

[yak-root-pom]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/pom.xml
[yak-bom]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-bom/pom.xml
[yak-license]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/LICENSE
[yak-platform]: https://github.com/weifuwan/yak-ops/tree/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-platform/src/main/java/io/yak/ops
[yak-login-controller]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-boot/src/main/java/io/yak/ops/boot/controller/LoginController.java
[yak-login-service]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-platform/src/main/java/io/yak/ops/security/service/impl/LoginServiceImpl.java
[yak-session]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-platform/src/main/java/io/yak/ops/security/authentication/HttpSessionAuthenticationManager.java
[yak-login-guard]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-platform/src/main/java/io/yak/ops/security/authentication/LoginAttemptGuard.java
[yak-password]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-platform/src/main/java/io/yak/ops/security/extend/impl/DefaultPasswordEncoder.java
[yak-security-properties]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-platform/src/main/java/io/yak/ops/security/config/YakSecurityProperties.java
[yak-bootstrap]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-platform/src/main/java/io/yak/ops/security/bootstrap/YakSecurityBootstrapInitializer.java
[yak-auth-interceptor]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-platform/src/main/java/io/yak/ops/security/web/YakAuthenticationInterceptor.java
[yak-security-assembly]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-boot/src/main/java/io/yak/ops/boot/config/SecurityConfiguration.java
[yak-user-controller]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-boot/src/main/java/io/yak/ops/boot/controller/UserController.java
[yak-user-service]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-platform/src/main/java/io/yak/ops/security/service/impl/UserServiceImpl.java
[yak-user-entity]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-dao/src/main/java/io/yak/ops/dao/entity/security/UserEntity.java
[yak-user-ui]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-ui/apps/web/app/management/users/index.tsx
[yak-workspace-controller]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-boot/src/main/java/io/yak/ops/boot/controller/WorkspaceController.java
[yak-workspace-service]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-platform/src/main/java/io/yak/ops/workspace/impl/WorkspaceServiceImpl.java
[yak-workspace-role]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-common/src/main/java/io/yak/ops/common/enums/workspace/WorkspaceRole.java
[yak-workspace-ui]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-ui/apps/web/app/management/workspaces/index.tsx
[yak-workspace-context]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-ui/apps/web/context/workspace-context.tsx
[yak-workspace-interceptor]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-boot/src/main/java/io/yak/ops/boot/workspace/WorkspaceContextInterceptor.java
[yak-mybatis]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-boot/src/main/java/io/yak/ops/boot/config/MybatisPlusConfiguration.java
[yak-v1]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-dao/src/main/resources/db/migration/yak-ops/V1__baseline.sql
[yak-migrations]: https://github.com/weifuwan/yak-ops/tree/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-dao/src/main/resources/db/migration/yak-ops
[yak-controllers]: https://github.com/weifuwan/yak-ops/tree/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-boot/src/main/java/io/yak/ops/boot/controller
[yak-preference-controller]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-boot/src/main/java/io/yak/ops/boot/controller/UserPreferenceController.java
[yak-preference-service]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-platform/src/main/java/io/yak/ops/preference/impl/UserPreferenceServiceImpl.java
[yak-preference-scene]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-common/src/main/java/io/yak/ops/common/enums/preference/UserPreferenceScene.java
[yak-yaml]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-boot/src/main/resources/application.yml
[yak-navigation]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-ui/apps/web/app/layout/navigation.ts
[yak-router]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-ui/apps/web/app/router/AppRouter.tsx
[yak-topbar]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-ui/apps/web/app/layout/TopBar.tsx
[yak-launcher]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-ui/apps/web/app/layout/ProductLauncher.tsx
[yak-toast-dedupe]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-ui/apps/web/utils/notification/notifyOnce.ts
[yak-ui-index]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-ui/packages/yak-ui/src/index.ts
[yak-ui-table]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-ui/packages/yak-ui/src/table/Table.tsx
[yak-theme]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-ui/apps/web/context/theme-context.tsx
[yak-themes]: https://github.com/weifuwan/yak-ops/tree/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-ui/apps/web/themes
[yak-i18n]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-ui/apps/web/app/datasource/i18n/index.ts
[yak-request]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-ui/apps/web/service/http/request.ts
[yak-response]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-ui/apps/web/service/http/response.ts
[yak-errors]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-boot/src/main/java/io/yak/ops/boot/exception/GlobalExceptionHandler.java
[yak-result]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-common/src/main/java/io/yak/ops/common/result/Result.java
[yak-page]: https://github.com/weifuwan/yak-ops/tree/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-common/src/main/java/io/yak/ops/common/page
[yak-openapi]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-boot/src/main/java/io/yak/ops/boot/config/OpenApiConfiguration.java
[yak-base-entity]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-dao/src/main/java/io/yak/ops/dao/entity/BaseEntity.java
[yak-logback]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-boot/src/main/resources/logback-spring.xml
[yak-sensitive]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-boot/src/main/java/io/yak/ops/boot/log/SensitiveDataConverter.java
[yak-sensitive-test]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-boot/src/test/java/io/yak/ops/boot/log/SensitiveDataConverterTest.java
[yak-datasource-controller]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-boot/src/main/java/io/yak/ops/boot/controller/DataSourceController.java
[yak-catalog-controller]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-boot/src/main/java/io/yak/ops/boot/controller/DataSourceCatalogController.java
[yak-credentials]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-business/yak-ops-business-datasource/src/main/java/io/yak/ops/business/datasource/util/DataSourceCredentialUtils.java
[yak-datasource-plugins]: https://github.com/weifuwan/yak-ops/tree/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-plugins/yak-ops-plugin-datasource
[yak-spi]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-spi/src/main/java/io/yak/ops/spi/SpiRegistry.java
[yak-spi-test]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-spi/src/test/java/io/yak/ops/spi/SpiRegistryTest.java
[yak-configuration]: https://github.com/weifuwan/yak-ops/tree/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-configuration/src/main
[yak-configuration-tests]: https://github.com/weifuwan/yak-ops/tree/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-configuration/src/test
[yak-schedule-controller]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-boot/src/main/java/io/yak/ops/boot/controller/ScheduleController.java
[yak-schedule-service]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-business/yak-ops-business-schedule/src/main/java/io/yak/ops/business/schedule/impl/ScheduleServiceImpl.java
[yak-schedule-target]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-common/src/main/java/io/yak/ops/common/enums/schedule/ScheduleTargetType.java
[yak-task-execution]: https://github.com/weifuwan/yak-ops/tree/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-task-execution/src/main
[yak-operations-ui]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-ui/apps/web/app/operations/dashboard.tsx
[yak-operations-controller]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-boot/src/main/java/io/yak/ops/boot/controller/DataSyncOperationsController.java
[yak-boot-pom]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-boot/pom.xml
[yak-dao-pom]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-dao/pom.xml
[yak-workflows]: https://github.com/weifuwan/yak-ops/tree/85338827bdbb745eaa6a017031640674a298a4f9/.github/workflows
[yak-dist]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-dist/src/main/assembly/assembly.xml
[yak-drivers]: https://github.com/weifuwan/yak-ops/tree/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-dist/src/main/jdbc-drivers-builtin
[yak-test-utils]: https://github.com/weifuwan/yak-ops/tree/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-test-utils
[yak-e2e]: https://github.com/weifuwan/yak-ops/tree/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-e2e/src/test
[yak-ui-tests]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-ui/tests/e2e/README.md
[yak-ui-root-package]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-ui/package.json
[yak-web-package]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-ui/apps/web/package.json
[yak-ui-package]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-ui/packages/yak-ui/package.json
[yak-id]: https://github.com/weifuwan/yak-ops/blob/85338827bdbb745eaa6a017031640674a298a4f9/yak-ops-common/src/main/java/io/yak/ops/common/util/IdUtils.java
