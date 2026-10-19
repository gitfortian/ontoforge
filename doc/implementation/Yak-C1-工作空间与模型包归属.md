# Yak C1：工作空间、成员与模型包归属

依据 [OFIP-0017 / PR #94](https://github.com/gitfortian/ontoforge/pull/94) 及 [维护者接受决定](https://github.com/gitfortian/ontoforge/pull/94#issuecomment-6261109442)。本切片接续 [B1/B2 本地账号说明](./Yak-B1-本地账号Session接入.md)，是开发实现候选；用户既有安装 UAT、生产安全验收和发布仍未完成。

## 从账号到真实建模

1. 按 B1 说明配置 PostgreSQL、外部游标签名秘密并初始化管理员，启动后端和前端。登录后左侧打开「工作空间」。普通用户也可以创建自己的空间。
2. 点击「创建工作空间」，填写固定标识（3–64 位小写字母、数字、连字符，以字母开头）和显示名称。创建者在同一事务中成为 OWNER，不自动获得历史模型包。
3. 在空间详情点击「登记模型包」，输入有效域式 packageId。OWNER 可登记尚不存在且未归属的新包。已有 Draft/Snapshot 的历史未归属包仅系统管理员能显式分配；其他空间已占有的包返回 409，同空间重复登记幂等。
4. 顶部「当前工作空间」选择该空间，进入「Native 本体建模」，创建刚登记的包、编辑对象类型并保存。刷新后读取真实服务器内容。选中的空间限制草稿列表及创建包选择；绑定、对象和 ObjectSet 页面也核对包是否属于所选空间。深链接的资源 API 始终按包授权，客户端选择不成为服务器授权上下文。
5. 管理员在「账号管理」创建普通账号。空间 OWNER 在详情搜索该账号（至少 2 字符、最多 20 个结果），选择角色并保存。另一个浏览器登录该用户，确认可以读取或编辑被授予的模型包；移除成员后再次请求立即得到 403。
6. 最后一位当前认证模式下可用的 OWNER 不可移除、降级或停用。先把另一可用主体设为 OWNER，再移除原 OWNER。系统管理员可恢复失管空间，但须显式加入成员才能获得模型业务权限。

顶部只列出 `/api/me` 返回的成员空间；系统管理员在管理页面可以看到全部空间，但没有自动业务权。选择偏好按 principalId 存在当前浏览器，不保存密码或 Token；旧值必须仍在服务端成员列表内才可使用。浏览器禁止存储时使用本次页面内存。切换空间会确认未保存编辑，再关闭旧编辑/查询状态并返回模型列表。「刷新成员权限」更新已移除的成员空间；没有自动重放失败操作。

## 已有角色与并发

沿用 V5 的六种角色；可以同时授予多个角色：

| 角色 | 当前已实现能力 |
| --- | --- |
| OWNER | 空间管理、草稿读写、校验/快照及已有运行时只读查询 |
| EDITOR | 草稿读写、校验/快照及已有运行时只读查询 |
| REVIEWER、PUBLISHER | 草稿/快照读取及已有运行时只读查询；正式审核和发布尚未实现 |
| VIEWER | 保留已有角色；不授予草稿/快照及依赖其授权的当前查询 |
| ACTIVATOR | 保留已有角色；当前没有激活功能 |

名称、成员角色和移除使用工作空间的 `expectedVersion`。修改任一成员、名称或新包归属都会递增空间版本；并发修改即使针对不同成员，也要求刷新核对。409 不自动重试。DELETE 成员成功返回 204，允许已有另一 OWNER 时退出自己的空间。没有空间删除、包转移或无条件覆盖 API。

账号状态变更和空间变更共享账号事务锁，锁内重新检查操作员权限；空间行锁及 CAS 串行处理同空间变更。模型包登记和草稿创建共享包标识事务锁，防止与尚未提交的历史模型创建竞争而冒领。包的全局唯一约束仍由数据库兜底。直接绕过应用修改数据库的操作不在这些并发保证内。

## 接口与兼容

| 接口 | 行为 |
| --- | --- |
| `GET/POST /api/platform/workspaces` | 自己的空间（系统管理员可列全部）/创建 `{workspaceKey,displayName}` |
| `GET/PUT /api/platform/workspaces/{id}` | 读取/改名 `{displayName,expectedVersion}`；OWNER 或管理员可改名 |
| `GET /api/platform/workspaces/{id}/members?q=` | 成员列表；OWNER/管理员可按当前可信认证模式检索可用主体 |
| `PUT /api/platform/workspaces/{id}/members/{principalId}` | `{roles,expectedVersion}`，替换整个角色集合 |
| `DELETE /api/platform/workspaces/{id}/members/{principalId}?expectedVersion=` | 移除全部角色 |
| `POST /api/platform/workspaces/{id}/packages` | `{packageId}`，明确登记或分配 |
| `GET /api/model/drafts?workspaceId=` | 可选空间筛选；验证真实成员资格后仍执行原包授权及服务器游标分页 |

Session 变更均要求 CSRF；JWT 模式使用原有签名、issuer/audience/有效期检查，只能授予当前 issuer 已登记的主体。没有本地账号与同名 OIDC 账号合并。显式 mvp-local 继续原单人路径，不启用这些管理 API。系统管理员权限不从客户端请求获取，也不绕过 Draft/Snapshot/Binding/查询授权。ObjectSet 撤权通过真实成员 API 验证；旧 cursor 经撤权失效后重新授权仍不能复活。

只新增前向 V9，给原 `b0_workspace` 增加版本；V1–V8 保持原字节。旧 workspaceId/principalId/accountId、包归属、角色、账号凭据版本、安装 UUID、Draft revision 与 Snapshot 内容保持不变。升级前备份，失败时保留迁移证据；不使用 repair、删除库或修改已应用迁移。

## 复用来源与验收范围

固定 Yak 提交 [`85338827bdbb745eaa6a017031640674a298a4f9`](https://github.com/weifuwan/yak-ops/tree/85338827bdbb745eaa6a017031640674a298a4f9)：适配 `WorkspaceServiceImpl` 的创建/发现/成员流程，`apps/web/app/management/workspaces` 的列表、表单和成员选择，以及 `context/workspace-context.tsx` 的当前空间选择方式。MySQL/MyBatis 改为本项目 PostgreSQL/JdbcTemplate；保留六种 B0 角色和现有 API，未移植 Yak OWNER/ADMIN/MEMBER 权限语义。公共组件继续直接使用第一批 Yak 源码，Apache LICENSE/NOTICE 随现有分发保留。

`WorkspaceIntegrationTest` 覆盖真实 HTTP/PG 创建、CAS、角色、最后 OWNER、跨空间拒绝、历史包接管、包唯一性、空间列表过滤和未提交模型创建竞争。`B0IdentityAuthorizationTest` 补充签名 JWT 的空间正负例；`NativeMigrationUpgradeTest` 验证 V2 → V8 → V9 保留原数据；`LocalSessionIntegrationTest` 用真实成员 HTTP 撤权并检查两用户 cursor 隔离与重新授权后 410。`session_stories.py` 新增真实 Chromium 空间操作，原 Session/账号故事及独立 JWT 业务故事继续运行。

确切 base/head SHA、各项 PASS/FAIL/NOT RUN 与 CI URL 在实现 PR 记录。命令沿用仓库 README；测试必须使用隔离 TEST_DB_*。此切片没有引入组织租户、完整 IAM、消息中心、字典、文件或连接凭据管理。
