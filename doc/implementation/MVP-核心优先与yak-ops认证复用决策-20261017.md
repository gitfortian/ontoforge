# OntoForge 核心 MVP 优先与 yak-ops 认证复用决策

> 2026-10-17 产品方向修订；对应本轮代码 PR：**显式 opt-in 的本机隔离 MVP**。本文是实施范围决策，不替代唯一的 [V1.3 总体架构](../本体产品总体功能与技术架构设计_v1.3.md)。正式认证架构采纳仍需未来单独评审。

## 1. 产品决策：把稀缺研发资源投入本体差异化

**MVP 核心目标**：在可隔离的制造测试事实中，从 Native ObjectType/LinkType 定义，经真实 PostgreSQL 数据绑定，构造可解释的 ObjectSet 查询结果，并追溯异常质量批次影响的业务对象及来源依据。实现优先顺序：

1. **M0 已有**：多个 ObjectType/Property/LinkType 的创建、持久化、版本修订/VALID 校验及本地只读快照（不是正式发布）。
2. **M1 已有**：PG17 的本机测试表发现，固定三表/10 项字段映射，预览样本和一条两跳关联，按批次返回单路径及有限证据。
3. **M1.1 本 PR 交付**：开发者**不配置 IdP**即可进入建模与质量追溯；需明确 opt-in `spring.profiles.active=mvp-local` 和仅本机隔离数据库；保留现有 Flyway V1–V5，避免迁移历史损坏。
4. **M2 下一个完整用户功能 PR**：一批异常批次 → 影响工单和订单**完整集合**（含去重、排序/分页、稳定来源表/列、404/断链/多路径错误语义），而不是将 `LIMIT 2` 多路径 409 当成最终产品效果。此步起逐步抽出受治理的最小 ObjectSet 查询合同、查询预算和执行证据。
5. **M3 后续**：更一般的 ObjectSet 过滤、关系遍历/聚合/字段映射及动态类型，优先有限真实场景；Source Binding 的漂移复核、版本和证据留存。
6. **M4 后续**：Agent 只读 Context SDK/MCP，逐步引入 Function/Policy/Action 的治理式执行；绝不让 Agent/Action 成为现阶段核心建模可用性的前置依赖。

## 2. 为什么 #17 与当前 MVP 方向不符

