package dev.lily.app.image

import dev.lily.app.media.SceneryStore
import dev.lily.app.session.ChatItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.io.File

sealed interface ImageRequestFeedback {
    data object Generating : ImageRequestFeedback
    data object Disabled : ImageRequestFeedback
    data class Failed internal constructor(
        internal val userText: String,
        internal val replyText: String,
    ) : ImageRequestFeedback
}

/**
 * 角色发图：在回复之后按语境挑来源，只有明确要图时才显示生成状态。
 *
 * 1. 回忆过去：用户明确指向聊天里出现过的旧图时，复用原路径，不复制、不重复占空间。
 * 2. 此刻想看：用户说「想看看 / 你在干嘛 / 给我看看 / 发张自拍」时，按当前对话的具体细节
 *    （在做什么、喝什么、在哪、时间与光线）生成；自拍用头像做参考图保持长相。
 * 3. 图库兜底：只有该发图但没 Key / 额度用完 / 生成失败时才翻 `files/scenery`；
 *    图库为空就什么都不做。
 *
 * 不做随机主动发图；明确请求也不受全局冷却拦截，避免「说了想看看却不给」。
 */
class ChatImages(
    private val scope: CoroutineScope,
    private val settings: () -> dev.lily.app.settings.AppSettings,
    private val items: () -> List<ChatItem>,
    private val portraitFile: () -> File?,
    private val store: ChatImageStore,
    private val scenery: SceneryStore,
    private val post: (String) -> Unit,
    /** 生成链路：返回图片字节；null 表示未配置或失败，调用方继续降级。 */
    private val generate: (suspend (prompt: String, reference: File?) -> ByteArray?)? = null,
    private val promptFor: (selfie: Boolean, context: String) -> String = { _, _ -> "" },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val sharing = Mutex()
    private val feedbackState = MutableStateFlow<ImageRequestFeedback?>(null)
    val feedback = feedbackState.asStateFlow()

    /** 用户发出明确要图的话时就开始生成，回复到达后再贴；把等待和模型回复重叠起来。 */
    private var earlyText: String? = null
    private var earlyResult: CompletableDeferred<String?>? = null

    /**
     * 在用户消息入列时调用。只对明确要图的句子预热；结果先不贴，
     * 等 [afterReply] 确认这一轮回复完成后再按原顺序发出，避免图片抢在文字前面。
     */
    fun prefetch(userText: String) {
        if (DECLINE_IMAGE_CUE.containsMatchIn(userText)) return
        if (!EXPLICIT_IMAGE_CUE.containsMatchIn(userText)) return
        if (earlyText != null) {
            // 上一句预热已经结束但回复没来：清掉，别把后面的请求永远挡在门外。
            if (earlyResult?.isCompleted == true) {
                earlyText = null
                earlyResult = null
            } else {
                return
            }
        }
        val image = settings().image
        if (!image.enabled) return
        val expected = userText
        earlyText = expected
        val result = CompletableDeferred<String?>()
        earlyResult = result
        scope.launch(Dispatchers.IO) {
            if (!sharing.tryLock()) {
                result.complete(null)
                return@launch
            }
            try {
                val portrait = portraitFile()
                val selfie = image.includeSelfie && portrait != null &&
                    SELFIE_CUE.containsMatchIn(expected) && !SCENE_OBJECT.containsMatchIn(expected)
                val prompt = promptFor(selfie, expected)
                if (prompt.isBlank() || generate == null) {
                    result.complete(null)
                    return@launch
                }
                feedbackState.value = ImageRequestFeedback.Generating
                val bytes = try {
                    generate.invoke(prompt, if (selfie) portrait else null)
                } catch (e: CancellationException) {
                    result.complete(null)
                    throw e
                } catch (e: Exception) {
                    null
                }
                val saved = bytes?.let(store::save)
                result.complete(saved?.absolutePath)
            } finally {
                sharing.unlock()
            }
        }
    }

    fun afterReply(userText: String, replyText: String) {
        if (DECLINE_IMAGE_CUE.containsMatchIn(userText)) return
        // 这一句已经预热过：等它出结果再贴，或者直接走图库兜底，不重复生成。
        if (earlyText == userText) {
            val result = earlyResult
            earlyText = null
            earlyResult = null
            scope.launch(Dispatchers.IO) {
                val path = result?.await()
                if (path != null) {
                    store.markShared(path)
                    post(path)
                    feedbackState.value = null
                } else {
                    if (!sharing.tryLock()) sharing.lock()
                    try {
                        val outcome = share(userText, replyText, allowGenerate = false) {
                            feedbackState.value = ImageRequestFeedback.Generating
                        }
                        feedbackState.value = when (outcome) {
                            ShareResult.SHARED, ShareResult.IGNORED -> null
                            ShareResult.DISABLED -> ImageRequestFeedback.Disabled
                            ShareResult.FAILED -> ImageRequestFeedback.Failed(userText, replyText)
                        }
                    } finally {
                        sharing.unlock()
                    }
                }
            }
            return
        }
        val explicit = EXPLICIT_IMAGE_CUE.containsMatchIn(userText)
        if (!explicit && (!settings().image.enabled ||
                (!WANT_CUE.containsMatchIn(userText) && !RECALL_CUE.containsMatchIn(userText)))) return
        launchShare(userText, replyText, explicit)
    }

    fun retry() {
        val failed = feedbackState.value as? ImageRequestFeedback.Failed ?: return
        if (feedbackState.compareAndSet(failed, ImageRequestFeedback.Generating)) {
            launchShare(failed.userText, failed.replyText, explicit = true)
        }
    }

    fun dismissFeedback() {
        val current = feedbackState.value
        if (current != ImageRequestFeedback.Generating) feedbackState.compareAndSet(current, null)
    }

    /** 角色被彻底删除后调用：丢掉预热结果与「正在生成」，避免残留的图片提示。 */
    fun reset() {
        earlyText = null
        earlyResult = null
        feedbackState.value = null
    }

    /**
     * 她主动发图：按她自己给的描述生成一张并直接贴到聊天里。
     *
     * 与 [share] 的区别是这里没有正则门槛——她想给用户看就发，用户没要求也发。
     * [description] 是她对这张图的描述；留空则按当前对话语境决定拍什么。
     *
     * @return 给模型看的一句话：成功说明已发出，失败说明原因。
     */
    suspend fun generateAndPost(description: String, selfie: Boolean?): String {
        val image = settings().image
        if (!image.enabled) return "发图开关是关着的，这次没发出去。"
        if (generate == null) return "现在没有可用的生图通道，这次没发出去。"
        if (!sharing.tryLock()) return "正在发上一张，这次没发出去。"
        try {
            val portrait = portraitFile()
            val wantSelfie = selfie ?: (image.includeSelfie && portrait != null)
            val useSelfie = wantSelfie && portrait != null
            val context = description.ifBlank { recentContext() }
            val prompt = promptFor(useSelfie, context)
            if (prompt.isBlank()) return "这次没想好拍什么，没发出去。"
            val bytes = try {
                generate.invoke(prompt, if (useSelfie) portrait else null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            val saved = bytes?.let(store::save)
            if (saved == null) return "这次没发出去。"
            store.markShared(saved.absolutePath)
            post(saved.absolutePath)
            return "已经发出去了。"
        } finally {
            sharing.unlock()
        }
    }

    /** 最近几轮对话的文字，用作发图语境。 */
    private fun recentContext(): String = items().takeLast(6).mapNotNull { item ->
        when (item) {
            is ChatItem.User -> item.text
            is ChatItem.Assistant -> item.text
            else -> null
        }
    }.joinToString("\n").takeLast(400)

    private fun launchShare(userText: String, replyText: String, explicit: Boolean, allowGenerate: Boolean = true) {
        scope.launch(Dispatchers.IO) {
            if (explicit) {
                sharing.lock()
            } else if (!sharing.tryLock()) {
                return@launch
            }
            try {
                val result = share(userText, replyText, allowGenerate) {
                    if (explicit) feedbackState.value = ImageRequestFeedback.Generating
                }
                if (explicit) {
                    feedbackState.value = when (result) {
                        ShareResult.SHARED, ShareResult.IGNORED -> null
                        ShareResult.DISABLED -> ImageRequestFeedback.Disabled
                        ShareResult.FAILED -> ImageRequestFeedback.Failed(userText, replyText)
                    }
                }
            } catch (e: CancellationException) {
                if (explicit) feedbackState.value = null
                throw e
            } catch (e: Exception) {
                if (explicit) feedbackState.value = ImageRequestFeedback.Failed(userText, replyText)
            } finally {
                sharing.unlock()
            }
        }
    }

    internal enum class ShareResult { SHARED, IGNORED, DISABLED, FAILED }

    /** 直接跑一次来源选择；[afterReply] 在 IO 线程调用它，测试也用它避免等调度。 */
    internal suspend fun share(
        userText: String,
        replyText: String,
        allowGenerate: Boolean = true,
        onGenerating: () -> Unit = {},
    ): ShareResult {
        if (DECLINE_IMAGE_CUE.containsMatchIn(userText)) return ShareResult.IGNORED
        val image = settings().image
        if (!image.enabled) return ShareResult.DISABLED
        val context = "$userText\n$replyText"

        // 1. 回忆过去：只引用聊天里出现过的原图，不重新生成、不下载。
        if (RECALL_CUE.containsMatchIn(userText)) {
            pickReuse(userText)?.let { path ->
                store.markShared(path)
                post(path)
                return ShareResult.SHARED
            }
        }

        // 2. 此刻想看：按这轮对话的细节生成，不随机挑生活场景。
        val wantsToSee = WANT_CUE.containsMatchIn(userText) || EXPLICIT_IMAGE_CUE.containsMatchIn(userText)
        if (!wantsToSee) return ShareResult.IGNORED

        if (allowGenerate) {
            val portrait = portraitFile()
            val selfie = image.includeSelfie && portrait != null &&
                SELFIE_CUE.containsMatchIn(userText) && !SCENE_OBJECT.containsMatchIn(userText)
            val prompt = promptFor(selfie, context)
            val bytes = if (prompt.isBlank() || generate == null) null else {
                onGenerating()
                try {
                    generate.invoke(prompt, if (selfie) portrait else null)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
            val saved = bytes?.let(store::save)
            if (saved != null) {
                store.markShared(saved.absolutePath)
                post(saved.absolutePath)
                return ShareResult.SHARED
            }
        }
        // 3. 图库兜底：无 Key 或生成失败时仍可分享匹配的图片。
        scenery.maybePick(context, now())?.let { picked ->
            store.markShared(picked.file)
            post(picked.file)
            return ShareResult.SHARED
        }
        return ShareResult.FAILED
    }

    /** 从聊天记录里挑一张最贴近当前话题的旧图；没有可用文件返回 null。 */
    private fun pickReuse(text: String): String? {
        data class Ref(val path: String, val mine: Boolean, val id: Long)

        val refs = items().mapNotNull { item ->
            when (item) {
                is ChatItem.UserImage -> Ref(item.file, mine = true, id = item.id)
                is ChatItem.AssistantImage -> Ref(item.file, mine = false, id = item.id)
                else -> null
            }
        }.filter { it.path.isNotBlank() && File(it.path).isFile }
        if (refs.isEmpty()) return null

        val aboutHers = text.contains("你") &&
            (text.contains("拍") || text.contains("发") || text.contains("给我") || text.contains("那张"))
        val aboutMine = text.contains("我") && (text.contains("拍") || text.contains("发"))
        val chosen = when {
            aboutHers -> refs.filterNot { it.mine }.maxByOrNull { it.id } ?: refs.maxByOrNull { it.id }
            aboutMine -> refs.filter { it.mine }.maxByOrNull { it.id } ?: refs.maxByOrNull { it.id }
            else -> refs.maxByOrNull { it.id }
        }
        return chosen?.path
    }

    private companion object {
        /** 直接点名要图片；普通的「你在干嘛」不显示发图状态。 */
        val EXPLICIT_IMAGE_CUE = Regex(
            "(想看看(?:你|现在|此刻|.{0,6}(?:照片|图片|自拍))|给我看看|让我看看|看看你|" +
                "(?:发|拍|生成|画|来)(?:一?张|个)(?:照片|图片|图|自拍)|" +
                "(?:给我|帮我|请)(?:发|拍|生成|画)(?:照片|图片|图|自拍)|" +
                "(?:send|show|take|generate)(?: me)? (?:a |your )?(?:photo|pic|picture|image|selfie)\\b)",
            RegexOption.IGNORE_CASE,
        )

        val DECLINE_IMAGE_CUE = Regex(
            "(?:别|不要|不用|不必)\\s*(?:再|给我|帮我|先|现在|主动|\\s)*(?:发|拍|生成|画|分享)" +
                ".{0,8}(?:图|照片|自拍)|" +
                "\\b(?:don't|do not|no need to)\\s+(?:send|show|take|generate)" +
                ".{0,18}\\b(?:photo|pic|picture|image|selfie)\\b",
            RegexOption.IGNORE_CASE,
        )

        /** 「昨天那张 / 上次发的照片 / 你拍的那张」这类明确指向旧图的话。 */
        val RECALL_CUE = Regex(
            "(昨天|上次|那天|之前|刚才|还记得|发过|拍过).{0,10}(图|照片|拍的|发的|自拍|那张|这张)|(那张|这张)(图|照片)",
        )

        /** 明确想看「此时此刻」时才会生成或借用图库；中英文都认。 */
        val WANT_CUE = Regex(
            "(想看看|给我看看|让我看看|看看你|发张|发个图|发一张|拍张|拍一张|自拍|长什么样|" +
                "你在干嘛|你在做什么|你在忙什么|在忙什么|你那边|你那儿|现在在做什么|喝的什么|吃的什么|" +
                "show me|let me see|selfie|send (me )?(a )?(photo|pic|picture)|what are you (doing|drinking|eating))",
            RegexOption.IGNORE_CASE,
        )

        /** 自拍请求：要她本人出镜。 */
        val SELFIE_CUE = Regex(
            "(自拍|长什么样|你的样子|你现在的样子|看看你|你在干嘛|你在做什么|你在忙什么|" +
                "selfie|show me you|what are you doing)",
            RegexOption.IGNORE_CASE,
        )

        /** 句子里出现这些物件时，更像「拍拍你眼前的场景」，不按自拍处理。 */
        val SCENE_OBJECT = Regex(
            "(咖啡|茶|喝的|在喝|喝什么|吃的|在吃|吃什么|窗|街|外面|猫|狗|书|桌面|电脑|花|天空|路上|厨房|" +
                "coffee|tea|drink|eating|food)",
            RegexOption.IGNORE_CASE,
        )
    }
}
