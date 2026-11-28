package dev.lily.app.session

import dev.lily.api.Action
import dev.lily.api.AgentEvent

/** One row of the chat list. */
sealed class ChatItem {
    abstract val id: Long

    /** 被回复那条消息的摘要；[fromMe] 决定引用块用谁的颜色。 */
    data class Quote(val fromMe: Boolean, val text: String)

    data class User(
        override val id: Long,
        val text: String,
        val quote: Quote? = null,
        val reaction: String? = null,
        /** 撤回后原位置只留一行提示，且不再进入后续上下文。 */
        val recalled: Boolean = false,
    ) : ChatItem()

    /** 用户从表情库发来的表情：无底色、不套文字气泡，[file] 为本地图片路径。 */
    data class UserSticker(override val id: Long, val file: String) : ChatItem()

    /** 用户从相册或相机发来的照片：按最长边 190dp、最高 240dp 等比裁切显示，点开可看大图。 */
    data class UserImage(override val id: Long, val file: String) : ChatItem()

    /** 转发到聊天里的动态：保留原作者、正文与图片，按帖子卡片展示，不退化成一句文字。 */
    data class UserFeedShare(
        override val id: Long,
        val postId: String,
        val authorName: String,
        val authorIsMe: Boolean,
        val text: String,
        val images: List<String>,
        val at: Long,
    ) : ChatItem()

    data class Assistant(
        override val id: Long,
        val text: String,
        val reaction: String? = null,
        /** 群聊里这句是谁说的；私聊恒为 null，读旧数据也安全。 */
        val from: String? = null,
    ) : ChatItem()

    /** 她分享的生活/景区图：左对齐，与用户照片同一套尺寸与看图流程。 */
    data class AssistantImage(override val id: Long, val file: String) : ChatItem()

    /**
     * What the user said as audio: the bubble plays [file] (their own recording) and shows
     * [durationMs]; [transcript] only goes to the agent and is never rendered.
     */
    data class UserVoice(override val id: Long, val file: String, val durationMs: Int, val transcript: String) : ChatItem()

    /**
     * 普通语音只显示播放条；短清唱额外显示歌词与情绪。Empty [file] until the TTS is ready.
     * [spokenText] 是这条语音实际朗读的文本，只给通话页的自回声比对用，不渲染。
     */
    data class AssistantVoice(
        override val id: Long,
        val file: String,
        val durationMs: Int,
        val songLyrics: String? = null,
        val songEmotion: String? = null,
        val spokenText: String? = null,
    ) : ChatItem()

    /** Compact progress line: steps, skill chosen, memory updated, task result. */
    data class Progress(override val id: Long, val text: String, val ok: Boolean? = null) : ChatItem()

    /**
     * 旧版聊天流里的通话条目：不再新增，时间线直接跳过（见 [dev.lily.app.ui.chatTimeline]）。
     * 保留类型只为读得懂进程里可能还留着的旧条目；通话记录统一在通话记录页。
     */
    data class CallRecord(
        override val id: Long,
        val startedAt: Long,
        val connectedAt: Long,
        val endedAt: Long,
        val outcome: CallOutcome,
        val reason: String? = null,
    ) : ChatItem() {
        val durationMs: Long
            get() = if (outcome == CallOutcome.CONNECTED && connectedAt > 0) (endedAt - connectedAt).coerceAtLeast(0) else 0
    }

    data class Question(override val id: Long, val text: String, val takeover: Boolean) : ChatItem()

    data class Error(override val id: Long, val text: String) : ChatItem()
}

enum class CallOutcome { CONNECTED, MISSED, CANCELED, FAILED }

