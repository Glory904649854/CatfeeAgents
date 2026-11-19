# CatfeeAgents 控制台

CatfeeAgents 的 Web 控制台。Nuxt 4 单端口同时提供页面、REST API、SSE 与
Android Agent WebSocket，本地 SQLite 存储。

完整文档：[../../docs/web-console.md](../../docs/web-console.md)

## 开发

```bash
pnpm install
cp .env.example .env
pnpm dev
```

要求 Node.js ≥ 22.21（见 `.nvmrc`；版本不符时脚本会给出提示）。
默认管理员用户名 `admin`；未设置 `NUXT_ADMIN_PASSWORD` 时，初始密码会随机生成并打印在启动日志中。

## 常用命令

```bash
pnpm dev          # 开发服务器（9777）
pnpm build        # 生产构建
pnpm start        # 运行 .output（9777）
pnpm db:demo      # 写入演示数据（需先启动一次）
pnpm db:reset     # 清空数据库
pnpm agent:sim    # 模拟 Agent 完成协议联调
pnpm typecheck    # 类型检查
```

## 目录

- `app/`：页面、布局、组件、Vuex、UnoCSS 样式
- `server/`：Nitro API、SSE、Agent WebSocket、SQLite
- `shared/`：前后端共享类型
- `scripts/`：数据库与协议联调脚本

协议契约在仓库根目录 [`contracts/`](../../contracts)。
