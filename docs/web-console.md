# CatfeeAgents Web 控制台

面向已授权 Android Agent 的远程管理控制台：设备接入、任务下发、实时事件、
暂停 / 继续 / 停止与审计记录。

> 本控制台只管理已注册、已授权的设备。不提供任意 Shell、隐蔽控制或绕过
> Android 权限的能力。

## 1. 架构

```text
Browser ──HTTP/SSE──┐
                    ├─ Nuxt 4 / Nitro（单端口 9777）
Android Agent ──WS───┘
                    ├─ REST API
                    ├─ SSE（控制台实时事件）
                    ├─ WebSocket（Agent 长连接）
                    └─ SQLite（本地单文件数据库）
```

**单端口**同时承载页面、REST API、SSE 和 Agent WebSocket。
生产环境只需要暴露一个端口，SQLite 不需要额外数据库服务。

### 技术选型

| 层 | 选型 | 说明 |
| --- | --- | --- |
| 应用框架 | Nuxt 4 + Nitro | 文件路由、服务端 API、单端口、SSE / WS |
| UI | Vue 3 + Naive UI | 与 APK 主题一致的黑白红扁平风格 |
| 状态 | Vuex 4 | 按需求指定；后续可平滑迁移 Pinia |
| 样式 | UnoCSS | 设计令牌、快捷类、图标（Lucide） |
| 校验 | Zod | API 入参校验 |
| 存储 | better-sqlite3 | WAL 模式、单进程、零运维 |
| 语言 | TypeScript | 前后端统一类型（`shared/types`） |

## 2. 运行环境

- Node.js **≥ 22.21**（Nuxt 4.6 要求；当前在 Node 22.23.2 验证）
- pnpm 9+
- Windows / Linux / macOS

> 低版本 Node 22.12 会因 `better-sqlite3` 原生模块不兼容而崩溃。请升级 Node
> 或使用 `nvm use` 切换（仓库提供 `.nvmrc`）。

## 3. 快速开始

```bash
cd web/console
pnpm install
cp .env.example .env        # Windows: copy .env.example .env
pnpm dev                    # http://localhost:9777
```

首次启动会自动建表，并创建管理员。未设置 `NUXT_ADMIN_PASSWORD` 时会随机生成密码，并且只输出一次：

```text
[catfee] 已创建初始管理员账号：admin / <随机密码>
```

请妥善保存该密码；也可以通过 `NUXT_ADMIN_USER` / `NUXT_ADMIN_PASSWORD` 指定初始账号。

### 演示数据（可选）

```bash
pnpm dev          # 先启动一次，生成表结构
pnpm db:demo      # 写入 3 台设备、5 个任务和事件
pnpm db:reset     # 清空数据库，下次启动重新建表
```

### Agent 协议联调

```bash
pnpm agent:sim
```

该脚本会模拟一台设备完成注册、连接、接收任务并回传事件，用于验证
控制台 → 设备 → 事件 → 任务状态的完整闭环。

## 4. 目录结构

```text
web/console/
├── app/
│   ├── components/        # AppBrand、StatusPill、TaskRow、EventRow 等
│   ├── composables/       # useSession、useLiveStream
│   ├── layouts/           # 桌面侧栏 + 移动端底部导航
│   ├── middleware/        # 全局登录态校验
│   ├── pages/             # 概览 / 设备 / 任务 / 日志 / 设置 / 登录
│   ├── plugins/           # Naive UI、Vuex
│   ├── store/             # Vuex 模块：auth / devices / tasks / events / ui
│   ├── utils/             # 主题、格式化、状态标签、反馈
│   └── assets/css/        # 最小 reset + 全局样式
├── server/
│   ├── api/               # REST API 与 SSE
│   ├── middleware/        # 控制台会话校验
│   ├── plugins/           # 数据库初始化
│   ├── routes/api/agent/  # Agent WebSocket（/_ws）
│   └── utils/             # 数据库、认证、Agent Hub、事件、审计
├── shared/types/          # 前后端共享类型
├── scripts/               # db:reset / db:demo / agent:sim
├── data/                  # SQLite（gitignore）
└── nuxt.config.ts
```

## 5. 环境变量

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `PORT` | `9777` | 生产端口（开发由 devServer 指定） |
| `NUXT_PUBLIC_URL` | `http://localhost:9777` | 对外地址，用于生成 WS / 注册地址 |
| `NUXT_DB_PATH` | `./data/catfee.db` | SQLite 路径 |
| `NUXT_ADMIN_USER` | `admin` | 首次启动创建的管理员 |
| `NUXT_ADMIN_PASSWORD` | 随机生成 | 首次启动密码；留空时输出到启动日志 |
| `NUXT_SESSION_DAYS` | `30` | 控制台会话有效期 |

`NUXT_PUBLIC_URL` 为 `https://` 时会自动启用 Secure Cookie。

## 6. 页面

| 路径 | 说明 |
| --- | --- |
| `/login` | 管理员登录 |
| `/` | 概览：在线设备、活跃任务、今日事件、最近任务与实时事件 |
| `/devices` | 设备列表、注册码生成、状态筛选 |
| `/devices/[id]` | 设备详情、能力、最近任务/事件、撤销授权 |
| `/tasks` | 任务列表、状态筛选、新建任务 |
| `/tasks/[id]` | 执行时间线、任务信息、策略、控制指令 |
| `/logs` | 事件日志、等级/类型/设备筛选、实时追加 |
| `/settings` | 账号、系统信息、Agent 接入地址、安全说明、审计 |

## 7. 数据模型

