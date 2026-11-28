package dev.lily.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import dev.lily.agent.LilyAgent
import dev.lily.api.Agent
import dev.lily.api.AuditSink
import dev.lily.api.ModelRole
import dev.lily.api.MemoryStore
import dev.lily.app.dream.DreamEngine
import dev.lily.app.dream.DreamConfig
import dev.lily.app.dream.DreamScheduler
import dev.lily.app.dream.DreamStore
import dev.lily.app.dream.RuleStore
import dev.lily.app.persona.AgentDirectory
import dev.lily.app.memory.HybridMemoryStore
import dev.lily.app.relationship.ProactiveEngine
import dev.lily.app.relationship.ProactiveScheduler
import dev.lily.app.relationship.RelationshipPrompt
import dev.lily.app.relationship.RelationshipStore
import dev.lily.app.safety.SafetyStore
import dev.lily.app.session.AgentSession
import dev.lily.app.session.SessionTexts
import dev.lily.app.settings.AppSettings
import dev.lily.app.settings.MemoryEmbeddingSettings
import dev.lily.app.settings.RelationSettings
import dev.lily.app.settings.SettingsStore
import dev.lily.app.trace.RecordingDevice
import dev.lily.app.trace.TraceWriter
import dev.lily.app.voice.CallController
import dev.lily.app.voice.VoiceMessages
import dev.lily.hands.AccessibilityDevice
import dev.lily.hands.MutableStopSignal
import dev.lily.hands.LilyAccessibilityService
import dev.lily.llm.LlmClientFactory
import dev.lily.llm.EmbeddingClient
import dev.lily.llm.LlmClients
import dev.lily.app.settings.McpSettingsStore
import dev.lily.mcp.McpCatalog
import dev.lily.mcp.McpConfig
import dev.lily.mcp.McpPlatform
import dev.lily.mcp.McpSelection
import dev.lily.mcp.McpToolProvider
import dev.lily.mcp.ServerStatus
import dev.lily.mcp.UserChannelApprovals
import dev.lily.memory.FileMemoryStore
import dev.lily.memory.PersistentVectorIndex
import dev.lily.sentinel.PolicySentinel
import dev.lily.skills.DirectorySkillRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.io.File
import java.io.IOException

/**
 * Manual dependency container. Everything is built lazily; [start] runs once from
 * [LilyApp.onCreate].
 *
 * Folders (`<external files>` = `/sdcard/Android/data/dev.lily.app/files`):
 * `memory/`, `skills/` (user skills, override bundled ones), `traces/`, optional `mcp.json` (MCP servers,
 * HTTP only; keys are written in the file because Android has no environment); bundled skills are
 * extracted to `<files>/bundled-skills/`.
 */
class AppGraph(context: Context, baseLlm: LlmClientFactory = LlmClients.default) {
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
    internal val agentDirectory by lazy { AgentDirectory.create(app) }

    /**
     * 用量记账：只存在本机，用来回答「这个 App 花了多少」。
     *
     * 所有模型客户端都从这里过一遍，所以聊天、操作、发动态、做梦、主动开口
     * 一次都不漏。
     */
    val usageStore by lazy { dev.lily.app.usage.UsageStore(File(app.filesDir, "usage.json")) }

    /** 包一层记用量；记账出问题也不该影响对话。 */
    private val llm: LlmClientFactory = LlmClientFactory { config ->
        dev.lily.app.usage.UsageRecordingClient(baseLlm.create(config)) { model, input, output ->
            usageStore.record(model, input, output)
        }
    }

    val memory by lazy { FileMemoryStore(memoryDir) }
    private val embeddingClient by lazy { EmbeddingClient() }

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

    val accessibilityDevice by lazy { AccessibilityDevice({ LilyAccessibilityService.instance.value }, app) }

    private val device by lazy { RecordingDevice(accessibilityDevice, traces::saveScreenshot) }

    /** 安全拦截与确认记录：`<files>/safety/blocks.jsonl`，在设置里可看可清空。 */
    val safetyStore by lazy { SafetyStore(File(externalRoot, "safety")) }

