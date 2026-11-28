package dev.lily.app.voice

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 通话里的一句往来；[audio] 只给用户侧保存的原始录音，可为空。 */
data class CallTurn(
    val at: Long,
    val fromMe: Boolean,
    val text: String,
    val audio: String? = null,
)

/**
 * 一通电话的完整明细，独立于聊天列表存储。
 * [incoming] 是呼叫方向：true 是她打来，false 是我拨出；旧文件没有这个字段时按我拨出读。
 */
data class CallLog(
    val startedAt: Long,
    val connectedAt: Long,
    val endedAt: Long,
    val outcome: String,
    val reason: String?,
    val turns: List<CallTurn>,
    val incoming: Boolean = false,
) {
    val durationMs: Long
        get() = if (outcome == dev.lily.app.session.CallOutcome.CONNECTED.name && connectedAt > 0) {
            (endedAt - connectedAt).coerceAtLeast(0)
        } else {
            0
        }
}

/**
 * 通话明细：每通电话一个 JSON，放在 `<files>/calls/<startedAt>.json`；
 * 用户侧的每句录音另存在 `<files>/calls/<startedAt>/u-<n>.<ext>`。
 * 聊天列表不再放通话条目，通话记录页直接读这里的 [list]。
 */
class CallLogStore(private val dir: File) {

    /** 把这一句的用户录音复制进通话目录；失败返回 null，不影响通话本身。 */
    fun saveAudio(startedAt: Long, index: Int, source: File): String? {
        if (startedAt <= 0 || !source.isFile) return null
        val callDir = File(dir, startedAt.toString())
        if (!callDir.exists() && !callDir.mkdirs()) return null
        val ext = source.extension.lowercase().ifBlank { "m4a" }
        val target = File(callDir, "u-$index.$ext")
        return try {
            source.copyTo(target, overwrite = true)
            target.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    fun save(log: CallLog) {
        if (log.startedAt <= 0) return
        if (!dir.exists() && !dir.mkdirs()) return
        val turns = JSONArray()
        log.turns.forEach { turn ->
            turns.put(
                JSONObject()
                    .put("at", turn.at)
                    .put("fromMe", turn.fromMe)
                    .put("text", turn.text)
                    .put("audio", turn.audio ?: JSONObject.NULL),
            )
        }
        val json = JSONObject()
            .put("startedAt", log.startedAt)
            .put("connectedAt", log.connectedAt)
            .put("endedAt", log.endedAt)
            .put("outcome", log.outcome)
            .put("reason", log.reason ?: JSONObject.NULL)
            .put("incoming", log.incoming)
            .put("turns", turns)
        runCatching { File(dir, "${log.startedAt}.json").writeText(json.toString()) }
    }

    /** 通话记录页用：按开始时间倒序读出全部明细；坏文件跳过，不影响其它记录。 */
    fun list(): List<CallLog> {
        val files = dir.listFiles { file -> file.isFile && file.extension == "json" } ?: return emptyList()
        return files.mapNotNull { file ->
            val startedAt = file.nameWithoutExtension.toLongOrNull() ?: return@mapNotNull null
            read(startedAt)
        }.sortedByDescending { it.startedAt }
    }

    fun read(startedAt: Long): CallLog? {
        val file = File(dir, "$startedAt.json")
        if (!file.isFile) return null
        return runCatching {
            val json = JSONObject(file.readText())
            val turns = json.optJSONArray("turns") ?: JSONArray()
            CallLog(
                startedAt = json.optLong("startedAt", startedAt),
                connectedAt = json.optLong("connectedAt", 0),
                endedAt = json.optLong("endedAt", 0),
                outcome = json.optString("outcome"),
                reason = json.optString("reason").takeIf { it.isNotBlank() && it != "null" },
                incoming = json.optBoolean("incoming", false),
                turns = buildList {
                    for (i in 0 until turns.length()) {
                        val t = turns.optJSONObject(i) ?: continue
                        add(
                            CallTurn(
                                at = t.optLong("at"),
                                fromMe = t.optBoolean("fromMe"),
                                text = t.optString("text"),
                                audio = t.optString("audio").takeIf { it.isNotBlank() && it != "null" },
                            ),
                        )
                    }
                },
            )
        }.getOrNull()
    }
}
