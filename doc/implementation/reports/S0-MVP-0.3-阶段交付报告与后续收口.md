# OntoForge 交付报告规范与 S0-MVP-0.3 阶段报告

> 状态：2026-10-16；PR #11 Draft / Not Released。此报告只记录已核实证据，不代替真实 Windows 11 本机验收。

## 一、强制交付规则（从本轮开始）

每项任务/PR 完成时必须在 `doc/implementation/reports/` 中提交一份报告，并在 PR 描述中提供直达链接。未实际运行的测试一律标注 NOT RUN，不能推断 PASS。完成代码、通过 CI、通过产品验收、已合并、已发布为不同状态，严禁混用。

报告必须逐项包含：
1. **目标与范围**：迭代号、PR、基线与最终 Head SHA、用户故事及不做事项。
2. **已交付功能**：以用户可实际操作的行为描述，注明前端入口、API、数据持久化及操作步骤；禁止只罗列类名。
3. **范围外与未交付**：占位、受限能力及安全边界，明确声明。
4. **缺陷与修复**：问题、严重度、原因、修复位置、回归用例。
5. **证据矩阵**：测试对象、环境、执行 SHA、运行 URL、结果 PASS/FAIL/NOT RUN、与用户操作的对应关系。
6. **风险与阻塞**：P0/P1/P2、未跑的本机步骤、是否允许合并/发布。
7. **进度与下一步**：本轮完成、未完成、下轮优先级、交付物、验收与退出条件。
8. **维护者验证**：Windows 11 / PostgreSQL 本机命令、预期结果、测试数据副作用与清理提示（不可逆项必须明确）。
9. **最终决策**：Draft / Ready / Merged / Released 之一及决策依据；由维护者签收的门禁不得以 Linux CI 自动代签。

原则：一个 PR 交付一个用户可验证的纵向能力；每次结束后附报告；严禁把“测试数量”替代“功能验收”。

## 二、本轮：S0-MVP-0.3 产品完成说明

- **PR**：https://github.com/gitfortian/ontoforge/pull/11
- **已核对 Head**：`ba0cb7063b2450af197a0d0fd670a23b1e41df8c`（本文件提交前）
- **阶段判定**：核心纵向链路代码已完成并通过该 SHA 的自动化；Draft / Not Released。
- **用户可操作功能**：在真实 React 工作台编辑草稿并校验；按 revision 冻结 Native 快照；按 key/revision 重新找回；浏览只读历史及详情；跨多页选择 From/To 对比结构变化；处理重复冻结与未知回执。
- **后端合同**：PostgreSQL V3 不可变持久化、JCS 规范字节与 SHA-256 回证、freeze 重放/竞争控制、keyset 历史分页、对象/属性结构 Diff。当前只允许 LOCAL_SNAPSHOT_ONLY。
- **本轮已修复缺陷**：浏览器断言被跳过；历史跨分页选择丢失；并发异 key 同 revision 负例缺失；游标及伪审批字段负例不足；TypeScript 语法问题。
- **自动化证据**：Java+PG17 14/14、Chromium 10/10，Required Quality https://github.com/gitfortian/ontoforge/actions/runs/38870992289 及 E2E https://github.com/gitfortian/ontoforge/actions/runs/38870992283，皆针对上述 Head；追加本报告后的新 SHA 必须重新检查 CI。
- **未完成**：全面 RFC8785/JCS 跨语言边界 fixtures；持久化损坏场景的 500 完整性拒绝；classification、ENUM、PK 及其他结构差异/不支持定义负例；Windows 11 本机 PostgreSQL 在真正应用重启后的持久验收（NOT RUN）。
- **严格非范围**：生产认证/授权/隔离、ReviewDecision、SchemaRelease/ReleaseDigest、Activation、Agent/工业 Action 均 NOT IMPLEMENTED。

## 三、下一步执行：S0-MVP-0.3H 高风险加严与签收

直接在 PR #11 现有分支补强，不另开割裂核心功能的新 PR：
1. JCS：补充 RFC8785 官方向量的关键边界（指数、负零、Unicode/UTF-16、转义、非法 surrogate、重复键行为），Java/Node/Python 对相同 fixtures 的字节与 SHA 比对；对无法合法规范化的输入需稳定拒绝，确认与 RFC 要求一致。
2. 完整性故障注入：在隔离 PG 测试中以受控方式模拟 canonical BYTEA、sha256、JSONB 镜像损坏，验证详情/列表/比较不泄露“有效快照”，返回明确 500 SNAPSHOT_INTEGRITY_FAILURE；验证实验环境在测试结束后清理或销毁。不得在用户真实 PG 上解除不可变保护。
3. Diff/Unsupported：覆盖分类变化、枚举集合增删与顺序、主键引用更换、属性必填变化、类型标识变更，以及 LinkType / Binding / Action 等不支持资产的拒绝与原数据不变。
4. 整套 PG17/Java/React/Chromium/Native/Docs Gate 在**最终 Head** PASS，回归不能绕过测试或仅复用历史 SHA。
5. Windows 11 本机独立门禁：先在**隔离测试数据库** Exercise，真实停止并重启 Spring Boot（数据库不删除），再 Recheck + Edge 人工验证。Exercise 会留下不可变草稿与两份快照，必须使用可抛弃测试库。
6. 提交最终报告，逐一标明 PASS/FAIL/NOT RUN 与证据。若 Windows 本机仍 NOT RUN，保持 Draft，不发布、不自动合并。

**本报告不等于 0.3H 已实现或验收通过**。工作状态以对应代码 SHA 和 CI 运行记录为准。

## 四、后续路线

- **当前：0.3H**——补强快照安全边界与完成独立验收，目标：S0-MVP-0.3 可签收。
- **随后：0.4 产品/合同评审**——先从已验收 Native 快照到受治理的数据绑定与 ObjectSet 的需求边界讨论，不在本轮偷渡实现；按 OFIP 多轮评审→原型→验收→纵向开发。
- **未来：审批/发布/激活及工业动作**——必须先明确权限和执行安全合同，不与 LOCAL_SNAPSHOT_ONLY 混为一谈。
