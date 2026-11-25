package dev.xiaoman.app.safety

import android.content.SharedPreferences
import dev.xiaoman.agent.ContentSafety
import dev.xiaoman.agent.RefusalSink
import org.json.JSONObject
import java.io.File

/**
 * Local, append-only ledger for the refusal fallback. Nothing leaves the phone.
 *
 * Records: one line per refusal (supplier, model, expression scale, coarse topic, time, whether a
 * retry was allowed or the hourly cap was hit), plus the retry outcome. The check count lives in
 * SharedPreferences so the refusal rate is honest without writing a line per normal reply.
 *
 * Deliberately narrow: this is not a jailbreak list, never rewrites content to pass a provider's
 * filter, and never tries to bypass provider policy. It only decides whether one in-character
 * retry is worth making, or whether to answer with a softer line.
 */
class RefusalStore(
    private val prefs: SharedPreferences,
    private val file: File,
    private val info: () -> Info,
    private val clock: () -> Long = System::currentTimeMillis,
) : RefusalSink {

    data class Info(val provider: String, val model: String, val style: String)

    data class Entry(
        val at: Long,
        val provider: String,
        val model: String,
        val style: String,
        val topic: String,
        val kind: String,
        val outcome: String?,
    )

    data class Stats(val checks: Long, val refusals: Long, val retriesLastHour: Int) {
        val rate: Double get() = if (checks <= 0) 0.0 else refusals.toDouble() / checks
    }

    private val retryTimes = ArrayDeque<Long>()

    override fun observed() {
        prefs.edit().putLong(CHECKS, prefs.getLong(CHECKS, 0) + 1).apply()
    }

    /** Returns true when one retry is allowed (under [MAX_RETRIES_PER_HOUR]). */
    override fun refused(category: ContentSafety.Category?, text: String): Boolean {
        val now = clock()
        pruneRetries(now)
        val allowed = retryTimes.size < MAX_RETRIES_PER_HOUR
        if (allowed) retryTimes.addLast(now)
        prefs.edit().putLong(REFUSALS, prefs.getLong(REFUSALS, 0) + 1).apply()
        append(Entry(now, info().provider, info().model, info().style, category?.label ?: topicOf(text), "refusal",
            if (allowed) "retry" else "hourly-cap"))
        return allowed
    }

    override fun retried(succeeded: Boolean) {
        val i = info()
        append(Entry(clock(), i.provider, i.model, i.style, "retry", "retry", if (succeeded) "ok" else "soft"))
    }

    fun stats(): Stats {
        pruneRetries(clock())
        return Stats(prefs.getLong(CHECKS, 0), prefs.getLong(REFUSALS, 0), retryTimes.size)
    }

    fun recent(limit: Int = 5): List<Entry> {
        if (!file.exists()) return emptyList()
        return runCatching {
            file.readLines().asSequence()
                .mapNotNull(::parse)
                .toList()
                .takeLast(limit)
                .reversed()
        }.getOrDefault(emptyList())
    }

    fun clear() {
        file.delete()
        prefs.edit().clear().apply()
        retryTimes.clear()
    }

    private fun pruneRetries(now: Long) {
        while (retryTimes.firstOrNull()?.let { now - it > HOUR } == true) retryTimes.removeFirst()
    }

    private fun append(entry: Entry) {
        runCatching {
            file.parentFile?.mkdirs()
            file.appendText(
                JSONObject()
                    .put("at", entry.at)
                    .put("provider", entry.provider)
                    .put("model", entry.model)
                    .put("style", entry.style)
                    .put("topic", entry.topic)
                    .put("kind", entry.kind)
                    .put("outcome", entry.outcome)
                    .toString() + "\n",
            )
        }
    }

    private fun parse(line: String): Entry? = runCatching {
        val o = JSONObject(line)
        Entry(
            at = o.optLong("at"),
            provider = o.optString("provider"),
            model = o.optString("model"),
            style = o.optString("style"),
            topic = o.optString("topic"),
            kind = o.optString("kind"),
            outcome = o.optString("outcome").takeIf { it.isNotBlank() },
        )
    }.getOrNull()

    /** Coarse topic for the ledger; never stores the refused text itself. */
    private fun topicOf(text: String): String = when {
        Regex("爱|喜欢|想你|难过|emo|孤独|分手").containsMatchIn(text) -> "情感"
        Regex("工作|加班|同事|老板|离职|面试").containsMatchIn(text) -> "工作"
        Regex("考试|作业|学习|论文|学校").containsMatchIn(text) -> "学习"
        Regex("吃|睡|天气|游戏|剧|音乐").containsMatchIn(text) -> "生活"
        else -> "其它"
    }

    companion object {
        const val MAX_RETRIES_PER_HOUR = 6
        private const val HOUR = 60 * 60 * 1000L
        private const val CHECKS = "checks"
        private const val REFUSALS = "refusals"
    }
}
