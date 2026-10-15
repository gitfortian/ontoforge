# OntoForge · 砺元 S0 开发基座：完整规划、极简 MVP 分版本实施

> 状态：实施计划。遵循 V1.3 架构，但不预先实现边缘模块或臆造业务数据。
> 目标：每一个版本独立可运行、可验收。用户、角色、权限、复杂多工作空间在 MVP **只作为后续规划**。
> 本地默认使用 Windows 11 原生 PostgreSQL；[Windows 操作与验收手册](./S0-Windows11-本机PostgreSQL-启动与验收.md)。

## 版本切分

| 版本 | 本轮必须亲自看到的结果 | 本轮明确不做 |
| --- | --- | --- |
| **S0-MVP-0.1（已完成基座）** | 本机 PostgreSQL、Spring Boot/Flyway V1、React 真实读取状态；刷新及后端重启可复验 | 登录、用户、角色、权限、工作空间、Native Model、对象查询、Action；界面只显示未实现占位 |
| **S0-MVP-0.2（PR #7 实施中）** | 真正的模型包与 ObjectType/Property 编辑、WIP 保存、校验、刷新重开和 revision 并发保护；Flyway V2/Java/React/真实 PG17+Chromium 验证 | 复杂 Registry、跨包引用、多人审批 |
| S0-MVP-0.3（候选未来） | 审核与不可变只读版本的独立 OFIP/安全合同；不重复开发 0.2 已交付的完整性校验 | 完整权限、多工作空间、多租户 |
| S0→P0-A 演进 | 草稿、审核、不可变发布、版本读取分批完成，每版本有明确功能和安全门禁 | 一次性铺完完整产品 |

> S0-MVP-0.2 已依据 **[Accepted OFIP-0002](../proposals/OFIP-0002-ObjectType-草稿建模MVP.md)** 获实施授权，并在 [PR #7](https://github.com/gitfortian/ontoforge/pull/7) 交付评审中；PR merge、Windows 本机验收和 Release 必须分别记录。0.3 仍是候选，不代表获准开发。

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

## S0-MVP-0.2 ObjectType 真实交付与验收

依据 OFIP-0002：Native Package 为 ObjectType/LinkType 定义集合；首版工作台先支持编辑首个 ObjectType，但数据库聚合不限一包一类型。**Save WIP 与完整 Native 候选校验是不同操作**，尚无审核/发布/激活。

- PostgreSQL `V2__native_package_drafts.sql` 新建草稿聚合表，保留 Flyway V1 和同一 installationId；当前可写草稿 packageId 唯一，未来不可变 Release 可独立多版本共存。
- `/api/model/drafts` 提供分页列表、创建、按 UUID 读取、revision 乐观锁编辑、按 revision 校验；409 明确拒绝旧版本提交；422 拒绝不安全的未知 DTO，业务不完整校验返回 200 + INCOMPLETE/INVALID issues。
- React 真正打开 Draft ID 地址、刷新重开；编辑时保存与校验分步，字段错误可定位，断网/并发时保留本地输入，不伪报保存成功。
- Java/真实 PG17 JUnit 自动测试新旧 Flyway、合法 WIP、VALID、错 PK、Type-scoped Property、并发 409 和恶意字段 422；独立真实 Chromium E2E 经过 Vite → Spring Boot → PG17 测试保存、刷新、校验、两标签页写冲突和断网。
- Windows 11 本机依然使用既有原生 PostgreSQL（非强制 Docker），从 [README 黄金故事](../../README.md) 和 `.\scripts\verify-s0.ps1` 执行独立验收；**没有用户本机报告则记为 NOT RUN**。
- 明确仍未实现 Auth/Workspace/Link 编辑、数据绑定、审核/发布、生产实例和工业 Action；不准作为联网生产服务开放。

## 安全与未来扩展

- **本版本没有身份认证、授权、工作空间隔离，不具备企业级安全能力。不得公网上线或加载真实生产数据。**
- 后端与 Vite 默认只监听 localhost。原生 PostgreSQL 必须仅用于受控本机开发；Docker Compose 是可选替代配置，不应占用同一 5432 端口。CI 使用隔离的临时 PostgreSQL。
- 后端 DB_PASSWORD 从环境变量读取，无默认明文密码；严禁将本机秘密提交到 Git、记录于文档或直接打印。
- 当前「Native 本体建模」必须是真实草稿 API / PostgreSQL 驱动页面；「Data Binding、用户与权限」仍为后续能力，不得做可提交却不存储的假功能。
- 保留 V1.3 模块规划、领域边界和 S0→P0 路线，但**在有真实功能和验收需求前，不预先生成空模块、权限表、用户 CRUD、微服务框架、通用工作流、万能工具类**。
- 需要接入真实业务/团队协作之前，必须先单独实现认证、授权和隔离并完成安全验证。将来的版本不允许仅凭「以后会补」绕过这道门禁。
