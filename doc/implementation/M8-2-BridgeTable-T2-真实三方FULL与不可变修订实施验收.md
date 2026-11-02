# M8.2 BridgeTable · T2 真实三方 FULL 与不可变修订阶段验收

> **2026-10-20 · 当前阶段：实现已提交、真实 S0 PG17/HTTP 通过；完整 PR Required Quality 以当前最新 head 检查为准。**  
> 仅 [Draft PR #103](https://github.com/gitfortian/ontoforge/pull/103) · [OFIP-0010 M8.2-only Accepted](https://github.com/gitfortian/ontoforge/pull/103#issuecomment-6281584798) · 已签收 UX-Low 与 UX-High。  
> **T2 完成不等于 M8.2 端到端完成**：ObjectSet Bridge OUT/IN、生产 React UI、三方 sourceReadRefs frozen cursor 和 T3/T4/T5 的生产黄金故事仍未交付；不得 Ready/merge/release。

## 1. 本阶段实际功能完成

| 用户/业务能力 | 本次真实代码及语义 | 交付程度 |
| --- | --- | --- |
| M:N 来源 FULL 校验 | [BridgeLinkBindingService](../../ontoforge-business/src/main/java/io/ontoforge/business/connection/BridgeLinkBindingService.java) 在同一只读 PostgreSQL 17 Connection/REPEATABLE_READ 中先锁定 SOURCE、TARGET、BRIDGE 三个受控表的 ACCESS SHARE，再复核当前结构并有界读取。非法来源/字段/身份/结构漂移/数据库错误 fail-closed | 真实实现 + PG17/HTTP 测试 |
| 互斥质量账本 | SQL NULL 两端、仅一端 NULL；非空两端缺 SOURCE、缺 TARGET、两端都缺；matchedPhysical、uniquePairCount、duplicatePairRows。七个互斥桶累加必须等于 bridgePhysicalRows，拒绝 NULL 误计 orphan | 真实实现 + 九行夹具精确断言 |
| 来源与预算 | 当前包权限、Local Snapshot、两个 M5 Binding 冻结修订/digest/已认证 sourceKey 与允许表、第三 Bridge sourceId/public/双 FK 列独立核验。三表总 10k physical rows、8 MiB/3s，Join candidate 10k 前置拒绝，不将截断结果当 FULL | 真实实现 + 10001 行拒绝 |
| REST 版本分流 | [LinkBindingController](../../ontoforge-boot/src/main/java/io/ontoforge/boot/controller/LinkBindingController.java) 对现有 `validate/create/update/get` 请求做严格 `local-link-binding-v1` FK / `local-link-binding-v2` BRIDGE_TABLE 分流，v2 字段白名单、双端修订与三指纹；旧 v1 禁止桥表字段/注入 SQL | 真实实现 + 旧 FK 回归 |
| 元数据持久化 | 复用 [V11](../../ontoforge-dao/src/main/resources/db/migration/V11__bridge_link_binding_mapping_mode.sql) 与 [BridgeRevision DAO](../../ontoforge-dao/src/main/java/io/ontoforge/dao/connection/LinkBindingRepository.java)，仅双端 ObjectBinding 加第三 Bridge 来源信息；旧 FK-only 三列真实 NULL，保存后不可修改 | 真实实现 + PG17 DB/DAO 测试 |
| CAS / UNKNOWN 恢复 | create/update 共用已有 `lockOperationKey/lockLinkIdentity/lockHead/advanceHead/recordOperation`、不可变 Revision、规范请求 SHA 内容摘要；重复 operation 重放已有 revision，变更 body 同 key 拒绝，旧 expectedRevision 409 | 真实实现 + HTTP/PG17 正负例 |
| 安全详情 | `get` 时重新核验 SOURCE/TARGET/BRIDGE 三份当前可读表与 fingerprint；旧 FK detail 禁止对 Bridge revision 返回缺第三来源的片面证据。撤销第三表 SELECT 后 403，不泄露历史表名/事实/指纹 | 真实实现 + grant revoke HTTP 负例 |

### 真实黄金样本

测试 [LinkBindingFullIntegrationTest](../../ontoforge-boot/src/test/java/io/ontoforge/boot/LinkBindingFullIntegrationTest.java) 增加 `bridgeV2RealPgHttpFullBucketsCasReplayRevokeAndBudget`：
- SOURCE `equipment`、TARGET `inspection-lot` 均有真实 M5 sourceKey/version/digest；
- BRIDGE `m8_equipment_inspection_bridge` 九行，其中 matched=3、uniquePairCount=2、duplicatePairRows=1、六类 NULL/orphan 各一行；
- `POST /api/runtime/link-bindings/validate` v2 HTTP 200 `VALID_WITH_ORPHANS`，完整互斥账本；
- `POST /api/runtime/link-bindings` v2 创建 201，保存 `BRIDGE_TABLE` 修订且三列旧 FK 为 NULL；同幂等键原 body 恢复同 digest；
- 合同仍合法但同 idempotencyKey 变更 Bridge 结构摘要 => 409 `IDEMPOTENCY_KEY_CONFLICT`；任意 SQL/FK v1 混入 v2 => 422；
- `PUT /api/runtime/link-bindings/{id}` 修改 / 原 Operation 重放成功，新 operation 旧 expectedRevision => 409；
- `REVOKE SELECT` 第三 Bridge 表后 GET 历史 revision 403、失败体不泄露物理键/桥表/指纹；
- +10001 条物理桥表行后重新 FULL 429，没有生成新的合法 Revision。

## 2. CI 失败诊断与修复保留

首版真实测试 [S0 #39361476527](https://github.com/gitfortian/ontoforge/actions/runs/39361476527) FAIL 于 **测试夹具包名不符合 Native 域式 ID 规则**，新 HTTP 业务断言当时尚未运行；改用合法 `m8.bridge.p<uuid>`，不削弱校验。

第二版 [S0 #39361711938](https://github.com/gitfortian/ontoforge/actions/runs/39361711938) FAIL 于 **幂等重用负例把 Bridge 双 FK 改成同列，先被 422 格式门禁拒绝，未命中想验证的 409 持久化摘要冲突**。负例修订为：保持 schema 合法，仅改变 Bridge fingerprint 64 位摘要，因此能验证持久化 key 的已有 receipt 与请求 digest 比对；原本 422 入参拒绝另有独立覆盖。

修订后 [S0 PG17/HTTP #39361998740](https://github.com/gitfortian/ontoforge/actions/runs/39361998740) **backend + frontend completed/success**，原 M8.1 全量 FK 故事未降级。此前失败记录保留并标明真实原因，不能称首次就通过。

## 3. 不能混淆的架构与验收边界

- **元数据与 SourcePG 不存在跨库原子事务**。只读 FULL 的证据在保存时锁定为一次当时可观察的来源/版本，未来权限撤销、DDL 或内容变化需在 get/traverse/page 的当前身份/来源资格再验证。源内容 freshness 不因 schemaFingerprint 变成不可变数据快照。
- **没有预生成完整 LinkFact 或 Bridge ObjectType**，仅冻结受控连接关系证据。真正的双向 ObjectSet Traverse/根局部 PARTIAL/COUNT_DISTINCT/SUM/分页 JWT cursor/T3 还必须在新独立业务黄金故事中验证。
- 当前 FULL 将三表行有界载入本次请求内存计算九桶，**没有**通用跨来源 JOIN、图引擎或 Action。授权范围只包括同 SourcePG、本地快照、一个显式桥表。
- 尚无生产 React/真实用户界面的服务器接线（T4）；已签收静态高保真 HTML 与 Chromium 原型只供目标交互对照，不能代替 React。
- 发版与 Windows 11 PG 实机 UAT 独立于 CI；#103 必须继续 Draft 直到 T3–T5、独立全功能 Review 及明确合并准入。

## 4. 接下来统一纵向实施出口

**T3 · ObjectSet Bridge 一跳执行（下一个产品可用能力）**：在已有 local-query-v2 真实编译/执行架构里按 revision.mapping_mode 路由到 BridgeTable 三方同源只读扫描；按完整类型化 ObjectRef 去重、只按本次 root 相关孤儿产出 PARTIAL/KNOWN_SUBSET；OUT/IN 保留 LinkFact 定义方向；COUNT_DISTINCT / SUM 按去重输出对象求值；生成三方 SourceReadRef、当前 ACL/DDL 重核和撤权不可复活的冻结 cursor。覆盖 M7/M8.1/M13 Agent 不回归。

**T4**：真正 React 工作台按 UX-High H01–H12 接线，真实 HTTP 错误与 CAS 恢复、375px/键盘/超时；不把静态 HTML 直接贴成生产页。

**T5**：补齐 R1 B01–B18 与 R2 C01–C12 实际 PG17+HTTP+React Chromium、预算扇出、权限变化、幂等并发与 Windows/UAT 剩余风险，独立深度 Review，同 head 全 Required Quality，通过后再 Ready/merge。

**状态：M8.2 T1 数据库结构 + T2 三方 FULL/不可变修订 已有代码及真实 PG17/HTTP 验收；T3/T4/T5 尚未实现或未验证。**
