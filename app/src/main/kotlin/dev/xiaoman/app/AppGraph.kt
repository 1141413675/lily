package dev.xiaoman.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import dev.xiaoman.agent.XiaomanAgent
import dev.xiaoman.api.Agent
import dev.xiaoman.api.AuditSink
import dev.xiaoman.api.ModelRole
import dev.xiaoman.app.dream.DreamEngine
import dev.xiaoman.app.dream.DreamScheduler
import dev.xiaoman.app.dream.DreamStore
import dev.xiaoman.app.dream.RuleStore
import dev.xiaoman.app.relationship.ProactiveEngine
import dev.xiaoman.app.relationship.ProactiveScheduler
import dev.xiaoman.app.relationship.RelationshipPrompt
import dev.xiaoman.app.relationship.RelationshipStore
import dev.xiaoman.app.safety.SafetyStore
import dev.xiaoman.app.session.AgentSession
import dev.xiaoman.app.session.SessionTexts
import dev.xiaoman.app.settings.AppSettings
import dev.xiaoman.app.settings.SettingsStore
import dev.xiaoman.app.trace.RecordingDevice
import dev.xiaoman.app.trace.TraceWriter
import dev.xiaoman.app.voice.CallController
import dev.xiaoman.app.voice.VoiceMessages
import dev.xiaoman.hands.AccessibilityDevice
import dev.xiaoman.hands.MutableStopSignal
import dev.xiaoman.hands.XiaomanAccessibilityService
import dev.xiaoman.llm.LlmClientFactory
import dev.xiaoman.llm.LlmClients
import dev.xiaoman.app.settings.McpSettingsStore
import dev.xiaoman.mcp.McpCatalog
import dev.xiaoman.mcp.McpConfig
import dev.xiaoman.mcp.McpPlatform
import dev.xiaoman.mcp.McpSelection
import dev.xiaoman.mcp.McpToolProvider
import dev.xiaoman.mcp.ServerStatus
import dev.xiaoman.mcp.UserChannelApprovals
import dev.xiaoman.memory.FileMemoryStore
import dev.xiaoman.sentinel.PolicySentinel
import dev.xiaoman.skills.DirectorySkillRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.io.IOException

/**
 * Manual dependency container. Everything is built lazily; [start] runs once from
 * [XiaomanApp.onCreate].
 *
 * Folders (`<external files>` = `/sdcard/Android/data/dev.xiaoman.app/files`):
 * `memory/`, `skills/` (user skills, override bundled ones), `traces/`, optional `mcp.json` (MCP servers,
 * HTTP only; keys are written in the file because Android has no environment); bundled skills are
 * extracted to `<files>/bundled-skills/`.
 */
class AppGraph(context: Context, private val llm: LlmClientFactory = LlmClients.default) {
    private val app: Context = context.applicationContext

    /** Process-wide scope; the session runs agent work here. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val externalRoot: File = app.getExternalFilesDir(null) ?: File(app.filesDir, "external")
    val memoryDir = File(externalRoot, "memory")
    val skillsDir = File(externalRoot, "skills")
    val tracesDir = File(externalRoot, "traces")
    val bundledSkillsDir = File(app.filesDir, "bundled-skills")
    val mcpFile = File(externalRoot, "mcp.json")

    val settings: SettingsStore by lazy { SettingsStore.create(app) }

    val memory by lazy { FileMemoryStore(memoryDir) }

    val skills by lazy { DirectorySkillRepository(listOf(bundledSkillsDir, skillsDir)) }

    val notifier by lazy { Notifier(app) { settings.current.persona.nameOr(app.getString(R.string.app_name)) } }

    val reminderScheduler by lazy { AlarmReminderScheduler(app) }

    /** Services ticked on the 服务 screen; keys live in the encrypted settings file. */
    val mcpSettings: McpSettingsStore by lazy { McpSettingsStore.create(app, settings.secret) }

    private var cachedMcp: Pair<McpSelection, McpToolProvider?>? = null

