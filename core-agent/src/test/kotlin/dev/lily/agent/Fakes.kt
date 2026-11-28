package dev.lily.agent

import dev.lily.api.Action
import dev.lily.api.ActionResult
import dev.lily.api.AgentConfig
import dev.lily.api.AgentEvent
import dev.lily.api.AgentListener
import dev.lily.api.AppInfo
import dev.lily.api.Bounds
import dev.lily.api.ChatRequest
import dev.lily.api.ChatResponse
import dev.lily.api.Decision
import dev.lily.api.Device
import dev.lily.api.GuardContext
import dev.lily.api.LlmClient
import dev.lily.api.LlmException
import dev.lily.api.MemoryStore
import dev.lily.api.ModelConfig
import dev.lily.api.ModelRole
import dev.lily.api.Note
import dev.lily.api.Observation
import dev.lily.api.ReminderScheduler
import dev.lily.api.ScreenState
import dev.lily.api.SearchHit
import dev.lily.api.Sentinel
import dev.lily.api.Skill
import dev.lily.api.SkillLoadResult
import dev.lily.api.SkillRepository
import dev.lily.api.StopSignal
import dev.lily.api.ToolCall
import dev.lily.api.UiNode
import dev.lily.api.UserChannel

/** LLM returning scripted responses in order; [fallback] answers once the script is used up. */
class ScriptedLlm(role: ModelRole = ModelRole.CHAT, var fallback: ((ChatRequest) -> ChatResponse)? = null) : LlmClient {
    override val config = ModelConfig(role, "http://fake", "fake-model", "secret")
    val requests = mutableListOf<ChatRequest>()
    private val script = ArrayDeque<(ChatRequest) -> ChatResponse>()

    fun reply(text: String) = apply { script.addLast { ChatResponse(text) } }
    fun tools(vararg calls: ToolCall) = apply { script.addLast { ChatResponse("", calls.toList()) } }
    fun fail(message: String = "boom") = apply { script.addLast { throw LlmException(message, 503, retryable = true) } }
    fun respond(block: (ChatRequest) -> ChatResponse) = apply { script.addLast(block) }

    override suspend fun complete(request: ChatRequest): ChatResponse {
        requests += request
        val next = script.removeFirstOrNull() ?: fallback ?: error("no scripted response left")
        return next(request)
    }
}

/** Device whose observe() returns [screens] in order (the last one repeats). */
class FakeDevice(vararg screens: ScreenState) : Device {
    private val queue = ArrayDeque(screens.toList())
    var observeError: Exception? = null
    val performed = mutableListOf<Action>()
    var onPerform: (Action) -> Unit = {}
    var observations = 0

    override suspend fun observe(): ScreenState {
        observeError?.let { throw it }
        observations++
        return if (queue.size > 1) queue.removeFirst() else queue.first()
    }

    override suspend fun perform(action: Action): ActionResult {
        performed += action
        onPerform(action)
        return ActionResult(true)
    }

    override suspend fun installedApps() = listOf(AppInfo("com.android.settings", "设置"))
}

fun screen(pkg: String, vararg texts: String, secure: Boolean = false) = ScreenState(
    screenshotPng = if (secure) null else byteArrayOf(1, 2, 3),
    widthPx = 1080,
    heightPx = 2400,
    packageName = pkg,
    nodes = texts.mapIndexed { i, t -> UiNode(id = i, text = t, className = "android.widget.TextView", bounds = Bounds(0, i * 100, 1000, i * 100 + 90), clickable = true) },
    secure = secure,
)

class InMemoryMemory : MemoryStore {
    val notes = LinkedHashMap<String, Note>()

    override suspend fun write(note: Note): Note = note.also { notes[it.permalink] = it }
    override suspend fun read(identifier: String): Note? = notes[identifier] ?: notes.values.firstOrNull { it.title == identifier }

    override suspend fun search(query: String, limit: Int): List<SearchHit> {
        val grams = query.windowed(2).toSet().ifEmpty { setOf(query) }
        return notes.values.mapNotNull { n ->
            val text = n.title + "\n" + n.body + n.observations.joinToString("\n") { it.content }
            val score = grams.count { it in text }
            if (score > 0) SearchHit(n.permalink, n.title, n.observations.firstOrNull()?.content ?: n.body.take(40), score.toDouble()) else null
        }.sortedByDescending { it.score }.take(limit)
    }

    override suspend fun appendObservation(identifier: String, observation: Observation): Note {
        val n = read(identifier) ?: Note(title = identifier, permalink = identifier)
        return write(n.copy(observations = n.observations + observation))
    }

