# M8.2 · BridgeTable UX-High 独立交互/产品 Review

> 日期：2026-10-19 · **状态：技术复核候选，待同一最新 head 所有 CI 完成 + 产品 Owner 正式签收**。  
> 唯一交付 [Draft PR #103](https://github.com/gitfortian/ontoforge/pull/103)；[UX-Low 明确产品签收](https://github.com/gitfortian/ontoforge/pull/103#issuecomment-6268995562)，并非本 UX-High 自动签收。无新的设计/测试/实现 PR，M8.3 仍另留未来唯一一个功能 PR。

## 1. 审查范围与依据

- **本期仅审真实高保真产品交互**：[全新 UX-High HTML](../prototypes/m8-2-bridge-table-highfi.html)、[真实 Chromium 测试](../prototypes/tests/test_m8_2_bridge_table_highfi_browser.py)、[专用 CI 及截图](../../.github/workflows/m8-2-bridge-highfi-browser.yml)、[高保真 H01–H12 签收矩阵](M8-2-BridgeTable-UX-High评审与签收矩阵.md)。
- **跨合同核对**：[M8.2 R1 产品语义](../design/M8-2-BridgeTable-R1-产品范围与LinkFact证据评审.md)、[R2 API/版本/三方来源合同](../design/M8-2-BridgeTable-R2-API版本持久化查询与三方证据合同评审.md)、已合并 M8.1 用户故事与当前仓库导航/高保真工作台风格。
- **当前代码事实**：仅提交 doc/prototypes、doc/implementation、浏览器测试和 CI；未实现 M8.2 生产 Java/Flyway/React、未合并、未发布。浏览器里 SOURCE/TARGET/BRIDGE 的读权限、REPEATABLE_READ、CAS、cursor/指纹为**固定合成数据**；不能证明真实服务端准入。

## 2. H01–H12 业务体验独立复核

| 范围 | 审核判断和可证伪测试 | 结果分级 |
| --- | --- | --- |
| 入口和导航 H01/H11 | 沿用本体建模/关系类型与 ObjectSet，不增加孤立“桥表工具”。首屏同时解释 SOURCE → Bridge → TARGET 和 M:N；五步对应既有工作流，单主操作 | 静态原型有正向设计证据，产品仍待审美/负担签收 |
| 两端身份 H02 | 两个 M5 Binding revision 与 Bridge 两 FK 分别选择；跨源、错 Snapshot、错向列、非法类型有负例；不暗自生成第三 ObjectType | 原型测试目标 |
| FULL + 质量 H03 | FULL 前端票据在 mapping/场景变化立即失效；物理行/null/orphan/duplicate 七桶互斥，总预算拒绝；慢校验改变输入取消旧回包 | 原型测试目标 |
| CAS/UNKNOWN H04 | 保存成功不能重复生成 revision；409 保留修订；UNKNOWN 必须原 operation 回读才能进查询；重置时 dirty 草稿/UNKNOWN 先确认 | 原型测试目标 |
| 查询身份/聚合 H05/H06 | OUT/IN 改变 root 与输出属性，不反转 LinkFact；pair 去重，已知子集 PARTIAL SUM 文案明确“非全量”，真空关系 COMPLETE | 原型测试目标 |
| 慢请求/乱序 H07/H08 | generation token 防止旧慢回包覆盖新快请求；显式取消、变更根条件、离开查询或 FULL 工作区必须撤销当前请求；不把 LOADING 当结果 | 原型测试目标 |
| 来源证据 H09/H10 | 正确展示三份 ReadRef/版本/指纹；撤权、漂移、TTL 隐藏原结果 DOM 并退休旧快照；权限失败不泄露三方来源卡片 | 原型测试目标 |
| 小屏/键盘 H12 | 375/768/1024/1440 各宽度与 Tab/panel 关联、左右键切换、可见失败聚焦 role=alert、重置确认 | 原型测试目标 |

## 3. 本轮独立技术审查所发现并已处理的真实交互阻断

| 严重度 | 缺陷 / 容易造成的产品风险 | 同 PR 修订与独立证据 |
| --- | --- | --- |
| P1 | 新增键盘页签交互初始化使用单一 DOM 元素调用 `.forEach`，导致高保真全部业务按钮无法运行；前两轮 Chromium 的 54/54 测试全部在初始化前失败，不是测试环境偶发 | 确认实际 CI pageerror 为 `$(...).forEach is not a function`，逐一改为 `querySelectorAll` 对应的 `$$()` 集合工具；加入初始化失败**立即给出 pageerror**的测试前置，不再 55 次超时。失败的 GitHub Actions 原始记录保留，不能计为通过 |
| P1 | 静态脚本的文本修复首次使用字符串替换 `$$`，被 JS replacement 规则解析为单个 `$`，造成同样缺陷残留 | 改用精确字面 `split/join` 保留 `$$`，提交前检查三个实例真实已变更。新同 head Chromium 成功才视为关闭 |
| P1 | 重置高保真演示会直接丢弃尚未保存配置、UNKNOWN 回执或未完成查询 | 增加受控确认；取消保留、确认重置；新 Chromium 正反例 |
| P1 | 慢 FULL/慢查询期间离开工作区，旧响应可能在新页面完成并影响当前证据 | 跨导航在**确认成功后**取消相应异步 generation；新 Chromium 延迟回包反例 |
| P2 | 错误状态的焦点与无障碍播报不明确 | 当前可见错误用 `role=alert`、`tabIndex=-1`、focus 定位；Chromium 验证 |
| P2 | 复杂桥表 FULL 质量、跨方向 SUM、三角色指纹密度对非专业用户可能仍偏高 | 高保真展示逐层渐进证据；需产品 Owner 检视 H01–H12 并按反馈修订 |

> 这份 Review **没有代产品 Owner 直接签收高保真**。仅基于可见源代码和真实 Chromium 自动化交互；网页视觉审美、业务可理解性、正式 React UI 需要产品 Owner 后续判定。

## 4. 尚未解决、不能用静态 prototype 代替的交付门槛

- **生产同事务和预算**：真正同一 PostgreSQL 17 READ ONLY/REPEATABLE_READ 的三表读取、三份 Select grants/schemaFingerprints、10k 物理行/Join/8 MiB/3s 压力、错误零载荷等 M8.2 B01–B18 尚未实施。
- **生产版本闭包**：LinkBinding v2 Bridge 附属来源/互斥持久化迁移、V10 历史 digest 与 operation receipt 不被破坏，真实 Head CAS 和 Unknown 回执重放尚未实现。
- **真实前端工程**：嵌入现有 OntoForge React 路由、主题组件、权限/错误 API、session 与端到端 Playwright，不允许直接复制静态 HTML 代替正式开发。
- **治理签收**：高保真产品正式签收单独于 UX-Low；签收后必须通过 OFIP-0010 的**M8.2 独立接受/实施决策**，绝不沿用 M8.1 的 Accepted 作为授权。

## 5. 当前预审建议

产品主线从模型定义→桥表来源→FULL→不可变修订→单跳查询→三方来源恢复是合理的；低保真已正式签收，无需重复评审架构层 R1/R2。**高保真建议在全部同 head Required Quality、High Chromium、低保真/合同/Docs Gate 通过且没有 P1 后，提交产品 Owner 按 H01–H12 独立签收**。CI 失败保留为正式问题证据，未关闭前不得宣称页面通过。

**PR #103 必须保持 Draft；不得启动 M8.2 生产代码，不能关闭或并掉 M8.1 Windows UAT #102 门禁。**