    /** MCP config of [selection] plus the HTTP servers of the optional `mcp.json` (stdio needs a desktop). */
    fun mcpConfig(selection: McpSelection): McpConfig {
        val ticked = McpCatalog.builtIn.toConfig(selection, McpPlatform.ANDROID)
        val file = McpConfig.load(mcpFile, emptyMap())
        val fileServers = file.servers.filter { it.enabled && it.url != null && ticked.servers.none { t -> t.name == it.name } }
        (ticked.warnings + file.warnings).forEach { Log.w(TAG, "mcp: $it") }
        return McpConfig(ticked.servers + fileServers, ticked.warnings + file.warnings)
    }

    /** The provider for [selection], rebuilt (and the old one closed) when the ticked services change. */
    @Synchronized
    private fun mcpFor(selection: McpSelection): McpToolProvider? {
        cachedMcp?.let { (s, provider) -> if (s == selection) return provider }
        cachedMcp?.second?.close()
        val config = mcpConfig(selection)
        val provider = if (config.servers.isEmpty()) null else McpToolProvider(config, UserChannelApprovals(session))
        cachedMcp = selection to provider
        return provider
    }

    /** Connects to the services of [selection] once, for the 测试连接 button; never kept. */
    suspend fun testMcp(selection: McpSelection): List<ServerStatus> {
        val config = mcpConfig(selection)
        if (config.servers.isEmpty()) return emptyList()
        return McpToolProvider(config, { false }).use { it.status(refresh = true) }
    }

    val runIds = RunIds()
    val uiVisible = MutableStateFlow(false)

    val traces by lazy {
        TraceWriter(
            root = tracesDir,
            secrets = { settings.current.secrets },
            log = { Log.i(TAG, it) },
            warn = { Log.w(TAG, it) },
        )
    }

    val accessibilityDevice by lazy { AccessibilityDevice({ XiaomanAccessibilityService.instance.value }, app) }

    private val device by lazy { RecordingDevice(accessibilityDevice, traces::saveScreenshot) }

    /** 安全拦截与确认记录：`<files>/safety/blocks.jsonl`，在设置里可看可清空。 */
    val safetyStore by lazy { SafetyStore(File(externalRoot, "safety")) }

    /** 拒答兜底账本与每小时重试上限；只在本地。 */
    val refusals by lazy {
        dev.xiaoman.app.safety.RefusalStore(
            prefs = app.getSharedPreferences("refusal-stats", Context.MODE_PRIVATE),
            file = File(File(externalRoot, "safety"), "refusals.jsonl"),
            info = {
                val s = settings.current
                dev.xiaoman.app.safety.RefusalStore.Info(
                    provider = android.net.Uri.parse(s.chat.baseUrl).host ?: s.chat.baseUrl,
                    model = s.chat.model,
                    style = s.persona.expression.label,
                )
            },
        )
    }

    val sentinel by lazy {
        PolicySentinel(
            approvals = session,
            audit = AuditSink { record ->
                traces.record(record)
                safetyStore.record(record)
            },
            selfPackage = app.packageName,
        )
    }

    val session: AgentSession by lazy {
        AgentSession(
            scope = scope,
            agent = ::agent,
            runIds = runIds,
            stopSignal = MutableStopSignal(),
            trace = traces,
            onTaskEnd = { sentinel.endTask(it) },
            texts = SessionTexts(
                notConfigured = app.getString(R.string.session_not_configured),
                busy = app.getString(R.string.session_busy),
                stopped = app.getString(R.string.session_stopped),
                approvedOnce = { app.getString(R.string.approval_resolved_allowed, it) },
                approvedTask = { app.getString(R.string.approval_resolved_task, it) },
                denied = { app.getString(R.string.approval_resolved_denied, it) },
            ),
            ready = { startup.await() },
            safetyAudit = { safetyStore.record(it) },
        )
    }

    /** 通话页的语音层：录音、识别、朗读；只调用 [AgentSession.submit]，不改变 agent 行为。 */
    val callLogs by lazy { dev.xiaoman.app.voice.CallLogStore(File(app.filesDir, "calls")) }

    val call by lazy { CallController(app, session, settings, scope, relationship, callLogs) }

    /**
     * 消息层的语音：聊天里的语音消息与语音回复。音频落在 `<files>/audio`，聊天行只存路径。
     */
    val voice by lazy { VoiceMessages(app, session, settings, scope, { call }, { openCall.value = true }) }

