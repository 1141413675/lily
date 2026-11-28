package dev.lily.agent

import dev.lily.api.ToolProvider
import dev.lily.api.ToolSpec
import dev.lily.api.Agent
import dev.lily.api.AgentConfig
import dev.lily.api.AgentEvent
import dev.lily.api.AgentListener
import dev.lily.api.ChatMessage
import dev.lily.api.ChatRequest
import dev.lily.api.ChatRole
import dev.lily.api.ContentPart
import dev.lily.api.Device
import dev.lily.api.LlmClient
import dev.lily.api.LlmException
import dev.lily.api.MemoryStore
import dev.lily.api.Note
import dev.lily.api.Observation
import dev.lily.api.ReminderScheduler
import dev.lily.api.SafetyAudit
import dev.lily.api.SafetyBlock
import dev.lily.api.Sentinel
import dev.lily.api.Skill
import dev.lily.api.SkillChannel
import dev.lily.api.SkillRepository
import dev.lily.api.StopSignal
import dev.lily.api.UserChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * The Lily agent: chat turns with memory and phone tools ([handle]), and phone tasks run by
 * the operator loop or a skill flow ([runPhoneTask]). See docs/ARCHITECTURE.md.
 *
 * Conversation history is kept in memory, bounded to the last [HISTORY_LIMIT] user/assistant messages.
 */
