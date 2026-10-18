# M6 Object Runtime · UX-Low 原型评审说明

**状态：** UX-Low 候选，等待产品评审；不是生产实现，不代表 OFIP-0008 已获 Accepted。

## 本轮交付

- 独立静态原型：\`doc/prototypes/m6-object-runtime-lowfi.html\`
- Chromium 交互测试：\`doc/prototypes/tests/test_m6_object_runtime_lowfi_browser.py\`
- 依据：Issue #45 的 2026-10-18 需求收敛与 OFIP-0008。

## 用户主线

1. 从已保存的 Local Snapshot / Binding revision 进入对象读取。
2. 输入规范业务键并可选指定属性子集。
3. 读取成功时显示一个带完整 ObjectRef 的对象、类型化属性和来源证据。
4. \`PRESENT\`、来源真实 \`NULL\`、未映射 \`MISSING\` 分开展示；\`0\` 和 \`false\` 保留为有效值。
5. 来源不存在、身份冲突、属性冲突、结构漂移、预算耗尽、无权与来源不可用分别返回独立错误，不输出部分对象。
6. 来源证据明确模型/Binding revision、执行时间、来源读取引用和 \`freshness=UNKNOWN\`；不把 SQL 执行时间误当业务数据新鲜度。

## 原型边界

- 页面内置合成 supplier 数据与可切换演示场景。
- 不连接 Spring Boot、PostgreSQL、JWT/OIDC 或真实来源。
- 原型 PASS 只能证明交互状态可操作、文案和结果互斥；不能替代 M6 的真实 PG17/HTTP/React/Chromium 生产验收。
- 不创建对象表、不做全量 Object Store、不做 ObjectSet/LinkFact/Action/Agent。
- OFIP-0008 继续 \`Decision: Pending\`；在维护者按仓库流程显式记录 \`[VOTE] Accepted\` 前，不得开始 M6 生产 Java/React/Flyway 实施。

## 自测

从仓库根目录执行（需已安装 Playwright 和 Chromium）：

~~~bash
python -m unittest doc.prototypes.tests.test_m6_object_runtime_lowfi_browser -v
~~~

当前 PR 的 CI 证据以工作流真实结果为准。截图可通过 \`UX_CAPTURE_SCREENSHOTS=1\` 启用，输出目录由 \`UX_SCREENSHOT_DIR\` 指定。

## 产品评审关注点

- 用户是否能理解“对象身份”与 Binding revision 的区别？
- 是否清楚区分不存在、无权、来源不可用和冲突？
- 属性子集请求是否仍显示完整 ObjectRef，并始终核验身份键？
- \`NULL\` / \`MISSING\` 是否容易区分？
- \`freshness=UNKNOWN\` 是否诚实且可理解？
- 失败后返回 M5 修复绑定是否为清晰恢复路径？