    /** 回复方式为「发起通话」时置 true；根界面切到通话 Tab 后复位。 */
    val openCall = MutableStateFlow(false)

    /** 关系档案：现有 Markdown 记忆里的 `relationship/profile.md`，不新开存储。 */
    val relationship by lazy { RelationshipStore(memory, scope) }

    /** 表情素材库：`<files>/stickers`，只存本机。 */
    val stickers by lazy { dev.xiaoman.app.media.StickerStore(File(externalRoot, "stickers")) }

    /** 聊天照片：`<files>/photos`，只存本机。 */
    val photos by lazy { dev.xiaoman.app.media.PhotoStore(File(app.filesDir, "photos")) }

    /** 生活分享图库：用户自建、按地点标签，她按聊天上下文挑图。 */
    val scenery by lazy { dev.xiaoman.app.media.SceneryStore(File(externalRoot, "scenery")) }

    /** 她主动发图：生成图只落应用私有目录 `<files>/images`；复用与图库只引用原文件。 */
    val chatImageStore by lazy { dev.xiaoman.app.image.ChatImageStore(File(app.filesDir, "images")) }

    /** 方舟图片生成；无 Key、网络或额度失败都返回 null，由发图逻辑静默降级。 */
    private val imageGenerator by lazy { dev.xiaoman.app.image.ImageGenerator() }

    /** 发图：回忆语境复用旧图；想看此刻按对话细节生成；失败或没 Key 时图库静默兜底。 */
    val chatImages by lazy {
        dev.xiaoman.app.image.ChatImages(
            scope = scope,
            settings = { settings.current },
            items = { session.items.value },
            portraitFile = { portrait.displayFile() },
            store = chatImageStore,
            scenery = scenery,
            post = { session.postAssistantImage(it) },
            generate = { prompt, reference ->
                val current = settings.current
                if (current.chat.apiKey.isBlank()) {
                    null
                } else {
                    imageGenerator.generate(
                        baseUrl = current.chat.baseUrl,
                        apiKey = current.chat.apiKey,
                        model = current.image.effectiveModel,
                        prompt = prompt,
                        reference = reference,
                    )
                }
            },
            promptFor = { selfie, context ->
                val current = settings.current
                dev.xiaoman.app.image.ImagePrompt.build(selfie, context, current.persona.nameOr(app.getString(R.string.app_name)), current.life)
            },
        )
    }

    /** 头像：从相册选的图存在 `<files>/portrait`，只落本机。 */
    val portrait by lazy { dev.xiaoman.app.portrait.PortraitStore(File(app.filesDir, "portrait")) }

    /** 用户本人档案：`<files>/profile/me.json`，不碰她的人设键。 */
    val myProfile by lazy { dev.xiaoman.app.profile.MyProfileStore(File(app.filesDir, "profile/me.json")) }

    /** 用户自己的头像：`<files>/profile/avatar`，与她的 [portrait] 分开。 */
    val myAvatar by lazy { dev.xiaoman.app.portrait.PortraitStore(File(app.filesDir, "profile/avatar")) }

    /** 克隆音色的本地索引：只存云端 uri 与名称，样本不落盘。 */
    val clones by lazy { dev.xiaoman.app.voice.ClonedVoiceStore(File(app.filesDir, "voices")) }

    /** 她自己的动态：`<files>/feed`，只存本机；生成见 [feed]。 */
    val feedStore by lazy { dev.xiaoman.app.feed.FeedStore(File(externalRoot, "feed")) }

    /** 动态生成：模型可用就写，不行退回模板；时间按本机时区。 */
    val feed by lazy {
        dev.xiaoman.app.feed.FeedEngine(
            store = feedStore,
            settings = { settings.current },
            llm = llm,
            scope = scope,
        )
    }

    /** 动态配图：`<files>/feed/images`，复制落盘，原图不动。 */
    val feedPhotos by lazy { dev.xiaoman.app.media.PhotoStore(File(externalRoot, "feed/images")) }

