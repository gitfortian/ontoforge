# OFIP-0015：M13 Agent 只读本体上下文与受限查询接口

**Status:** Discussion

**Discussion:** [PR #58 — [DISCUSS][OFIP-0007–0016] M5-M14 需求 Review 与方案设计](https://github.com/gitfortian/ontoforge/pull/58)；需求讨论见 [M13 #52](https://github.com/gitfortian/ontoforge/issues/52)。

**Decision:** Pending（须维护者显式记录决定、日期和 permalink）

**Implementation:** Not Started

**Release:** Not Released

**Related:** [OFIP-0001](./OFIP-0001-需求提案文档治理与CI证据门禁.md) · [V1.3 候选](../本体产品总体功能与技术架构设计_v1.3.md) · [Native V0.2 候选](../design/核心本体元模型与Native-DSL-AST契约_v0.2.md) · [ObjectSet Query 候选 Schema](../design/schema/objectset-query-v0.2.schema.json) · [Result 候选 Schema](../design/schema/objectset-result-v0.2.schema.json) · [术语表](../砺元本体产品统一术语词汇表.md) · [本机核心优先决策](../implementation/MVP-核心优先与yak-ops认证复用决策-20261017.md)

> 原评审选择只读版本化 REST API。2026-10-19 用户要求增加可配置 `baseUrl / apiKey / model` 的 OpenAI 兼容模型调用；下列修订将受限只读问答纳入候选首切片，等待维护者明确决定。MCP 仍为后续独立适配，没有 Action 或模型代替服务端授权。

## Motivation

开发者希望让 Agent 先发现可见的类型和关系，再按需读取少量业务对象及证据，而不是把整份本体与数据库一次拼入 Prompt。
黄金故事：同一个只读客户端在两个自定义领域，发现类型→读取字段与关系→发起受限 ObjectSet→打开某对象的关系及来源证据，并逐步说明结论的范围和依据。
客户端更换领域时只改变资源引用和查询条件，不修改服务端硬编码对象名或编写领域专用工具。

审阅基线 `6968ea0804f57b2b82f237e37f812bcadb32f343`：

- [QualityMvpController.java](../../ontoforge-boot/src/main/java/io/ontoforge/boot/controller/QualityMvpController.java) 已有有限 ObjectSet 和关系追溯 HTTP 读能力，当前仍是固定三类型合同，不是通用 Agent Context。
- [B0AuthorizationService.java](../../ontoforge-platform/src/main/java/io/ontoforge/platform/identity/B0AuthorizationService.java) 已有真实 JWT/数据库包授权；[LocalMvpAuthorizationService.java](../../ontoforge-platform/src/main/java/io/ontoforge/platform/identity/LocalMvpAuthorizationService.java) 是本机无认证路径，不能把占位身份传播为委托权限。
- Native V0.2 查询候选明确拒绝 rawSql 和客户端 AuthorizationContext；[结构测试](../design/tests/test_native_v02.py) 不能替代运行时权限和泄漏验收。
- 当前没有通用 Context REST、MCP Server 或 Agent 运行器，不能把已合并的 M2/M3 宣称为本提案已交付。

Issue 的方向合理，但 API/MCP 同时首发会把协议生命周期、身份委托与语义查询一起扩成大切片。
本提案选择 REST 先行，复用 M7–M9 查询/漂移/证据合同及 M11 可核验资源链接；M13 不依赖 M12，可与沙箱 Action 并行。
正式对外访问额外依赖 M10 正式版本锁和独立身份/权限 Gate；本机只读开发不必等待企业登录或正式发行。
非目标：LLM 平台、自然语言转任意 SQL、RAG 索引、向量库、自动模型变更、Action、通用工作流/自主 Agent。外部模型调用仅限下述显式配置、固定资源范围的 OpenAI 兼容只读问答。

### 2026-10-19 修订：OpenAI 兼容只读问答，待决定

用户明确要求实际接入 OpenAI 兼容模型服务，而非仅导出 function/tool Schema；开发完成后提交 PR。本修订不回填为历史 Accepted，也不将开发请求伪造为 GitHub 投票。生产代码实施前仍按 [CONTRIBUTING](../../.github/CONTRIBUTING.md) 记录维护者对修订范围的显式决定；本文件继续 `Discussion / Decision Pending / Implementation Not Started / Not Released`。

代码复核基线为 `5d189bc4704fcf51405f2eb5fe78947c198801b2`：

| 依赖 | 已有实际能力 | 本切片处理 |
| --- | --- | --- |
| M4–M7 | `RuntimeTypeResolver`、`DataBindingService`、`ObjectRuntimeService`、`ObjectSetQueryService` 已有真实实现 | 直接复用服务、类型合同、身份校验与冻结分页，不复制 SQL 或创建空 Maven 模块 |
| M8.1 | `LinkTraverseService` 已有一跳关系查询及冻结分页 | 只公布真实支持的一跳能力；未绑定的 LinkType 不宣称存在 LinkFact |
| M9 | 已有内嵌来源/质量、TTL 和逐页复核；统一证据读取 API 尚缺失 | 在原冻结记录上补最小证据读取及同一复核路径；不等待完整 M9 漂移修复 UI |
| M11 | 已有对象与查询工作台；没有按 evidenceRef 打开同一执行的页面 | 首切片用 REST/确定性调用脚本核验同一执行，不承诺不存在的 Studio 深链；完整工作台收口后续交付 |
| M12 | Action 与只读问答独立 | 无依赖，不注册 Action 工具 |
| M10 / 正式安全 | 正式 Release、对外委托及安全验收未完成 | 不阻塞本机合成数据开发；继续阻断正式对外服务 |

本次黄金故事：开发者为服务端配置一个支持工具调用的模型，选择明确 Local Snapshot 和已保存 Binding 修订，提交自然语言问题；服务端发现类型、执行受限查询并返回解释与实际证据。切换第二个自定义领域时只更换资源引用，不增加领域专用代码。CLI/HTTP 是本切片的用户入口，不新增完整 Agent UI，也不把它宣称为 M11 UI 验收。

## Public Interfaces

### 两种明确的运行边界

| 形态 | 版本与身份 | 允许范围 |
| --- | --- | --- |
| 本机只读开发 | 显式 opt-in `mvp-local`；无可信用户身份；锁定 Local Snapshot + Binding 版本 | loopback 服务及隔离合成测试事实，开发者受限调用；不是可共享的委托令牌 |
| 正式对外服务 | M10 接受并实施的正式 Release + 受信主体/授权决策 | 只有通过独立安全验收后，才允许外部受授权客户端访问；本提案不默认开启 |

首切片开发 Profile 不允许通过请求参数选择正式模式、伪造主体、扩大数据库或改变监听地址。
现有 `mvp-local` 默认 deny 未登记路径，增加本地 Context 读端点须显式列举并保留拒绝其他方法/路径测试，不能 permitAll `/api/**`。
上述表格为原评审的边界。当前 [B1/B2 Session](../implementation/Yak-B1-本地账号Session接入.md) 及 [C1 空间授权](../implementation/Yak-C1-工作空间与模型包归属.md) 已有开发实现；修订允许在 loopback 本机 Session 模式下复用真实主体、CSRF 与存储的包权限。显式 `mvp-local` 仍无可信用户身份，不得冒充多用户隔离。模型供应商只收到专用 API Key 和受限消息，不能收到浏览器 Cookie、用户 JWT 或数据库凭据。正式对外委托仍未设计/验收。

### OpenAI 兼容模型协议与问答 API（本次修订候选）

本切片选择 **Chat Completions 非流式 function calling** 子集。协议依据为已核对的 [OpenAI Function calling](https://developers.openai.com/api/docs/guides/function-calling) 与 [Create chat completion](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)。配置的模型必须支持该子集；不承诺任意模型、Responses、SSE、旧 `function_call` 或供应商私有协议自动兼容。不预设模型名，不探测或替换用户配置的模型。

| 服务端设置 | 合同 |
| --- | --- |
| `ontoforge.agent.enabled` / `ONTOFORGE_AGENT_ENABLED` | 默认 `false`，不影响原应用启动；启用时校验必要配置与本机边界 |
| `ontoforge.agent.base-url` / `ONTOFORGE_AGENT_BASE_URL` | 明确包含 API 版本前缀，例如 `https://api.openai.com/v1`；只追加 `/chat/completions`，不自动重复 `/v1` |
| `ontoforge.agent.api-key` / `ONTOFORGE_AGENT_API_KEY` | 外部注入；只用于供应商 Bearer Header，不落库、不回显、不写请求/异常日志 |
| `ontoforge.agent.model` / `ONTOFORGE_AGENT_MODEL` | 外部指定的模型标识，原样发送；缺失时拒绝启用，不静默选默认模型 |

`baseUrl / apiKey / model` 只由部署配置提供，不允许请求或模型修改。地址拒绝 userinfo、query 和 fragment；远端使用 HTTPS，仅显式本机开发允许 loopback HTTP。禁用重定向和自动重试，不根据来源文本抓取 URL，也不把上游错误正文/认证头返回客户端。用户配置的兼容服务需要单独实测，Stub 验证不等于真实供应商验收。

候选入口为 `POST /api/agent/v1/runs`，请求固定为 `runVersion="local-agent-v1"`、`question`、`scope={snapshotId,bindingRefs:[{bindingId,revision}],linkBindingRefs:[{linkBindingId,revision}]}` 及可选的降低预算。`linkBindingRefs` 可省略或为空，此时不注册关系查询工具。Snapshot 所属包由服务端解析，逐一验证 Binding 的包、Snapshot、摘要及权限；LinkBinding 两端须匹配已锁定的对象 Binding 修订。一次运行锁定这些版本；模型不能扩展资源范围、选择 latest、伪造身份或替换用户选定修订。首切片为单次无持久历史问答，不接受调用方自带的 system/assistant/tool 消息、工具定义、模型参数透传或 AuthorizationContext。

回复含 `runVersion`、`requestId`、`status`、`reasonCode`、`answer`、实际 `executionIds`、`evidenceRefs`、版本锁、原始质量与预算、模型 usage（上游缺失时标未知）。`status` 限定 `COMPLETE / INCOMPLETE / FAILED`：协议完整且正常结束为 COMPLETE；令牌/轮数预算耗尽、拒答或过滤为 INCOMPLETE；授权、来源、上游协议/传输失败为 FAILED，并提供稳定 reasonCode。这里的 COMPLETE 只表示问答流程结束，不能替代底层数据质量。`answer` 是模型解释而非经验证的事实；实际证据目录由服务端生成，不能把模型编造的引用加入其中。未查得证据可回答能力说明，但不得宣称完成事实验证。模型文本不能改变结构化查询的质量、状态或完整性。

工具目录由服务端固定代码构造，仅包括范围内的类型发现、类型详情、绑定引用发现、M6 单对象读取、M7 ObjectSet/翻页、M8.1 一跳查询/翻页及证据读取。工具调用直接复用 Business 服务与当前可信身份，不经携带 Cookie 的内部 HTTP。工具名、参数字段、JSON 类型、版本和资源范围均在执行前校验；未知工具、rawSql、写操作、任意 URL 或客户端身份字段明确拒绝。工具描述来自代码，用户模型描述/源值只进入不可信数据区。

模型交互按 `messages + tools` 请求，保留供应商 assistant 的 `tool_calls`，随后追加带相同 `tool_call_id` 的 `role=tool` 结果，再请求解释或下一步。发送 `n=1`、`stream=false`、`parallel_tool_calls=false` 和 `max_completion_tokens=2048`；服务端独立校验，每次响应最多允许一个工具调用，超出即在任何工具执行前失败，不静默降级到旧参数。合法但不支持的工具/参数返回结构化失败，不能换成空结果；不可安全恢复的授权、来源、超时或上游协议错误终止运行。只有 `stop` 且协议完整时才能标问答完成；`length`、`content_filter`、拒答、缺失 choices、损坏参数、未知 finish_reason 或工具轮数耗尽均有明确未完成状态。

候选服务器上限：问题 8 KiB UTF-8；最多 6 次模型请求、5 次工具调用、同一时刻 1 次工具执行；每次模型响应最多 256 KiB；累计发给模型的请求体最多 1 MiB；单次模型请求最多 15 秒且整个 run 最多 60 秒。所有 HTTP/数据库调用使用剩余截止时间，不能仅在最后一轮检查超时。每次工具结果和最终完整信封各最多 64 KiB，按实际 UTF-8 序列化字节计数；超限明确失败，不裁剪事实后冒充完整。不以供应商 usage 代替本地预算检查。

开启模型调用还须明确配置可发送的隔离合成模型包范围；每次运行同时满足该范围和当前主体实际权限。默认不外发任何包；不能将匿名 `mvp-local` 当作扩大可发送范围的许可。模型请求包括问题、必要类型元数据和选定的工具结果；不会发送整个本体、连接凭据或其他包数据。默认不持久化 Prompt、回答和工具结果，不记录业务值；只记录必要 requestId、版本、调用计数、耗时及错误码。模型服务与应用均只在显式开发配置下连接，不自动调用真实供应商进行收费验收。

### 本切片证据读取的实现约束

M7 的 `sourceReadRef.id` 当前只是内嵌来源标识，不是可解析证据 URL。本切片将 `evidenceRef` 绑定原 `resultSnapshotRef` 与 execution，从原冻结记录读取版本、来源、质量和预算，复用与翻页相同的主体/Binding/模型/来源结构与资格复核；查询只有一页或只有聚合时同样可读。不得通过重新运行查询重建旧证据，普通源 DML 不改变原执行事实。

证据 TTL 不超过原结果的 5 分钟，进程重启失效；过期返回明确 `410`。复核失败终止旧句柄，恢复权限也不能复活已观察到拒绝的句柄。当前 `actorKey` 没有权限撤销代次，不能承诺检测两次请求之间未被观察到的“撤权再恢复”；正式服务前须补独立撤销合同。模型等待期间可能退出登录或被撤权，故每次工具读取、发给模型之前及最终交付前都重新核验当前账号/会话和包权限；不能只在最初 HTTP 过滤器中检查一次。

M6 即时读取没有冻结证据存储，首切片将实际 sourceReadRef、Binding、版本与质量作为结构化内嵌证据包含在最终响应中，不只传给模型；不承诺可再次打开的长期句柄。M8.1 证据读取须复用其冻结记录与原复核路径，不能创建缺少来源复核的 Agent 缓存。遇到尚无安全证据读取实现的工具，保持未注册并如实公布能力，不用假链接填齐目录。

### 版本化只读 API

候选前缀 `/api/context/v1`，HTTP POST 仅用于结构化只读查询，不承诺所有 POST 都是业务写操作：

| 接口 | 语义 |
| --- | --- |
| `GET /packages` | 受限分页发现当前可见模型包与可用版本；不返回不可见包计数 |
| `GET /types` | 在明确 sourceRef 下列出可见类型摘要，默认不加载所有字段/关系 |
| `GET /types/{objectTypeId}` | 读取该版本类型、可读属性、关系和支持的查询能力，名称/描述均为不可信内容 |
| `POST /queries` | 受类型检查和预算约束的 ObjectSet 请求，返回本次执行页及证据引用 |
| `POST /queries/pages` | 仅提交当前执行 cursor，复用 M7 翻页合同；不能另带 AST 或更换版本 |
| `POST /objects/read` | 复用 M6 ObjectReadRequest 的 bindingId、bindingRevision、typed key、propertyRefs；服务端从绑定解析 TypeRef，不把结构化 ObjectRef 塞进裸 ID URL |
| `GET /evidence/{evidenceRef}` | 读取经重新授权的证据；引用本身不是可转让读取凭据 |

TypeRef 统一为 `{sourceRef, objectTypeId}`，本机 sourceRef 为 `LOCAL_SNAPSHOT(snapshotId)`；`VALIDATED_DRAFT(draftId, revision)` 仅可作为显式开发预览，不用于长期保存的上下文句柄。
正式服务的 `SCHEMA_RELEASE(releaseId)` 变体须在 M10/OFIP-0005 接受新合同时显式加入；当前 V0.2 的 `schemaReleaseRef` 不能装入 Snapshot ID。
查询直接采用 M7 接受后的本机运行时请求合同，保留自己的版本；不声称现有 V0.2 候选 Schema 已支持本机 sourceRef。
本提案只增加上下文响应外壳，不另写一套 SQL、关系遍历或聚合实现。

候选 `ContextEnvelope v1` 至少含 contextVersion、requestId、明确 sourceRef/TypeRef、Binding 引用、queryDigest、executionId、数据、分页和 evidenceRefs。
Binding 引用统一 `{kind,bindingId,revision,digest,sourceId}`；不使用含混 bindingVersion 或将 revision 转为 Native SemVer。
返回完整性/精度/时效/answerScope/截断原因及允许的后续操作，未知/部分/过期不能被客户端适配层翻译成成功或空数组。
没有执行查询的类型发现不伪造 executionId；无来源水位时标明未知，不把当前时间伪装为来源 checkpoint。
授权决策、主体、权限版本/撤销代次在服务端产生并绑定执行，外壳只暴露必要决策引用，不接受来路身份字段。

### 错误、预算和分页

| 情形 | 客户端必须看到的结果 |
| --- | --- |
| 不存在或不可见资源 | 符合已接受的防枚举规则；不通过错误、数量或路径暴露被拒对象 |
| 类型/步骤/运算不支持 | 明确合同错误及可支持能力，不交给模型猜 SQL |
| 模型/Binding 漂移或旧句柄 | 明确失效，重新发现/复核，不自动替换版本 |
| 查询/输出预算耗尽 | 明确限额与截断/失败状态，不能伪装“全量结果为 0” |
| 会话/委托失效或撤权 | 停止继续读和翻页，使句柄失效；不回显旧缓存结果 |

所有客户端请求预算只能降低服务器上限；页大小、深度、访问对象数和超时沿用 M7 更严格值，不因 Agent 接口而放宽。
上下文外壳另设输出字节预算，首切片候选每页最多 64 KiB；元数据发现也分页并受预算限制。
首切片超出输出字节预算直接返回安全预算错误，客户端可缩小投影/页大小重查；不静默截断文本、对象或证据。
未来若支持表示层文本截断，必须另设 contextCoverage/truncatedFields，不能修改底层 ObjectSet 成员完整性或冒充未截断事实；本期不开放。
分页使用受完整性保护的游标，绑定查询摘要、固定版本、Binding、来源一致性状态、主体与权限代次、有效期；不可跨查询/主体重放。
游标和 contextId 都不能代替授权；沿用 M7 resultSnapshotRef 的有界冻结成员/值/计数，普通源 DML 不重算旧页，新 execution 才读取新事实。
结果与详细证据 TTL 同为 5 分钟、重启失效；后续每页仍按 M9 复核当前权限、来源资格/结构，断线 fail-closed；不承诺源 MVCC 历史。

## Proposed Changes

### 1. 渐进发现，不让模型猜能力

客户端先列可见包及版本，再按选定 sourceRef 获取类型摘要；只对选中的类型加载字段、关系和可用运算。
类型详情返回可读属性的类型、是否可筛选/排序/遍历以及必要的范围约束，不返回连接凭据和任意物理 SQL。
查询构造与校验复用真实 ObjectSet 服务；关系展开只允许已声明、已绑定和已获权的 LinkType。
查询结果保留对象身份去重及关系路径证据；ObjectType/LinkType 不因元数据存在就被当作已存在对象或 LinkFact。
来源证据采用服务端可核验引用；客户端回答中的引用须能回到同一执行和版本，不能只链接当前对象页。

### 2. 权限、缓存及撤销

正式服务从真实主体解析有效权限；客户端不得自填 role、workspace grant、policy decision 或 AuthorizationContext。
先过滤可见 Schema/属性/关系，再编译查询并检查每一步权限，最后输出与证据接口复核；不能只在末端删几列。
没有行/字段授权实现时，只能开放经过明确授权的完整隔离资源范围；需要部分授权的资源保持不可用，不能宣称包授权已经实现细粒度安全。
数量、聚合、关系端点、类型目录、错误消息及证据都继承相同可见性，不泄漏隐藏对象数或字段名。
缓存键至少包含可信主体、权限代次、版本、Binding、queryDigest；本机开发缓存标记无授权并禁止跨模式复用。
正式首切片不启用跨主体上下文缓存；每次读取、分页、证据展开及返回前重新验证权限有效性，撤权后停止未来交付并使句柄失效。
已交给客户端/模型的内容无法可靠撤回；必须如实限定撤权保证，不声称删除服务器缓存即可清除模型已有知识。
审计只记录必要请求标识、查询摘要、版本/预算/拒绝及证据引用，不保存令牌、密码、完整 Prompt 或默认记录敏感查询结果。

### 3. 内容不可信与只读边界

用户写的模型 label/description、源字符串、文档和证据内容均为不可信数据，不作为系统指令、工具定义、目标地址或授权声明执行。
返回结构化数据区及稳定字段，文字中的“忽略规则”“调用某 URL”不能改变工具参数、查询预算或客户端权限。
Context 不执行来源提供的脚本、SQL、模板或网页链接，不做由输入 URL 驱动的外网抓取；对象页链接来自服务端受控路由。
服务端拒绝业务写操作及 Action 工具名；即使 M12 已实施，也不自动出现在本只读能力目录中。
Agent 可请求受限查询、解释和候选建议，不能把解释文本回写模型、批准发布或转换为执行授权。
Context 合同与只读工具用确定性客户端验收；本次修订的模型交互必须另用真实 HTTP 协议 Stub 验证完整工具循环。真实供应商/模型体验仍是独立证据，未配置并实际执行时明确标记 `NOT RUN`。

### 4. 切片和协议演进

修订获接受后，首个实现 PR 交付本机只读“问题→模型工具调用→发现→查询→证据→解释”闭环及两领域复用，提供确定性 HTTP/CLI 调用示例和同一执行证据核验；不依赖尚未实现的 M11 深链。
不复制查询服务、不加空 SDK/MCP 模块，不在同一 PR 建完整 Agent UI。
业务工具分派和预算属于 `ontoforge-business` 的实际 Agent 用例；模型 HTTP 与 REST/安全适配属于 `ontoforge-boot`，部署设置校验属于 `ontoforge-configuration`。若模型客户端需要接口，必须有本切片真实消费者与测试，不提前建立通用插件/工作流框架。Core 不依赖 HTTP 或模型 SDK；本切片默认无数据库迁移，不创建未被调用的 `ontoforge-agent` Maven 模块。
正式对外服务作为下一独立切片，只有 M10 与身份/权限、委托和撤权负例通过后开放。
MCP 适配在 REST 语义稳定后独立评审支持的传输、身份绑定、会话生命周期、工具发现及错误映射；MCP 会话不等于业务授权。
适配器仅将同样的发现/查询/证据调用映射为受限工具，复用权限和预算；不新增旁路 SQL 或隐式自动执行。
若维护者选择 MCP 先行，应显式更改本决策并替换 REST 首切片，不能把两个协议同时塞回一个首发 PR。

## Compatibility, Deprecation, and Migration Plan

- 保留现有 M2/M3 固定质量端点和原 UI；通用 Context 以 `/v1` 新合同发布，不静默改变旧响应语义。
- Native V0.2 查询/结果 Schema、现有编辑格式和快照摘要不因本提案改写；本机运行时新版本与候选 Schema 的对应关系明确记录。
- 正式 `SCHEMA_RELEASE` sourceRef、外部委托与新传输支持均需显式兼容决策，不能把本机句柄自动升级为对外身份或发布资产。
- 首切片默认无新领域表，复用已有资源；若需持久执行摘要/短期句柄则说明保留期，用实施时下一个未占用的前向迁移，已应用 V1–V9 不改。
- 短期 contextId/游标及 M9 详细证据共同为 5 分钟 TTL/重启失效，不承诺长期可引用；证据过期明确返回失效，不从当前来源重造历史依据。
- 持久证据档案需要独立用户故事、留存/权限/容量合同与前向迁移，不在本提案范围。
- 未知 contextVersion/queryVersion/recordVersion 明确拒绝，旧客户端不靠猜测兼容；新增字段遵循已声明的严格合同与版本策略。
- 回滚先停止新上下文请求，保留已承诺的证据查询或明确不可用；不返回静态假数据维持“成功”。
- JWT/本机 Profile 的隔离、签名负例与来源边界保留；本机 Session 复用现有 B1/C1，实现仅登记精确方法和路径并保留 CSRF；MCP/外部委托不在本次范围。

## Test Plan

以下是未来实现要求，不是本提案 PR 的已执行结果。

| 范围 | 正例 | 必须负例 | 真实证据 |
| --- | --- | --- | --- |
| 发现/合同 | 两领域逐步发现并构造合法查询 | 未支持字段/运算、伪造 auth、rawSql、未知版本 | Schema/类型及实际 HTTP 测试 |
| 查询/来源 | 去重对象、合法关系、可追溯证据 | 漂移、缺失来源、旧 Binding、部分/过期错误映射 | PG17 + Spring Boot，复用 M7–M9 夹具 |
| 分页/预算 | 同版本稳定遍历与有界输出 | 跨主体游标、预算抬高、文本超大、翻页期间来源变化 | 边界夹具、字节计数及实际响应 |
| 权限/撤销 | 受信主体范围内发现/查询/证据 | 隐藏类型/统计泄漏、跨包、撤权缓存、证据直链、会话失效 | 真实授权及撤销中途请求测试 |
| 内容与只读 | 恶意描述仍作为普通字符串输出 | 注入工具指令、外网 URL、SQL/写方法、Action 请求 | 确定性客户端与服务拒绝证据 |
| 使用体验 | HTTP/CLI 读取原 execution 证据、核对同一版本 | 证据被当前执行替换、过期仍成功、跨 scope 引用 | 真实 HTTP/CLI；已有工作台回归另列，M11 深链后续独立验收 |
| 协议适配（后续） | MCP 与 REST 语义一致 | 会话复用越权、限额/错误状态被抹平 | 独立适配器合同与客户端测试 |
| OpenAI 协议 | 真实 HTTP Stub 收到配置模型、Bearer、messages/tools；发现→查询→tool_call_id 回传→解释，两个自定义领域共用代码 | 工具参数损坏/未知工具/ID 错配/多工具批次/未知 finish_reason、上游 401/429/5xx/超时/超大响应，禁止重定向/重试及错误正文泄漏 | Java 客户端/分派测试 + 真实 Spring HTTP + 隔离 PG17；Stub 结果单独列示 |
| 模型调用边界 | 未启用时不连网；服务端批准的合成包内执行；等待模型时仍复核当前主体 | 请求改 URL/Key/model/身份、跨 scope/跨包、Prompt 注入写工具、退出登录/禁用账号/撤权后继续外发或最终交付、预算耗尽/伪造证据 | 实际请求计数、脱敏检查、可控 Stub 中途阻塞与授权变更测试 |

本机验收不配置 IdP，必须证明 loopback、隔离测试库和未知路径/写操作拒绝；正式服务验收必须另测真实主体及撤权，二者分开报告。
按变更选择 `mvn -pl ontoforge-boot -am verify`（独立 `TEST_DB_*`）、必要浏览器 E2E 与 Native 合同回归；记录 base/head SHA、suite、运行链接及两套领域夹具。
本提案 PR 只检查文档与必要候选结构回归，实际 Context/PG/浏览器/MCP/LLM/生产发布分别标 `NOT RUN` 或 CI 的 `NOT SELECTED`，不能以文档绿灯写成产品可用。
实施 PR 另列协议 Stub、真实供应商、真实 PG、HTTP/CLI、已有浏览器回归与用户本机 UAT 的结果；未新增 Agent UI 不等于已有 UI 回归已通过。代码 Review 须标记 base/head SHA，CI PASS 不代表功能发布。

## Rejected Alternatives

1. **REST、SDK、MCP、Agent UI 同时首发**：协议/会话/工具生命周期使一个只读用户故事失焦；先固定一个可验证入口，再薄适配。
2. **全本体全结果一次进入 Prompt**：扩大泄漏面和费用，并隐藏分页/不完整语义；采用渐进发现、服务端预算和证据下钻。
3. **Agent 直接连数据库或提交任意 SQL**：绕过类型、权限、预算及来源证据；只调用真实受控查询合同。
4. **只读无需身份、撤权和 M10**：只读仍会泄露商业事实；本机开发和正式服务分别设 Gate。
5. **M13 必须等待 M12 Action**：读取、解释和证据是独立价值，且不应附带写权限；两者可并行。
6. **清缓存就保证撤回所有 Agent 信息**：已交付内容无法可靠抹除；明确只阻止未来读取并限制留存与传播。

---

**Decision Gate:** 请维护者明确决定是否接受本次 OpenAI 兼容、固定合成资源范围、有限只读工具循环的首切片修订。接受设计不等于开放对外服务；本机开发、模型协议/真实供应商、正式版本/身份、MCP 适配各自有独立实现与验收证据。
