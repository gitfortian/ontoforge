# OntoForge · 砺元 S0 开发基座：完整规划、极简 MVP 分版本实施

> 状态：实施计划。遵循 V1.3 架构，但不预先实现边缘模块或臆造业务数据。
> 目标：每一个版本独立可运行、可验收。用户、角色、权限、复杂多工作空间在 MVP **只作为后续规划**。
> 本地默认使用 Windows 11 原生 PostgreSQL；[Windows 操作与验收手册](./S0-Windows11-本机PostgreSQL-启动与验收.md)。

## 版本切分

| 版本 | 本轮必须亲自看到的结果 | 本轮明确不做 |
| --- | --- | --- |
| **S0-MVP-0.1（当前基座）** | 本机 PostgreSQL、Spring Boot/Flyway V1、React 真实读取状态；刷新及后端重启可复验 | 登录、用户、角色、权限、工作空间、Native Model、对象查询、Action；界面只显示未实现占位 |
| S0-MVP-0.2（下个 PR，需确认） | 第一个最小 Native ObjectType 草稿模型创建和读取，基于 V0.2 合同做必要字段验证 | 复杂 Registry、跨包引用、多人审批 |
| S0-MVP-0.3（未来） | 单模型校验与错误反馈，以及版本化只读视图（以设计评审确定） | 完整权限、多工作空间、多租户 |
| S0→P0-A 演进 | 草稿、审核、不可变发布、版本读取分批完成，每版本有明确功能和安全门禁 | 一次性铺完完整产品 |

> S0-MVP-0.2 与 0.3 是候选路线，不代表已批准开发或已经实现。

## MVP-0.1 用户故事

作为本地开发者，我能启动一个真正使用 PostgreSQL 的后端和 React 前端，在浏览器看到当前 API 和数据库真实连接状态；断开服务会出现错误，而不是展示“演示正常”。本机数据库持久保留 Flyway 迁移和 installationId，重启后可复验。

### Windows 原生 PostgreSQL 验收

1. 本机 PostgreSQL 服务运行；在 `127.0.0.1:5432` 创建或复用数据库 `ontoforge`，不删除现有数据；本地默认 DB_USERNAME=`postgres`。
2. 在启动后端的 PowerShell 窗口安全输入 DB_PASSWORD 环境变量（必须设置，不能使用代码内置默认密码）；运行 `mvn -pl ontoforge-boot spring-boot:run`。
3. 使用 Node.js 22：进入 `ontoforge-ui` 后运行 `npm install`，再执行 `npm run dev`，浏览器访问 http://127.0.0.1:5173。
4. 新 PowerShell 设置相同的 DB_* 参数，执行 `.\scripts\verify-s0.ps1`，验证 `/api/system/status` 返回 S0-MVP-0.1 / CONNECTED，且 Flyway V1 成功、数据库 installationId 与 API 一致。
5. 记录 installationId，停止后端时 UI 应展示连接失败；重启后运行脚本带 `-ExpectedInstallationId`，确保保持同一 UUID。
6. CI 必须运行独立 PostgreSQL/Flyway 集成测试及 React/TypeScript build，且不依赖开发机的 PostgreSQL 密码；其他旧 POC 测试不得受到影响。

**验收状态说明**：CI 和代码/文档检查不能替代你 Windows 机器上的实际运行；本机连接、页面与重启验收必须由 Windows 操作者执行后签收。

## 安全与未来扩展

- **本版本没有身份认证、授权、工作空间隔离，不具备企业级安全能力。不得公网上线或加载真实生产数据。**
- 后端与 Vite 默认只监听 localhost。原生 PostgreSQL 必须仅用于受控本机开发；Docker Compose 是可选替代配置，不应占用同一 5432 端口。CI 使用隔离的临时 PostgreSQL。
- 后端 DB_PASSWORD 从环境变量读取，无默认明文密码；严禁将本机秘密提交到 Git、记录于文档或直接打印。
- 前端导航“本体建模、Data Binding、用户与权限”只显示“规划中”，不能做出可提交但实际不存储的假功能。
- 保留 V1.3 模块规划、领域边界和 S0→P0 路线，但**在有真实功能和验收需求前，不预先生成空模块、权限表、用户 CRUD、微服务框架、通用工作流、万能工具类**。
- 需要接入真实业务/团队协作之前，必须先单独实现认证、授权和隔离并完成安全验证。将来的版本不允许仅凭「以后会补」绕过这道门禁。