    /** 动态收藏：本机单独一个文件，不动动态库与记忆。 */
    val savedPosts by lazy { dev.xiaoman.app.feed.SavedPostsStore(File(app.filesDir, "saved_posts.json")) }

    /** 动态 → 记忆自动抽取；情绪只更新关系心情。 */
    val feedMemory by lazy {
        dev.xiaoman.app.feed.FeedMemory(
            store = feedStore,
            memory = memory,
            settings = { settings.current },
            llm = llm,
            scope = scope,
            onMood = { mood, basis -> relationship.setMood(mood, basis, System.currentTimeMillis()) },
        )
    }

    /** 你发动态后，她的点赞与一句短评论。 */
    val feedReactions by lazy {
        dev.xiaoman.app.feed.FeedReactions(
            store = feedStore,
            settings = { settings.current },
            llm = llm,
            scope = scope,
            appName = app.getString(R.string.app_name),
        )
    }

    /** 「我们」页空状态里「去填她的生活」：置 true 后由设置页打开「她是谁」。 */
    val openPersona = MutableStateFlow(false)

    val proactiveScheduler by lazy { ProactiveScheduler(app) { settings.current.relation.proactive } }

    /** 主动开口：频率上限、免打扰、话术守卫都在这里；失败只写日志。 */
    val proactive by lazy {
        ProactiveEngine(
            store = relationship,
            settings = settings,
            session = session,
            notifier = notifier,
            llm = llm,
            scope = scope,
            uiVisible = uiVisible,
            appName = app.getString(R.string.app_name),
            decisionFile = File(app.filesDir, "proactive/last-decision.json"),
        )
    }

    /** 做梦（记忆整理）：文件在 `<files>/dream`，规则写进记忆的 `rules/` 下。 */
    val dreamStore by lazy { DreamStore(File(externalRoot, "dream")) }

    val rules by lazy { RuleStore(memory) }

    private val dreamScheduler by lazy { DreamScheduler(app) { dreamStore.config() } }

    val dream by lazy {
        DreamEngine(
            memory = memory,
            settings = settings,
            llm = llm,
            store = dreamStore,
            rules = rules,
            scope = scope,
            appName = app.getString(R.string.app_name),
            onSchedule = dreamScheduler::schedule,
        )
    }

