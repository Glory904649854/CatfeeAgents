# 安全策略

## 报告问题

请通过 GitHub 仓库的 **Security → Report a vulnerability**（私密安全公告）提交问题，不要开公开 issue。请附上版本、复现步骤和影响范围；确认后会在修复版本中致谢（如你愿意）。

## 安全边界

CatfeeAgents 需要无障碍服务来读屏和操作页面，可选 Root 来注入手势、截图和执行系统命令。这两项权限都高于普通应用：

- 只在自己持有或明确授权的设备上使用。
- 不要把开启了无障碍或 Root 的设备借给他人，也不要在上面登录敏感账号。
- Root 是可选增强项，非 Root 设备只使用无障碍和系统截图能力。

Web 控制台可以下发任务、读取执行日志和页面文字，应当视为与手机同等级别的敏感系统：

- 公网部署必须放在 HTTPS 反向代理后面，Agent 使用 `wss://`。
- 修改初始管理员密码，使用独立强密码，不要复用其他系统口令。
- 通过防火墙、VPN 或访问控制限制控制台来源；不要直接暴露 SQLite 数据目录。
- 控制台日志会保存页面文字、工具参数和结果，按敏感数据管理，定期清理。

## 已知行为

- `android:usesCleartextTraffic="true"`：允许连接局域网控制台和 HTTP 模型服务。生产环境应全部使用 HTTPS/WSS；如果只连公网服务，可以在 `AndroidManifest.xml` 中改为 `false`。
- `DebugReceiver` 导出但受 `android.permission.DUMP` 保护，只有 adb/shell 或系统签名应用能触发；`consolebind` 命令仅在 debug 包生效。
- 演示数据脚本和 `agent-sim` 只用于本地开发，不包含真实设备数据。
- API Key、控制台设备凭证保存在 `EncryptedSharedPreferences`；源码和仓库不包含真实密钥。

## 部署检查

1. 设置 `NUXT_ADMIN_PASSWORD`，或使用首次启动随机生成的密码并妥善保存。
2. 设置 `NUXT_PUBLIC_URL=https://...`，让会话 Cookie 带上 `Secure`。
3. 反向代理只转发控制台端口，限制请求体大小并配置超时。
4. 定期更新依赖：`pnpm audit`、`./gradlew dependencyUpdates`（如已配置）。
5. 备份或迁移前先停止服务，避免复制到不一致的 SQLite WAL 文件。
