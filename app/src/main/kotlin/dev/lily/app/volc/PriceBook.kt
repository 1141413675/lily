package dev.lily.app.volc

import dev.lily.app.settings.ArkCatalog
import dev.lily.app.settings.ArkModel
import dev.lily.app.settings.ArkModality
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 在线拿到的官方价格。
 *
 * 方舟的定价接口返回的是「当前账号对应的价格」——有折扣的企业用户拿到的就是折扣价，
 * 比公开的价目表更准。
 */
data class OfficialPrice(
    /** 元 / 百万 tokens。 */
    val inputPerMTok: Double? = null,
    /** 元 / 百万 tokens。 */
    val outputPerMTok: Double? = null,
    /** 元 / 张。 */
    val perImage: Double? = null,
    /** 计价档位的说明，例如「输入 ≤32K 档」。 */
    val note: String = "",
) {
    val isEmpty: Boolean get() = inputPerMTok == null && outputPerMTok == null && perImage == null
}

/**
 * 价格表：内置快照 + 在线覆盖。
 *
 * 内置快照来自官方文档（2026-11-28），永远在，作为兜底；
 * 在线价格拉到后覆盖同名模型。
 *
 * 覆盖是进程内的内存状态：它只是「当前这一版价格」，
 * 拉不到就退回快照，不会让界面空着。
 */
object PriceBook {

    private val overlay = ConcurrentHashMap<String, OfficialPrice>()

    /** 覆盖某个模型的价格。 */
    fun applyOfficial(prices: Map<String, OfficialPrice>) {
        overlay.clear()
        overlay.putAll(prices)
    }

    /** 清掉在线价格，回到内置快照。 */
    fun clearOfficial() {
        overlay.clear()
    }

    /** 当前是不是用的在线价格。 */
    val usingOfficial: Boolean get() = overlay.isNotEmpty()

    /** 覆盖了几个模型。 */
    val officialCount: Int get() = overlay.size

    /** 在线价格是哪天拉的；没拉过就是 null。 */
    @Volatile
    var fetchedAt: Long? = null
        private set

    /**
     * 记下在线价格并落到本机。
     *
     * 在线价格是「当前账号对应的价格」，重启后还用它比退回内置快照更准。
     * 存到 `<files>/ark-prices.json`，只在本机。
     */
    fun applyOfficial(prices: Map<String, OfficialPrice>, file: File? = null, now: Long = System.currentTimeMillis()) {
        overlay.clear()
        overlay.putAll(prices)
        fetchedAt = now
        if (file != null) persist(file)
    }

    /** 从本机读回上次拉到的在线价格；没有就保持内置快照。 */
    fun loadOfficial(file: File, now: Long = System.currentTimeMillis()) {
        if (!file.isFile) return
        runCatching {
            val root = JSONObject(file.readText())
            val prices = LinkedHashMap<String, OfficialPrice>()
            val models = root.optJSONObject("prices") ?: JSONObject()
            for (model in models.keys()) {
                val o = models.optJSONObject(model) ?: continue
                prices[model] = OfficialPrice(
                    inputPerMTok = if (o.has("in")) o.optDouble("in") else null,
                    outputPerMTok = if (o.has("out")) o.optDouble("out") else null,
                    perImage = if (o.has("img")) o.optDouble("img") else null,
                    note = o.optString("note"),
                )
            }
            if (prices.isNotEmpty()) {
                overlay.clear()
                overlay.putAll(prices)
                fetchedAt = root.optLong("fetchedAt", now)
            }
        }
    }

    /** 清掉在线价格，回到内置快照。 */
    fun reset(file: File? = null) {
        clearOfficial()
        fetchedAt = null
        file?.let { runCatching { it.delete() } }
    }

    private fun persist(file: File) {
        runCatching {
            file.parentFile?.takeIf { !it.exists() }?.mkdirs()
            val models = JSONObject()
            overlay.forEach { (model, price) ->
                models.put(
                    model,
                    JSONObject().apply {
                        price.inputPerMTok?.let { put("in", it) }
                        price.outputPerMTok?.let { put("out", it) }
                        price.perImage?.let { put("img", it) }
                        put("note", price.note)
                    },
                )
            }
            file.writeText(JSONObject().put("fetchedAt", fetchedAt ?: 0L).put("prices", models).toString())
        }
    }

