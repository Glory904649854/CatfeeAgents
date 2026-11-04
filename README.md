# CatfeeAgent

一个面向 Android 的通用 LLM 助手：既能正常聊天、分析图片/文件，也能用自然语言让 Agent 观察屏幕、选择工具并操作手机。

项目不针对任何具体 App 写死逻辑。没有包名判断、没有某款应用的专用流程；所有能力都以通用原子工具 + 通用页面识别的方式提供。答题/表单只是其中一个通用场景。

---

## 核心能力

- **统一对话**：同一个输入框里聊天、问答、分析附件或让它操作手机，不需要先选 Chat/Agent 模式。
- **流式输出**：SSE 流式返回，边生成边显示；带思考过程、打字机光标和动效。
- **附件分析**：
  - 支持一次上传多张图片/多个文件；
  - 图片先在本地做 PP-OCR / ML Kit 识别，再把 OCR 文本交给 LLM 判断；
  - 文本直接读取，PDF 渲染前几页后 OCR，DOCX/XLSX/PPTX 解包提取文本；
  - 不支持格式会明确提示，不会假装读过。
- **手机操作确认**：首次需要读取/控制手机时，在聊天框内弹一次确认卡；允许后本会话不再询问。
- **ReAct Agent**：OpenAI 兼容 function calling，循环执行「观察 → 思考 → 动作 → 校验」。
- **通用原子工具**：点击、长按、滑动、输入、启动 App、读无障碍树、截图、OCR、等待、校验、记忆、计划等。
- **本地 OCR**：
  - ML Kit 中文识别；
  - PP-OCRv3 ONNX（det + rec）本地推理；
  - 两个引擎结果合并，兼顾浅色小题号与短选项字母；
  - `long_ocr` 滚动截图拼接，按真实滚动偏移还原整页坐标。
- **通用答题/表单**：`answer_form` 自动读题、三模型独立作答投票、逐题像素核对、跨屏按选项字母确认，全部答完才提交。
- **按 App 隔离的记忆**：`files/memory/<package>.md`，每轮自动注入当前前台 App 的简短记忆。
- **远程步骤日志**：可选接入 `clouddream-server` 的 `/logReport`，按任务/步骤上报输入输出，便于排查与优化。
- **会话与统计**：历史会话可继续；每个会话显示累计 tokens、上下文长度、消息数/步骤数。
- **Markdown 输出**：标题、列表、加粗、行内代码、代码块；代码块带语言和复制按钮，整条消息也可复制。

## 统一对话 / 会话

- 普通聊天、问答、附件分析：直接回答，不调用设备工具。
- 需要读取/操作手机时：模型会先请求工具；App 在聊天框内弹出「允许控制手机？」确认卡。
  - 允许后写入当前会话 `phone_allowed`，本会话后续设备操作不再询问；
  - 拒绝则本次不执行，下一次仍会询问。
- 每条会话持久化消息、附件元数据、Agent 步骤、结果、token 累计和上下文长度；
  菜单 →「历史会话」可回到旧会话继续，菜单 →「新建会话」可开新会话。
- 切换会话后继续发消息：历史消息和附件解析摘要会作为 `priorContext` 带入新任务。

## 运行要求

| 项目 | 要求 |
| --- | --- |
| Android | 8.0+（minSdk 26） |
| 架构 | arm64 真机 |
| Root | Magisk / su 可用（uinput 注入、截图、部分系统操作） |
| 无障碍 | 开启 CatfeeAgent 的无障碍服务 |
| 通知 | Android 13+ 需授予通知权限 |
| 网络 | 能访问所配置的 LLM 接口；远程日志需访问 `https://api.catfee.cn`（可改） |

> Root 能力很强，请只在测试机/受控设备上使用。

---

## 快速开始

### 1. 构建

```bash
gradlew.bat assembleDebug
```

APK 输出：

```text
app/build/outputs/apk/debug/app-debug.apk
```

### 2. 安装

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 3. 开启无障碍

