# M6 Object Runtime · UX-High 原型评审说明

**状态：UX-High 已于 2026-10-18 获产品所有者签收。**
**前置：UX-Low 已签收（2026-10-18），见 [UX-Low 签收记录](M6-ObjectRuntime-UX-Low评审说明.md)。**
**范围：高保真静态交互原型，不包含生产 Runtime 实现。**

## 本轮交付

- 高保真原型：`doc/prototypes/m6-object-runtime-highfi.html`
- 真实 Chromium 回归：`doc/prototypes/tests/test_m6_object_runtime_highfi_browser.py`
- 专用原型门禁：`.github/workflows/m6-object-runtime-lowfi-browser.yml`（同时回归 UX-Low 与 UX-High）
- 依据：[M6 Issue #45](https://github.com/gitfortian/ontoforge/issues/45)、[OFIP-0008](../proposals/OFIP-0008-M6-通用ObjectRuntime.md) 与 UX-Low 已签收合同。

## 高保真设计重点

1. **三栏工作台**：左侧请求上下文与属性选择，中间对象/错误结果，右侧读取证据与质量信封。
2. **对象身份可读**：对象名称与完整 `ObjectRef` 分开呈现；业务键、身份域和 Binding revision 不混成一个字段。
3. **属性表格可核验**：展示业务名称、稳定属性标识、类型、值状态、类型化值与来源列；`PRESENT` / `NULL` / `MISSING` 用不同状态标签。
4. **请求上下文固定**：明确 Snapshot、Binding revision 和 identity domain；切换旧 Binding 时身份域和证据版本同步变化。
5. **错误恢复可执行**：404、503、身份/属性冲突、结构漂移、超预算、非规范键、权限拒绝分别提供错误说明和恢复建议。
6. **证据单独分组**：模型与绑定、执行信息、来源读取引用、质量信封分组呈现；没有可靠业务水位时 `freshness=UNKNOWN`。
7. **响应式布局**：桌面三栏，窄屏折叠为单栏，属性表格允许局部横向滚动而不是撑破整个页面。

## 验收证据

- UX-High 专用测试覆盖成功读取、属性子集、错误状态互斥、Binding revision 与 identity domain 一致性、重置和移动端布局。
- 工作流会同时运行 UX-Low 与 UX-High 的 Chromium 回归，并上传截图 artifact。
- 测试 PASS 只证明静态原型交互，不证明真实 API、PG17、权限或 Runtime 功能已实现。

## 产品所有者签收记录（2026-10-18）

产品所有者明确确认“页面签收，继续实施”。UX-High 原型正式签收，设计范围包括对象身份、属性值语义、错误与恢复、来源证据、视觉层级及窄屏布局。

自动化证据：[M6 专项 Chromium 13/13 PASS](https://github.com/gitfortian/ontoforge/actions/runs/39101690717)；[全量原型回归 PASS](https://github.com/gitfortian/ontoforge/actions/runs/39101690740)；[Required Quality 9/9 PASS](https://github.com/gitfortian/ontoforge/actions/runs/39101690739)；[Architecture / Docs Gates PASS](https://github.com/gitfortian/ontoforge/actions/runs/39101690718)。

## 产品签收范围

- 对象身份与 Binding revision 是否足够清楚？
- 属性表格是否让类型、值状态、来源列一目了然？
- 右侧来源证据是否能帮助用户判断“本次读取用了什么版本”，又不会误认为 freshness 已保证？
- 各错误的恢复建议是否明确、可执行且不泄露来源细节？
- 三栏信息密度、视觉层级和窄屏布局是否符合正式产品工作台预期？

## 不可越过的决策门

- UX-High 已获产品所有者签收；原型可归档为已签收设计基线。
- OFIP-0008 仍为 `Decision: Pending`。
- **在 UX-Low 与 UX-High 均获产品所有者签收，且维护者依仓库治理流程显式 `[VOTE] Accepted` 前，不启动 M6 生产 Java/React/Flyway 实施。**
