package dev.lily.app.relationship

import android.util.Log
import dev.lily.api.ChatMessage
import dev.lily.api.ChatRequest
import dev.lily.api.ModelRole
import dev.lily.app.settings.AppSettings
import dev.lily.app.session.AgentSession
import dev.lily.app.settings.RelationSettings
import dev.lily.llm.LlmClientFactory
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
    private val settings: () -> AppSettings,
    private val session: AgentSession,
    private val notify: (String) -> Unit,
    private val llm: LlmClientFactory,
    private val scope: CoroutineScope,
    private val uiVisible: StateFlow<Boolean>,
    /** 昵称为空时用的回退名（当前应用名），避免主动消息里写死旧默认名。 */
    private val appName: String,
    /** 每次检查的结论落在这里（`<files>/proactive/last-decision.json`），logcat 被系统日志淹没时也能事后看。 */
    private val decisionFile: File? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
    /** 主动开口时顺带发一张图；null 表示这台设备发不了。 */
    private val sendImage: (suspend (description: String, selfie: Boolean?) -> String)? = null,
    /** 主动开口时顺带发一条动态；null 表示这台设备发不了。 */
    private val postDynamic: (suspend (text: String) -> String)? = null,
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
        val current = settings()
        val config = current.relation
        val outcome = ProactiveDecision.evaluate(now, relationship, config, session.busy.value)
        val diagnosis = outcome.diagnosis
        val trigger = outcome.trigger ?: run {
            record(diagnosis, "no-send:${diagnosis.blocker ?: "no-trigger"}")
            return
        }
        val text = compose(trigger, relationship, current)
        ProactiveGuard.check(text)?.let { rule ->
            record(diagnosis, "rejected:$rule", text)
            return
        }
        // A task may have started while the model was composing; do not cut into it.
        if (session.busy.value) {
            record(diagnosis, "aborted:busy", text)
            return
        }
        if (!settings().relation.proactive) {
            record(diagnosis, "aborted:disabled")
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
        if (notified) notify(text)
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

    private suspend fun compose(trigger: ProactiveTrigger, relationship: Relationship, current: AppSettings): String {
        val config = current.relation
        val fallback = RelationshipPrompt.fallback(trigger, config)
        val chat = current.chat
        if (!chat.configured) return fallback
        return try {
            val client = llm.create(chat.toModelConfig(ModelRole.CHAT))
            val prompt = RelationshipPrompt.opening(relationship, config, trigger, name = current.persona.nameOr(appName))
            val response = withTimeout(COMPOSE_TIMEOUT_MS) {
                client.complete(
                    ChatRequest(
                        listOf(ChatMessage.system(prompt)),
                        tools = companionTools(),
                    ),
                )
            }
            // 她可能在这一轮里顺带发一张图或一条动态：执行掉，再让她补一句嘴上说的话。
            val said = if (response.toolCalls.isEmpty()) {
                response.text
            } else {
                runCompanionTools(response.toolCalls)
                val follow = withTimeout(COMPOSE_TIMEOUT_MS) {
                    client.complete(
                        ChatRequest(
                            listOf(
                                ChatMessage.system(prompt),
                                ChatMessage.assistant(response.text.ifBlank { "（已经发出去了）" }),
                            ),
                            tools = companionTools(),
                        ),
                    )
                }
                follow.text
            }
            said.trim().takeIf { ProactiveGuard.check(it) == null } ?: fallback
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

    /** 主动开口时她能用的「身体」：发图与发动态。设备给不了就不给。 */
    private fun companionTools(): List<dev.lily.api.ToolSpec> = buildList {
        if (sendImage != null) {
            add(
                dev.lily.api.ToolSpec(
                    dev.lily.agent.AgentTools.SEND_IMAGE,
                    "生成并发出一张图，让用户看到你此刻的样子或你眼前的东西。",
                    dev.lily.agent.AgentTools.specs
                        .first { it.name == dev.lily.agent.AgentTools.SEND_IMAGE }.parameters,
                ),
            )
        }
        if (postDynamic != null) {
            add(
                dev.lily.api.ToolSpec(
                    dev.lily.agent.AgentTools.POST_DYNAMIC,
                    "发一条动态到「动态」页，写你今天做了什么、看到了什么、心情如何。",
                    dev.lily.agent.AgentTools.specs
                        .first { it.name == dev.lily.agent.AgentTools.POST_DYNAMIC }.parameters,
                ),
            )
        }
    }

    /** 执行她主动开口时点的工具；失败只记日志，不影响这一句话本身。 */
    private suspend fun runCompanionTools(calls: List<dev.lily.api.ToolCall>) {
        for (call in calls) {
            try {
                when (call.name) {
                    dev.lily.agent.AgentTools.SEND_IMAGE -> {
                        val args = parseArgs(call.argumentsJson)
                        val description = args["description"]?.toString()?.trim('"', ' ')?.trim().orEmpty()
                        val selfie = when (args["selfie"]?.toString()?.trim('"')?.lowercase()) {
                            "true" -> true
                            "false" -> false
                            else -> null
                        }
                        sendImage?.invoke(description, selfie)?.let { log("proactive: send_image -> $it") }
                    }
                    dev.lily.agent.AgentTools.POST_DYNAMIC -> {
                        val args = parseArgs(call.argumentsJson)
                        val text = args["text"]?.toString()?.trim('"', ' ')?.trim().orEmpty()
                        if (text.isNotEmpty()) {
                            postDynamic?.invoke(text)?.let { log("proactive: post_dynamic -> $it") }
                        }
                    }
                    else -> Unit
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("proactive: tool ${call.name} failed (${e.javaClass.simpleName})")
            }
        }
    }

    private fun parseArgs(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return runCatching {
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(raw) as? kotlinx.serialization.json.JsonObject
                ?: return emptyMap()
            obj.mapValues { (_, v) ->
                (v as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
            }
        }.getOrDefault(emptyMap())
    }

    companion object {
        private const val TAG = "Lily"
        private const val COMPOSE_TIMEOUT_MS = 8_000L
    }
}