[#17](https://github.com/gitfortian/ontoforge/pull/17) 已合并 `main`（包含 JWT/OIDC、工作空间及包授权、Flyway V5），这是**真实存在的代码事实**，但其价值属于面向企业扩展的通用基础能力，并非本体核心差异化。现状中 JWT Resource Server 默认缺少 OIDC issuer/JWKS/audience 就拒绝启动，UI 因 `/api/me` 失败而无法开始本地建模，且管理员必须先预分配包。该依赖使 MVP 的业务验证被通用身份体系阻塞。

**修正**：不改写 V5 历史 SQL，也不在默认启动模式添加匿名后门。通过可审计的本机独立 Profile 与安全约束恢复核心体验；不声称此模式是生产认证。

## 3. 安全隔离策略与代码合同

| 运行形态 | 何时使用 | 实际认证/权限状态 | 强制边界 |
| --- | --- | --- | --- |
| `mvp-local` 明确 opt-in | 单人本机可抛弃测试库 | 无账号、无会话、无用户隔离；UI 清楚标记；可自己创建任意合法 packageId | Spring Boot 必须绑定 loopback，PG JDBC 必须是本地 `ontoforge` 或 `ontoforge_test_*` 数据库；严禁生产事实、公网、反向代理与隧道 |
| 未启用 `mvp-local` 的现有 #17/JWT | B0 兼容测试或单独治理研究 | 要求有效签名 JWT、issuer/audience、已分配工作空间/模型包；无配置启动 fail-closed | **不代表正式生产安全签收**；保留已有正反例 CI |
| 后续 yak-ops HttpSession 集成 | 需要实际多用户/浏览器登录时 | 复用 yak-ops 登录/账号/密码/session/鉴权组件，不新造身份系统 | Session Cookie、CSRF、固定/过期会话、跨实例、工作空间 ACL/审计需独立测试与评审；**不是现在的 MVP 任务** |

本机 `mvp-local` 的“身份显示”只是用于兼容已有 UI 的**清楚标记的本机占位**（issuer=`local-mvp`），没有真正签名凭证，不能传给第三方或作为访问审计证据；浏览器不会弹出登录。CI 应在不配置 `OIDC_*` 时跑真实 `create → list → model → preview → bind → trace`，并证明非本机地址、远程 PG 和非测试库被拒绝。

### 特别注意

在本机模式下，`localhost` 监听与数据库名称白名单并不足以防止有意的端口代理或误将生产数据写到测试库；**用户需要自行确认隔离库确实专用且脱敏**。应用仍包含原 #17 的 B0 表和服务，不意味着用户已被授权在工厂环境使用。

## 4. yak-ops 复用评估（不在本轮拷贝/实现认证）

源码：[weifuwan/yak-ops](https://github.com/weifuwan/yak-ops)（Apache License 2.0）。

| 已有代码资源 | 对 OntoForge 的复用策略 | 不直接复制的条件 |
| --- | --- | --- |
| `yak-ops-platform/.../authentication/HttpSessionAuthenticationManager.java` | 未来浏览器登录会话优先复用；JSESSIONID + 账号级会话失效 | 核对 Jakarta/Spring Boot 依赖、Session 生命周期/跨节点策略 |
| `yak-ops-platform/.../service/LoginService.java` 及实现 | 优先复用凭据校验、登录/退出、登录失败保护 | 密码保护、CSRF、网络边界、安全测试与用户数据迁移 |
| `yak-ops-boot/.../SecurityConfiguration.java`、`YakAuthenticationInterceptor.java` | 后续抽象为独立可复用认证适配层，避免 OntoForge 二次实现 | 不将 Spring Security Resource Server 与 Servlet Interceptor 两套模式默认叠加 |
| `yak-ops-dao/.../security/UserEntity`/Repository、UI 登录页 | 后续按用户管理及 UI 风格需求适配 | 表前缀、Flyway 版本、静态资源/CSS、Apache-2.0 原声明与 NOTICE |

**暂不做**：不在本轮“顺手移植”用户管理、权限、角色、企业 SSO；不要一次性复制全部 yak-ops 模块。等核心 ObjectSet 价值通过产品签收，再按所需范围集成；单独验收许可证/归属、数据库命名和实际登录退出安全。

## 5. 任务粒度、研发出口和验收

| 任务 | 用户可观察结果 | 完成门槛 |
| --- | --- | --- |
| M1.1 本 PR | 无需登录就可在本机创建/打开对象草稿、预览/绑定质量来源、查询到订单；页面明确显示隔离模式 | 真实 PG17/HTTP 测试 + React 构建 + Windows PS 参考步骤；非本机安全负例；JWT 旧路径无回归 |
| M2 下一独立 PR | 输入异常批次后，看到**所有受影响工单与客户订单**，支持明确的分页和来源路径，而不是多路径 409 | 自备合成多路径数据、真实 SQL 结果去重稳定、分页游标、有界输出/失败处理、浏览器黄金故事 |
| M3 下一阶段 | ObjectSet 的最小可组合查询与绑定证据 | 用户验证筛选、关系遍历、稳定排序和权限预留，反例不泄露 |
| 认证复用（延后） | 项目有实际多用户需求后直接使用 yak-ops 的 HttpSession 基础能力 | 许可合规、会话/CSRF/失效/迁移/多节点验收，**不作为当前 MVP GATE** |

任何阶段不得把“CI PASS”“PR 已合并”直接称为 Windows 11 实际安装已验收，更不得将本机无认证模式部署到工厂/公网。
