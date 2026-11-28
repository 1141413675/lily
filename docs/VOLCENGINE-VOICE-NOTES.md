# 火山引擎语音能力接入说明（2026-11-28）

给后续在 Lily 上做语音接入的人看。

---

## 一、已安装：火山引擎 Agent 技能包

### 安装位置

`~/.agents/skills/`（QwenWork 会同时加载 `~/.qwenworkcn/skills/` 与 `~/.agents/skills/`）

| 技能 | 行数 | 用途 |
|---|---|---|
| `volcengine-cli` | 190 | 火山引擎 CLI 总入口（`ve`） |
| `volcengine-troubleshooting` | 77 | 排障 |
| `volcengine-knowledge-search` | 144 | 检索官方文档（`ve docs search/fetch`） |
| `volcengine-find-skills` | 127 | 查找其他产品技能 |
| `volcengine-feedback` | 110 | 提交反馈 |
| `volcengine-billing-and-cost-management` | 100 | 账单与费用 |

### 安装范围说明

官方安装器的 `--agent other` 会铺到所有检测到的宿主（本次检测到 7 个：`.agents` / `.hermes` / `.qoder-cn` / `.trae` / `.trae-cn` / `.codeartsdoer` / `.codebuddy`）。

**按用户要求，只保留了 `~/.agents/skills/`**，其余 6 个宿主目录里的 volcengine 副本已删除（可从系统回收站恢复）。如需给其他宿主安装，跑：

```bash
npx --yes skills add volcengine/volcengine-agent-toolkit --global --yes --copy --full-depth \
  --skill volcengine-cli volcengine-troubleshooting volcengine-knowledge-search \
  --skill volcengine-find-skills volcengine-feedback volcengine-billing-and-cost-management
```

### ve CLI

- 安装位置：`~/.local/bin/ve`，版本 **2.0.2**（要求 ≥ 1.1.11）
- `npm i -g @volcengine/cli` 在这台机器上装不了：npm 全局目录在 DevEco Studio 里，无写权限（EPERM）。改用官方 CDN 安装器：
  ```bash
  curl -fsSL https://cloudcache.volccdn.com/ve/install.sh | sh
  ```
  装到 `~/.local/bin`（不 sudo）。**注意 PATH 里可能还没有 `~/.local/bin`。**
- `ve arkcli`（火山方舟）会按需自动下载，当前版本 1.0.49，装在 `~/.volcengine/proxy-cli/bin/arkcli`

### 能力检查（已通过）

| 检查项 | 结果 |
|---|---|
| `ve version` | 2.0.2 ✅ |
| `ve login --help` 含 `--no-browser` | ✅ |
| `ve ecs DescribeInstances --help` 含 `--output` | ✅ |
| `ve arkcli --help` 入口存在 | ✅ |
| `ve docs search` 实际检索成功 | ✅ |

### 授权状态：❌ 方舟未登录

```
$ ve arkcli auth status
{ "auth_method": "none", "logged_in": false,
  "hint": "run `arkcli auth login` or `arkcli auth login volc-sso`" }
```

登录由用户自己完成：`ve arkcli auth login`。**不要用创建资源、发起模型请求或购买套餐的方式去验证授权。**

### 调用标识

每次调 `ve` 都要设三个环境变量（技能 frontmatter 要求）：

```bash
export VE_CALLER_TYPE=ai_agent
export VE_CALLER_NAME="QwenWork"
export VE_SKILL_NAME=<当前技能的 frontmatter name>
```

### 已知问题

`ve skills update` 报 `Skill content sha256 mismatch for volcengine-billing-and-cost-management`，
重试仍失败。**技能内容本身完整可用**（SKILL.md + references + scripts，version 3.0.9），
不匹配是 CLI 内置的期望 sha 与 GitHub 源版本的差异。不影响使用。

### 当前会话尚未加载

新装的技能需要**新开会话或重启 QwenWork** 才会出现在技能清单里。当前会话用 `skill_search` 查不到 volcengine-* 属正常。
本次验证是直接调用 `ve docs search` 完成的（技能的实际能力入口）。

---

## 二、语音模型现状与下一步

### A. 实时全双工（通话）—— 已迁到豆包 3.0 Seeduplex

已完成，代码在 `app/src/main/kotlin/dev/lily/app/voice/`：

| 文件 | 作用 |
|---|---|
| `Seeduplex.kt` | 3.0 事件层：构造与解析，可单测 |
| `RealtimeClient.kt` | 连接、音频、本地判停、打断、工具回传、优雅关闭 |
| `VoiceRouting.kt` | 音色与通道的兼容判断 |

关键协议事实（官方《接入必读》）：

