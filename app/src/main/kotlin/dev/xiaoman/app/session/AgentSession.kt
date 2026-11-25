package dev.xiaoman.app.session

import dev.xiaoman.agent.PaymentSafety
import dev.xiaoman.api.Agent
import dev.xiaoman.api.AgentEvent
import dev.xiaoman.api.AgentListener
import dev.xiaoman.api.ApprovalAnswer
import dev.xiaoman.api.ApprovalGateway
import dev.xiaoman.api.ApprovalRequest
import dev.xiaoman.api.SafetyAudit
import dev.xiaoman.api.SafetyBlock
import dev.xiaoman.api.UserChannel
import dev.xiaoman.app.RunIds
import dev.xiaoman.hands.MutableStopSignal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Texts the session adds to the chat itself; resolved from resources by the app. */
data class SessionTexts(
    val notConfigured: String = "还没有配置模型。",
    val busy: String = "已有任务在运行。",
    val stopped: String = "已停止",
    val approvedOnce: (String) -> String = { "已允许：$it" },
    val approvedTask: (String) -> String = { "本次任务都允许：$it" },
    val denied: (String) -> String = { "已拒绝：$it" },
)

class PendingQuestion(val id: Long, val taskId: String, val question: String, val takeover: Boolean) {
    internal val answer = CompletableDeferred<String>()
}

class PendingApproval(val id: Long, val request: ApprovalRequest) {
    internal val answer = CompletableDeferred<ApprovalAnswer>()
}

/**
 * The one conversation with the agent: runs chat turns and phone tasks one at a time in [scope],
 * keeps the chat list, and is the agent's [UserChannel] (the next chat input answers a question)
 * and [ApprovalGateway] (the approval card / notification answers it).
 *
 * [AgentService][dev.xiaoman.app.AgentService] keeps the process in the foreground while [busy].
 *
 * Every phone task ends with a terminal event in the trace: when the agent cannot run (not
 * configured, busy, crashed, cancelled) the session emits `Failed` + `PhoneTaskFinished` itself.
 */
