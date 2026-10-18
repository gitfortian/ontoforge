# M6 Object Runtime · UX-Low 原型签收记录

**状态：UX-Low 已由产品所有者签收（2026-10-18）。**
**结论：四项交互原则全部认可，无阻断性 UX-Low 修改项；允许进入 UX-High 高保真设计。**
**边界：本记录只签收交互原型，不代表 OFIP-0008 获得维护者批准，也不授权生产代码实施。**

## 签收依据

- 原型：[M6 Object Runtime UX-Low](../prototypes/m6-object-runtime-lowfi.html)
- PR：[PR #64 — M6 Object Runtime 按键读取 UX-Low](https://github.com/gitfortian/ontoforge/pull/64)
- 需求与领域合同：[Issue #45](https://github.com/gitfortian/ontoforge/issues/45)
- 设计决策：[OFIP-0008 — M6 通用 Object Runtime](../proposals/OFIP-0008-M6-通用ObjectRuntime.md)
- 自动化证据：
  - [M6 UX-Low Chromium：5/5 通过](https://github.com/gitfortian/ontoforge/actions/runs/39098922723)
  - [全量原型回归：132 tests 通过](https://github.com/gitfortian/ontoforge/actions/runs/39098922643)
  - [Required Quality：9/9 jobs 通过](https://github.com/gitfortian/ontoforge/actions/runs/39098922606)
  - [Architecture / Docs Gates：通过](https://github.com/gitfortian/ontoforge/actions/runs/39098922545)

## 产品所有者签收意见

1. **对象身份：认可。** `ObjectRef` 由 package、ObjectType、identity domain 和规范类型化键共同确定；Binding revision 属于读取证据，不是业务身份的一部分。
2. **属性值语义：认可。** `PRESENT`、来源真实 SQL `NULL` 与未映射 `MISSING` 分开；`0`、`false` 和空字符串可作为有效值。
3. **错误与恢复：认可。** 对象不存在、来源不可用、无权、身份/属性冲突、结构漂移和预算耗尽采用不同结果与恢复路径；失败不返回伪造的部分成功对象。
4. **来源证据：认可。** 展示 Snapshot/Binding 版本、执行时间和来源引用；缺少可靠业务水位时明确使用 `freshness=UNKNOWN`。

## 用户主线

1. 从已保存的 Local Snapshot / Binding revision 进入对象读取。
2. 输入规范业务键，可选指定属性子集。
3. 成功时显示完整 `ObjectRef`、类型化属性值和来源证据。
4. 一致重复物理行可以折叠；不一致重复行、必填 NULL 和类型冲突拒绝成功返回。
5. 失败状态给出安全错误码和恢复建议，不泄露对象值或原始 SQL。

## UX-Low 验收范围

- [x] 交互主线、对象身份和 Binding revision 表达通过产品评审。
- [x] `PRESENT` / `NULL` / `MISSING` 及 falsey 值表达通过产品评审。
- [x] 404 / 403 / 409 / 422 / 429 / 503 等失败语义与恢复路径通过产品评审。
- [x] 来源证据和 `freshness=UNKNOWN` 表达通过产品评审。
- [x] 专用 Chromium 测试 5/5 通过；全量原型与 Required Quality 门禁通过。
- [x] 产品所有者明确认可以上四项。

## 下一步：UX-High

UX-Low 不需要因本次意见额外改动交互原则；下一阶段将把相同的已签收合同深化为 UX-High，包括更完整的对象摘要、属性表格与类型标识、来源证据分组、错误恢复操作、加载/空状态和响应式布局。UX-High 将独立评审、独立签收，并补充 Chromium 回归。

## 严格实施门禁

- 当前页面使用合成 supplier 数据与 Mock 状态，不连接真实 API、Spring Boot 或 PostgreSQL。
- 本原型 PASS 不能替代生产功能的 PG17 / HTTP / React / Chromium 验收。
- 不创建全量 Object Store，不实施 M7 ObjectSet、M8 LinkFact、Action 或 Agent。
- **在 UX-Low 与 UX-High 均获产品所有者签收，且 OFIP-0008 依仓库治理流程取得维护者显式 `[VOTE] Accepted` 前，不启动 M6 生产 Java/React/Flyway 实施。**
