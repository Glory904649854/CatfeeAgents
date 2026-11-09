<div align="center">
  <img src="app/src/main/res/drawable/app_logo.png" width="104" alt="CatfeeAgents" />

  <h1>CatfeeAgents</h1>

  **会聊天，也能替你操作手机的 Android LLM Agent**

  统一对话 · 通用原子工具 · 本地多模态 · 全链路可观测

  <br />

  ![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
  ![Kotlin](https://img.shields.io/badge/Kotlin-2.2.10-7F52FF?logo=kotlin&logoColor=white)
  ![Compose](https://img.shields.io/badge/Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)
  ![Root](https://img.shields.io/badge/Root-Magisk-EA4AAA)
  ![LLM](https://img.shields.io/badge/LLM-OpenAI%20Compatible-111827)

  <br />

  [快速开始](#快速开始) · [核心能力](#核心能力) · [工作方式](#工作方式) · [工具系统](#工具系统) · [常见问题](#常见问题)
</div>

---

> **不绑定任何 App。** 没有针对某款应用的包名判断和专用流程，所有能力都由通用原子工具 + 页面识别组合完成。聊天、看图、读文件、操作手机，都在同一个输入框里。

## 核心能力

| | 能力 | 说明 |
| :--: | --- | --- |
| 💬 | **统一对话** | 普通问答、附件分析、手机操作共用一个输入框，不用先选 Chat / Agent 模式。需要碰手机时，模型自己请求进入设备模式。 |
| 🧵 | **多会话并发** | 多个聊天会话可以同时运行；切换会话不会打断另一个会话。设备操作全局只允许一个 Agent，冲突时提示并可重试，聊天仍可继续。 |
| 👁 | **状态看得见** | 通知栏和可选悬浮条实时显示「第 N 步 · 等待模型 / 操作中 / 等待确认」、运行时长和最后活动时间，支持一键停止或回到会话。 |
| 🧠 | **本地多模态** | 无障碍树优先；读不到时降级到 ML Kit、PP-OCRv3、滚动长截图和视觉模型。图片附件先在本地 OCR，再把文字交给 LLM。 |
| 🧰 | **原子工具** | 点击、长按、滑动、输入、按键、启动 App、读页面、截图、OCR、等待、校验、记忆、计划等都可独立调用，可组合出任意任务。 |
| 🗂 | **会话管理** | 历史会话支持搜索、置顶、收藏、归档、重命名、导出 Markdown；消息可复制、引用、编辑重发、重新生成、分支出新会话。 |
| 🧭 | **按 App 记忆** | 每个包名一份简短记忆，自动注入当前前台 App 的上下文；任务结束后只沉淀稳定入口、控件和坑，不记录题目答案或一次性数据。 |
| 📊 | **用量与成本** | 每个会话显示 tokens、上下文长度、消息/步骤数、累计耗时；可配置每 1M token 单价估算费用。 |
| 📡 | **远程日志** | 可选接入 `clouddream-server` 的 `/logReport`，异步上报每一步输入、模型输出、工具参数与结果，便于回放和优化。 |
| 🖥 | **Web 控制台** | 仓库内提供 Nuxt 单端口控制台：设备注册、任务下发、实时事件、暂停/继续/停止与审计。Agent 主动通过 WebSocket 接入，Web 端不直接操作设备。 |

## Web 控制台

[`web/console`](web/console) 是面向已授权设备的远程 Agent 管理端，与 APK 共用
黑白红扁平设计。Nuxt 4 单端口同时承载页面、REST API、SSE 和 Agent WebSocket，
数据存储在本地 SQLite。

```bash
cd web/console
pnpm install
pnpm dev        # http://localhost:3000
```

首次启动创建管理员 `admin / change-me`，可用环境变量覆盖。
详见 [Web 控制台文档](docs/web-console.md)、
[Agent 协议契约](contracts/README.md) 与 [OpenAPI](contracts/openapi.yaml)。


## 快速开始

### 1. 环境要求

| 项目 | 要求 |
| --- | --- |
| 系统 | Android 8.0+（API 26） |
| 架构 | `arm64-v8a` / `armeabi-v7a` |
| Root | Magisk 或可用的 `su`，用于 uinput 注入、截图和部分系统操作 |
| 无障碍 | 开启 CatfeeAgents 的无障碍服务 |
| 通知 | Android 13+ 需授予通知权限，否则看不到 Agent 状态 |
| 构建 | JDK 17、Android SDK 36 |
| 网络 | 能访问所配置的 OpenAI 兼容接口；远程日志默认访问 `https://api.catfee.cn` |

> 当前版本面向 **Root 设备**。非 Root 路线（无障碍点击 + MediaProjection + Shizuku/ADB）尚未落地，见[已知限制](#已知限制)。

### 2. 构建与安装

```bash
# Windows
gradlew.bat assembleDebug

# macOS / Linux
./gradlew assembleDebug
```

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`

### 3. 开启无障碍

安装或强制停止 App 后，部分系统会关闭无障碍服务，需要重新开启：

```bash
adb shell settings put secure enabled_accessibility_services \
  com.catfee.rootauto/com.catfee.rootauto.a11y.UiObserverService
adb shell settings put secure accessibility_enabled 1
```

也可以手动进入「设置 → 无障碍 → 已下载的应用 → CatfeeAgents」开启。

### 4. 安装 Root daemon（首次使用）

`uinput` 点击/滑动依赖常驻 daemon。仓库已带 `daemon/dist` 预编译产物，可直接打包 Magisk 模块：

```powershell
powershell -ExecutionPolicy Bypass -File magisk/build-magisk.ps1
```

把生成的 `dist/CatfeeRootAuto-v0.1.0.zip` 刷入 Magisk 并重启。修改过 C 源码时，先重新编译：

```powershell
powershell -ExecutionPolicy Bypass -File daemon/build.ps1
```

### 5. 配置模型

打开右上角菜单 → **设置 → 模型**：

1. 填写 `Base URL`（OpenAI 兼容地址，填到 `/v1` 或只填域名均可）；
2. 填写 `API Key`；
3. 填写模型 ID，例如 `deepseek-v4.1-flash`；
4. 点 **拉取模型列表**，接口返回的模型会全部列出来，点任意一个即可切换并保存；
5. 也可以手动填写模型 ID 后点 **保存模型配置**。

设置页顶部的能力标签会显示 `无障碍 / ROOT / 悬浮窗` 状态。API Key、Agent Token 使用 `EncryptedSharedPreferences` 保存。

### 6. 开始任务

直接描述目标即可，例如：

```text
打开设置，查看当前 Android 版本，然后返回
```

```text
把当前页面的问卷填完，逐题核对后再提交
```

```text
看看这张截图里有哪些可点击的按钮，帮我点进设置
```

首次需要读取/操作手机时，聊天框内会弹出一次授权卡；允许后本会话不再询问。运行中可随时停止，步骤会实时出现在会话时间线和通知栏。

## 工作方式

```mermaid
flowchart LR
    A["自然语言目标"] --> B{"需要操作手机?"}
    B -- "否" --> C["直接回答 / 分析附件"]
    B -- "是" --> D["聊天内授权一次"]
    D --> E["ReAct 循环"]
    E --> F["观察<br/>无障碍 → OCR → 视觉"]
    F --> G["LLM 选择工具"]
    G --> H["点击 / 输入 / 滑动 / 等待"]
    H --> I["校验页面变化"]
    I -- "未完成" --> F
    I -- "完成" --> J["结果 + 记忆 + 远程上报"]
```

- **工具门控**：聊天模式只发 `enter_agent_mode`；进入设备模式后发 24 个核心工具，其余能力用 `load_tools` 按组加载，避免几十个 schema 干扰模型。
- **先观察再动作**：动作后默认等待页面稳定再读取；点击结果只表示“已发送”，必须再用 `verify_*` / `observe` 验证。
- **坐标统一**：所有工具使用当前**显示方向**坐标；注入前按 rotation 转成设备 natural 坐标，越界直接拒绝。
- **上下文预算**：最多 60 条消息 / 40K 字符；单条工具结果最多 10K，便签 4K，计划 3K；当前任务目标固定在 system 中，不会被裁剪掉。
- **证据优先**：颜色、角标、进度、勾选状态必须给出具体视觉证据；页面文字、进度和选中态优先于视觉猜测。

## 工具系统

进入设备模式后默认加载 **核心工具**：

```text
observe  tap  tap_node  swipe  input_text  key  launch_app  open_url
wait  wait_for_text  media_state  media_speed  wait_media
find_text  scroll_to_text  get_activity  ocr
verify_text  verify_changed  note  todo_write  memory_read  memory_write  finish
```

其余能力按组加载（`load_tools`，也可由任务关键词自动激活）：

| 组 | 工具 | 用途 |
| --- | --- | --- |
| `inspect` | `read_page` `screenshot` `long_ocr` `see_screen` | 完整页面、截图、滚动长图、视觉识别 |
| `form` | `list_options` `verify_option` `verify_question` `answer_form` `ask_llm` `second_opinion` | 通用选项式表单、答题、复核 |
| `input` | `long_press` `double_tap` `swipe_direction` `clear_text` `set_text_id` | 扩展输入手势 |
| `apps` | `launch_activity` `stop_app` `list_packages` `app_info` `get_prop` | App / Activity / 包信息 |
| `system` | `shell` `settings_get` `settings_put` | 只读 shell、系统设置读写 |
| `files` | `file_list` `file_read` `file_write` | 应用沙箱内文件 |
| `memory` | `memory_list` `todo_read` | 记忆与计划读取 |

`load_tools("all")` 可一次性加载全部高级工具。`shell` 只允许只读查询，禁止 `input tap/swipe/keyevent`、`sendevent` 等 UI 注入，避免绕过坐标校验。

### 视频 / 播放任务

播放页不再靠猜“有没有播完”：

- `media_state` 读取当前时间、总时长、倍速、播放状态和控件坐标；只认播放器底部/边缘的纯时间文本，画面里的日期和时间戳会被排除；控制条自动隐藏后用上一次时长补全，切换视频时自动清掉旧缓存，不会点画面导致暂停。
- `media_speed(2.0)` 自动找“倍速/速度”控件并选择目标倍速，选项通过无障碍点击完成，避免横屏坐标偏移。
- `wait_media(target_ratio)` 每 15 秒重新读一次进度：按 `(总时长 × 目标比例 - 当前时间) / 实测倍速` 计算等待，播放暂停/缓冲会提前返回，不会盲目等满 5 分钟。
- 找不到倍速选项时不会退出播放页，直接用当前实测倍速继续等待；看完后再返回验证任务点状态。

## 会话、记忆与成本

- **会话列表**：`最近 / 置顶 / 收藏 / 归档` 四个视图，支持搜索；每行右侧 `⋮` 可置顶、收藏、归档、重命名、导出 Markdown、删除。
- **自动标题与摘要**：新会话根据首轮对话生成短标题和摘要；手动重命名后不再自动覆盖。
- **会话检查点**：同一会话每次请求都会带紧凑的原始目标、上次任务清单、结果和最近执行步骤；由模型按当前消息语义判断是新任务还是继续，不靠“继续”关键词。
- **消息操作**：长按可复制、引用、编辑并重发、重新生成，或从该消息分支出新会话。
- **附件**：一次最多 6 个；支持多图和多文件同时上传。图片本地 OCR；PDF 渲染前 6 页后 OCR；DOCX/XLSX/PPTX 解包提取文本；单文件上限 30MB。
- **模型**：点击输入框下方的模型名，可只切换当前会话的模型，不影响全局默认和其他会话。
- **记忆中心**：`设置 → 记忆` 查看、编辑、删除按 App 隔离的记忆。
- **统计**：`设置 → 统计` 查看 token、上下文、耗时，配置每 1M token 单价估算费用。

## Agent 状态

Agent 运行时，通知栏显示：

```text
Agent · 第 12 步 · 等待模型
最后动作：observe
已运行 03:42 · 最后更新 2s 前
```

阶段包括 `等待模型 / 操作中 / 等待确认 / 已完成 / 已停止 / 异常`。通知提供 **停止** 和 **打开会话** 两个动作；`stopAgent()` 只停止当前 Agent，不影响其他并发聊天。

设置中可开启 **悬浮状态窗**（需授予悬浮窗权限）。它是一个只读小胶囊，切到其他 App 时也能看到步骤、阶段和耗时；因为可能被截图/OCR 捕获，不需要时可以关闭。

## 通用答题

`answer_form` 不依赖具体 App，只处理通用的「题干 + 选项」结构：

1. 用 `long_ocr` 滚动截图并按真实滚动偏移拼接，避免只看到答案、看不到题目；
2. 三个模型分别独立作答并给出置信度；
3. 漏答会强制补答，最多 2 轮；整批失败再逐题询问；
4. 按答案字母集合投票，票数最多者胜出；三票全分歧时取平均置信度最高者；
5. 每个目标选项连续 3 次像素采样为选中才确认，跨屏选项按字母累计；
6. 所有题目确认完成才允许提交，有未答题不会点提交。

## 远程日志（可选）

把每一步的输入/输出上报到日志系统，不阻塞 Agent：

1. 在日志系统「日志报告 → 接入凭证」选择项目，生成 6 位一次性校验码；
2. App `设置 → 日志` 填写 `Log Base URL` 和校验码，点 **换取**；
3. 成功后自动填入 Agent JWT、显示绑定项目并打开开关，点 **保存日志配置**。

上报内容包括任务信息、每步模型 `content / reasoning / tool_calls`、工具参数与结果、LLM 耗时、异常和最终结果。文本单步最多 16000 字符，单条工具结果最多 8000 字符，**不上传截图二进制**。

> 现有 `GET /logReport/reports` 只接受用户登录 JWT；Agent JWT 只能 `POST /reports`、`POST /upload`、`GET /whoami`。如果要让 Agent 自己拉取历史日志做诊断，需要在服务端增加 `VerifyAgentToken` 作用域的只读接口。

## 目录结构

```text
app/src/main/java/com/catfee/rootauto/
├── MainActivity.kt              # 统一对话、会话、附件、设置
├── agent/
│   ├── ReActAgent.kt            # 主循环、流式、工具编排、上下文裁剪
│   ├── AgentTools.kt            # 工具定义、分组与门控
│   ├── ToolRuntime.kt           # 工具实现与页面校验
│   ├── AgentController.kt       # 多会话运行、Agent 全局锁、状态
│   ├── FormSolver.kt            # 通用选项式表单/答题
│   └── AppMemory.kt             # 按包名隔离的记忆
├── a11y/UiObserverService.kt    # 无障碍树、滚动、文本输入
├── session/
│   ├── SessionStore.kt          # 会话/消息/附件/token 持久化
│   ├── SessionManager.kt        # 会话切换、分支、裁剪
│   ├── SessionExporter.kt       # Markdown 导出
│   └── AttachmentAnalyzer.kt    # 图片 OCR / PDF / Office / 文本解析
├── ocr/
│   ├── OcrEngine.kt             # ML Kit + PP-OCR 合并
│   ├── PaddleOcrEngine.kt       # PP-OCRv3 ONNX 推理
│   └── LongOcr.kt               # 滚动拼接长页 OCR
├── root/
│   ├── RootShell.kt             # libsu root shell
│   ├── DaemonClient.kt          # uinput / 截图 daemon
│   ├── ScreenGeometry.kt        # display ↔ natural 坐标转换
│   └── ScreenVision.kt          # 截图 + 视觉模型
├── service/
│   ├── AgentStatusService.kt    # 前台通知 + 悬浮状态条
│   └── DisplaySettings.kt       # 悬浮窗开关
├── llm/                         # LlmClient / LlmSettings / UsageSettings
└── log/                         # LogSettings / AgentTrace

app/src/main/assets/ocr/         # PP-OCRv3 det/rec ONNX
daemon/                          # native 输入注入守护进程
magisk/                          # Magisk 模块
```

## 关键设计

| 主题 | 做法 |
| --- | --- |
| 页面读取 | 无障碍优先，空节点/画布降级到 OCR 和视觉模型；`read_page` / `see_screen` 对未变化页面做缓存；屏外节点标 `[屏外]`，提示先滚动再点 |
| 坐标 | 工具坐标统一为显示坐标；用 `dumpsys input` 的 `SurfaceOrientation` 校准固定横屏；旋转时优先无障碍手势，竖屏走 uinput；越界直接报错并给出当前尺寸 |
| 视频 | 读取播放时间/总时长/倍速；按实测倍速和剩余时长分段等待；控制条隐藏用缓存时长补全，不点画面、不退播放页 |
| 稳定性 | 动作后默认 `800ms` 稳定等待；相同动作连续 3 次拒绝、6 次终止；滚动/等待不受限 |
| 长任务 | 单次 `wait` 最长 5 分钟，每 15 秒报进度；LLM 超过 20 秒发心跳；最多 3000 步 / 8 小时 |
| 流式 | SSE 增量输出；服务端不支持 `stream_options` 时自动回退普通请求 |
| 安全 | `shell` 禁止 UI 注入；远程日志不上传截图；Key/Token 加密存储；源码不含真实密钥 |

## 常见问题

<details>
<summary><b>长任务看起来卡住了？</b></summary>

先看会话时间线或通知栏是否还有「等待模型 / 等待页面」心跳。如果有，说明是在等模型或等页面；如果心跳停止且长时间没有新步骤，再检查网络、无障碍服务和 LLM 接口。上下文超过 60 条消息或 40K 字符会自动裁剪，单条工具结果最多保留 10K。
</details>

<details>
<summary><b>安装或强停后无障碍失效？</b></summary>

重新执行第 3 节的 `settings put secure ...` 命令，或手动在系统设置里开启。部分 ROM 每次覆盖安装都会重置无障碍授权。
</details>

<details>
<summary><b>OCR 读不到浅色题号或小字？</b></summary>

优先用 `long_ocr`：它会滚动扫描并拼接整页，再按真实偏移还原坐标。PP-OCRv3 对浅色文字更敏感，ML Kit 对短选项字母更稳，两个引擎会合并结果。确保测试时屏幕常亮。
</details>

<details>
<summary><b>远程日志没有数据？</b></summary>

检查 `设置 → 日志` 的开关、绑定项目和「上报成功」状态；确认日志系统筛选的项目/时间范围正确；JWT 被吊销后需要重新生成校验码换取。
</details>

<details>
<summary><b>三个模型答案不一致？</b></summary>

按票数多数；三票全分歧时取平均置信度最高者。模型自评置信度不代表真实正确率，最终结果仍可能波动。
</details>

## 安全与隐私

- Root 权限可以执行高权限操作，请只在测试机或受控设备上使用；
- LLM Key 与 Agent JWT 使用 `EncryptedSharedPreferences` 保存，不写入日志；
- 远程日志会包含页面文字、工具参数与结果，请确认日志系统的访问权限；
- 不上传截图二进制；文本和工具结果会按长度截断；
- 源码不包含真实 API Key；`local.properties` 已加入 `.gitignore`。

## 已知限制

- **仅支持 Root 设备**：非 Root 路线尚未实现；
- **Agent 全局单例**：聊天可以并发，但同一时间只允许一个会话操作手机；
- **远程 trace 未按 run 隔离**：并发聊天时远程日志可能混在同一任务视图，本地会话和功能不受影响；
- **通知读取 / 分享面板接入**：尚未实现，当前通过无障碍和主动截图获取页面。

## 构建信息

| 项目 | 值 |
| --- | --- |
| 应用名 | `CatfeeAgents` |
| applicationId | `com.catfee.rootauto`（包名保持不变，兼容已授权的无障碍/日志配置） |
| 版本 | `0.1.0` |
| SDK | compileSdk 36 / targetSdk 34 / minSdk 26 |
| UI | Jetpack Compose + Material 3 |
| OCR | ML Kit Chinese + ONNX Runtime（PP-OCRv3） |
| 网络 | OkHttp |
| 输入注入 | native daemon + uinput |
| JDK | 17 |

## License

内部项目，未声明开源许可证。
