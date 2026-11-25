package dev.xiaoman.app.relationship

import android.util.Log
import dev.xiaoman.api.ChatMessage
import dev.xiaoman.api.ChatRequest
import dev.xiaoman.api.ModelRole
import dev.xiaoman.app.Notifier
import dev.xiaoman.app.session.AgentSession
import dev.xiaoman.app.settings.RelationSettings
import dev.xiaoman.app.settings.SettingsStore
import dev.xiaoman.llm.LlmClientFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 主动开口：按 [ProactiveDecision] 判断要不要说，用一次模型调用生成一句（失败用真实档案的模板兜底），
 * 通过 [AgentSession] 进聊天列表，前台不可见时复用提醒通知渠道。
 *
 * One attempt per check, at most [RelationSettings.maxPerDay] sends a day; failures only log.
 */
class ProactiveEngine(
    private val store: RelationshipStore,
    private val settings: SettingsStore,
    private val session: AgentSession,
    private val notifier: Notifier,
    private val llm: LlmClientFactory,
    private val scope: CoroutineScope,
    private val uiVisible: StateFlow<Boolean>,
    /** 昵称为空时用的回退名（当前应用名），避免主动消息里写死旧默认名。 */
    private val appName: String,
    /** 每次检查的结论落在这里（`<files>/proactive/last-decision.json`），logcat 被系统日志淹没时也能事后看。 */
    private val decisionFile: File? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) {
    private val running = AtomicBoolean(false)

    /** Called by the periodic alarm and by the relationship screen's "现在检查一次". */
    fun check() {
        if (!running.compareAndSet(false, true)) return
        scope.launch {
            try {
                runOnce()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("proactive: skipped (${e.javaClass.simpleName})")
            } finally {
                running.set(false)
            }
        }
    }

    private suspend fun runOnce() {
        val now = clock()
        val relationship = store.load()
        val config = settings.current.relation
        val outcome = ProactiveDecision.evaluate(now, relationship, config, session.busy.value)
        val diagnosis = outcome.diagnosis
        val trigger = outcome.trigger ?: run {
            record(diagnosis, "no-send:${diagnosis.blocker ?: "no-trigger"}")
            return
        }
        val text = compose(trigger, relationship, config)
        ProactiveGuard.check(text)?.let { rule ->
            record(diagnosis, "rejected:$rule", text)
            return
        }
        // A task may have started while the model was composing; do not cut into it.
        if (session.busy.value) {
            record(diagnosis, "aborted:busy", text)
            return
        }
        store.markProactive(
            now = now,
            topic = (trigger as? ProactiveTrigger.FollowUp)?.topic,
            habit = (trigger as? ProactiveTrigger.HabitMiss)?.habit,
            clearGreeting = trigger is ProactiveTrigger.Greeting,
        )
        session.postAssistant(text)
        val notified = !uiVisible.value
        if (notified) notifier.showProactive(text)
        record(diagnosis, if (notified) "sent+notified" else "sent", text)
    }

    /** 一行固定前缀的日志 + 同内容落盘；两条通道都失败也不影响主动开口本身。 */
    private fun record(diagnosis: ProactiveDiagnosis, conclusion: String, text: String? = null) {
        val json = buildString {
            append('{')
            append("\"now\":").append(diagnosis.now)
            append(",\"localTime\":\"").append(diagnosis.localTime).append('"')
            append(",\"proactiveEnabled\":").append(diagnosis.proactiveEnabled)
            append(",\"busy\":").append(diagnosis.busy)
            append(",\"quietHours\":").append(diagnosis.quietHours)
            append(",\"quietStart\":\"").append(diagnosis.quietStart).append('"')
            append(",\"quietEnd\":\"").append(diagnosis.quietEnd).append('"')
            append(",\"dailyCapReached\":").append(diagnosis.dailyCapReached)
            append(",\"proactiveCount\":").append(diagnosis.proactiveCount)
            append(",\"maxPerDay\":").append(diagnosis.maxPerDay)
            append(",\"gapBlocked\":").append(diagnosis.gapBlocked)
            append(",\"gapRemainingMs\":").append(diagnosis.gapRemainingMs)
            append(",\"minGapHours\":").append(diagnosis.minGapHours)
            append(",\"lastProactiveAt\":").append(diagnosis.lastProactiveAt)
            append(",\"lastContactAt\":").append(diagnosis.lastContactAt)
            append(",\"hoursSinceContact\":").append(diagnosis.hoursSinceContact ?: "null")
            append(",\"absenceHours\":").append(diagnosis.absenceHours)
            append(",\"absenceEligible\":").append(diagnosis.absenceEligible)
            append(",\"pendingGreetingReady\":").append(diagnosis.pendingGreetingReady)
            append(",\"openTopicCandidates\":").append(diagnosis.openTopicCandidates)
            append(",\"habitCandidates\":").append(diagnosis.habitCandidates)
            append(",\"blocker\":").append(diagnosis.blocker?.let { "\"$it\"" } ?: "null")
            append(",\"trigger\":").append(diagnosis.trigger?.let { "\"${it.escape()}\"" } ?: "null")
            append(",\"conclusion\":\"").append(conclusion).append('"')
            if (text != null) append(",\"text\":\"").append(text.escape()).append('"')
            append('}')
        }
        log("PROACTIVE_DECISION $json")
        val file = decisionFile ?: return
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json)
        }
    }

    private fun String.escape(): String =
        replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")

    private suspend fun compose(trigger: ProactiveTrigger, relationship: Relationship, config: RelationSettings): String {
        val fallback = RelationshipPrompt.fallback(trigger, config)
        val chat = settings.current.chat
        if (!chat.configured) return fallback
        return try {
            val client = llm.create(chat.toModelConfig(ModelRole.CHAT))
            val response = withTimeout(COMPOSE_TIMEOUT_MS) {
                client.complete(ChatRequest(listOf(ChatMessage.system(RelationshipPrompt.opening(relationship, config, trigger, name = settings.current.persona.nameOr(appName))))))
            }
            response.text.trim().takeIf { ProactiveGuard.check(it) == null } ?: fallback
        } catch (e: TimeoutCancellationException) {
            log("proactive: compose timed out")
            fallback
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("proactive: compose failed (${e.javaClass.simpleName})")
            fallback
        }
    }

    companion object {
        private const val TAG = "Xiaoman"
        private const val COMPOSE_TIMEOUT_MS = 8_000L
    }
}
