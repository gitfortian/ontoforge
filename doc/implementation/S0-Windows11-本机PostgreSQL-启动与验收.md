# OntoForge S0｜Windows 11 本机 PostgreSQL 启动与验收

> 当前本机启动与只读基座验收入口。ObjectType/LinkType、Snapshot 的范围和环境结果见[产品进度总账](./OntoForge-MVP-产品功能全景与成熟度矩阵.md)。基座检查不能代替完整功能、权限或生产验收。
> **默认方式：Windows 11 原生 PostgreSQL，不需要 Docker Desktop，也不需要删除或重建已有数据库。**

## 1. 基准与配置合同

- Java 21、Maven、Node.js 22、PowerShell 5.1+，本机已安装并启动 PostgreSQL（推荐 17，已有兼容版本可先按步骤验证）。
- 本机数据库：`127.0.0.1:5432`；库名 `ontoforge`；用户名默认 `postgres`。如现有角色或端口不同，通过环境变量显式覆盖。
- 后端 `ontoforge-boot/src/main/resources/application.yml`：`DB_URL` 默认 `jdbc:postgresql://127.0.0.1:5432/ontoforge`；`DB_USERNAME` 默认 `postgres`；**`DB_PASSWORD` 必须由环境变量提供，不包含默认值**。
- CI 使用自己的容器化 PostgreSQL 测试数据库 `ontology` 与 CI 专用凭据，通过 `DB_URL`/`DB_USERNAME`/`DB_PASSWORD` 覆盖；CI 数据库名不要求与开发机相同。
- 根目录 `compose.yaml` 仅为**可选的旧 Docker 开发方式**，它定义另一组 `ontology` 数据库凭据；本机原生方式请**不要执行 `docker compose up`**，避免占用 5432 或连到错误实例。旧容器卷不做迁移或删除。

## 2. 首次准备（Windows PowerShell）

**2.1 验证本机 PostgreSQL 服务**

```powershell
Get-Service -Name 'postgresql*'
# 已安装 psql 并加入 PATH 时：
psql --version
# 未加入 PATH 时可使用，例如：
& 'C:\Program Files\PostgreSQL\17\bin\psql.exe' --version
```

版本目录 `17` 按你的实际安装版本替换；数据库服务需要处于 Running 状态。通过 `java -version`、`mvn -version`、`node -v`、`npm -v` 检查其余工具。

**2.2 只在数据库不存在时创建 `ontoforge`**

```powershell
# 安装时创建的 postgres 超级用户会提示输入其已有密码。
psql -h 127.0.0.1 -p 5432 -U postgres -d postgres -W -c "SELECT datname FROM pg_database WHERE datname = 'ontoforge';"
# 如果未查到 ontoforge，再执行下面这一句：
psql -h 127.0.0.1 -p 5432 -U postgres -d postgres -W -c "CREATE DATABASE ontoforge;"
```

未把 `psql` 加入 PATH 时，将命令中的 `psql` 改为 `& 'C:\Program Files\PostgreSQL\17\bin\psql.exe'`。**库已存在就直接复用，不执行 DROP DATABASE，也不删除任何数据目录。**

**2.3 为当前 PowerShell 窗口设置连接参数**

```powershell
$env:DB_URL = 'jdbc:postgresql://127.0.0.1:5432/ontoforge'
$env:DB_USERNAME = 'postgres'
# 输入时不回显，不要把真实密码写进 Git、文档或 PowerShell Profile。
$env:DB_PASSWORD = [System.Net.NetworkCredential]::new('', (Read-Host 'PostgreSQL password' -AsSecureString)).Password
```

只在当前终端会话设置；打开**新终端**要重新设置。若使用不同角色/端口/数据库，将三项保持一致地修改，不要改变别人的 PostgreSQL 用户密码以匹配代码。

## 3. 启动顺序

**终端 A：在仓库根目录启动后端（保留上一步的环境变量）**

```powershell
mvn -pl ontoforge-boot spring-boot:run
```

首次启动会执行当前 Flyway V1–V3；已有版本会正常复验，不会每次生成新安装 ID。启动失败时先读错误日志，不要通过删除数据库解决 Flyway 或密码错误。

**终端 B：启动前端（无需 DB_PASSWORD）**

