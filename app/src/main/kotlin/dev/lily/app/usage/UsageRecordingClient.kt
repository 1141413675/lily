package dev.lily.app.usage

import dev.lily.api.ChatRequest
import dev.lily.api.ChatResponse
import dev.lily.api.LlmClient
import dev.lily.api.ModelConfig

/**
 * 记用量的模型客户端。
 *
 * 包在真正的客户端外面：每次调用完，如果响应带了 `usage` 就记一笔。
 * 包在这里而不是改各个客户端，是因为所有客户端都从这里创建——
 * 聊天、操作、发动态、做梦、主动开口，一处不漏。
 *
 * 记账失败绝不能影响对话，所以这里不抛异常。
 */
class UsageRecordingClient(
    private val inner: LlmClient,
    private val record: (model: String, inputTokens: Int, outputTokens: Int) -> Unit,
) : LlmClient {

    override val config: ModelConfig get() = inner.config

    override suspend fun complete(request: ChatRequest): ChatResponse {
        val response = inner.complete(request)
        runCatching {
            response.usage?.let { record(config.model, it.promptTokens, it.completionTokens) }
        }
        return response
    }
}
