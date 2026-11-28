package dev.lily.app.image

import dev.lily.app.life.LifeSettings
import dev.lily.llm.LlmClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * 方舟图片生成：沿用聊天模型的地址与 Key，模型由设置里的 `image.model` 决定。
 * 任何错误（无 Key、超时、限流、返回格式不符）都返回 null，由 [ChatImages] 静默降级。
 */
class ImageGenerator(private val http: OkHttpClient = LlmClients.sharedHttp) {

    suspend fun generate(
        baseUrl: String,
        apiKey: String,
        model: String,
        prompt: String,
        reference: File?,
    ): ByteArray? = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank() || apiKey.isBlank() || prompt.isBlank()) return@withContext null
        val client = http.newBuilder()
            .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
        val url = baseUrl.trimEnd('/') + "/images/generations"
        try {
            // 优先直接要 base64；个别部署不支持 b64 时用 URL 再试一次。
            for (format in listOf("b64_json", "url")) {
                try {
                    return@withContext post(client, url, apiKey, model, prompt, reference, format)
                        ?.takeIf { it.isNotEmpty() }
                } catch (e: HttpResponseException) {
                    if (e.code != 400) break
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun post(
        client: OkHttpClient,
        url: String,
        apiKey: String,
        model: String,
        prompt: String,
        reference: File?,
        format: String,
    ): ByteArray? {
        val body = JSONObject().apply {
            put("model", model)
            put("prompt", prompt.take(MAX_PROMPT))
            // Seedream 5.x 官方示例同样是 2K 档；response_format 支持 url，b64_json 失败会自动重试。
            put("size", "2K")
            put("response_format", format)
            put("watermark", false)
            // 5.x 文档未提供组图参数，只在 4.x 及更早的 Seedream 上关闭组图，避免 5.x 因未知参数被拒。
            if (model.startsWith("doubao-seedream") && !model.contains("seedream-5")) {
                put("sequential_image_generation", "disabled")
            }
            reference?.takeIf { it.isFile }?.let { file ->
                val bytes = file.readBytes()
                val mime = if (bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()) {
                    "image/png"
                } else {
                    "image/jpeg"
                }
                put("image", "data:$mime;base64," + Base64.getEncoder().encodeToString(bytes))
            }
        }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody(JSON))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                // 只记状态码与响应体片段，便于分辨 Key / 模型名 / 参数问题；不含 Key。
                val snippet = runCatching { response.body?.string()?.take(300) }.getOrNull()
                android.util.Log.w("Lily", "image generation HTTP ${response.code}: $snippet")
                throw HttpResponseException(response.code)
            }
            val root = JSONObject(response.body?.string().orEmpty())
            val first = root.optJSONArray("data")?.optJSONObject(0) ?: return null
            val encoded = first.optString("b64_json")
            if (encoded.isNotBlank()) {
                return runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
            }
            val link = first.optString("url")
            if (link.isBlank()) return null
            val download = Request.Builder().url(link).get().build()
            client.newCall(download).execute().use { image ->
                if (!image.isSuccessful) return null
                return image.body?.bytes()
            }
        }
    }

    private class HttpResponseException(val code: Int) : Exception("HTTP $code")

    companion object {
        private const val TIMEOUT_SECONDS = 120L
        private const val MAX_PROMPT = 800
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * 把当前这轮对话、人设与生活设定拼成图片提示词；不写入任何 Key。
 * 提示词必须锚定对话里的具体细节（在做什么、手里拿什么、在哪、时间与光线），不能退化成随机生活场景。
 */
object ImagePrompt {
    fun build(
        selfie: Boolean,
        context: String,
        name: String,
        life: LifeSettings,
        at: java.time.LocalDateTime = java.time.LocalDateTime.now(),
    ): String {
        val hint = context.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .take(160)
        val details = listOf(life.city, life.doing, life.routine, life.pet, life.media)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("，")
        val scene = if (details.isBlank()) "她的日常生活里" else details
        val moment = momentOf(at.hour)
        val light = lightOf(at.hour)
        val anchor = "画面必须对应对话里提到的动作、饮品、物件与地点，不要把随机生活场景套进来。"
        return if (selfie) {
            "手机前置自拍，画面里是${name}本人；保持与参考图相同的五官、发型、年龄与肤色。" +
                "此刻是${moment}，${light}；${scene}。对话里刚发生的是：$hint。$anchor" +
                "真实手机照片，轻度手持感，不过度美颜，无文字、无水印。"
        } else {
            "第一人称视角的生活随手拍（${name}本人不出镜）：$hint。" +
                "此刻是${moment}，${light}；${scene}。$anchor" +
                "真实手机照片，生活化，自然光，无文字、无水印。"
        }.take(800)
    }

    private fun momentOf(hour: Int): String = when (hour) {
        in 5..7 -> "清晨"
        in 8..10 -> "上午"
        in 11..13 -> "中午"
        in 14..16 -> "下午"
        in 17..18 -> "傍晚"
        in 19..22 -> "晚上"
        else -> "深夜"
    }

    private fun lightOf(hour: Int): String = when (hour) {
        in 6..8 -> "柔和的晨光从侧面进来"
        in 9..15 -> "明亮的自然日光"
        in 16..18 -> "偏暖的低角度夕光"
        in 19..22 -> "室内暖灯与窗外夜色"
        else -> "只开着一盏小灯的室内"
    }
}