```powershell
cd ontoforge-ui
npm ci
npm run dev
```

打开 `http://127.0.0.1:5173`。页面通过代理访问后端 `http://127.0.0.1:8080/api/system/status`，应该显示 API/PostgreSQL 已连接和真实 Installation ID。

**终端 C：执行只读验收（需重新设置当前终端的 DB_* 变量）**

```powershell
$env:DB_URL = 'jdbc:postgresql://127.0.0.1:5432/ontoforge'
$env:DB_USERNAME = 'postgres'
$env:DB_PASSWORD = [System.Net.NetworkCredential]::new('', (Read-Host 'PostgreSQL password' -AsSecureString)).Password
# 在仓库根目录执行；若 psql 未加入 PATH，请传 -PsqlPath。
.\scripts\verify-s0.ps1
# 示例：.\scripts\verify-s0.ps1 -PsqlPath 'C:\Program Files\PostgreSQL\17\bin\psql.exe'
```

验收脚本只读检查：`flyway_schema_history` 的 V1 成功记录、`platform_installation` UUID、真实 HTTP 状态，以及**API UUID 与 PostgreSQL UUID 一致**。它不会执行 `CREATE`、`DROP` 或 `UPDATE`。

## 4. 重启与失败恢复验收

1. 记录脚本输出的 `Installation ID`，保留数据库运行。
2. 停止终端 A 中的后端，前端按「重新检测」应展示连接失败，不得假装成功。
3. 重新在终端 A 启动 Spring Boot；前端刷新或重新检测应恢复真实状态。
4. 在终端 C 再次执行 `.\scripts\verify-s0.ps1 -ExpectedInstallationId '首次记录的 UUID'`，验证数据库持久状态未变化。
5. Maven 集成测试必须另开终端，连接独立的 `ontoforge_test_*` 测试库，**不能复用上述开发 DB_* 作为测试连接**。测试使用单独 TEST_DB_*，启动前拒绝开发库名、远程主机与带选项的 JDBC URL：

```powershell
# 先由操作者建立独立测试库，不重建/清空原有开发库。
$env:TEST_DB_URL = 'jdbc:postgresql://127.0.0.1:5432/ontoforge_test_local'
$env:TEST_DB_USERNAME = 'postgres' # 按实际测试库角色设置
$env:TEST_DB_PASSWORD = [System.Net.NetworkCredential]::new('', (Read-Host 'Test DB password' -AsSecureString)).Password
# 仅在独立测试库显式授权故障注入；这会临时关闭测试表触发器。
$env:TEST_DB_ALLOW_FAULT_INJECTION = 'true'
mvn -B -pl ontoforge-boot -am verify
```

**完成标准**：健康 API 返回 `service=ontoforge`、`database=CONNECTED` 和当前代码提供的版本；V1–V3 成功；API 与数据库 UUID 一致；重启后相同；后端离线时前端显示失败。独立测试库中的 Maven 与前端构建分别记录结果，不混同为用户验收。

## 5. 常见失败（非破坏性排查）

| 现象 | 先检查 |
| --- | --- |
| `psql` 不是内部或外部命令 | 使用安装目录完整路径，或将 PostgreSQL 的 `bin` 加入当前 PATH |
| 连接被拒绝 / 5432 无法连接 | `Get-Service 'postgresql*'`，核对端口和本机数据库服务是否 Running；关闭与原生库争用 5432 的容器 |
| `password authentication failed` | 核对 PostgreSQL 实际角色和终端中 `DB_USERNAME`/`DB_PASSWORD`，不要修改代码中的默认密码 |
| `database "ontoforge" does not exist` | 按第 2 节检查并创建缺失的库；已有数据的库不能覆盖 |
| Flyway 校验失败 | 查看迁移记录和 SQL 版本，不要删除 `flyway_schema_history` 或数据库卷来“修复” |
| 前端显示连接失败 | 检查后端是否启动及 8080 是否被占用；先访问状态 API |
| API 与数据库 UUID 不同 | 后端终端与验收终端指向了不同的 DB_URL；逐项核对，不要直接覆盖安装 ID |

**安全限制**：S0 尚未实现身份认证、授权及工作空间隔离，只能在隔离的本机开发环境使用，不得暴露到公网或接入真实生产数据。