安装或强制停止 App 后，部分系统会关闭无障碍服务，需要重新开启：

```bash
adb shell settings put secure enabled_accessibility_services \
  com.catfee.rootauto/com.catfee.rootauto.a11y.UiObserverService
adb shell settings put secure accessibility_enabled 1
```

也可以在系统「无障碍 → 已下载的应用」中手动开启。

### 4. 配置 LLM

打开 App 右上角菜单 → **Settings**：

- `Base URL`：OpenAI 兼容地址，填到 `/v1` 或只填域名；
- `API Key`；
- `Model`：例如 `deepseek-v4.1-flash`；
- 点 `Save` 保存，或 `Test` 拉取模型列表验证连通性。

首次使用请在 Settings 里填写自己的 BaseUrl / API Key / 模型；仓库不包含真实 Key。

### 5. 执行任务

在底部输入框描述目标，例如：

```text
打开设置，查看当前 Android 版本，然后返回
```

点右侧圆形发送按钮。Agent 会自己选择工具执行；运行中可随时点 `Stop`。步骤会实时显示在 Activity 时间线，通知栏也会显示当前动作。

---

## 通用答题 / 表单机制

`answer_form` 不依赖具体 App，适用于「题干 + A/B/C/D 选项」的通用页面。

流程：

1. 滚动拼接识别整页题目（`long_ocr` + 选项字母归一化）；
2. 三个模型各自独立作答，并给出 `confidence`：
   - `deepseek-v4.1-flash`
   - `glm-5.3-flash`
   - `kimi-k2.6`
3. 模型漏答时强制补答（最多 2 轮，整批失败再逐题问）；
4. 按答案字母集合投票：
   - 票数最多者胜出；
   - 三票全部分歧时取平均置信度最高的答案；
5. 落到屏幕上后，每个目标选项连续 3 次像素采样为选中才确认；
6. 选项可跨屏：每屏只确认当前可见的字母，累计到整题完成；
7. 所有题目确认完成才点提交；有未答题不会提交。

单题提示词模板：

```text
只输出 JSON: {"answers":[{"q":1,"letters":["A"],"confidence":85}]}
判断题 A=对 B=错; 多选返回所有正确字母; confidence 是正确概率(0-100)。
1.[单选题] <题干>
A <选项>
B <选项>
C <选项>
D <选项>
```

该机制只处理通用选项式表单，不包含任何具体 App 的包名/控件 id 判断。

---

## 远程日志（可选）

对接 `clouddream-server` 的 `logReport` 模块。

### 启用

1. 打开日志系统 →「日志报告」→「接入凭证」；
2. 选择项目节点，生成 6 位一次性校验码；
3. App 菜单 → Settings → **Remote trace**：
   - `Log Base URL` 默认 `https://api.catfee.cn`；
   - 输入校验码 → `Exchange`；
   - 成功后自动填入 Agent JWT、显示绑定项目并打开开关；
   - 点 `Save trace config`。

也可以直接粘贴已有的 Agent Token 并打开开关。

> 读取说明：现有 `GET /logReport/reports` 只接受**用户登录 JWT**；Agent JWT 只能
> `POST /reports`、`POST /upload`、`GET /whoami`。如果希望 Agent 自己拉取历史日志做诊断，
> 需要在服务端新增一个 `VerifyAgentToken` 作用域的只读接口（按 token 绑定项目查询）。

### 上报内容

每个任务会生成一个 `task_id`，异步上报（不阻塞 Agent）：

- **任务开始**：目标、模型、当前 App 包名、设备/Android 版本、system prompt；
- **每一步**：上一条输入消息、模型 `content` / `reasoning` / 计划工具调用、实际工具 `arguments` / `result` / `elapsed_ms`、LLM 耗时；
- **异常**：LLM 重试、超时、用户停止、任务异常；
- **任务结束**：成功/失败、最终结果、步数、总耗时。

接口：