    /** 充电 + 息屏时补做梦；进程在时有效，其余情况靠每晚的闹钟。 */
    private val idleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                if (isCharging()) dream.runIfDue()
            } else if (intent.action == Intent.ACTION_POWER_CONNECTED) {
                if (app.getSystemService(PowerManager::class.java)?.isInteractive == false) dream.runIfDue()
            }
        }
    }

    private fun isCharging(): Boolean {
        val intent = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    /** Bundled skills extracted and skills loaded once. */
    lateinit var startup: Deferred<Unit>
        private set

    private var cachedAgent: Pair<Pair<AppSettings, McpSelection>, Agent>? = null

    /** The agent for the current settings, rebuilt when they change; null until both models are configured. */
    @Synchronized
    fun agent(): Agent? {
        val current = settings.current
        if (!current.configured) return null
        val key = current to mcpSettings.current
        cachedAgent?.let { (k, agent) -> if (k == key) return agent }
        val agent = XiaomanAgent(
            chat = llm.create(
                current.chat.toModelConfig(ModelRole.CHAT).copy(
                    // 按人设长度收紧硬上限：偏短句 350、偏细腻 700；正常回复远低于上限，只挡超长输出。
                    maxTokens = if (current.persona.length == dev.xiaoman.app.persona.Length.SHORT) 350 else 700,
                ),
            ),
            operator = llm.create(current.operator.toModelConfig(ModelRole.OPERATOR)),
            device = device,
            memory = memory,
            skills = skills,
            sentinel = sentinel,
            users = session,
            reminders = reminderScheduler,
            config = current.agentConfig(),
            persona = dev.xiaoman.app.persona.PersonaPrompt.rewrite(
                dev.xiaoman.agent.Prompts.DEFAULT_PERSONA,
                current.persona,
                current.emojiStyle,
                // 没设昵称时用当前应用名，改 app_name 后重新构建 Agent 即生效。
                fallbackName = app.getString(R.string.app_name),
                replyMode = current.replyMode,
            ),
            idFactory = runIds::next,
            lastInteraction = { relationship.state.value.previousContactAt.takeIf { it > 0 } },
            // 易变段：关系状态 + 用户本人档案。用户档案只读 StateFlow，改完下一次回复就生效，
            // 不需要重建 Agent，也不会动到稳定前缀的缓存。
            contextNotes = {
                listOf(
                    RelationshipPrompt.section(relationship.state.value, current.relation),
                    dev.xiaoman.app.profile.MyProfilePrompt.section(myProfile.state.value),
                ).filter { it.isNotBlank() }.joinToString("\n\n")
            },
            safetyAudit = { safetyStore.record(it) },
            refusal = refusals,
            externalTools = mcpFor(key.second),
        )
        cachedAgent = key to agent
        return agent
    }

    /** 系统改时区 / 改时间后，按新的本地时间重排墙钟闹钟（23:30 做梦、主动检查）。 */
    fun rearmSchedules() {
        runCatching { dream.schedule() }
        runCatching { proactiveScheduler.schedule() }
    }

    fun start() {
        startup = scope.async(Dispatchers.IO) { prepareSkills() }
        session.renderReply = voice::onReply
        session.onUtterance = { role, text ->
            dream.record(role, text)
            relationship.record(role, text)
        }
        // 撤回：原话不再进模型历史，也不再进夜间做梦的日志。
        session.onRecall = { text ->
            val current = agent()
            if (current is XiaomanAgent) scope.launch(Dispatchers.IO) { runCatching { current.forget(text) } }
            dreamStore.forget("user", text)
        }
        // 主动发图：优先复用聊天里的旧图，其次生成，最后图库兜底；全部静默。
        session.onReply = { userText, replyText ->
            chatImages.afterReply(userText, replyText)
        }
        scope.launch(Dispatchers.IO) { runCatching { relationship.load() } }
        // 动态落库后若有未抽取的帖子（离线、模型失败、进程被杀），启动时补一遍。
        feedMemory.extractPending()
        scope.launch(Dispatchers.IO) { voice.audio.cleanup() }
        scope.launch(Dispatchers.IO) { dreamStore.cleanup() }
        // 生成的图片沿用同一套「按数量/体积/时间」清理策略。
        scope.launch(Dispatchers.IO) { chatImageStore.cleanup() }
        dream.schedule()
        proactiveScheduler.schedule()
        ContextCompat.registerReceiver(
            app,
            idleReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_POWER_CONNECTED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        notifier.createChannels()
        OverlayController(session, accessibilityDevice, title = { settings.current.persona.nameOr(app.getString(R.string.app_name)) }, uiVisible = uiVisible).start(scope)
        scope.launch(Dispatchers.Main.immediate) {
            session.busy.collect { busy -> if (busy) AgentService.start(app) }
        }
        scope.launch(Dispatchers.Main.immediate) { session.approval.collect(notifier::showApproval) }
        scope.launch(Dispatchers.Main.immediate) { session.question.collect(notifier::showQuestion) }
    }

    private suspend fun prepareSkills() {
        try {
            val info = app.packageManager.getPackageInfo(app.packageName, 0)
            val version = "${BuildConfig.VERSION_CODE}-${info.lastUpdateTime}"
            if (BundledSkills.extract(app.assets, bundledSkillsDir, version)) Log.i(TAG, "skills: bundled skills extracted ($version)")
            skillsDir.mkdirs()
            memoryDir.mkdirs()
        } catch (e: IOException) {
            Log.w(TAG, "skills: cannot extract bundled skills: ${e.message}")
        }
        val result = skills.reload()
        logSkillProblems(result.skills.size, result.problems)
    }

    fun logSkillProblems(count: Int, problems: List<dev.xiaoman.api.SkillProblem>) {
        Log.i(TAG, "reload-skills: $count skills, ${problems.size} problems")
        problems.forEach { p -> Log.w(TAG, "reload-skills: problem ${p.source}${p.line?.let { ":$it" }.orEmpty()} ${p.message}") }
    }

    companion object {
        const val TAG = "Xiaoman"
    }
}