```text
users              管理员账号（scrypt 密码哈希）
sessions           HttpOnly 会话（只存 token 哈希）
enrollment_tokens  一次性设备注册码
devices            设备身份、能力、心跳、撤销状态
tasks              任务意图、状态、策略
commands           TASK_ASSIGN / PAUSE / RESUME / STOP
agent_events       结构化执行事件
audit_events       管理端操作审计
```

状态机：

```text
queued → waiting_device → accepted → running
running → paused → running
running → stopping → stopped
running → succeeded / failed
断线     → interrupted
```

任务真实状态以 Agent 上报的事件为准；控制台只表达控制意图。

## 8. HTTP API

完整定义见 [`contracts/openapi.yaml`](../../contracts/openapi.yaml)。

```text
POST /api/auth/login | /api/auth/logout      GET /api/auth/me
GET  /api/overview
GET  /api/devices                            POST /api/devices/enroll
GET  /api/devices/:id                        POST /api/devices/:id/revoke
GET  /api/tasks                              POST /api/tasks
GET  /api/tasks/:id                          POST /api/tasks/:id/{pause|resume|stop}
GET  /api/events                             GET  /api/stream (SSE)
GET  /api/system/info                        GET  /api/audit
POST /api/agent/enroll                       POST /api/agent/heartbeat
POST /api/agent/events
WS   /api/agent/_ws
```

## 9. Agent 接入流程

1. 控制台生成一次性注册码（8 位 `ABCD-2345`，10 分钟有效），并展示
   `catfee://enroll?url=<console>&code=<token>` 二维码。
2. APK `设置 → 控制台` 可手动输入注册码，或点「扫码绑定」扫描二维码；
   `ConsoleClient.enroll()` 调用 `POST /api/agent/enroll` 换取
   `deviceId + deviceSecret`，保存在 `EncryptedSharedPreferences`。
3. APK 通过 `RemoteConsoleService` 保持前台长连接，连接
   `ws://<host>/api/agent/_ws`，首条消息发送 `hello`。
4. 服务端返回 `hello_ack`，并补发离线期间排队的指令。
5. 任务以 `command` 下发，APK 返回 `command_ack`，随后上报 `event`。

设备端实现位于 `app/src/main/java/com/catfee/rootauto/console/`：

- `ConsoleSettings`：设备凭证与启用状态；
- `ConsoleClient`：注册、心跳、WebSocket、事件批量补传；
- `RemoteTaskRunner`：`TASK_ASSIGN / PAUSE / RESUME / STOP`，按 `commandId` 去重；
- `RemoteConsoleService`：前台服务，保证锁屏/退后台后仍能接收任务。

日志由 `log/AgentTrace.kt` 统一写入 `/api/agent/events`：Web 任务使用服务端
`taskId`，本地聊天使用 `local_` 前缀。`task.started / succeeded / failed /
stopped` 会同步任务状态，`agent.task.step` 只写时间线。任务策略
`allowRoot` / `allowScreenshot` 会在设备端再次校验。

调试包可通过 adb 绑定与查看状态：

```bash
adb shell am broadcast -a com.catfee.rootauto.DEBUG \
  -n com.catfee.rootauto/.DebugReceiver --es cmd consolebind \
  --es baseUrl http://192.168.1.100:9777 --es code <一次性注册码>

adb shell am broadcast -a com.catfee.rootauto.DEBUG \
  -n com.catfee.rootauto/.DebugReceiver --es cmd consolestatus
```

消息与状态语义见 [`contracts/README.md`](../../contracts/README.md)。

## 10. 设计系统

与 APK Compose 主题保持一致：

| Token | 值 |
| --- | --- |
| 画布 / 卡片 | `#F7F8FA` / `#FFFFFF` |
| 主文字 / 次文字 | `#111827` / `#6B7280` |
| 描边 | `#E5E7EB` |
| 品牌红 | `#E11D2E` |
| 成功 / 警告 / 失败 | `#16A34A` / `#D97706` / `#DC2626` |
| 圆角 | 8 / 12 / 14 / 16px |
| 主按钮 | 黑色 `#111827`，品牌红只做强调 |

扁平化：1px 描边、无重阴影、低饱和状态底色。Logo 复用
`app/src/main/res/drawable/app_logo.png`。

### 响应式

- `≥1024px`：固定侧栏 + 内容区
- `768–1023px`：侧栏隐藏，顶部菜单
- `<768px`：顶部栏 + 抽屉 + 底部五项导航，卡片单列

## 11. 安全说明

- 设备必须通过一次性注册码接入，凭证可撤销。
- 设备凭证通过 `x-device-id / x-device-secret` 请求头传输；公网部署必须使用
  HTTPS/WSS，内网测试才使用 `http://`。
- 控制台会话使用 HttpOnly Cookie；密码使用 scrypt。
- Web 端只暴露任务与控制指令，不提供任意 Shell。
- Root 能力默认关闭，任务策略中显式记录。
- 事件 payload 超过 16 KB 截断；敏感字段应在 Agent 侧脱敏。
- 审计表记录登录、注册、撤销、任务创建与控制操作。

## 12. 当前限制与后续

- 暂未提供密码修改 / 多用户管理界面，账号来自环境变量。
- 暂未实现截图对象存储；`allowScreenshot` 只控制设备端截图/视觉工具，不上传画面。
- 单实例 SQLite；多副本部署前需要迁移 PostgreSQL。
- 设备组、批量任务、任务模板、审批流尚未实现。
- Agent WS 使用 Nitro 实验性 WebSocket 支持，升级 Nitro 时需回归验证。
- 任务暂停为协作式暂停，进程被杀后不能从原步骤精确恢复。

建议下一阶段：设备策略、任务模板、截图脱敏与对象存储、RBAC、
WebSocket 断线补偿和多设备调度。
