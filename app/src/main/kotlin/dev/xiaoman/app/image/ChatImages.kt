package dev.xiaoman.app.image

import dev.xiaoman.app.media.SceneryStore
import dev.xiaoman.app.session.ChatItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 她主动发图：在回复之后按语境挑来源，失败静默降级，不弹错误、不发明理由。
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
    private val settings: () -> dev.xiaoman.app.settings.AppSettings,
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
    private val running = AtomicBoolean(false)

    fun afterReply(userText: String, replyText: String) {
        if (userText.isBlank() && replyText.isBlank()) return
        if (!settings().image.enabled) return
        scope.launch(Dispatchers.IO) {
            if (!running.compareAndSet(false, true)) return@launch
            try {
                share(userText, replyText)
            } catch (e: Exception) {
                // 发图是锦上添花：任何失败都不能影响聊天。
            } finally {
                running.set(false)
            }
        }
    }

    /** 直接跑一次来源选择；[afterReply] 在 IO 线程调用它，测试也用它避免等调度。 */
    internal suspend fun share(userText: String, replyText: String) {
        val image = settings().image
        if (!image.enabled) return
        val context = "$userText\n$replyText"

        // 1. 回忆过去：只引用聊天里出现过的原图，不重新生成、不下载。
        if (RECALL_CUE.containsMatchIn(userText)) {
            pickReuse(userText)?.let { path ->
                store.markShared(path)
                post(path)
                return
            }
        }

        // 2. 此刻想看：按这轮对话的细节生成，不随机挑生活场景。
        val wantsToSee = WANT_CUE.containsMatchIn(userText)
        if (wantsToSee) {
            if (store.canGenerate(image.dailyLimit)) {
                val portrait = portraitFile()
                val selfie = image.includeSelfie && portrait != null &&
                    SELFIE_CUE.containsMatchIn(userText) && !SCENE_OBJECT.containsMatchIn(userText)
                val prompt = promptFor(selfie, context)
                val bytes = if (prompt.isBlank()) null else generate?.invoke(prompt, if (selfie) portrait else null)
                if (bytes != null && bytes.isNotEmpty()) {
                    val saved = store.save(bytes)
                    if (saved != null) {
                        store.markGenerated()
                        store.markShared(saved.absolutePath)
                        post(saved.absolutePath)
                        return
                    }
                }
            }
            // 3. 图库兜底：无 Key、额度用尽或生成失败才走到这里；库空则静默结束。
            scenery.maybePick(context, now())?.let { picked ->
                store.markShared(picked.file)
                post(picked.file)
            }
        }
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
