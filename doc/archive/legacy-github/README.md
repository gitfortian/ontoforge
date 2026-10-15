# OntoForge GitHub 项目迁移记录（2026-10-15）

> 迁移来源：`gitfortian/ontology-system` → `gitfortian/ontoforge`。归档由原始 GitHub PR 描述及评论生成，仅用于保留讨论证据；并不等同于迁移 GitHub 原生 PR、Review 或 Actions 实体。

## Git 数据核验

- 原始 `main` SHA：`0ae5b190c713690cf708547d89660ac9b7c2e7f2`；迁移后新仓库同名提交 SHA 完全相同。
- Git 全量可达提交对象及 13 条 heads 已由 GitHub runner 推送，新旧全部分支的 head SHA 均一致。
- 54 个文件的原始根 Git Tree SHA：`e7484edabc4efb6c68ba100bf6963eb1901ab696`；新仓库相同。
- 原仓库没有发布的 GitHub Release；迁移时未发现独立 Git Tags。
- 唯一独立 Issue #1 已复制到新仓库 [Issue #1](https://github.com/gitfortian/ontoforge/issues/1)。

## 原有 PR 描述与讨论归档

| 原 PR | 标题 | 原合并提交 | 归档 |
|---|---|---|---|
| #2 | docs: 本体平台架构 V1.1 — OMBOK + GB/T 48000.3/4 专项评审 | [`8ac89ab827`](https://github.com/gitfortian/ontoforge/commit/8ac89ab827ec4178899e9e54822eaa6691cfb104) | [PR #2](./pr-02.md) |
| #3 | docs: 核心本体元模型与 DSL/AST 契约 V0.1（基于架构 V1.1） | [`b93eefc6dd`](https://github.com/gitfortian/ontoforge/commit/b93eefc6ddfa54b9b1e93b599c71d3bf35415c9a) | [PR #3](./pr-03.md) |
| #4 | Docs/ontology architecture v1.1 ombok standard | [`7759f76fa9`](https://github.com/gitfortian/ontoforge/commit/7759f76fa955276cf5508d395293ab0ed2957068) | [PR #4](./pr-04.md) |
| #5 | docs: 本体产品架构 V1.2 — Palantir 对标的企业原生本体路线 | [`287bf25db7`](https://github.com/gitfortian/ontoforge/commit/287bf25db7ae39401384d27fe55b513ca7d9c41f) | [PR #5](./pr-05.md) |
| #6 | research: Palantir Ontology 运营内核深度蒸馏与架构冻结证据 | [`23c43aabef`](https://github.com/gitfortian/ontoforge/commit/23c43aabef9604729a317fee6408ff21bf07735a) | [PR #6](./pr-06.md) |
| #7 | research: POC-Query / POC-Edit 两轮实测与生产选型门禁 | [`c3184156a4`](https://github.com/gitfortian/ontoforge/commit/c3184156a4fafa551cd0dffc3a9f2db977a3f907) | [PR #7](./pr-07.md) |
| #8 | research: PostgreSQL 17 ObjectSet / Outbox 真实运行 POC 与选型证据 | [`13f65dcb53`](https://github.com/gitfortian/ontoforge/commit/13f65dcb534a9d2d0a035914c686cafefa62ebc9) | [PR #8](./pr-08.md) |
| #9 | research: ObjectSet 高扇出、授权代价与 Action 竞态的最终架构选型 Gate | [`a73f486a3e`](https://github.com/gitfortian/ontoforge/commit/a73f486a3e43998b2baf753fa0438357ecfa49c8) | [PR #9](./pr-09.md) |
| #10 | design: Native Ontology DSL/AST V0.2 契约、制造业黄金样例与架构签收 Gate | [`4e10fdc922`](https://github.com/gitfortian/ontoforge/commit/4e10fdc922ef4537e3288ac9870ace7d72de74b9) | [PR #10](./pr-10.md) |
| #11 | docs: 统一本体产品架构 V1.3，清理过期设计和 POC 文档 | [`2b7a535fab`](https://github.com/gitfortian/ontoforge/commit/2b7a535fabf48e81ed4b3dbe88fe77e3f0e07736) | [PR #11](./pr-11.md) |
| #12 | feat(s0): S0-MVP-0.1 最小可运行底座（PostgreSQL + Spring Boot + React） | [`8173eac684`](https://github.com/gitfortian/ontoforge/commit/8173eac684c4309f73df1271171613259ca458bb) | [PR #12](./pr-12.md) |
| #13 | refactor(s0): 对齐 Yak Ops 模块命名，统一 Maven Reactor 与 Boot/UI 工程结构 | [`29c5c06163`](https://github.com/gitfortian/ontoforge/commit/29c5c06163d8568f7fd86788170ea7673152d094) | [PR #13](./pr-13.md) |
| #14 | refactor(brand): OntoForge 砺元产品品牌、Maven/Java/前端统一命名 | [`0ae5b190c7`](https://github.com/gitfortian/ontoforge/commit/0ae5b190c713690cf708547d89660ac9b7c2e7f2) | [PR #14](./pr-14.md) |

## 历史 CI 记录

已记录原仓库 **72 次** GitHub Actions 的运行 ID、工作流名称、分支、提交、开始时间、状态、结论等元数据，详见 [legacy-actions-runs.json](./legacy-actions-runs.json)。

**注意：原 Actions 的执行日志、下载附件/Artifacts、GitHub 原生 PR 审核线程、审批状态、原始 issue comment URL 与 PR 编号实体并未通过 Git 迁移。** 若将原仓库删除，原始 Actions 链接与 PR 页面将失效；若需要审计级留存，请先下载 Actions Artifacts/Job Logs 并单独归档，或保留旧仓库为只读历史仓库。原 Git 提交树与可达提交历史不受影响。

## 项目迁移后的日常开发

- 日常开发使用新仓库 `https://github.com/gitfortian/ontoforge.git`，旧仓库停止新增 PR/Issue。
- 新仓库 Maven `ontoforge` / Boot `ontoforge-boot` / UI `ontoforge-ui` 名称保持不变。
- `README.md`、V1.3 和研究文档中的原仓库 PR / Actions URLs 是历史证据链接，**删除旧仓库会让其原始页面不可访问**；可使用本归档中的 PR 描述和已保存的原提交 SHA 追溯技术决策。
- 不建议在未确认历史 Actions 附件和审核合规要求之前删除源仓库。
