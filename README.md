<div align="center">
  <img src="app/src/main/res/drawable/app_logo.png" width="96" alt="CatfeeAgents" />

  <h1>CatfeeAgents</h1>

  <p>会聊天，也能替你操作手机的 Android Agent</p>

  <p>
    <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white" alt="Android 8.0+" />
    <img src="https://img.shields.io/badge/Kotlin-2.2-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin" />
    <img src="https://img.shields.io/badge/Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white" alt="Compose" />
    <img src="https://img.shields.io/badge/LLM-OpenAI%20Compatible-111827" alt="OpenAI Compatible" />
  </p>

  [快速开始](#快速开始) · [功能](#功能) · [工具](#工具) · [Web 控制台](#web-控制台) · [限制](#限制)
</div>

---

CatfeeAgents 是一个运行在 Android 上的 LLM Agent。它把聊天、附件分析和手机操作放在同一个输入框里：普通问题直接回答，需要查看或操作手机时，再由模型申请进入设备模式。

项目不针对具体 App 写业务流程。所有操作都来自通用工具、页面识别和模型决策，因此可以用于不同应用，也可以配合 Web 控制台做多设备管理。

## 功能

- **统一对话**：问答、图片/文件分析、手机操作共用一个入口，不需要先切换模式。
- **多会话**：多个聊天可以同时运行；设备操作全局只允许一个 Agent，冲突时会提示。
- **设备操作**：基于 ReAct 循环，模型观察当前页面后选择工具，执行后再确认页面变化。
- **本地识别**：优先读取无障碍树；读不到时使用 ML Kit、PP-OCRv5、滚动截图和视觉模型。
- **会话管理**：支持搜索、置顶、收藏、归档、重命名、Markdown 导出；消息可引用、编辑重发、重新生成和分支。
- **App 记忆**：按包名保存入口、控件、坑和流程，任务开始后按相关性注入；可手动查看和修改。
- **状态与控制**：通知栏和悬浮窗显示当前步骤、耗时和最后动作，支持暂停、继续和停止。
- **用量统计**：会话内显示 token、上下文、步骤和耗时，可按单价估算费用。
- **Web 控制台**：仓库内附带 Nuxt 控制台，支持设备注册、任务下发、实时事件和审计。
- **DECISION 加速（可选）**：高置信度时跳过主模型的 `observe` / `wait`；默认关闭，点击和输入仍由主模型决定。

## 快速开始

### 环境

| 项目 | 要求 |
| --- | --- |
| 系统 | Android 8.0+（API 26） |
| 架构 | arm64-v8a / armeabi-v7a |
| 无障碍 | 必需；非 Root 设备依赖它读屏和点击 |
| Root | 可选，用于 uinput 注入、快速截图和系统级操作 |
| 构建 | JDK 17、Android SDK 36 |
| 网络 | 能访问所配置的 OpenAI 兼容接口 |

Root 是增强项，不是运行前提。Android 11+ 的无障碍设备可以完成读屏、点击、滑动、输入、启动应用和截图 OCR；Android 8–10 无 Root 时不支持截图。

### 构建与安装

```bash
# Windows
gradlew.bat assembleDebug

# macOS / Linux
./gradlew assembleDebug
```

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 开启无障碍

安装或强制停止后，部分系统会重置无障碍授权，需要重新开启：

```bash
adb shell settings put secure enabled_accessibility_services \
  com.catfee.rootauto/com.catfee.rootauto.a11y.UiObserverService
adb shell settings put secure accessibility_enabled 1
```

也可以手动进入「设置 → 无障碍 → 已下载的应用 → CatfeeAgents」开启。

### Root daemon（可选）

uinput 点击和滑动依赖常驻 daemon。仓库带有预编译产物，可以直接打 Magisk 模块：

```powershell
powershell -ExecutionPolicy Bypass -File magisk/build-magisk.ps1
```

修改过 C 源码时先重新编译：

```powershell
powershell -ExecutionPolicy Bypass -File daemon/build.ps1
```

### 配置模型

打开右上角菜单 → **设置 → 模型**：

1. 填写 Base URL（OpenAI 兼容地址，填到 `/v1` 或只填域名都可以）；
2. 填写 API Key；
3. 填写模型 ID；
4. 可以点「拉取模型列表」直接选择，也可以手动填写后保存。

API Key 和 Web 控制台设备凭证使用 `EncryptedSharedPreferences` 保存。

### 开始使用

直接用自然语言描述目标，例如：

```text
打开设置，查看当前 Android 版本，然后返回
```

```text
把当前页面的问卷填完，逐题核对后再提交
```

```text
看看这张截图里有哪些可点击的按钮，帮我点进设置
```

首次需要操作手机时会弹出一次授权；允许后本会话不再询问。运行中可以随时暂停或停止。

## 工具

设备模式下提供 16 个工具：

```text
enter_agent_mode  observe  ocr  tap  swipe  input  key  wait
find  scroll  open  todo_write  todo_read  note  memory
request_capability  finish
```

- `tap` 支持按文字、描述、resource-id 或坐标点击，也支持长按和双击。
- `swipe` 支持方向滑动和坐标滑动。
- `open` 可以传包名、应用名或 URL，不需要先查包名。
- `observe` 在无障碍树为空时自动切换到本地 OCR；`find`、`tap`、`scroll` 也有 OCR 兜底。
- `input` 在无法定位输入框时，会使用剪贴板完成输入。
- `memory` 统一管理记忆的读取、写入、搜索和删除。

`load_tools`、`see_screen`、`read_page` 等低频或耗时入口不再暴露给模型。

## 记忆

每个 App 保存一份结构化记忆，包括类型（入口、控件、坑、流程、偏好）、页面、正文、选择器、置信度、使用次数和最后验证时间。

任务运行时只注入与当前目标和页面相关的条目；其余内容保留在归档中。任务结束后，后台会整理本次执行结果，合并重复项、补充失败经验，并淘汰长期无效的内容。

App 内「设置 → 记忆」可以查看和修改；绑定 Web 控制台后，也可以在设备详情页查看和编辑。

## 状态与控制

Agent 运行时，通知栏显示当前步骤、阶段、耗时和最后动作。状态包括等待模型、操作中、等待确认、已暂停、已完成、已停止和异常。

悬浮窗默认贴右侧显示，显示同样的状态，并提供暂停、继续和停止按钮。可以按住卡片正文拖动，位置会保存。截图和 OCR 前会自动隐藏，避免进入识别结果。

停止操作需要两步确认，避免误触。

## Web 控制台

[`web/console`](web/console) 是配套的远程管理端，和设备共用黑白红设计。单端口同时提供页面、REST API、SSE 和 Agent WebSocket，数据保存在本地 SQLite。

```bash
cd web/console
pnpm install
pnpm dev        # http://localhost:9777
```

首次启动会创建管理员 `admin`；未设置 `NUXT_ADMIN_PASSWORD` 时密码随机生成，只在启动日志中输出一次。公网部署建议放在 HTTPS 反向代理后面，设备使用 WSS。

绑定方式：Web 设备页生成一次性注册码和二维码，APK 在「设置 → 控制台」输入注册码，或直接扫码完成绑定。绑定后设备会保持长连接，Web 端可以下发任务、查看事件、暂停或停止任务。

远程任务默认会被设备拒绝；需要在手机「设置 → Web 控制台」中显式开启「允许远程任务直接操作手机」，控制台才能让设备执行任务。

更多说明见 [Web 控制台文档](docs/web-console.md) 和 [Agent 协议](contracts/README.md)。

## 目录结构

```text
app/src/main/java/com/catfee/rootauto/
├── MainActivity.kt              # 对话、会话、附件、设置
├── agent/                       # ReAct 循环、工具、控制器、记忆
├── a11y/                        # 无障碍树、滚动、文本输入
├── session/                     # 会话与附件持久化
├── ocr/                         # ML Kit / PP-OCR / 滚动 OCR
├── root/                        # Root shell、输入注入、截图
├── service/                     # 前台通知与悬浮状态条
├── console/                     # 设备凭证、WebSocket、远程任务
├── llm/                         # 模型配置与请求
└── log/                         # Agent 事件上报

app/src/main/assets/ocr/         # PP-OCRv5 模型
daemon/                          # native 输入注入
magisk/                          # Magisk 模块
```

## 限制

- **非 Root**：可以使用无障碍、系统截图和 OCR；`shell`、`settings_put`、任意路径文件读写、`stop_app` 和 Magisk 模块需要 Root。Android 8–10 无 Root 时不支持截图，`FLAG_SECURE` 页面也无法截图。
- **设备操作单例**：聊天可以并发，但同一时间只能有一个会话操作手机。
- **暂停不恢复执行点**：暂停不会取消当前任务，但 App 进程被系统杀死后无法从原步骤继续。
- **业务条件由用户给出**：Agent 不知道某个 App 的完成标准，需要用户目标和当前页面证据来判断。
- **通知和分享面板**：目前没有接入通知读取，页面内容通过无障碍和截图获取。

## 安全

- Root 权限较高，建议只在测试机或受控设备上使用。
- API Key 和设备凭证加密保存，不写入日志。
- Web 端日志包含页面文字、工具参数和结果，请限制控制台账号权限。
- 本地 OCR 不上传截图；使用视觉降级时，截图会发送给当前配置的模型服务。文本和工具结果按长度截断。
- 源码不包含真实 API Key，`local.properties` 已加入 `.gitignore`。
- 漏洞报告和部署检查见 [SECURITY.md](SECURITY.md)。

## 构建信息

| 项目 | 值 |
| --- | --- |
| 应用名 | CatfeeAgents |
| applicationId | com.catfee.rootauto |
| 版本 | 1.0.0（versionCode 10000） |
| SDK | compileSdk 36 / targetSdk 34 / minSdk 26 |
| UI | Jetpack Compose + Material 3 |
| OCR | ML Kit Chinese + ONNX Runtime（PP-OCRv5） |
| 输入注入 | native daemon + uinput |

## License

[Apache License 2.0](LICENSE)