class AgentSession(
    private val scope: CoroutineScope,
    private val agent: () -> Agent?,
    private val runIds: RunIds,
    val stopSignal: MutableStopSignal,
    private val trace: AgentListener,
    private val onTaskEnd: (String) -> Unit = {},
    private val texts: SessionTexts = SessionTexts(),
    private val ready: suspend () -> Unit = {},
    /** Local audit of hard refusals; the message layer refuses even before the agent is configured. */
    private val safetyAudit: SafetyAudit = SafetyAudit.NONE,
) : UserChannel, ApprovalGateway {

    private val ids = AtomicLong()
    private val running = AtomicBoolean(false)

    private val _items = MutableStateFlow<List<ChatItem>>(emptyList())
    val items: StateFlow<List<ChatItem>> = _items.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)

    /** Short text of the current step for the overlay and the notification. */
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _question = MutableStateFlow<PendingQuestion?>(null)
    val question: StateFlow<PendingQuestion?> = _question.asStateFlow()

    private val _approval = MutableStateFlow<PendingApproval?>(null)
    val approval: StateFlow<PendingApproval?> = _approval.asStateFlow()

    private val _events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    /** Phone tasks started but without PhoneTaskFinished yet. */
    private val openRuns = LinkedHashSet<String>()

    /** Trace first, then the UI. */
    val listener = AgentListener { event ->
        try {
            trace.onEvent(event)
        } catch (e: Exception) {
            // A full disk must not stop the task.
        }
        synchronized(openRuns) {
            when (event) {
                is AgentEvent.PhoneTaskStarted -> openRuns += event.taskId
                is AgentEvent.PhoneTaskFinished -> openRuns -= event.taskId
                else -> Unit
            }
        }
        if (event is AgentEvent.PhoneTaskFinished) onTaskEnd(event.taskId)
        StepText.status(event)?.let { _status.value = it }
        if (event is AgentEvent.Reply) {
            // The message layer may swap the text reply for a voice bubble (or open the call screen).
            val id = ids.incrementAndGet()
            // 「看情况」的 [语音]/[文字] 标记只给客户端用，去掉后再入列、入记忆。
            val (parsed, hint) = ReplyPresentation.parse(event.text)
            // 出口检查：系统提示/技能说明残留不进气泡，也不进语音；标记按原样补回给语音层。
            val clean = ReplyGuard.sanitize(parsed)
            val rendered = hint?.let { if (it) "[语音]\n$clean" else "[文字]\n$clean" } ?: clean
            (renderReply?.invoke(rendered, id) ?: ChatItem.Assistant(id, clean))?.let(::add)
            logUtterance("assistant", clean)
            try {
                onReply?.invoke(lastUserText, clean)
            } catch (e: Exception) {
                // 生活分享失败不能影响回复。
            }
        } else {
            StepText.chatItem(event, ids.incrementAndGet())?.let(::add)
        }
        _events.tryEmit(if (event is AgentEvent.Reply) event.copy(text = ReplyPresentation.parse(event.text).first) else event)
    }

    /**
     * The chat layer's presentation hook for [AgentEvent.Reply]: return the row to add, or null for
     * the plain text reply. A voice bubble is added empty and later rewritten with [replace].
     */
    @Volatile
    var renderReply: ((text: String, id: Long) -> ChatItem?)? = null

    /**
     * Mirrors user/assistant text into the local conversation log used by the dream job. A failure
     * here is swallowed: logging must never affect the turn.
     */
    @Volatile
    var onUtterance: ((role: String, text: String) -> Unit)? = null

    private fun logUtterance(role: String, text: String) {
        try {
            onUtterance?.invoke(role, text)
        } catch (e: Exception) {
            // Ignored on purpose.
        }
    }

    sealed class Submit {
        data object Answered : Submit()
        data object Started : Submit()
        data object Busy : Submit()
        data object NotConfigured : Submit()
        data object Empty : Submit()
        data object Refused : Submit()
    }

    /**
     * Chat input: answers a pending question, else starts a chat turn. [display] builds the user's
     * row from its new id (voice messages need it for the audio file name); null is a plain text row.
     */
    fun submit(text: String, display: ((Long) -> ChatItem)? = null, quote: ChatItem.Quote? = null): Submit {
        val message = text.trim()
        if (message.isEmpty()) return Submit.Empty
        val pending = _question.value
        if (pending != null && pending.answer.complete(message)) {
            addUser(message, display, quote)
            return Submit.Answered
        }
        // Hard refusal at the message layer: works even while the models are not configured yet.
        PaymentSafety.instruction(message)?.let { hit ->
            addUser(message, display, quote)
            safetyAudit.blocked(SafetyBlock("chat", message, hit.rule, hit.matched))
            add(ChatItem.Assistant(ids.incrementAndGet(), PaymentSafety.REFUSAL))
            logUtterance("assistant", PaymentSafety.REFUSAL)
            return Submit.Refused
        }
        val agent = agent()
        if (agent == null) {
            addUser(message, display, quote)
            add(ChatItem.Error(ids.incrementAndGet(), texts.notConfigured))
            return Submit.NotConfigured
        }
        val started = launch(null) { agent.handle(message, listener, stopSignal) }
        if (!started) return Submit.Busy
        addUser(message, display, quote)
        return Submit.Started
    }

    private fun addUser(message: String, display: ((Long) -> ChatItem)?, quote: ChatItem.Quote? = null) {
        lastUserText = message
        add(display?.invoke(ids.incrementAndGet()) ?: ChatItem.User(ids.incrementAndGet(), message, quote))
        logUtterance("user", message)
    }

    /** 给一条消息贴上表情，或在同一处再点一次取消；表情只在本机会话里，不发给模型。 */
    fun react(id: Long, emoji: String?) {
        _items.update { list ->
            list.map {
                when (it) {
                    is ChatItem.User -> if (it.id == id) it.copy(reaction = emoji) else it
                    is ChatItem.Assistant -> if (it.id == id) it.copy(reaction = emoji) else it
                    else -> it
                }
            }
        }
    }

    /** 撤回自己发过的一条文字消息：原位置留一行提示，并从后续上下文里剔除。 */
    fun recall(id: Long) {
        val target = _items.value.firstOrNull { it is ChatItem.User && it.id == id } as? ChatItem.User ?: return
        if (target.recalled) return
        _items.update { list ->
            list.map { if (it is ChatItem.User && it.id == id) it.copy(recalled = true, reaction = null) else it }
        }
        try {
            onRecall?.invoke(target.text)
        } catch (e: Exception) {
            // 撤回提示必须留下，上下文剔除失败不影响界面。
        }
    }

    /** 撤回后的清理钩子：把原话从模型历史与做梦日志里删掉。 */
    @Volatile
    var onRecall: ((String) -> Unit)? = null

    /** 最近一条用户文本；生活分享按上下文挑图时用它。 */
    @Volatile
    private var lastUserText: String = ""

    /**
     * 每轮助手回复落进聊天后回调（用户这条 + 她的回复），用于「生活分享」按地点挑图。
     * 抛异常会被吞掉，不能影响回复本身。
     */
    @Volatile
    var onReply: ((userText: String, replyText: String) -> Unit)? = null

    /** Rewrites the row [id] in place (a voice bubble after its audio is ready); no-op when it is gone. */
    fun replace(id: Long, item: ChatItem) {
        _items.update { list -> list.map { if (it.id == id) item else it } }
    }

    /**
     * Adds an assistant row that did not come from an agent turn (a proactive opening). The text is
     * also mirrored to the conversation log so the dream job sees it.
     */
    fun postAssistant(text: String) {
        val message = text.trim()
        if (message.isEmpty()) return
        add(ChatItem.Assistant(ids.incrementAndGet(), message))
        logUtterance("assistant", message)
    }

    /** 本地发送的表情：只进聊天列表，不触发模型回合。 */
    fun postSticker(file: String) {
        if (file.isBlank()) return
        add(ChatItem.UserSticker(ids.incrementAndGet(), file))
    }

    /** 她分享的生活/景区图：左对齐，与用户照片共用气泡尺寸和看图流程。 */
    fun postAssistantImage(file: String) {
        if (file.isBlank()) return
        add(ChatItem.AssistantImage(ids.incrementAndGet(), file))
    }

    /** 本地发送的照片：只进聊天列表，不触发模型回合。 */
    fun postImage(file: String) {
        if (file.isBlank()) return
        add(ChatItem.UserImage(ids.incrementAndGet(), file))
    }

    /** 删除一行（看图页的「删除」）；不存在的 id 忽略。 */
    fun removeItem(id: Long) {
        _items.update { list -> list.filterNot { it.id == id } }
    }

    /**
     * Runs one phone task directly (debug bridge). [runId] becomes the task id and trace folder;
     * an invalid or missing one is replaced. Returns false when it could not start; the trace then
     * already holds Failed + PhoneTaskFinished for that run.
     */
    fun runPhoneTask(goal: String, skill: String?, runId: String?): Boolean {
        val id = runId?.takeIf(RunIds::isValid) ?: runIds.generate()
        // Same hard refusal as the agent, so a debug/skill entry cannot bypass it even unconfigured.
        PaymentSafety.instruction(goal)?.let { hit ->
            safetyAudit.blocked(SafetyBlock("task", goal, hit.rule, hit.matched))
            fail(id, PaymentSafety.REFUSAL)
            return false
        }
        val agent = agent()
        if (agent == null) {
            fail(id, texts.notConfigured)
            return false
        }
        val started = launch(id) {
            runIds.prime(id)
            agent.runPhoneTask(goal, skill, listener, stopSignal)
        }
        if (!started) fail(id, texts.busy)
        return started
    }

    /** Emits Failed + PhoneTaskFinished for a run that cannot (or could not) complete. */
    fun fail(runId: String, message: String) {
        listener.onEvent(AgentEvent.Failed(runId, message))
        listener.onEvent(AgentEvent.PhoneTaskFinished(runId, false, message, 0))
    }

    /** Overlay Stop / notification Stop / debug STOP: cooperative stop, pending prompts released. */
    fun stop() {
        if (!_busy.value) return
        stopSignal.stop()
        _approval.value?.answer?.complete(ApprovalAnswer(approved = false))
        _question.value?.answer?.complete(texts.stopped)
        _status.value = texts.stopped
    }

    fun answerApproval(answer: ApprovalAnswer, approvalId: Long? = null) {
        val pending = _approval.value ?: return
        if (approvalId != null && pending.id != approvalId) return
        pending.answer.complete(answer)
    }

    override suspend fun ask(taskId: String, question: String, takeover: Boolean): String {
        val pending = PendingQuestion(ids.incrementAndGet(), taskId, question, takeover)
        if (stopSignal.stopped) return texts.stopped
        _question.value = pending
        try {
            return pending.answer.await()
        } finally {
            _question.compareAndSet(pending, null)
        }
    }

    override suspend fun request(request: ApprovalRequest): ApprovalAnswer {
        if (stopSignal.stopped) return ApprovalAnswer(approved = false)
        val pending = PendingApproval(ids.incrementAndGet(), request)
        val before = _status.value
        _approval.value = pending
        _status.value = "等待确认：${request.prompt}"
        try {
            val answer = pending.answer.await()
            val line = when {
                !answer.approved -> texts.denied(request.prompt)
                answer.scope == dev.xiaoman.api.ApprovalScope.TASK -> texts.approvedTask(request.prompt)
                else -> texts.approvedOnce(request.prompt)
            }
            add(ChatItem.Progress(ids.incrementAndGet(), line, answer.approved))
            return answer
        } finally {
            _approval.compareAndSet(pending, null)
            if (!stopSignal.stopped) _status.value = before
        }
    }

    /** Starts [block] unless something is running. [runId] is pre-registered as open (debug runs). */
    private fun launch(runId: String?, block: suspend () -> Unit): Boolean {
        if (!running.compareAndSet(false, true)) return false
        stopSignal.reset()
        if (runId != null) synchronized(openRuns) { openRuns += runId }
        _status.value = null
        _busy.value = true
        scope.launch {
            var failure: String? = null
            try {
                ready()
                block()
            } catch (e: CancellationException) {
                failure = texts.stopped
                throw e
            } catch (e: Exception) {
                failure = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            } finally {
                withContext(NonCancellable) {
                    val open = synchronized(openRuns) { openRuns.toList().also { openRuns.clear() } }
                    open.forEach { fail(it, failure ?: texts.stopped) }
                    if (failure != null && open.isEmpty()) add(ChatItem.Error(ids.incrementAndGet(), failure))
                    _question.value?.answer?.cancel()
                    _approval.value?.answer?.cancel()
                    _question.value = null
                    _approval.value = null
                    _status.value = null
                    running.set(false)
                    _busy.value = false
                }
            }
        }
        return true
    }

    private fun add(item: ChatItem) {
        _items.update { (it + item).takeLast(MAX_ITEMS) }
    }

    companion object {
        const val MAX_ITEMS = 500
    }
}