- 端点 `wss://openspeech.bytedance.com/api/v3/duplex/realtime/dialogue`，鉴权 `X-Api-Key`
- **模型固定 `1.2.6.1`**，不是用户填的模型名
- 建会话用 `session.create`（旧版是 `session.update`），纯 JSON 文本帧，音频 Base64 内嵌
- **3.0 没有服务端 VAD**：客户端要自己判断「用户说完了」，静够阈值后发 `input_audio_buffer.commit` 强制判停
- **麦克风静音时必须发 `input_audio_mute.commit` 保活**，否则服务端判无输入而超时（45000003）
- 工具结果回传结构变了：`conversation.item.create` + `items` 数组 + `role=tool` + `content[].type=input_text`，必须带与下行一致的 `call_id`
- 结束会话要先发 `session.close`、等 `session.closed` 再断链，直接断会被记 `ContextCanceled`（55000001）
- 输入 16k PCM，**输出 24k `pcm_s16le`**（播放端不必重采样）
- 限流：QPM 60、TPM 10 万（按 AppID/项目维度）

**音色**：S2S 全双工音色与豆包 TTS 2.0 是**同一套 `*_uranus_bigtts` id**，所以人设里选的音色可直接用于全双工。
但注意：音色列表里标 `仅限单向流使用，不支持双向流` 的那些（如部分外语音色）**不能**用于全双工。

**为什么必须迁**：Seeduplex 的纯端到端模式支持 Function Calling、MCP 与联网搜索，**旧版都不支持**。

### B. 语音合成（TTS）3.0 —— 尚未接入，值得做

**TTS 3.0 与现有 seed-tts-2.0 是不同的接入方式**，不是改参数能升的：

| | 现有（seed-tts-2.0） | TTS 3.0 |
|---|---|---|
| 域名 | `openspeech.bytedance.com` | **`voice.cn-beijing.volces.com`** |
| 双向流路径 | `/api/v3/tts/bidirection` | `/api/v3/text-to-speech/{voice_id}/streaming-input` |
| 音色参数 | body 里 `speaker` | **`voice_id` 放在 URL 路径里** |
| 语种 | 无 | `language_code`（100+ 语言/方言） |
| `X-Api-Resource-Id` 头 | `seed-tts-2.0` | 不需要 |
| 复刻音色 | — | `language` 指定录音语种；`POST /api/v3/tts/voice_clone` 注册，`POST /api/v3/tts/get_voice` 查状态 |

其他接口：

| 场景 | 方法与路径 |
|---|---|
| 单向流式（HTTP） | `POST /api/v3/text-to-speech/{voice_id}/streaming` |
| 双向流式（WebSocket） | `WSS /api/v3/text-to-speech/{voice_id}/streaming-input` |
| 音色注册（复刻） | `POST /api/v3/tts/voice_clone` |
| 音色查询 | `POST /api/v3/tts/get_voice` |
| 音色设计（文字描述或角色图） | `POST /api/v3/tts/voice_design` |

鉴权统一 `X-Api-Key: <API_KEY>`。

**计费**：按输入字符数。汉字（含日文汉字）每个计 2 个字符；英文字母、希腊字母、假名、标点、空格、回车各计 1 个。
例：`你好，world!` = 2 汉字 ×2 + 5 字母 + 2 标点 = **11 个计费字符**。

**对 Lily 的意义**：
1. 音色体系升级——TTS 3.0 有 100+ 语言/方言，而 Lily 现在是纯中文
2. `voice_design`（用文字描述或角色图设计音色）——可以做成「上传一张图，生成 TA 的声音」
3. 语速/音调/音量在 3.0 里是**会话级参数**（`speed` / `pitch` / `loudness`），比现在的逐句合成更自然
4. 现有 `voice_id` 直接放进 URL 路径，和 Lily 现在的 `speaker` 字段可以一一映射

参考：
- 概述：https://docs.volcengine.com/docs/doubao-voice/seed-tts-3-0-intro?lang=zh
- 语种列表：https://docs.volcengine.com/docs/doubao-voice/seed-tts-3-0-language-support
- 音色列表：https://docs.volcengine.com/docs/doubao-voice/voice-list-3-0-1

---

## 三、检索官方文档的正确姿势

装了技能包之后，查火山引擎文档不用再手写 URL：

```bash
export PATH="$HOME/.local/bin:$PATH"
export VE_CALLER_TYPE=ai_agent VE_CALLER_NAME="QwenWork" VE_SKILL_NAME=volcengine-knowledge-search

ve docs search "关键词" --limit 3 --output text
ve docs search "跨区域复制如何配置" --limit 5 --snippet-length 300 --output text
ve docs fetch "https://www.volcengine.com/docs/6349/162514?lang=zh" --output text
```

`--service-code tos` 可限定服务域。完整示例见 `~/.agents/skills/volcengine-knowledge-search/SKILL.md`。
