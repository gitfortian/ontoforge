# OntoForge · 砺元｜品牌与工程命名约定

> 正式产品品牌：**OntoForge 砺元**。本文件是 S0 工程标识迁移规则，不改变已冻结的 V1.3 技术设计和 Native DSL/AST V0.2 契约。

## 品牌与定位

- **Onto**：Ontology，表达可靠、可治理的企业业务本体。
- **Forge**：锻造。工业本体不是单纯靠绘图建出来的，而是在实际业务数据、来源核验、对象关系和决策行动闭环里被锻造出来。
- **砺**：磨砺、精炼；**元**：根本、元素。面向制造业，传达务实、可靠、专业的技术调性。
- 中文展示统一 **砺元**；中英联合展示 **OntoForge · 砺元**；程序内 ASCII 标识统一 **ontoforge**。禁止 Ontology System、Onto-Forge、ontoforge-system 等多种前缀并行。

## 规范工程坐标

| 层面 | 正式标识 |
|---|---|
| Maven Parent | `io.ontoforge:ontoforge:0.1.0-SNAPSHOT` |
| 后端 Boot 模块 | `io.ontoforge:ontoforge-boot` |
| Java 包前缀 | `io.ontoforge` |
| Java 启动类 | `io.ontoforge.boot.OntoForgeApplication` |
| Controller 包 | `io.ontoforge.boot.controller` |
| 前端根目录 / npm package | `ontoforge-ui` |
| Spring 应用名 | `ontoforge` |
| 状态 API service 字段 | `ontoforge` |
| 产品名称 | `OntoForge · 砺元` |

现有 S0 只实现 Maven Parent、Boot、UI；其余候选模块按 [S0 模块规划](./S0-工程模块规划与命名规范.md) 中的 `ontoforge-core`、`ontoforge-platform`、`ontoforge-dao` 等命名，仍不提前创建空工程。

## 兼容性与历史保留

1. **不修改数据库名** `ontology`、PostgreSQL 用户 `ontology`、Compose 数据卷逻辑名 `ontology_pgdata`、`platform_installation` 表名和 Flyway `V1__foundation.sql`。这些是既有本地开发数据的物理身份，不等于面向客户的产品品牌。未来若确需迁移数据库/Volume，必须有明确且可回滚的数据迁移计划。
2. 维持 `GET /api/system/status` 路由、JSON 属性名、端口 8080/5173、安装 UUID 和 `S0-MVP-0.1` 版本语义不变；仅 `service` 展示值从旧产品 ID 调整为 `ontoforge`。
3. 保留版本化 **Native V0.2 JSON Schema、manufacturing.quality 包 ID、GitHub 历史提交、旧 POC 原始代码与证据链接**；不能做全仓粗暴字符串替换，以免制造无关合同 Diff。
4. 当前 GitHub 仓库仍是 `gitfortian/ontology-system`。仓库远程名称的变更与源代码工程命名是不同操作；源代码可直接通过 PR 改名，GitHub Repository 的改名需具备管理员权限并由仓库设置单独执行。**本 PR 不声称已修改 GitHub 仓库名**。
5. 旧构建产物和 Maven 缓存 `io/ontology/system` 不再用于后续发行；新发布坐标为 `io/ontoforge`。这属于 S0 开发期工程坐标迁移，没有已发布的生产客户端需自动迁移。
6. 本机旧数据卷不必销毁：只移动 Compose 文件之外的工程代码与根目录命名，保持原 `compose.yaml` 中的持久化 Volume 声明和默认 Compose project 不变。

## 本轮验收

- 根 Maven `pom.xml` 只注册真实 `ontoforge-boot`；Java 包名前缀为 `io.ontoforge`。
- `mvn -B -pl ontoforge-boot -am verify` 在 PostgreSQL 17 服务上真实运行既有 Flyway 与 API 集成测试。
- `cd ontoforge-ui` 后执行 `npm install && npm run build` 或 `npm run dev`。
- 页面、浏览器标题显示 OntoForge 砺元；状态 API service 为 `ontoforge`，数据库安装 UUID 不重建。
- 用户、角色、权限、工作空间、Native 模型创建仍是**未实现**，只规划/占位。
- 文档/Native V0.2 自动化契约回归必须无损。

> 品牌改名不自动表示产品可公网部署。S0-MVP 依旧只适合本地开发，不得接入生产数据。