```http
POST {base}/logReport/reports
Authorization: Bearer <Agent JWT>
Content-Type: application/json

{
  "content_type": "json",
  "level": "info",
  "title": "Step 1 · observe",
  "content": {
    "task_id": "T...",
    "step": 1,
    "goal": "...",
    "input": { "last_message": "..." },
    "output": { "content": "...", "reasoning": "...", "tool_calls": [] },
    "tools": []
  },
  "tags": ["agent", "step", "com.example.app"],
  "source": "CatfeeAgent/Android",
  "extra": { "task_id": "T...", "step": 1, "package": "com.example.app" }
}
```

文本单步最多 16000 字符，单条工具结果最多 8000 字符；不上传截图二进制。

---

## 工具分类

| 类别 | 工具示例 |
| --- | --- |
| 观察 | `observe`、`read_page`、`screenshot`、`ocr`、`long_ocr`、`find_text`、`get_activity`、`app_info`、`get_prop`、`list_packages` |
| 操作 | `tap`、`double_tap`、`long_press`、`swipe`、`swipe_direction`、`key`、`input_text`、`clear_text`、`set_text_id`、`tap_text`、`tap_id`、`tap_desc`、`launch_app`、`launch_activity`、`stop_app`、`open_url`、`settings_get`、`settings_put`、`shell` |
| 等待/校验 | `wait`、`wait_for_text`、`verify_text`、`verify_option`、`verify_question`、`verify_changed`、`scroll_to_text` |
| 表单/答题 | `answer_form`、`list_options` |
| 记忆/计划 | `memory_read`、`memory_write`、`memory_list`、`note`、`todo_write`、`todo_read` |
| 模型辅助 | `ask_llm`、`second_opinion` |
| 文件 | `file_list`、`file_read`、`file_write` |
| 控制 | `finish`、`tool_list` |

工具清单以 `AgentTools.definitions()` 为准。

---

## 目录结构

```text
app/src/main/java/com/catfee/rootauto/
├── MainActivity.kt              # 统一对话 UI（流式、附件、会话、设置）
├── agent/
│   ├── ReActAgent.kt            # 主循环、流式、权限门、消息裁剪
│   ├── AgentTools.kt            # 工具定义与分发
│   ├── ToolRuntime.kt           # 工具实现/页面校验
│   ├── FormSolver.kt            # 通用选项式表单/测验
│   ├── AppMemory.kt             # 按包名隔离的简短记忆
│   └── AgentController.kt       # 任务状态、流式文本、确认/状态流
├── a11y/UiObserverService.kt    # 无障碍树、滚动、焦点信息
├── session/
│   ├── SessionStore.kt          # 会话/消息/附件/token 持久化
│   ├── SessionManager.kt        # 当前会话与历史摘要
│   └── AttachmentAnalyzer.kt    # 图片 OCR / PDF/Office/文本解析
├── ocr/
│   ├── OcrEngine.kt             # ML Kit + PP-OCR 合并
│   ├── PaddleOcrEngine.kt       # PP-OCRv3 ONNX 推理
│   └── LongOcr.kt               # 滚动拼接长页 OCR
├── root/                        # RootShell / DaemonClient / ScreenVision
├── llm/                         # LlmClient / LlmSettings（含 SSE 流式）
├── log/
│   ├── LogSettings.kt           # 远程日志配置 + 校验码换 JWT
│   └── AgentTrace.kt            # 异步步骤上报
└── service/AgentStatusService.kt

app/src/main/assets/ocr/         # PP-OCRv3 det/rec ONNX
daemon/                          # 原生输入注入守护进程
magisk/                          # Magisk 模块相关文件
```

---

## 设计说明

