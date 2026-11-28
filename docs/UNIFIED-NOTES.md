# Lily 统一说明（2026-11-28）

给接下来在这个仓库上工作的 AI（或人）看。**请先读完这一页再动代码。**

---

## 一、现在的情况

之前有两个 AI 同时在改这个项目，改出了两套代码、装出了两个 App。**现在已经合并成一个了。**

| | 之前 | 现在 |
|---|---|---|
| 代码 | `~/Documents/lily`（xiaoman 命名）<br>`outputs/lily-renamed`（lily 命名） | **只有 `~/Documents/lily`** |
| 包名 | `dev.xiaoman.app` + `dev.lily.app` 两个 | **`dev.lily.app` 一个** |
| 桌面图标 | 两个都叫 Lily，看不出区别 | **一个** |
| 分支 | `feat/vendor-presets-tts` | **`feat/lily-unified-ui`**（新增，未提交） |

**当前分支 `feat/lily-unified-ui` 就是合并后的完整版**（含两个 AI 的全部改动），工作区里有 374 个文件的改动，**尚未提交、未推送**。

原有分支 `feat/vendor-presets-tts`（HEAD = `3c2a508`）和 `backup/vendor-tts-snapshot` 都完整保留，没有动过。

---

## 二、改名约定（重要，别改回去）

代码包名和品牌名是**故意分开的**：

| | 命名 | 说明 |
|---|---|---|
| 代码包 | `dev.lily.app` / `dev.lily.agent` / … | 统一 lily |
| 类名 | `LilyApp` / `LilyAgent` / `LilyRoot` / `LilyAccessibilityService` / `LilySwitch` / `LilyTheme` / `LilySans` / `LilyPalette` | 统一 Lily 前缀 |
| **品牌字标** | `LilyMark.kt`、函数 `LilyMark()` | **保留 Lily**，这是产品名 |
| **品牌资源** | `ic_stat_lily.xml`、`ic_notification_lily.xml`、`lily_brand.xml` | **保留 lily**，这是资源名 |
| **应用名** | `<string name="app_name">Lily</string>` | **保留 Lily** |
| 无障碍资源键 | `lily_accessibility_*`、`lily_overlay_*` | 已统一 |

曾经存在过 `xiaoman` / `小满` 的痕迹，**现在一个都不剩**。请不要往回改。

---

## 三、两个 AI 各自做了什么（都已合并）

### 来自前一个 AI（13:29 那批 UI 增量）

1. **字号体系抬底 + 分流**（`DesignTokens.kt`）
   - 新增 `TypeScale.caption = 13sp/18sp`（信息性辅助说明）
   - `label` 11.5 → 12.5sp，`micro` 10.5 → 11sp
   - 圆角 `lg` 20→16、`md` 14→12；`touchTarget` 44→48dp
   - **动机**：原有 106 处信息性文字挤在 10.5/11.5sp 两档，是「小字密密麻麻」的直接来源
2. **三个共享组件**（`Theme.kt`）
   - `CaptionTextStyle` — 信息性文本样式
   - `InlineError(text)` — 错误留在页面里（带 `liveRegion`），不再只用 Toast
   - `ConfirmDangerDialog(title, text, onConfirm, onDismiss, confirmLabel)` — 破坏性操作统一二次确认
3. **二次确认覆盖到所有不可逆操作**：撤回消息、删共享经历、删话题、删习惯、删图、移出群成员
4. **Toast → 内联错误**：`RelationshipScreen`、`PersonaScreen` 的「保存失败，请重试」
5. **模型页字段标签与输入框脱开**（`FieldLabel`，`SettingsScreen.kt`）——原来标签贴着输入框上沿，归属关系不直观
6. **「拉取可用模型」按钮** `TextButton` → `OutlinedButton`，和说明文字分层

### 来自后一个 AI（改名 + UI 改造）

1. **全量改名**：`xiaoman` / `XIAOMAN` / `小满` → `lily` / `LILY` / `Lily`，343 个文件
2. **动效词汇表**（`ui/MotionStates.kt`，新增）——全 app 唯一的动效来源：
   - `PulsingDots`（三个依次亮起的点）、`SpinningArc`（转弧）、`SlidingBar`（滑动条）、`BreathingHalo`（呼吸光环）
   - 规则：**瞬时状态用动效，持续事实用文字**
3. **私聊不带头像**：她的文字气泡和语音气泡贴着同一条左边线（原来文字带头像、语音不带，看着像两个人）
4. **通话上灵动岛**（`Notifier.kt`）：`CallStyle` + `android.requestPromotedOngoing`，实测出胶囊
5. **语音图标方向修正**：两侧都改成「图标在左、波纹朝右」，完全一致（原来我的那条波纹朝屏幕外指）
6. **微信式按住说话浮层**：深色浮层 + 上滑取消（箭头变红）
7. **ChatGPT 式通话球**（`VoiceOrb`）：思考/她在听/她在说三态，一屏只留一个主角，屏幕不再写状态
8. **厂商选择器**（`VendorPickerSheet`，新增）：27 个厂商分三组（自家模型厂商 / 聚合平台 / 本机自建），可搜索
9. **音色选择器**（`VoicePicker`，新增）：三列网格 + 分段控件 + 搜索，原来的长列表（八屏）压到一屏

