# M13：OpenAI 兼容只读 Agent 本机开发

设计依据：[OFIP-0015](../proposals/OFIP-0015-M13-Agent只读上下文.md) 与 [2026-10-19 明确接受记录](https://github.com/gitfortian/ontoforge/pull/104#issuecomment-6269890450)。本切片入口是 HTTP/CLI；模型解释和底层查询证据分别返回。正式对外服务、MCP、Action、完整 Agent UI 和生产发布未交付。

## 配置与启动

沿用已有 [Session/账号配置](./Yak-B1-本地账号Session接入.md) 和 [工作空间/包授权](./Yak-C1-工作空间与模型包归属.md)。先在已有工作台创建合成模型、校验并冻结 Local Snapshot，再保存通过校验的数据绑定；查询来源使用独立只读账号。不要使用生产数据。

在启动后端的终端设置：

```powershell
$env:ONTOFORGE_AGENT_ENABLED = 'true'
$env:ONTOFORGE_AGENT_BASE_URL = 'https://api.openai.com/v1'
$env:ONTOFORGE_AGENT_MODEL = 'your-tool-capable-model'
$env:ONTOFORGE_AGENT_API_KEY = [System.Net.NetworkCredential]::new('', (Read-Host 'Model API key' -AsSecureString)).Password
$env:ONTOFORGE_AGENT_ALLOWED_PACKAGES = 'demo.synthetic'
.\scripts\run-local.ps1
```

`baseUrl` 是包含供应商 API 版本前缀的根地址，后端只追加 `/chat/completions`。协议为非流式 Chat Completions 的 `messages/tools/tool_calls/tool_call_id` 子集，使用 `max_completion_tokens`；模型必须支持该工具协议。不自动换模型、改用 Responses 或降级到供应商私有参数。远端 HTTPS，HTTP 只接受字面 loopback 地址，如 `http://127.0.0.1:11434/v1`；服务仍只能监听 loopback。

Agent 默认关闭。开启但缺少 Key、模型、合法地址或明确合成包允许列表时启动失败，不回退到无约束模式。允许列表只限制外发资源，用户仍须拥有该模型包实际权限。Key 仅在服务端供应商 Bearer Header 中使用，不放进请求 JSON、前端或 Git。客户端不能覆盖模型配置或上传工具定义。

## 提问与读取原执行证据

在 Git 忽略或仓库外位置保存 scope JSON，替换为工作台/API 返回的真实 UUID 和修订：

```json
{
  "snapshotId": "00000000-0000-4000-8000-000000000001",
  "bindingRefs": [
    {"bindingId": "00000000-0000-4000-8000-000000000002", "revision": 1}
  ],
  "linkBindingRefs": []
}
```

```powershell
.\scripts\invoke-agent.ps1 -ScopePath C:\temp\agent-scope.json -Question '发现本领域类型，查询对象并说明依据' -ReadEvidence
```

脚本会提示输入本地用户账号密码，保留 Cookie/CSRF，完成后退出该脚本创建的 Session。模型 Key 不交给脚本。只有明确使用无认证隔离 Profile 时才加 `-LocalMvp`。

直接 HTTP 入口为 `POST /api/agent/v1/runs`，请求 `{runVersion:"local-agent-v1",question,scope}`；本地 Session 模式沿用 Cookie 和 CSRF。可选 `limits` 仅能降低 `maxModelRequests`（最多 6）、`maxToolCalls`（最多 5）、`timeoutSeconds`（最多 60）。未知字段、来路身份、rawSql 或跨范围引用拒绝。

工具逐步发现选定快照类型和绑定，读取对象，执行 `local-query-v1` ObjectSet/分页。提供明确 LinkBinding 修订且双端属于选定绑定时，另开放 `local-query-v2` 一跳查询/分页；空 `linkBindingRefs` 不注册关系工具。数据不会改写模型定义或触发 Action。

`status=COMPLETE` 表示问答正常结束；`answerKind=MODEL_GENERATED` 明确解释来自模型。`factVerification=NO_EXECUTION_EVIDENCE` 表示没有执行事实读取，不能把该回答当作已验证业务结论。`evidence` 保留原始质量、来源、版本及预算；`UNKNOWN / PARTIAL / NULL / MISSING` 不随模型文字改成完整成功。

`evidenceRefs` 由服务端真实执行产生，例如 `query:local-result:<UUID>` 或 `traverse:local-result:<UUID>`。同一登录主体可用 `GET /api/context/v1/evidence/{reference}` 打开原执行元数据。它复用原查询记录并重新核验当前权限、版本和来源资格，不重新查询当前事实。普通来源 DML 不改变原执行的冻结成员/值。M6 单对象读取只有最终响应中的即时内嵌证据，不伪造可再次打开的句柄。

## 失效、限制与验收

- 原查询结果和证据最多保存 5 分钟，重启失效；过期 `410 RESULT_EXPIRED`。引用和游标不能授权，另一用户即使属于同一空间也不能读取。
- 等待模型期间退出、停用账号或撤权，将停止后续模型请求和最终业务数据交付。已发现权限/来源失败的句柄永久失效；未被请求观察到的撤权后恢复无法由当前权限系统完整检测。
- 问题最多 8 KiB，HTTP 请求最多 32 KiB；模型响应最多 256 KiB，每个工具结果和最终完整信封各最多 64 KiB，累计模型请求最多 1 MiB。超限明确失败，不静默裁剪对象、证据或文本。
- 单次模型调用最多 15 秒，整次运行最多 60 秒；最多同时 4 次运行，没有无限等待队列。来源读取保留原 M6/M7/M8 限额，查询超时按剩余预算收紧；调用超时后停止等待并中断运行，JDBC 的底层取消时间仍受驱动和现有查询限额约束。
- `INCOMPLETE` 与 `MODEL_TOKEN_LIMIT / MODEL_CONTENT_FILTER / MODEL_REFUSAL / AGENT_TOOL_LIMIT` 保留未完成状态。HTTP/权限/协议错误返回 `FAILED` 和稳定 reasonCode；不透传上游错误正文、Key、Cookie 或查询业务值。不做应用层自动重试或重定向。

验收应使用两个用户自定义领域、真实隔离 PostgreSQL、真实 Session/CSRF HTTP 和可控模型 HTTP Stub；分别验证正常工具循环、原执行证据、跨主体、跨范围、来源漂移、撤权/退出、异常和预算。真实供应商联通、用户既有安装人工验收、浏览器回归及正式发布各自记录，不用 Stub 或文档测试代替。