- Agent 每轮把 system prompt、便签、计划、App 记忆和最近对话发给模型；
- 普通消息没有工具调用时，模型文本直接作为最终回答，不强制 `finish`；
- 首次设备读取/操作通过 `ensurePhonePermission` 在聊天框内请求一次授权，授权写入会话；
- 图片本地 OCR、文件本地解析后再把文本交给模型；代码块和消息都支持复制；
- SSE 流式输出失败时自动回退普通请求，兼容不支持 `stream_options` 的服务端；
- 历史同时按 80 条消息和 60K 字符预算裁剪；单条工具结果最多 16K 字符，超出会截断并提示重新读取；
- 任务目标每轮固定在 system 中，不会因为历史裁剪而丢失；
- 裁剪以 assistant 为边界，避免留下没有 `tool_calls` 的孤儿 tool 消息导致接口报错；
- 便签/计划分别限制 4K/3K 字符，防止长任务自己把上下文撑爆；
- 连续重复调用、连续无界面变化、只观察不动作时，会注入硬提醒（user 消息）；
- 动作后默认等待页面稳定再读取，减少读到旧界面的概率；
- 优先使用无障碍读页面，读不到时降级到 OCR/截图；
- 所有页面操作使用屏幕绝对坐标，滚动后重新识别坐标，不复用旧值；
- 长等待（`wait` / `wait_for_text`）单次最长 5 分钟，每 15 秒发一条进度；LLM 请求超过 20 秒也会发心跳和上下文规模。

---

## 常见问题

**安装/强停后无障碍失效？**
重新执行上面的 `settings put secure ...` 命令，或手动在系统设置里开启。

**OCR 读不到浅色题号/小字？**
使用 `long_ocr`；PP-OCRv3 对浅色文字更敏感，ML Kit 对短选项字母更稳。确保屏幕常亮。

**长任务看起来卡住？**
先看 Activity 是否出现「等待模型响应」或「等待…中」心跳：

- 上下文现在按 80 条 / 60K 字符双预算裁剪，单条工具结果最多 16K 字符；
- 任务目标每轮固定在 system，不会因为裁剪丢失；
- `wait` / `wait_for_text` 单次最长 5 分钟，每 15 秒发进度；需要更久时 Agent 会重新发起；
- LLM 请求超过 20 秒会显示心跳和当前上下文规模；
- 远程日志的 `context.chars`、`context.messages`、`llm_ms` 可以直接定位是否是上下文过大。

如果心跳仍在更新，说明是在等模型/等页面，不是进程卡死；如果心跳停止且长时间无新步骤，再检查网络、无障碍服务和 LLM 接口。

**LLM 请求被重定向到网页/登录页？**
手机所连 Wi‑Fi 可能被强制门户拦截。先完成该 Wi‑Fi 认证，或换网络。App 允许 cleartext HTTP，但强制门户仍会拦截。

**远程日志没有出现？**
- Settings 里 Remote trace 是否打开；
- 是否显示绑定项目和「上报成功」状态；
- 日志系统筛选的项目/时间范围是否正确；
- JWT 是否被吊销（吊销后重新生成校验码）。

**三模型答案不一致？**
按票数多数；三票全分歧时取平均置信度最高者。模型自评置信度不代表真实正确率，最终分数可能波动。

**答题页核对一直不通过？**
检查选项 OCR 字母是否被误读（如 `Al`、`B|`、小写 `c`）；当前解析已做归一化。跨屏选项会逐字母累计确认。

---

## 安全与隐私

- Root 权限可执行高权限操作，请只在受控设备使用；
- LLM Key 与 Agent JWT 使用 `EncryptedSharedPreferences` 保存，不写入日志；
- 远程日志会包含页面文字、工具参数与结果，请确认日志系统的访问权限；
- 不上传截图二进制；文本与工具结果会做长度截断；
- 源码不包含真实 LLM API Key；`local.properties` 已加入 `.gitignore`，请在本机 Settings 或本地配置中填写。

---

## 构建信息

- 应用名：`CatfeeAgent`
- `applicationId`: `com.catfee.rootauto`（包名保持不变，兼容已授权的无障碍/日志配置）
- `versionName`: `0.1.0`
- `compileSdk`: 36 / `targetSdk`: 34 / `minSdk`: 26
- UI：Jetpack Compose + Material 3
- OCR：ML Kit Chinese + ONNX Runtime（PP-OCRv3）
- 网络：OkHttp

---

## License

内部项目，未声明开源许可证。