/** Human-readable (Chinese) text for agent events, used by the chat list and the status overlay. */
object StepText {
    fun action(action: Action): String = when (action) {
        is Action.Tap -> "点击" + where(action.target, action.nodeId)
        is Action.LongPress -> "长按" + where(action.target, action.nodeId)
        is Action.Swipe -> "滑动" + where(action.target, null)
        is Action.TypeText -> "输入「${action.text}」" + where(action.target, action.nodeId)
        is Action.Back -> "返回"
        is Action.Home -> "回到桌面"
        is Action.LaunchApp -> "打开 ${action.packageName}"
        is Action.OpenDeeplink -> "打开链接 ${action.uri.substringBefore('?')}"
        is Action.Wait -> "等待 ${action.millis} 毫秒"
        is Action.AskUser -> "询问：${action.question}"
        is Action.Finish -> "结束：${action.summary}"
    }

    private fun where(target: String?, nodeId: Int?): String = when {
        !target.isNullOrBlank() -> " $target"
        nodeId != null -> " #$nodeId"
        else -> ""
    }

    /** Overlay / notification status for [event], or null to keep the previous one. */
    fun status(event: AgentEvent): String? = when (event) {
        is AgentEvent.PhoneTaskStarted -> "开始：${event.goal}"
        is AgentEvent.StepPlanned -> "第 ${event.stepIndex + 1} 步：${action(event.action)}"
        is AgentEvent.StepBlocked -> "第 ${event.stepIndex + 1} 步已拦截"
        is AgentEvent.NeedsUser -> if (event.takeover) "请接管手机，完成后回来继续" else "等待你回答：${event.question}"
        is AgentEvent.PhoneTaskFinished -> if (event.success) "已完成" else "未完成"
        else -> null
    }

    /** The chat row for [event], or null when it only updates the status. */
    fun chatItem(event: AgentEvent, id: Long): ChatItem? = when (event) {
        is AgentEvent.Reply -> ChatItem.Assistant(id, event.text)
        is AgentEvent.Notice -> ChatItem.Progress(id, event.text)
        is AgentEvent.SkillChosen -> ChatItem.Progress(id, "使用技能 ${event.skillName}")
        is AgentEvent.PhoneTaskStarted -> ChatItem.Progress(id, "开始手机任务：${event.goal}")
        is AgentEvent.StepPlanned -> null
        is AgentEvent.StepExecuted -> {
            val failure = event.result.message?.takeIf { !event.result.ok }?.let { "（$it）" }.orEmpty()
            ChatItem.Progress(id, "${event.stepIndex + 1}. ${action(event.action)}$failure", event.result.ok)
        }
        is AgentEvent.StepBlocked -> ChatItem.Progress(id, "${event.stepIndex + 1}. ${action(event.action)}：已拦截（${event.decision.reason}）", false)
        is AgentEvent.NeedsUser -> ChatItem.Question(id, event.question, event.takeover)
        is AgentEvent.MemoryUpdated -> ChatItem.Progress(id, "已更新记忆 ${event.permalink}")
        is AgentEvent.ToolCalled -> ChatItem.Progress(id, event.summary, event.ok)
        is AgentEvent.PhoneTaskFinished -> ChatItem.Progress(
            id,
            (if (event.success) "任务完成" else "任务未完成") + "（${event.steps} 步）：${event.summary}",
            event.success,
        )
        is AgentEvent.Failed -> ChatItem.Error(id, event.message)
    }
}

/**
 * 「看情况」时模型在回复首行写的呈现标记：`[语音]` / `[文字]`。
 * 标记只用于客户端决定文字还是语音气泡，不展示、不写进记忆。
 */
object ReplyPresentation {
    private val voice = Regex("""^\s*\[(语音|voice)\]\s*""", RegexOption.IGNORE_CASE)
    private val text = Regex("""^\s*\[(文字|text)\]\s*""", RegexOption.IGNORE_CASE)

    /** 返回去掉标记后的正文；第二项 null 表示模型没写标记。 */
    fun parse(raw: String): Pair<String, Boolean?> {
        val head = raw.trimStart()
        if (voice.containsMatchIn(head)) return head.replaceFirst(voice, "").trimStart() to true
        if (text.containsMatchIn(head)) return head.replaceFirst(text, "").trimStart() to false
        return raw to null
    }
}
