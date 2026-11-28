# Lily

[中文](README.md)

Lily is an open-source AI companion for Android. Set up a character, chat by text or voice,
share private feed posts, and revisit your memories together.

> AI companionship is like a lily: beautiful, fragrant, and poisonous to cats. Too controllable
> is frightening; too charming is just as dangerous. Lily wants to be the flower that keeps its
> own label attached.

Lily also inherits optional assistant features. When enabled, it can help with phone actions
through Android accessibility or connect to external services. Developers can extend these
features with skill folders containing `SKILL.md` and an optional `flow.yaml`.

- The Android app requires Android 11 (API 30) or newer.
- iOS cannot run it on the phone alone (one app may not operate other apps). You can try it from a Mac
  that drives the iPhone through WebDriverAgent; see [docs/ios.md](docs/ios.md) (Chinese).
- MCP servers (Amap, Tencent Maps, DiDi, 12306 ...) can serve lookups and bookings without operating
  the phone; see [docs/mcp.md](docs/mcp.md) (Chinese).
- A web version (add to the iPhone home screen) offers chat and one-tap MCP services without an app;
  see [docs/web.md](docs/web.md) (Chinese).
- Models: any OpenAI-compatible endpoint (Volcano Ark, Bailian, OpenRouter, vLLM, Ollama ...). You
  configure a chat model and a screen ("operator") model that accepts images.

## Safety

Every action passes the Sentinel policy before it reaches the phone, outside the model's control:
typing into password fields and acting on secure (FLAG_SECURE) screens are denied; taps on things
like 支付 / 发送 / 删除 / 确认呼叫 need your approval; a skill may only operate the apps it lists.
Skills can add confirmation words but never remove the built-in ones. Every decision is audited.

## Build

JDK 17 and Android SDK 35.

```
scripts/gradle.sh --online test :app:assembleDebug   # first run fetches dependencies
scripts/gradle.sh test :app:assembleDebug            # later runs work offline
```

`scripts/gradle.sh` wraps `./gradlew` and keeps build output outside the checkout; see the script
header for its environment variables.
The GitHub Actions CI workflow also builds a Lily debug APK.

## Use

1. Install the debug APK and open Lily.
2. In Me → Models and APIs, enter the base URL, model name, and API key for the chat and operator models.
3. Set up your character from Me, then start a conversation in Chat.
4. To use phone actions, enable Lily's accessibility service when prompted.

## Skills and developer CLI

- Format: [docs/skills.md](docs/skills.md); examples in [skills/examples/](skills/examples/).
- [cli/](cli/README.md) (Python 3, stdlib only) lints skills, pushes them over adb, runs tasks on a
  debug build and shows traces:

```
python3 cli/lily.py lint skills/examples/*
python3 cli/lily.py push skills/examples/taxi-home
python3 cli/lily.py run "打开设置里的 Wi-Fi 页面" --skill open-settings-wifi --follow
python3 cli/lily.py traces show <run_id>
```

## Layout

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): `core-*` are plain Kotlin/JVM modules (contracts,
LLM client, memory, skills, sentinel, agent), `hands` is the accessibility layer, `app` the UI.

## Memory

Notes are Markdown files in the Basic Memory format under
`/sdcard/Android/data/dev.lily.app/files/memory/`.
Forgetting deletes the file.

Those files belong to you, not to the app: pull the whole directory over `adb`, or open it with a
file manager where you have permission.

## Upstream vs. Lily

Lily is built on the open-source project Xiaoman (MIT, upstream copyright retained). Roughly:

**Inherited from upstream**: the accessibility-driven phone-action loop, the Sentinel policy layer, the
Basic Memory format and the skill system (`SKILL.md` / `flow.yaml`), MCP connectivity, the iOS WDA and
web versions, and the TRAE classroom handbook.

**Built for Lily**:

- **Multi-agent companionship** — every character has its own profile, conversations, companionship
  settings, memories, and call records, and several agents can live in one app.
- **Semantic memory** — optional vector retrieval on top of keyword search, with a per-character local
  index and a keyword fallback when embeddings are unavailable.
- **Group chats** — several agents in one room, with its own group list and session management.
- **Trash** — deleted agents and conversations can be restored.
- **Voice** — Doubao 2.0 streaming ASR and TTS with SiliconFlow as fallback; voices belong to a single
  agent instead of a global setting.
- **Interface redesign** — a Claude-style design system (paper background, terracotta accent, serif
  headings, larger body text, ≥48dp touch targets).

## License

Lily is based on Xiaoman and retains the upstream MIT license and copyright notice.
See [LICENSE](LICENSE).