    /**
     * 某个模型当前的价格。
     *
     * 有在线价就用在线的；没有就退回内置快照；都没有就 null。
     */
    fun priceOf(modelId: String): ArkModel? {
        val base = ArkCatalog.byId(modelId) ?: return null
        val official = overlay[modelId] ?: return base
        return base.copy(
            inputPerMTok = official.inputPerMTok ?: base.inputPerMTok,
            outputPerMTok = official.outputPerMTok ?: base.outputPerMTok,
            perImage = official.perImage ?: base.perImage,
            note = official.note.ifBlank { base.note },
        )
    }

    /** 只有文本模型有「元/百万 tokens」这套价；图片模型按张。 */
    fun modalityOf(modelId: String): ArkModality? = ArkCatalog.byId(modelId)?.modality
}

/** 把官方价格响应解析成 [OfficialPrice]；单位不认识就返回 null，宁可不用也不给错数。 */
internal fun parseOfficialPrices(body: String): Map<String, OfficialPrice> {
    val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyMap()
    val result = root["Result"] as? JsonObject ?: return emptyMap()
    val list = result["ModelPrices"] as? JsonArray ?: return emptyMap()

    val out = mutableMapOf<String, OfficialPrice>()
    list.forEach { element ->
        val obj = element as? JsonObject ?: return@forEach
        val name = (obj["FoundationModelName"] as? JsonPrimitive)?.content ?: return@forEach
        val groups = obj["PriceGroups"] as? JsonArray ?: return@forEach
        val group = pickGroup(groups) ?: return@forEach
        val price = parsePrice(group) ?: return@forEach
        if (!price.isEmpty) out[name] = price
    }
    return out
}

/**
 * 挑一个价格分组。
 *
 * 同一个模型会有多个档位（输入 ≤32K、32K~128K…）。默认档不带 `window` 维度，
 * 优先用它；没有就取第一个。
 */
private fun pickGroup(groups: JsonArray): JsonObject? {
    val objs = groups.mapNotNull { it as? JsonObject }
    objs.firstOrNull { group ->
        val dims = group["Dimensions"] as? JsonArray ?: return@firstOrNull false
        dims.none { (it as? JsonObject)?.get("Name")?.jsonPrimitive?.content == "window" }
    }?.let { return it }
    return objs.firstOrNull()
}

private fun parsePrice(group: JsonObject): OfficialPrice? {
    val items = group["PriceItems"] as? JsonArray ?: return null
    var input: Double? = null
    var output: Double? = null
    var image: Double? = null
    items.forEach { element ->
        val item = element as? JsonObject ?: return@forEach
        val label = (item["Label"] as? JsonPrimitive)?.content ?: return@forEach
        val raw = (item["Price"] as? JsonPrimitive)?.doubleOrNull ?: return@forEach
        val unit = (item["PriceUnitCode"] as? JsonPrimitive)?.content ?: return@forEach
        when (label) {
            "Prompt" -> input = perMillionTokens(unit, raw)
            "Completion" -> output = perMillionTokens(unit, raw)
            "ToICompletion" -> image = perImage(unit, raw)
        }
    }
    if (input == null && output == null && image == null) return null
    val note = windowNote(group)
    return OfficialPrice(input, output, image, note)
}

/**
 * 把接口返回的单价换算成「元 / 百万 tokens」。
 *
 * 单位码不认识就返回 null——宁可不用在线价，也不能显示一个差了几百倍的数字。
 */
private fun perMillionTokens(unitCode: String, raw: Double): Double? {
    val code = unitCode.uppercase()
    val perMillion = when {
        code.endsWith("PER_MILLION_TOKEN") -> raw
        code.endsWith("PER_THOUSAND_TOKEN") -> raw * 1_000.0
        code.endsWith("PER_TOKEN") -> raw * 1_000_000.0
        else -> null
    }
    return perMillion
}

/** 元/张。单位码不认识就返回 null。 */
private fun perImage(unitCode: String, raw: Double): Double? {
    val code = unitCode.uppercase()
    return when {
        code.endsWith("PER_IMAGE") || code.endsWith("PER_PICTURE") -> raw
        else -> null
    }
}

/** 计价档位说明，例如「输入≤32K 档」。 */
private fun windowNote(group: JsonObject): String {
    val dims = group["Dimensions"] as? JsonArray ?: return ""
    val window = dims.mapNotNull { it as? JsonObject }
        .firstOrNull { it["Name"]?.jsonPrimitive?.content == "window" }
        ?.get("Value")?.jsonPrimitive?.content
        ?: return ""
    return "官方档位：$window"
}