class LilyAgent(
    private val chat: LlmClient,
    operator: LlmClient,
    device: Device,
    private val memory: MemoryStore,
    private val skills: SkillRepository,
    sentinel: Sentinel,
    users: UserChannel,
    private val reminders: ReminderScheduler,
    private val config: AgentConfig = AgentConfig(),
    private val persona: String = Prompts.DEFAULT_PERSONA,
    /** Persona context that changes without rebuilding the agent: relationship state and its boundaries. */
    private val contextNotes: () -> String = { "" },
    /** Local audit of hard refusals and confirmation decisions. */
    private val safetyAudit: SafetyAudit = SafetyAudit.NONE,
    /** Refusal ledger and the hourly retry cap; the agent only asks whether a retry is allowed. */
    private val refusal: RefusalSink = RefusalSink.NONE,
    private val clock: () -> Long = System::currentTimeMillis,
    /** 上一次用户开口的时间（本机本地时钟）；用于每轮注入「距上次互动多久」。 */
    private val lastInteraction: () -> Long? = { null },
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    /** Extra tools such as MCP servers; their names must not shadow the built-in ones. */
    private val externalTools: ToolProvider? = null,
    /** 生成并发出一张图；null 表示这台设备发不了图。 */
    private val sendImage: (suspend (description: String, selfie: Boolean?) -> String)? = null,
    /** 发一条动态；null 表示这台设备发不了动态。 */
    private val postDynamic: (suspend (text: String) -> String)? = null,
) : Agent {
    private val loop = OperatorLoop(operator, device, sentinel, users, config, safetyAudit)
    private val flows = FlowRunner(loop, users)
    private val historyLock = Mutex()
    private val history = ArrayDeque<ChatMessage>()
    /** 被裁剪出窗口的更早对话，压成一段滚动摘要；受 [historyLock] 保护，不额外调用模型。 */
    private val earlier = StringBuilder()

    override suspend fun handle(userMessage: String, listener: AgentListener, stop: StopSignal) {
        val taskId = idFactory()
        val emit: (AgentEvent) -> Unit = listener::onEvent
        // Hard refusal before the model or any tool runs.
        PaymentSafety.instruction(userMessage)?.let { hit ->
            safetyAudit.blocked(SafetyBlock("chat", userMessage, hit.rule, hit.matched))
            emit(AgentEvent.Reply(taskId, PaymentSafety.REFUSAL))
            return
        }
        // Content hard lines share one word list with the refusal fallback; no retry, told plainly.
        ContentSafety.classify(userMessage)?.let { category ->
            refusal.refused(category, userMessage)
            emit(AgentEvent.Reply(taskId, category.reply))
            return
        }
        val tools = ChatTools(
            memory = memory,
            skills = skills,
            clock = clock,
            reminders = reminders,
            external = externalTools,
            phoneTasks = config.phoneTasks,
            sendImage = sendImage,
            postDynamic = postDynamic,
            phoneTask = { goal, skill -> runPhoneTask(goal, skill, listener, stop) },
        )
        try {
            val builtIn = builtInSpecs()
            val toolSpecs = builtIn + externalSpecs()
            // 历史与滚动摘要一起取，保证同一轮看到的窗口一致。
            val (past, earlierSummary) = historyLock.withLock { history.toList() to earlier.toString() }
            val system = ChatMessage.system(
                systemPrompt(userMessage, hasExternalTools = toolSpecs.size > builtIn.size, earlier = earlierSummary),
            )
            val turn = mutableListOf(ChatMessage.user(userMessage))
            var lastText: String? = null
            var finalText: String? = null
            for (round in 0..MAX_TOOL_ROUNDS) {
                val offerTools = round < MAX_TOOL_ROUNDS
                val response = chat.complete(
                    ChatRequest(listOf(system) + past + turn, tools = if (offerTools) toolSpecs else emptyList()),
                )
                val text = response.text.trim()
                if (response.toolCalls.isEmpty() || !offerTools) {
                    finalText = text.ifEmpty { if (response.toolCalls.isNotEmpty()) Prompts.TOOL_ROUNDS_EXCEEDED else "" }
                    break
                }
                if (text.isNotEmpty()) {
                    emit(AgentEvent.Reply(taskId, text))
                    lastText = text
                }
                turn += ChatMessage(ChatRole.ASSISTANT, listOf(ContentPart.Text(response.text)), toolCalls = response.toolCalls)
                for (call in response.toolCalls) {
                    turn += ChatMessage.tool(call.id, tools.execute(call, taskId, emit))
                }
                if (stop.stopped) break
            }
            val answer = finalText?.takeIf { it.isNotEmpty() }?.let { text ->
                guardReply(text, taskId, system, past, turn, emit)
            }
            if (!answer.isNullOrEmpty()) emit(AgentEvent.Reply(taskId, answer))
            remember(userMessage, answer?.takeIf { it.isNotEmpty() } ?: lastText)
        } catch (e: LlmException) {
            emit(AgentEvent.Failed(taskId, Prompts.llmFailed(e)))
        }
    }

    override suspend fun runPhoneTask(goal: String, skillName: String?, listener: AgentListener, stop: StopSignal): AgentEvent.PhoneTaskFinished {
        val taskId = idFactory()
        // A skill, flow or debug entry can bypass chat; refuse the goal here too.
        PaymentSafety.instruction(goal)?.let { hit ->
            safetyAudit.blocked(SafetyBlock("task", goal, hit.rule, hit.matched))
            listener.onEvent(AgentEvent.Failed(taskId, PaymentSafety.REFUSAL))
            return AgentEvent.PhoneTaskFinished(taskId, false, PaymentSafety.REFUSAL, 0).also(listener::onEvent)
        }
        fun finish(success: Boolean, summary: String, steps: Int) =
            AgentEvent.PhoneTaskFinished(taskId, success, summary, steps).also(listener::onEvent)

        val wanted = skillName?.trim()?.takeIf { it.isNotEmpty() }
        val skill = wanted?.let { quietly(null) { skills.get(it) } }
        if (skill?.channel == SkillChannel.CHAT) return finish(false, "${Prompts.CHAT_SKILL_PHONE_REFUSED}：${skill.name}", 0)
        if (skill != null) listener.onEvent(AgentEvent.SkillChosen(taskId, skill.name))
        listener.onEvent(AgentEvent.PhoneTaskStarted(taskId, goal, skill?.name))
        if (wanted != null && skill == null) return finish(false, "${Prompts.SKILL_NOT_FOUND}：$wanted", 0)

        val task = TaskRun(taskId, goal, skill, listener, stop)
        val outcome = try {
            task.references = references(skill)
            task.howto = howto(goal, skill)
            val flow = skill?.flow
            if (flow != null && flow.steps.isNotEmpty()) flows.run(task, flow) else loop.run(task)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmException) {
            val message = Prompts.operatorLlmFailed(e)
            listener.onEvent(AgentEvent.Failed(taskId, message))
            LoopOutcome(false, message)
        } catch (e: Exception) {
            // e.g. the accessibility service is not connected: device.observe() throws.
            val message = e.message?.takeIf { it.isNotBlank() } ?: Prompts.UNEXPECTED_ERROR
            listener.onEvent(AgentEvent.Failed(taskId, message))
            LoopOutcome(false, message)
        }
        if (outcome.success && config.learnFromSuccess) quietly(Unit) { learn(task) }
        return finish(outcome.success, outcome.summary, task.steps)
    }

    private fun builtInSpecs(): List<ToolSpec> = AgentTools.specs.filter { spec ->
        when (spec.name) {
            AgentTools.PHONE_TASK -> config.phoneTasks
            // 这两项是「她的身体」：设备给不了就不出现在清单里，她也就不会假装能做。
            AgentTools.SEND_IMAGE -> sendImage != null
            AgentTools.POST_DYNAMIC -> postDynamic != null
            else -> true
        }
    }

    /** External tools that do not clash with built-in names; a failing provider simply offers nothing. */
    private suspend fun externalSpecs(): List<ToolSpec> {
        val provider = externalTools ?: return emptyList()
        val builtIn = AgentTools.specs.map { it.name }.toSet()
        return try {
            provider.specs().filter { it.name !in builtIn }.distinctBy { it.name }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 系统提示分两段：前段逐字符稳定（人设、工具说明、技能、长期规则），
     * 后段是本轮易变内容（关系状态、当前时间、更早对话摘要、记忆命中）。
     * 方舟按前缀做上下文缓存，固定内容必须放在最前面且顺序不变，否则每轮都全价重算。
     */
    private suspend fun systemPrompt(
        userMessage: String,
        hasExternalTools: Boolean = false,
        earlier: String = "",
    ): String = buildString {
        // —— 稳定段 ——
        append(persona.trim())
        if (hasExternalTools) append("\n").append(Prompts.EXTERNAL_TOOLS_HINT)
        val list = quietly(emptyList()) { skills.list() }
        if (config.phoneTasks) {
            append("\n\n").append(Prompts.SKILLS_HEADER).append('\n')
            val phoneSkills = list.filter { it.channel != SkillChannel.CHAT }
            if (phoneSkills.isEmpty()) append(Prompts.NO_SKILLS)
            else phoneSkills.forEach { append("- ").append(it.name).append(": ").append(it.description).append('\n') }
        }
        val chatSkills = list.filter { it.channel == SkillChannel.CHAT }
        if (chatSkills.isNotEmpty()) {
            append("\n\n").append(Prompts.CHAT_SKILLS_HEADER).append('\n').append(Prompts.CHAT_SKILLS_HINT)
            var remaining = MAX_CHAT_SKILLS_CHARS
            for (skill in chatSkills) {
                append("\n\n### ").append(skill.name).append("：").append(skill.description)
                if (remaining <= 0) continue
                val instructions = skill.instructions.take(MAX_CHAT_SKILL_CHARS).take(remaining)
                if (instructions.isNotEmpty()) append('\n').append(instructions)
                remaining -= instructions.length
                // References share the workflow budget so many notes cannot inflate the prompt.
                for (ref in skill.memoryRefs) {
                    if (remaining <= 0) break
                    val note = quietly(null) { memory.read(ref) } ?: continue
                    val text = NoteText.forPrompt(note).take(remaining)
                    append("\n\n").append(text)
                    remaining -= text.length
                }
            }
        }
        // Rules confirmed by the user (dream): only active, confident ones; nothing when there are none.
        val learned = quietly(emptyList()) { memory.list(RULE_FOLDER) }
            .filter { it.type == RULE_TYPE }
            .mapNotNull { note ->
                val extras = note.extraFrontmatter
                val statement = extras["statement"]?.toString()?.trim().orEmpty()
                val confidence = (extras["confidence"] as? Number)?.toDouble() ?: 0.0
                if (extras["status"]?.toString() == "active" && statement.isNotEmpty() && confidence >= MIN_RULE_CONFIDENCE) {
                    confidence to statement
                } else {
                    null
                }
            }
            .sortedByDescending { it.first }
            .take(MAX_RULES)
        if (learned.isNotEmpty()) {
            append("\n\n").append(Prompts.RULES_HEADER).append('\n').append(Prompts.RULES_HINT).append('\n')
            learned.forEach { append("- ").append(it.second).append('\n') }
        }

        // —— 易变段：以下内容每轮都可能不同，放在稳定段之后，不破坏前缀缓存 ——
        val context = quietly("") { contextNotes() }.trim()
        if (context.isNotEmpty()) append("\n\n").append(context)
        append("\n\n").append(Prompts.nowSection(LocalTimeContext.describe(clock(), lastInteractionMillis = lastInteraction())))
        if (earlier.isNotBlank()) {
            append("\n\n").append(Prompts.EARLIER_HEADER).append('\n').append(earlier.trim())
        }
        val hits = quietly(emptyList()) { memory.search(userMessage, MEMORY_HITS) }
        if (hits.isNotEmpty()) {
            append("\n\n").append(Prompts.MEMORY_HEADER).append('\n')
            hits.forEach {
                val snippet = it.snippet.replace('\n', ' ').take(MEMORY_SNIPPET_CHARS)
                append("- ").append(it.title).append(" (").append(it.permalink).append("): ").append(snippet).append('\n')
            }
        }
    }.trimEnd()

    /**
     * 拒答兜底：命中客服式拒答时同轮重试 1 次（不写入历史）；仍失败或超出每小时上限则换含蓄表达。
     * 硬红线不重试。这个函数只返回最终要显示、要记入历史的那一句。
     */
    private suspend fun guardReply(
        reply: String,
        taskId: String,
        system: ChatMessage,
        past: List<ChatMessage>,
        turn: List<ChatMessage>,
        emit: (AgentEvent) -> Unit,
    ): String {
        refusal.observed()
        val verdict = RefusalGuard.check(reply)
        val hard = ContentSafety.classify(reply)
        if (!verdict.refused && hard == null) return reply
        if (hard != null) {
            refusal.refused(hard, reply)
            return hard.reply
        }
        if (!refusal.refused(null, reply)) {
            emit(AgentEvent.Notice(taskId, RefusalGuard.NOTICE))
            return RefusalGuard.SOFT_FALLBACK
        }
        return try {
            val retried = chat.complete(
                ChatRequest(listOf(system) + past + turn + ChatMessage.user(RefusalGuard.RETRY_PROMPT)),
            )
            val text = retried.text.trim()
            val ok = text.isNotEmpty() && !RefusalGuard.check(text).refused && ContentSafety.classify(text) == null
            refusal.retried(ok)
            if (ok) {
                text
            } else {
                emit(AgentEvent.Notice(taskId, RefusalGuard.NOTICE))
                RefusalGuard.SOFT_FALLBACK
            }
        } catch (e: LlmException) {
            refusal.retried(false)
            emit(AgentEvent.Notice(taskId, RefusalGuard.NOTICE))
            RefusalGuard.SOFT_FALLBACK
        }
    }

    private suspend fun remember(userMessage: String, reply: String?) = historyLock.withLock {
        history.addLast(ChatMessage.user(userMessage))
        if (!reply.isNullOrBlank()) history.addLast(ChatMessage.assistant(reply))
        while (history.size > HISTORY_LIMIT) foldIntoSummary(history.removeFirst())
        // Never start the window with an assistant message.
        while (history.firstOrNull()?.role == ChatRole.ASSISTANT) history.removeFirst()
    }

    /** 把滑出窗口的一条消息压进滚动摘要：单行、截断，整体只保留最近 [SUMMARY_MAX_CHARS] 字。 */
    private fun foldIntoSummary(message: ChatMessage) {
        val text = message.text.trim().replace('\n', ' ').take(SUMMARY_LINE_CHARS)
        if (text.isEmpty()) return
        val line = (if (message.role == ChatRole.USER) "用户：" else "她：") + text
        if (earlier.isNotEmpty()) earlier.append('\n')
        earlier.append(line)
        if (earlier.length > SUMMARY_MAX_CHARS) {
            // 从最旧的一行开始丢，保持摘要始终贴着最近的对话。
            val cut = earlier.length - SUMMARY_MAX_CHARS
            val nextLine = earlier.indexOf("\n", cut)
            earlier.delete(0, if (nextLine < 0) earlier.length else nextLine + 1)
        }
    }

    /** 用户撤回了一条消息：把它从对话历史里删掉，后面的请求不再带上这句话。 */
    suspend fun forget(userMessage: String) {
        val message = userMessage.trim()
        if (message.isEmpty()) return
        historyLock.withLock {
            val index = history.indexOfLast { it.role == ChatRole.USER && it.text == message }
            if (index >= 0) history.removeAt(index)
            // 摘要里同一句也一并删掉，撤回后不再被提起。
            val line = "用户：" + message.replace('\n', ' ').take(SUMMARY_LINE_CHARS)
            val kept = earlier.toString().lines().filterNot { it == line }
            earlier.setLength(0)
            earlier.append(kept.joinToString("\n"))
        }
    }

    private suspend fun references(skill: Skill?): String {
        if (skill == null) return ""
        return skill.memoryRefs.mapNotNull { ref -> quietly(null) { memory.read(ref) }?.let { NoteText.forPrompt(it) } }.joinToString("\n\n")
    }

    /** `howto` notes for the goal: the skill's own note first, then search hits of type howto. */
    private suspend fun howto(goal: String, skill: Skill?): String {
        val notes = LinkedHashMap<String, Note>()
        skill?.let { s -> quietly(null) { memory.read(howtoPermalink(s.name)) }?.let { notes[it.permalink] = it } }
        for (hit in quietly(emptyList()) { memory.search(goal, MEMORY_HITS) }) {
            if (notes.size >= MAX_HOWTO_NOTES) break
            if (hit.permalink in notes) continue
            quietly(null) { memory.read(hit.permalink) }?.takeIf { it.type == HOWTO_TYPE }?.let { notes[it.permalink] = it }
        }
        return notes.values.take(MAX_HOWTO_NOTES).joinToString("\n\n") { NoteText.forPrompt(it, maxChars = 800) }
    }

    /** Records the successful path as `- [path] a → b → c #skill` in `howto/<skill-or-app>`. */
    private suspend fun learn(task: TaskRun) {
        if (task.path.isEmpty()) return
        val key = task.skill?.name ?: task.launchedPackage ?: task.lastPackage ?: return
        val permalink = howtoPermalink(key)
        val observation = Observation(PATH_CATEGORY, task.path.joinToString(" → "), tags = listOf(key))
        val existing = memory.read(permalink)
        if (existing != null) {
            if (existing.observations.any { it.category == observation.category && it.content == observation.content }) return
            memory.appendObservation(permalink, observation)
        } else {
            val title = "$key 操作路径"
            memory.write(
                Note(
                    title = title,
                    permalink = permalink,
                    type = HOWTO_TYPE,
                    tags = listOf(HOWTO_TYPE),
                    body = NoteText.body(title, listOf(observation), emptyList()),
                    observations = listOf(observation),
                    updatedAtMillis = clock(),
                ),
            )
        }
        task.emit(AgentEvent.MemoryUpdated(task.taskId, permalink))
    }

    private suspend fun <T> quietly(default: T, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        default
    }

    companion object {
        const val MAX_TOOL_ROUNDS = 8
        /** 最近 8 轮原始消息；更早的压进滚动摘要。 */
        const val HISTORY_LIMIT = 16
        const val MEMORY_HITS = 3
        private const val MEMORY_SNIPPET_CHARS = 160
        private const val SUMMARY_MAX_CHARS = 800
        private const val SUMMARY_LINE_CHARS = 60
        const val HOWTO_TYPE = "howto"
        const val PATH_CATEGORY = "path"
        const val RULE_FOLDER = "rules"
        const val RULE_TYPE = "rule"
        const val MIN_RULE_CONFIDENCE = 0.6
        const val MAX_RULES = 8
        private const val MAX_HOWTO_NOTES = 3
        const val MAX_CHAT_SKILL_CHARS = 2000
        const val MAX_CHAT_SKILLS_CHARS = 8000

        fun howtoPermalink(key: String) = "howto/$key"
    }
}