---

## 四、遗留问题（还没做的，按优先级）

| 优先级 | 问题 | 位置 |
|---|---|---|
| 🔴 | 约 400 处中文文案内联在 Composable 里，`strings.xml` 已有 100+ 条却不用 | `VoiceCloneScreen` 72 处、`FeedScreen` 60、`MyProfileSection` 50、`SettingsScreen` 39 |
| 🟠 | **同一动作多种实现**：「删除」9 种、「试听/播放」4 种、「返回」2 种、「取消」文案在「取消/再想想」间混用 | 全 app |
| 🟠 | **触控目标 < 44dp**：`GroupScreens` 返回箭头 24dp、`VoicePicker` 试听喇叭 26dp、`FeedScreen` 移除图片 × 20dp、`ComposerPanels` 按钮 34dp 高 | 见左 |
| 🟡 | **38 处 `contentDescription = null`**，部分是有意义的图标 | `FeedScreen` 4、`MemoryScreen` 4、`ChatComponents` 6 |
| 🟡 | **302 处裸 `.dp` 字面量**，绕过 `Space`/`Radius` token | `ChatComponents` 59、`ChatScreen` 40、`FeedScreen` 38 |
| 🟡 | `tapTarget()` 只约束高度不约束宽度，全 app **0 处**用 `minimumInteractiveComponentSize()` | `Theme.kt:202` |
| 🟡 | 仍有「正在…」类过程状态用文字表达，违反 `MotionStates.kt:32` 的规则 | `CallScreen` 4 处、`MemoryScreen:324`、`FeedScreen:940` |
| 🟡 | 约 8 个破坏性操作仍无二次确认（`ScenerySection` 删图已补，其余待查） | 待排查 |

**建议顺序**：先把「同动作多实现」统一掉（抽 `DangerButton` / `IconAction` / `PlayPreviewButton` 三个共享组件），再处理触控目标，最后清理文案外置。

---

## 五、构建与验证

```bash
cd ~/Documents/lily
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH=$JAVA_HOME/bin:$PATH

scripts/gradle.sh --online :app:testDebugUnitTest :app:assembleDebug
```

当前状态：**259 项单测全绿，`:app:assembleDebug` 成功**。

真机（`192.168.3.74:41281`，HONOR BVL-AN00 / MagicOS 11）：

```bash
D=192.168.3.74:41281
adb -s $D install -r ~/.cache/xiaoman/*/out/app/outputs/apk/debug/app-debug.apk
adb -s $D shell am start -n dev.lily.app/.MainActivity
# 调试桥（debug 包）
adb -s $D shell am broadcast -n dev.lily.app/.debug.DebugReceiver \
  -a dev.lily.debug.SAY --es text "在吗"
```

无障碍服务需手动开启：`dev.lily.app/dev.lily.hands.LilyAccessibilityService`。

---

## 六、已知的历史遗留（与本次统一无关）

- `ios-runner` 的 `paymentTapIsBlockedWhenNobodyIsThere` 断言期望「已自动拒绝」，实际是拒答文案「这个得你自己来。付款、密码、验证码这类操作我不会做。」——**模型侧文案改了，测试没跟上**
- `web-server` 的 `LocalLaunchTest.startMovesToTheNextFreePort` 失败——端口探测环境问题
- `ios-runner/Main.kt` 原先缺 `is AgentEvent.Notice` 分支导致编译不过，**已修**
- 仓库仍有 8 个提交未推送到 `origin`（`origin/main` 落后于 `feat/vendor-presets-tts`）

---

## 七、备份位置（改错了可以回滚）

| 内容 | 路径 |
|---|---|
| 统一前的原仓库完整状态（patch + 文件树 + HEAD） | `outputs/backup-orig-lily-20261128-1340/` |
| 前一个 AI 的独立改动（7078 行 patch） | `outputs/backup-orig-lily-20261128-1340/other-ai-work.patch` |
| 原仓库里的 git stash | `git stash list` → `stash@{0}`（pre-unify-1345） |
| 合并后的完整副本（lily 命名，未包名迁移前的版本） | `outputs/lily-renamed/` |
| 体验走查报告 | `outputs/lily-ux-audit.md` |

---

## 八、请注意

- **不要在 `~/Documents/lily` 之外再开一份代码副本。** 之前出问题就是因为两个 AI 各有一份，改到最后两边分叉、装出两个 App。
- **不要改回 xiaoman / 小满命名**（品牌字标 `LilyMark`、`app_name`、资源文件 `*_lily.xml` 除外——那些是品牌名，故意保留）。
- **改动先跑单测再提交。** `:app:testDebugUnitTest` 全绿是最低门槛。
- 当前 `feat/lily-unified-ui` 分支的 374 个文件改动**尚未提交**。要提交请先确认内容，或让我来提交。
