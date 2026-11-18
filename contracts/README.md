# Catfee Agent 协议契约 v1

这是 Web 控制台与 Android Agent 之间的最小协议集合。实现方可以替换传输层，
但应保持消息语义、标识字段和状态机一致。

## 1. 角色

- **Agent**：Android APK，主动建立出站 WebSocket，执行设备侧任务。
- **Console Server**：Nitro 单端口服务，负责设备注册、任务、指令、事件与审计。
- **Web Console**：浏览器端，只通过 HTTP/SSE 与服务端交互，不直接访问设备。

## 2. 注册

1. 管理员在控制台生成一次性 `enrollment token`（10 分钟有效）。
2. Agent 调用 `POST /api/agent/enroll`，提交 token 与设备信息。
3. 服务端返回 `deviceId` 与 `deviceSecret`。
4. Agent 使用 `deviceId + deviceSecret` 完成 WebSocket `hello`。

设备凭证只保存在设备安全存储中。撤销授权后，设备连接与后续任务立即失效。

## 3. WebSocket

- 地址：`ws://<host>/api/agent/_ws`
- 首条消息必须是 `hello`，否则不会进入可派发状态。
- 心跳间隔建议 30 秒；服务端 90 秒无心跳视为离线。
- 同一 `deviceId` 只保留一条活动连接，新连接会替换旧连接。

### 消息信封

所有消息使用 JSON：

```json
{
  "type": "event",
  "deviceId": "dev_xxx",
  "taskId": "task_xxx",
  "sequence": 12,
  "sentAt": "2026-11-09T10:00:00.000Z",
  "payload": {}
}
```

`type` 为必填。业务字段放在 `payload` 或同级扩展字段中。服务端与 Agent 都必须
忽略未知字段，以便协议向前兼容。

## 4. 消息类型

### Agent → Server

| type | 说明 |
| --- | --- |
| `hello` | 设备认证与能力上报 |
| `heartbeat` | 心跳，服务端回 `heartbeat_ack` |
| `command_ack` | 确认收到并执行控制指令 |
| `event` | 上报执行事件 |
| `task_state` | 直接上报任务状态（可选，等价于发送 task.* 事件） |

### Server → Agent

| type | 说明 |
| --- | --- |
| `hello_ack` | 认证成功，返回协议版本与待下发指令数量 |
| `heartbeat_ack` | 心跳响应 |
| `command` | 任务下发 / 暂停 / 继续 / 停止 |
| `error` | 协议或认证错误 |

## 5. 任务生命周期

```text
queued
  → waiting_device（设备离线）
  → accepted
  → running
  → paused / stopping
  → succeeded / failed / stopped / expired / interrupted
```

控制台只负责表达控制意图；**任务真实状态以 Agent 上报的事件为准**。
WebSocket 断开时，服务端会把进行中的任务标记为 `interrupted`。

## 6. 幂等与可靠性

- 每条下行指令带唯一 `commandId`，Agent 必须按 `commandId` 去重。
- 每条事件带设备级单调递增 `sequence`（建议从 1 开始）。
- 服务端以事件 `id` 去重，重复事件不会重复写入。
- 断线重连后，服务端会重发 `pending` / `sent` 状态的指令；Agent 依靠
  `commandId` 去重，而不是依赖“只发送一次”。
- 设备离线时创建的任务进入 `waiting_device`，重连后自动下发。
- 控制台停止离线设备任务时，排队指令会过期，任务直接结束。

## 7. 事件命名

推荐使用 `domain.action`：

```text
device.online / device.offline / device.enrolled
task.queued / task.accepted / task.started / task.paused
task.resumed / task.stopped / task.succeeded / task.failed
agent.task.started / agent.task.step / agent.task.succeeded
agent.task.failed / agent.task.stopped / agent.event
tool.called / tool.result
model.request / model.response
command.ack
security.device_revoked
```

事件 `payload` 建议包含 `summary`、`error` 或 `reason`，控制台会优先展示这些字段。
单个事件序列化后超过 16 KB 会被截断并标记 `truncated`，避免日志放大。

`task.*` 事件必须带服务端任务 `taskId`，会同步任务状态；本地聊天使用
`agent.task.*`，只写日志，不影响 Web 任务。

## 8. 文件

- `envelope.schema.json`：消息信封
- `device.schema.json`：注册与设备信息
- `task.schema.json`：任务下发
- `command.schema.json`：控制指令
- `event.schema.json`：事件上报
- `openapi.yaml`：控制台 HTTP API 摘要