    override suspend fun delete(identifier: String): Boolean = read(identifier)?.let { notes.remove(it.permalink) } != null
    override suspend fun list(folder: String?): List<Note> = notes.values.filter { folder == null || it.permalink.startsWith("$folder/") }
}

class FakeSkills(vararg skills: Skill) : SkillRepository {
    private val all = skills.toList()
    override suspend fun reload() = SkillLoadResult(all, emptyList())
    override suspend fun list() = all
    override suspend fun get(name: String) = all.firstOrNull { it.name == name }
}

fun skill(name: String, description: String = name, flow: dev.lily.api.Flow? = null, deeplinks: Map<String, String> = emptyMap(), memoryRefs: List<String> = emptyList()) =
    Skill(name = name, description = description, apps = listOf("com.example.app"), deeplinks = deeplinks, memoryRefs = memoryRefs, instructions = "按步骤操作 $name", flow = flow, source = "test")

/** Denies whatever [policy] returns a non-Allow decision for. */
class FakeSentinel(private val policy: (Action) -> Decision = { Decision.Allow() }) : Sentinel {
    val authorized = mutableListOf<Action>()
    override suspend fun guard(context: GuardContext, action: Action) = policy(action)
    override suspend fun authorize(context: GuardContext, action: Action): Boolean {
        authorized += action
        return guard(context, action) is Decision.Allow
    }
}

class FakeUsers(vararg answers: String) : UserChannel {
    private val queue = ArrayDeque(answers.toList())
    val asked = mutableListOf<Pair<String, Boolean>>()
    override suspend fun ask(taskId: String, question: String, takeover: Boolean): String {
        asked += question to takeover
        return queue.removeFirstOrNull() ?: "好"
    }
}

class FakeReminderScheduler : ReminderScheduler {
    val scheduled = mutableListOf<Pair<String, Long>>()
    var failure: Exception? = null
    override fun schedule(text: String, atMillis: Long) {
        failure?.let { throw it }
        scheduled += text to atMillis
    }
}

class Events : AgentListener {
    val all = mutableListOf<AgentEvent>()
    override fun onEvent(event: AgentEvent) {
        all += event
    }
    inline fun <reified T : AgentEvent> of(): List<T> = all.filterIsInstance<T>()
}

class Stop(var value: Boolean = false) : StopSignal {
    override val stopped get() = value
}

/** Wires a [LilyAgent] with fakes; tests tweak the parts they care about. */
class Harness(
    val chat: ScriptedLlm = ScriptedLlm(ModelRole.CHAT),
    val operator: ScriptedLlm = ScriptedLlm(ModelRole.OPERATOR),
    val device: FakeDevice = FakeDevice(screen("com.android.launcher", "设置")),
    val memory: InMemoryMemory = InMemoryMemory(),
    val skills: FakeSkills = FakeSkills(),
    val sentinel: FakeSentinel = FakeSentinel(),
    val users: FakeUsers = FakeUsers(),
    val reminders: FakeReminderScheduler = FakeReminderScheduler(),
    val config: AgentConfig = AgentConfig(),
    val clock: () -> Long = { 1000L },
    val lastInteraction: () -> Long? = { null },
    /** 「她的身体」：发图与发动态。null 表示这台设备没有这项能力。 */
    val sendImage: (suspend (description: String, selfie: Boolean?) -> String)? = null,
    val postDynamic: (suspend (text: String) -> String)? = null,
) {
    private var ids = 0
    val safetyAudit = mutableListOf<dev.lily.api.SafetyBlock>()
    val agent = LilyAgent(
        chat,
        operator,
        device,
        memory,
        skills,
        sentinel,
        users,
        reminders,
        config,
        clock = clock,
        lastInteraction = lastInteraction,
        idFactory = { "t${++ids}" },
        safetyAudit = { safetyAudit += it },
        sendImage = sendImage,
        postDynamic = postDynamic,
    )
    val events = Events()
    val stop = Stop()
}

/**
 * 这台设备实际能给出的内置工具名。
 *
 * 「她的身体」（发图 / 发动态）按设备能力裁剪：Harness 默认不给，所以默认清单里没有它们。
 */
fun builtInToolNames(
    sendImage: Boolean = false,
    postDynamic: Boolean = false,
): List<String> = AgentTools.specs
    .filter {
        when (it.name) {
            AgentTools.SEND_IMAGE -> sendImage
            AgentTools.POST_DYNAMIC -> postDynamic
            else -> true
        }
    }
    .map { it.name }

/** Operator reply JSON for an action object. */
fun op(action: String, thought: String = "想一想") = "{\"thought\": \"$thought\", \"action\": $action}"
