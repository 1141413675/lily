package dev.lily.app.usage

import dev.lily.app.settings.ArkCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.Locale

/**
 * 用量记账：每次模型调用记一笔，按天、按模型分开存。
 *
 * 只存在本机（`<files>/usage.json`），不上传，也不含任何 Key。
 *
 * 为什么自己记：响应里本来就带着 `usage`（输入/输出 token 数），但从来没有人读它。
 * 自己记的好处是精确——只算这个 App 花了多少，不掺别的程序；而且所有厂商通用，
 * 不需要用户再多交一份凭证。
 *
 * 想看「整个账号」的口径，那是方舟控制台或官方 `GetInferenceUsage` 的事，
 * 本机这份只管自己。
 */
class UsageStore(
    private val file: File,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /** 某个模型在某一天的用量。 */
    data class Entry(
        val inputTokens: Long = 0,
        val outputTokens: Long = 0,
        val requests: Long = 0,
    ) {
        val totalTokens: Long get() = inputTokens + outputTokens
    }

    /** 一天，按模型拆开。 */
    data class Day(
        val date: String,
        val byModel: Map<String, Entry> = emptyMap(),
    ) {
        val inputTokens: Long get() = byModel.values.sumOf { it.inputTokens }
        val outputTokens: Long get() = byModel.values.sumOf { it.outputTokens }
        val requests: Long get() = byModel.values.sumOf { it.requests }

        /** 按用量排好的模型清单（多的在前）。 */
        fun ranked(): List<Pair<String, Entry>> = byModel.entries
            .sortedByDescending { it.value.totalTokens }
            .map { it.key to it.value }
    }

    private var days: MutableMap<String, Day> = linkedMapOf()

    /** 记一笔就动一下，界面照着它刷新。 */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    init {
        read()
    }

    /** 记一笔调用。模型名空着或 token 都是 0 时不记。 */
    @Synchronized
    fun record(model: String, inputTokens: Int, outputTokens: Int) {
        val name = model.trim()
        if (name.isEmpty()) return
        if (inputTokens <= 0 && outputTokens <= 0) return
        val date = todayKey()
        val day = days[date] ?: Day(date)
        val entry = day.byModel[name] ?: Entry()
        days[date] = day.copy(
            byModel = day.byModel + (name to entry.copy(
                inputTokens = entry.inputTokens + inputTokens,
                outputTokens = entry.outputTokens + outputTokens,
                requests = entry.requests + 1,
            )),
        )
        prune()
        persist()
        _revision.value++
    }

    /** 今天。 */
    @Synchronized
    fun today(): Day = days[todayKey()] ?: Day(todayKey())

    /**
     * 本月：返回该月每一天，最新的在前。
     *
     * 按本机时区算「本月」——用户看到的月份就是这个。
     */
    @Synchronized
    fun thisMonth(): List<Day> {
        val prefix = todayKey().take(7)
        return days.values.filter { it.date.startsWith(prefix) }.sortedByDescending { it.date }
    }

    /** 最近 N 天，最新的在前。 */
    @Synchronized
    fun recent(count: Int): List<Day> = this.days.values
        .sortedByDescending { it.date }
        .take(count)

    /** 某段时间的合计。 */
    fun totalOf(list: List<Day>): Entry = Entry(
        inputTokens = list.sumOf { it.inputTokens },
        outputTokens = list.sumOf { it.outputTokens },
        requests = list.sumOf { it.requests },
    )

    /** 清空全部记录。 */
    @Synchronized
    fun clear() {
        days = linkedMapOf()
        persist()
        _revision.value++
    }

    /** 只保留最近 [KEEP_DAYS] 天，免得文件越攒越大。 */
    private fun prune() {
        val keep = days.values.sortedByDescending { it.date }.take(KEEP_DAYS)
        days = keep.associateBy { it.date }.toMutableMap()
    }

    private fun todayKey(): String = dateOf(now())

    private fun read() {
        if (!file.isFile) return
        runCatching {
            val root = JSONObject(file.readText())
            val byDay = root.optJSONObject("days") ?: JSONObject()
            val parsed = linkedMapOf<String, Day>()
            for (date in byDay.keys()) {
                val models = byDay.optJSONObject(date) ?: continue
                val entries = linkedMapOf<String, Entry>()
                for (model in models.keys()) {
                    val e = models.optJSONObject(model) ?: continue
                    entries[model] = Entry(
                        inputTokens = e.optLong("in"),
                        outputTokens = e.optLong("out"),
                        requests = e.optLong("n"),
                    )
                }
                parsed[date] = Day(date, entries)
            }
            days = parsed
        }
    }

    private fun persist() {
        runCatching {
            file.parentFile?.takeIf { !it.exists() }?.mkdirs()
            val root = JSONObject()
            val byDay = JSONObject()
            days.values.forEach { day ->
                val models = JSONObject()
                day.byModel.forEach { (model, entry) ->
                    models.put(
                        model,
                        JSONObject()
                            .put("in", entry.inputTokens)
                            .put("out", entry.outputTokens)
                            .put("n", entry.requests),
                    )
                }
                byDay.put(day.date, models)
            }
            root.put("days", byDay)
            file.writeText(root.toString())
        }
    }

    companion object {
        /** 留 90 天，够看趋势，也不会让文件长到离谱。 */
        const val KEEP_DAYS = 90

        /** 本地时区的日期，`yyyy-MM-dd`。 */
        fun dateOf(millis: Long): String {
            val cal = Calendar.getInstance()
            cal.timeInMillis = millis
            return String.format(
                Locale.US,
                "%04d-%02d-%02d",
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH),
            )
        }
    }
}

/**
 * 按当前价格表估算花费。
 *
 * 价格表里有这个模型才算得出来；算不出来的返回 null——宁可说「不知道」，
 * 也不要给一个看着像那么回事的错数字。
 */
fun estimateCost(byModel: Map<String, UsageStore.Entry>): Double? {
    var total = 0.0
    var priced = false
    byModel.forEach { (model, entry) ->
        val price = ArkCatalog.byId(model) ?: return@forEach
        val input = price.inputPerMTok
        val output = price.outputPerMTok
        if (input == null && output == null) return@forEach
        total += (input ?: 0.0) * entry.inputTokens / 1_000_000.0
        total += (output ?: 0.0) * entry.outputTokens / 1_000_000.0
        priced = true
    }
    return if (priced) total else null
}

/** 某个模型一段时间的花费。 */
fun estimateCost(model: String, entry: UsageStore.Entry): Double? {
    val price = ArkCatalog.byId(model) ?: return null
    val input = price.inputPerMTok
    val output = price.outputPerMTok
    if (input == null && output == null) return null
    return (input ?: 0.0) * entry.inputTokens / 1_000_000.0 +
        (output ?: 0.0) * entry.outputTokens / 1_000_000.0
}