    /** 拒答兜底账本与每小时重试上限；只在本地。 */
    val refusals by lazy {
        dev.lily.app.safety.RefusalStore(
            prefs = app.getSharedPreferences("refusal-stats", Context.MODE_PRIVATE),
            file = File(File(externalRoot, "safety"), "refusals.jsonl"),
            info = {
                val s = settings.current
                dev.lily.app.safety.RefusalStore.Info(
                    provider = android.net.Uri.parse(s.chat.baseUrl).host ?: s.chat.baseUrl,
                    model = s.chat.model,
                    style = s.persona.expression.label,
                )
            },
        )
    }

    val sentinel by lazy {
        newSentinel(session)
    }

    val session: AgentSession by lazy {
        newSession(::agent) { sentinel }
    }

    private fun newSentinel(session: AgentSession) = PolicySentinel(
        approvals = session,
        audit = AuditSink { record ->
            traces.record(record)
            safetyStore.record(record)
        },
        selfPackage = app.packageName,
    )

    private fun newSession(agent: () -> Agent?, sentinel: () -> PolicySentinel): AgentSession =
        AgentSession(
            scope = scope,
            agent = agent,
            runIds = runIds,
            stopSignal = MutableStopSignal(),
            trace = traces,
            onTaskEnd = { sentinel().endTask(it) },
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

    /** 通话页的语音层：录音、识别、朗读；只调用 [AgentSession.submit]，不改变 agent 行为。 */
    val callLogs by lazy { dev.lily.app.voice.CallLogStore(File(app.filesDir, "calls")) }

    private val primaryCall = lazy {
        CallController(app, session, { settings.current }, scope, relationship, callLogs,
            canStart = { activeCallAgentId() == null })
    }
    val call get() = primaryCall.value

    /**
     * 消息层的语音：聊天里的语音消息与语音回复。音频落在 `<files>/audio`，聊天行只存路径。
     */
    val voice by lazy {
        VoiceMessages(app, session, { settings.current }, scope, { call }, { openCall.value = AgentDirectory.PRIMARY_ID })
    }

    /** 带上发起来电的角色 ID，避免切换会话时打开错的人。 */
    val openCall = MutableStateFlow<String?>(null)

    /** 通知带上发出消息的角色 ID，由导航消费。 */
    val openChat = MutableStateFlow<String?>(null)

    /** 关系档案：现有 Markdown 记忆里的 `relationship/profile.md`，不新开存储。 */
    val relationship by lazy { RelationshipStore(memory, scope) }

    /** 表情素材库：`<files>/stickers`，只存本机。 */
    val stickers by lazy { dev.lily.app.media.StickerStore(File(externalRoot, "stickers")) }

    /** 聊天照片：`<files>/photos`，只存本机。 */
    val photos by lazy { dev.lily.app.media.PhotoStore(File(app.filesDir, "photos")) }

    /** 生活分享图库：用户自建、按地点标签，她按聊天上下文挑图。 */
    val scenery by lazy { dev.lily.app.media.SceneryStore(File(externalRoot, "scenery")) }

    /** 她主动发图：生成图只落应用私有目录 `<files>/images`；复用与图库只引用原文件。 */
    val chatImageStore by lazy { dev.lily.app.image.ChatImageStore(File(app.filesDir, "images")) }

    /** 方舟图片生成；无 Key、网络或额度失败都返回 null，由发图逻辑静默降级。 */
    private val imageGenerator by lazy { dev.lily.app.image.ImageGenerator() }

    /** 发图：回忆语境复用旧图；想看此刻按对话细节生成；失败或没 Key 时图库静默兜底。 */
    val chatImages by lazy {
        newChatImages(session, { settings.current }, portrait, chatImageStore, scenery)
    }

    private fun newChatImages(
        session: AgentSession,
        settings: () -> AppSettings,
        portrait: dev.lily.app.portrait.PortraitStore,
        images: dev.lily.app.image.ChatImageStore,
        scenery: dev.lily.app.media.SceneryStore,
    ) = dev.lily.app.image.ChatImages(
            scope = scope,
            settings = settings,
            items = { session.items.value },
            portraitFile = { portrait.displayFile() },
            store = images,
            scenery = scenery,
            post = { session.postAssistantImage(it) },
            generate = { prompt, reference ->
                val current = settings()
                // 生图用哪家由「发图」页决定；没单独填 Key 就沿用聊天那家的。
                val key = current.image.apiKey.ifBlank { current.chat.apiKey }
                if (key.isBlank()) {
                    null
                } else {
                    imageGenerator.generate(
                        baseUrl = current.image.effectiveBaseUrl,
                        apiKey = key,
                        model = current.image.effectiveModel,
                        prompt = prompt,
                        reference = reference,
                    )
                }
            },
            promptFor = { selfie, context ->
                val current = settings()
                dev.lily.app.image.ImagePrompt.build(selfie, context, current.persona.nameOr(app.getString(R.string.app_name)), current.life)
            },
        )

    /** 头像：从相册选的图存在 `<files>/portrait`，只落本机。 */
    val portrait by lazy { dev.lily.app.portrait.PortraitStore(File(app.filesDir, "portrait")) }

    /** 用户本人档案：`<files>/profile/me.json`，不碰她的人设键。 */
    val myProfile by lazy { dev.lily.app.profile.MyProfileStore(File(app.filesDir, "profile/me.json")) }

    /** 用户自己的头像：`<files>/profile/avatar`，与她的 [portrait] 分开。 */
    val myAvatar by lazy { dev.lily.app.portrait.PortraitStore(File(app.filesDir, "profile/avatar")) }

    /** 克隆音色的本地索引：只存云端 uri 与名称，样本不落盘。 */
    val clones by lazy { dev.lily.app.voice.ClonedVoiceStore(File(app.filesDir, "voices")) }

    /** 她自己的动态：`<files>/feed`，只存本机；生成见 [feed]。 */
    val feedStore by lazy { dev.lily.app.feed.FeedStore(File(externalRoot, "feed")) }

    /** 动态生成：模型可用就写，不行退回模板；时间按本机时区。 */
    val feed by lazy {
        dev.lily.app.feed.FeedEngine(
            store = feedStore,
            settings = { settings.current },
            llm = llm,
            scope = scope,
        )
    }

    /** 动态配图：`<files>/feed/images`，复制落盘，原图不动。 */
    val feedPhotos by lazy { dev.lily.app.media.PhotoStore(File(externalRoot, "feed/images")) }

    /** 动态收藏：本机单独一个文件，不动动态库与记忆。 */
    val savedPosts by lazy { dev.lily.app.feed.SavedPostsStore(File(app.filesDir, "saved_posts.json")) }

    /** 动态 → 记忆自动抽取；情绪只更新关系心情。 */
    val feedMemory by lazy {
        dev.lily.app.feed.FeedMemory(
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
        dev.lily.app.feed.FeedReactions(
            store = feedStore,
            settings = { settings.current },
            llm = llm,
            scope = scope,
            appName = app.getString(R.string.app_name),
        )
    }

    val proactiveScheduler by lazy {
        ProactiveScheduler(app) {
            settings.current.relation.proactive || agentDirectory.agents.value.any { it.relation.proactive }
        }
    }

    /** 主动开口：频率上限、免打扰、话术守卫都在这里；失败只写日志。 */
    val proactive by lazy {
        ProactiveEngine(
            store = relationship,
            settings = { settings.current },
            session = session,
            notify = { notifier.showProactive(it) },
            llm = llm,
            scope = scope,
            uiVisible = uiVisible,
            appName = app.getString(R.string.app_name),
            decisionFile = File(app.filesDir, "proactive/last-decision.json"),
            // 主动开口时她也能顺手发一张图或一条动态。
            sendImage = { description, selfie -> chatImages.generateAndPost(description, selfie) },
            postDynamic = { text -> postDynamic(text) },
        )
    }

    /**
     * 她发一条动态。
     *
     * 走 [FeedEngine.shouldPost] 的同一套频率规则：每天不超过 3 条、间隔至少 4 小时、
     * 只在 5–22 点。工具调用也必须守规矩，否则她能在深夜连发一串。
     */
    private fun postDynamic(text: String): String {
        val body = text.trim()
        if (body.isEmpty()) return "要发的内容是空的，没发出去。"
        val at = System.currentTimeMillis()
        if (!feed.shouldPost(at, feedStore.posts.value)) {
            return "今天已经发得够多了，过一会儿再说。"
        }
        val post = feedStore.addHer(body, at, dev.lily.app.feed.FeedStore.SOURCE_MODEL)
        return if (post == null) "这条动态没能发出去。" else "动态已经发出去了。"
    }

    /** 做梦（记忆整理）：文件在 `<files>/dream`，规则写进记忆的 `rules/` 下。 */
    val dreamStore by lazy { DreamStore(File(externalRoot, "dream")) }

    val rules by lazy { RuleStore(memory) }

    private val dreamScheduler by lazy { DreamScheduler(app) { dreamStore.config() } }

    val dream by lazy {
        DreamEngine(
            memory = memory,
            settings = { settings.current },
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
                if (isCharging()) runDueDreams()
            } else if (intent.action == Intent.ACTION_POWER_CONNECTED) {
                if (app.getSystemService(PowerManager::class.java)?.isInteractive == false) runDueDreams()
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
        val key = current.copy(memoryEmbedding = MemoryEmbeddingSettings()) to mcpSettings.current
        cachedAgent?.let { (k, agent) -> if (k == key) return agent }
        val agent = newAgent(current, session, checkNotNull(conversation(AgentDirectory.PRIMARY_ID)).memorySearch, relationship, sentinel, mcpFor(key.second), primary = true)
        cachedAgent = key to agent
        return agent
    }

    private fun newAgent(
        current: AppSettings,
        session: AgentSession,
        memory: MemoryStore,
        relationship: RelationshipStore,
        sentinel: PolicySentinel,
        tools: McpToolProvider?,
        primary: Boolean,
    ): Agent = LilyAgent(
            chat = llm.create(
                current.chat.toModelConfig(ModelRole.CHAT).copy(
                    // 按人设长度收紧硬上限：偏短句 350、偏细腻 700；正常回复远低于上限，只挡超长输出。
                    maxTokens = if (current.persona.length == dev.lily.app.persona.Length.SHORT) 350 else 700,
                ),
            ),
            operator = llm.create(current.operator.toModelConfig(ModelRole.OPERATOR)),
            device = device,
            memory = memory,
            skills = skills,
            sentinel = sentinel,
            users = session,
            reminders = reminderScheduler,
            config = current.agentConfig().copy(phoneTasks = primary),
            persona = dev.lily.app.persona.PersonaPrompt.rewrite(
                dev.lily.agent.Prompts.DEFAULT_PERSONA,
                current.persona,
                current.emojiStyle,
                // 没设昵称时用当前应用名，改 app_name 后重新构建 Agent 即生效。
                fallbackName = app.getString(R.string.app_name),
                replyMode = current.replyMode,
                life = current.life,
                imagesEnabled = current.image.enabled,
            ),
            idFactory = runIds::next,
            lastInteraction = { relationship.state.value.previousContactAt.takeIf { it > 0 } },
            // 易变段：关系状态 + 用户本人档案。用户档案只读 StateFlow，改完下一次回复就生效，
            // 不需要重建 Agent，也不会动到稳定前缀的缓存。
            contextNotes = {
                listOf(
                    RelationshipPrompt.section(relationship.state.value, current.relation),
                    dev.lily.app.profile.MyProfilePrompt.section(myProfile.state.value),
                ).filter { it.isNotBlank() }.joinToString("\n\n")
            },
            safetyAudit = { safetyStore.record(it) },
            refusal = refusals,
            externalTools = tools,
            // 「她的身体」：设备给不了就不给她这个工具，她也就不会假装能做到。
            sendImage = if (current.image.enabled) {
                { description, selfie -> chatImages.generateAndPost(description, selfie) }
            } else {
                null
            },
            postDynamic = { text -> postDynamic(text) },
        )

    /** 默认角色的会话外壳；彻底删除后可丢弃重建，让记忆索引与缓存一起换新。 */
    private var primaryConversation: Conversation? = null

    private val otherConversations = mutableMapOf<String, Conversation>()

    @Synchronized
    internal fun conversation(id: String): Conversation? = when {
        id == AgentDirectory.PRIMARY_ID ->
            primaryConversation ?: Conversation(id).also { primaryConversation = it }
        agentDirectory.find(id) != null -> otherConversations.getOrPut(id) { Conversation(id) }
        else -> null
    }

    /** 发送那一下的「呼」。短音走 SoundPool，按下就响。 */
    internal val chatSounds by lazy { dev.lily.app.ChatSounds(app) }

    /** 群列表。v1 只存成员关系，群消息跟私聊一样在内存里。 */
    internal val groupChats by lazy { dev.lily.app.chat.GroupChatStore.create(app) }

    private val groupRooms = mutableMapOf<String, dev.lily.app.chat.GroupRoom>()

    /** 一间群聊。群被解散或不存在就返回 null，界面自然退回列表。 */
    @Synchronized
    internal fun groupRoom(id: String): dev.lily.app.chat.GroupRoom? {
        if (groupChats.find(id) == null) return null
        return groupRooms.getOrPut(id) {
            dev.lily.app.chat.GroupRoom(
                group = { groupChats.find(id) },
                nameOf = ::agentName,
                ask = { agentId, prompt -> conversation(agentId)?.sayInGroup(prompt) },
                scope = scope,
            )
        }
    }

    /** 界面上显示的名字：平时用她的昵称，没设就用应用名。 */
    internal fun agentName(id: String): String = when (id) {
        AgentDirectory.PRIMARY_ID -> settings.current.persona.nameOr(app.getString(R.string.app_name))
        else -> agentDirectory.find(id)?.persona?.nameOr(app.getString(R.string.app_name)).orEmpty()
    }

    /** 这个人还在不在。删进最近删除、或已经彻底删掉，都算不在 —— 通知不该把她拉出来。 */
    internal fun isAgentAvailable(id: String): Boolean {
        if (id == AgentDirectory.PRIMARY_ID) return agentDirectory.primaryState().deletedAt == null
        // 档案已经不在了（彻底删除）或进了最近删除，都算不可用。
        return agentDirectory.find(id)?.let { it.deletedAt == null } ?: false
    }

    /** 删除的第一段：移入「最近删除」，只打时间戳，一个字节都不删。 */
    internal fun trashAgent(id: String): Boolean = agentDirectory.trash(id)

    /** 从「最近删除」捞回来。 */
    internal fun restoreAgent(id: String): Boolean = agentDirectory.restore(id)

    /** 拉黑 / 解除：不隐藏她，只断她的主动消息、来电与推送。 */
    internal fun setAgentBlocked(id: String, blocked: Boolean): Boolean =
        agentDirectory.setBlocked(id, blocked)

    /**
     * 删除的第二段：物理意义上清空她的一切 ——
     * 索引、会话记录、相册与画像、记忆与向量、关系、梦境、通话记录。
     * 先摘索引 —— 索引写不进去就什么都不删，避免"人没了记录还在"。
     */
    @Synchronized
    internal fun purgeAgent(id: String): Boolean {
        if (id == AgentDirectory.PRIMARY_ID) return purgePrimary()
        if (!agentDirectory.remove(id)) return false
        otherConversations.remove(id)
        runCatching { File(app.filesDir, "agents/$id").deleteRecursively() }
        runCatching { File(externalRoot, "agents/$id").deleteRecursively() }
        runCatching { File(app.filesDir, "memory-vectors/$id.bin").delete() }
        groupChats.forgetAgent(id)
        return true
    }

    /**
     * 默认角色也可以被彻底删掉：她的会话、相册、记忆、头像都清，
     * 设定回到出厂 —— 否则"删了"只是从列表里藏起来，她还会被再次创建出来。
     */
    @Synchronized
    private fun purgePrimary(): Boolean {
        // 默认角色的数据不在 agents/primary：记忆、照片、生成图、通话、做梦都在各自的根目录。
        runCatching { memoryDir.deleteRecursively() }
        runCatching { File(app.filesDir, "agents/${AgentDirectory.PRIMARY_ID}").deleteRecursively() }
        runCatching { File(externalRoot, "agents/${AgentDirectory.PRIMARY_ID}").deleteRecursively() }
        runCatching { File(app.filesDir, "memory-vectors/${AgentDirectory.PRIMARY_ID}.bin").delete() }
        runCatching { File(app.filesDir, "photos").deleteRecursively() }
        runCatching { File(app.filesDir, "images").deleteRecursively() }
        runCatching { File(app.filesDir, "calls").deleteRecursively() }
        runCatching { File(externalRoot, "dream").deleteRecursively() }
        runCatching { File(app.filesDir, "portrait").deleteRecursively() }
        // 动态（含配图）、生活图库、语音录音与动态收藏也属于她。
        runCatching { File(externalRoot, "feed").deleteRecursively() }
        runCatching { File(externalRoot, "scenery").deleteRecursively() }
        runCatching { File(app.filesDir, "audio").deleteRecursively() }
        runCatching { File(app.filesDir, "saved_posts.json").delete() }
        groupChats.forgetAgent(AgentDirectory.PRIMARY_ID)

        // 磁盘清完还要清内存：消息行、关系、头像、发图配额与 Agent 缓存；
        // 会话外壳一并丢弃，下次取用时按空记忆重建，不会挂着她删之前的记录。
        session.resetConversation()
        portrait.reset()
        chatImageStore.reset()
        chatImages.reset()
        scenery.reset()
        feedStore.reset()
        savedPosts.reset()
        cachedAgent = null
        primaryConversation = null
        scope.launch(Dispatchers.IO) { runCatching { relationship.reset() } }

        // 设定回到出厂，她以空白档案重新出现，而不是带着旧记忆「重生」。
        runCatching { settings.save(settings.current.copy(persona = AppSettings().persona)) }
        return agentDirectory.clearPrimary()
    }

    /** 启动时清掉已经过了保留期的她。 */
    internal fun purgeExpiredAgents(keepDays: Int) {
        val keep = keepDays * 24L * 60L * 60L * 1000L
        agentDirectory.expiredIds(keep).forEach { runCatching { purgeAgent(it) } }
    }

    @Synchronized
    internal fun activeCallAgentId(): String? =
        if (primaryCall.isInitialized() && call.state.value.active) AgentDirectory.PRIMARY_ID
        else otherConversations.values.firstOrNull { it.hasActiveCall() }?.id

    internal fun isVoiceUsedByAnotherAgent(voice: String, currentId: String): Boolean =
        (currentId != AgentDirectory.PRIMARY_ID && settings.current.persona.voice == voice) ||
            agentDirectory.agents.value.any { it.id != currentId && it.persona.voice == voice }

    internal inner class Conversation(val id: String) {
        val isPrimary = id == AgentDirectory.PRIMARY_ID
        private val files = File(app.filesDir, "agents/$id")
        private val external = File(externalRoot, "agents/$id")
        private fun current(): AppSettings {
            val shared = settings.current
            return if (isPrimary) shared else checkNotNull(agentDirectory.find(id)).effectiveSettings(shared)
        }

        val effectiveSettings by lazy {
            if (isPrimary) settings.settings else combine(settings.settings, agentDirectory.agents) { shared, agents ->
                agents.first { it.id == id }.effectiveSettings(shared)
            }.stateIn(scope, SharingStarted.Eagerly, current())
        }

        /** 群聊里借她一句话；她的发布/来电/私聊记录都不受影响。 */
        internal suspend fun sayInGroup(prompt: String): String? = session.sayOnce(prompt)

        fun saveSettings(value: AppSettings): Boolean {
            if (!isPrimary) return agentDirectory.update(id, value)
            val global = settings.current
            return settings.save(global.copy(
                persona = value.persona,
                emojiStyle = value.emojiStyle,
                life = value.life,
                // 语速两套朗读引擎共用：只改速度，供应商与两边的 Key/音色都保留。
                tts = global.tts.withSpeed(value.tts.speed),
            ))
        }

        fun saveRelation(relation: RelationSettings): Boolean {
            val saved = if (isPrimary) {
                settings.save(settings.current.copy(relation = relation))
            } else {
                agentDirectory.updateRelation(id, relation)
            }
            if (saved) proactiveScheduler.schedule()
            return saved
        }

        val portrait by lazy {
            if (isPrimary) this@AppGraph.portrait else dev.lily.app.portrait.PortraitStore(File(files, "portrait"))
        }
        val photos by lazy {
            if (isPrimary) this@AppGraph.photos else dev.lily.app.media.PhotoStore(File(files, "photos"))
        }
        val scenery by lazy {
            if (isPrimary) this@AppGraph.scenery else dev.lily.app.media.SceneryStore(File(external, "scenery"))
        }

        val memory by lazy {
            if (isPrimary) this@AppGraph.memory else FileMemoryStore(File(external, "memory"))
        }
        val memorySearch by lazy {
            HybridMemoryStore(
                notes = memory,
                index = PersistentVectorIndex(File(app.filesDir, "memory-vectors/$id.bin")),
                settings = { settings.current.memoryEmbedding },
                scope = scope,
                embed = { config, inputs ->
                    embeddingClient.embed(checkNotNull(config.requestUrl), config.model.trim(), config.apiKey, inputs)
                },
            )
        }
        val relationship by lazy {
            if (isPrimary) this@AppGraph.relationship else RelationshipStore(memory, scope)
        }
        val dreamStore by lazy {
            if (isPrimary) this@AppGraph.dreamStore
            else DreamStore(File(external, "dream"), defaultConfig = DreamConfig(enabled = false))
        }
        private val dreamScheduler by lazy { DreamScheduler(app, id, dreamStore::config) }
        val dream by lazy {
            if (isPrimary) this@AppGraph.dream else DreamEngine(
                memory = memory,
                settings = ::current,
                llm = llm,
                store = dreamStore,
                rules = RuleStore(memory),
                scope = scope,
                appName = app.getString(R.string.app_name),
                onSchedule = dreamScheduler::schedule,
            )
        }
        val proactive by lazy {
            if (isPrimary) this@AppGraph.proactive else ProactiveEngine(
                store = relationship,
                settings = ::current,
                session = session,
                notify = { text -> notifier.showProactive(text, agentId = id, agentName = current().persona.nameOr(app.getString(R.string.app_name))) },
                llm = llm,
                scope = scope,
                uiVisible = uiVisible,
                appName = app.getString(R.string.app_name),
                decisionFile = File(files, "proactive/last-decision.json"),
                // 和主 Agent 一样：发图关着就不给她这个工具。
                sendImage = if (current().image.enabled) {
                    { description, selfie -> chatImages.generateAndPost(description, selfie) }
                } else {
                    null
                },
                postDynamic = { text -> postDynamic(text) },
            )
        }
        private val sentinel by lazy { newSentinel(session) }
        private var cached: Pair<AppSettings, Agent>? = null

        @Synchronized
        private fun agent(): Agent? {
            val current = current()
            if (!current.configured) return null
            val key = current.copy(memoryEmbedding = MemoryEmbeddingSettings())
            cached?.let { (previous, agent) -> if (previous == key) return agent }
            return newAgent(current, session, memorySearch, relationship, sentinel, tools = null, primary = false).also {
                cached = key to it
            }
        }

        val session: AgentSession by lazy {
            if (isPrimary) {
                this@AppGraph.session
            } else {
                newSession(::agent) { sentinel }.apply {
                    renderReply = { text, itemId -> voice.onReply(text, itemId) }
                    onUtterance = { role, text ->
                        dream.record(role, text)
                        relationship.record(role, text)
                        // 明确要图时先开始生成，和模型回复并行，缩短等待；被删/被拉黑的人不再发图。
                        if (role == "user" && isAgentAvailable(id)) chatImages.prefetch(text)
                    }
                    onReply = { text, reply -> if (this@AppGraph.isAgentAvailable(id)) chatImages.afterReply(text, reply) }
                    onRecall = { text ->
                        val current = agent()
                        if (current is LilyAgent) scope.launch(Dispatchers.IO) { runCatching { current.forget(text) } }
                        dreamStore.forget("user", text)
                    }
                    scope.launch(Dispatchers.IO) {
                        runCatching { relationship.load() }
                        runCatching { dreamStore.cleanup() }
                    }
                }
            }
        }

        val callLogs by lazy {
            if (isPrimary) this@AppGraph.callLogs else dev.lily.app.voice.CallLogStore(File(files, "calls"))
        }
        private val localCall = lazy {
            CallController(app, session, ::current, scope, relationship, callLogs,
                canStart = { activeCallAgentId() == null })
        }
        val call get() = if (isPrimary) this@AppGraph.call else localCall.value
        fun hasActiveCall(): Boolean = localCall.isInitialized() && localCall.value.state.value.active

        /** 拉黑或已进最近删除的人，不再主动找上来。默认角色走同一套判断。 */
        private fun silenced(): Boolean {
            val profile = this@AppGraph.agentDirectory.find(id)
            if (profile != null) return profile.blocked || profile.deletedAt != null
            if (id == AgentDirectory.PRIMARY_ID) {
                val state = this@AppGraph.agentDirectory.primaryState()
                return state.blocked || state.deletedAt != null
            }
            return false
        }

        fun ringIncoming() {
            if (silenced()) return
            if (call.ringIncoming()) openCall.value = id
        }

        val voice by lazy {
            if (isPrimary) this@AppGraph.voice
            else VoiceMessages(app, session, ::current, scope, { call }, { openCall.value = id }, File(files, "audio"))
        }
        val chatImages by lazy {
            if (isPrimary) this@AppGraph.chatImages
            else newChatImages(session, ::current, portrait, dev.lily.app.image.ChatImageStore(File(files, "images")), scenery)
        }
    }

    internal fun checkProactiveAgents() {
        // 被拉黑的人都先摘出去：她的主动消息不该在拉黑之后还发进来。
        val spoken = agentDirectory.agents.value.filter { it.deletedAt == null && !it.blocked }
        val primary = agentDirectory.primaryState()
        if (settings.current.relation.proactive && primary.deletedAt == null && !primary.blocked) proactive.check()
        spoken.filter { it.relation.proactive }.forEach { added ->
            conversation(added.id)?.proactive?.check()
        }
    }

    private fun runDueDreams() {
        dream.runIfDue()
        agentDirectory.agents.value
            .filter { it.deletedAt == null && !it.blocked }
            .forEach { added -> conversation(added.id)?.dream?.runIfDue() }
    }

    /** 系统改时区 / 改时间后，按新的本地时间重排墙钟闹钟（23:30 做梦、主动检查）。 */
    fun rearmSchedules() {
        runCatching { dream.schedule() }
        agentDirectory.agents.value.forEach { added -> runCatching { conversation(added.id)?.dream?.schedule() } }
        runCatching { proactiveScheduler.schedule() }
    }

    fun start() {
        startup = scope.async(Dispatchers.IO) { prepareSkills() }
        session.renderReply = voice::onReply
        session.onUtterance = { role, text ->
            dream.record(role, text)
            relationship.record(role, text)
            if (role == "user" && isAgentAvailable(AgentDirectory.PRIMARY_ID)) chatImages.prefetch(text)
        }
        // 撤回：原话不再进模型历史，也不再进夜间做梦的日志。
        session.onRecall = { text ->
            val current = agent()
            if (current is LilyAgent) scope.launch(Dispatchers.IO) { runCatching { current.forget(text) } }
            dreamStore.forget("user", text)
        }
        // 发图：优先复用聊天里的旧图，其次生成，最后图库兜底；她已经被删就不再贴。
        session.onReply = { userText, replyText ->
            if (isAgentAvailable(AgentDirectory.PRIMARY_ID)) chatImages.afterReply(userText, replyText)
        }
        scope.launch(Dispatchers.IO) { runCatching { relationship.load() } }
        // 动态落库后若有未抽取的帖子（离线、模型失败、进程被杀），启动时补一遍。
        feedMemory.extractPending()
        scope.launch(Dispatchers.IO) { voice.audio.cleanup() }
        scope.launch(Dispatchers.IO) { dreamStore.cleanup() }
        // 生成的图片沿用同一套「按数量/体积/时间」清理策略。
        scope.launch(Dispatchers.IO) { chatImageStore.cleanup() }
        rearmSchedules()
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

    fun logSkillProblems(count: Int, problems: List<dev.lily.api.SkillProblem>) {
        Log.i(TAG, "reload-skills: $count skills, ${problems.size} problems")
        problems.forEach { p -> Log.w(TAG, "reload-skills: problem ${p.source}${p.line?.let { ":$it" }.orEmpty()} ${p.message}") }
    }

    companion object {
        const val TAG = "Lily"
    }
}
