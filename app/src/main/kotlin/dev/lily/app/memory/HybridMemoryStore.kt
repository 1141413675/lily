package dev.lily.app.memory

import dev.lily.api.MemoryStore
import dev.lily.api.SearchHit
import dev.lily.app.settings.MemoryEmbeddingSettings
import dev.lily.memory.PersistentVectorIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.security.MessageDigest

internal data class VectorIndexProgress(
    val indexed: Int = 0,
    val total: Int = 0,
    val running: Boolean = false,
    val unavailable: Boolean = false,
)

/** Markdown owns the memory; a role's local vector index can always be rebuilt from it. */
internal class HybridMemoryStore(
    private val notes: MemoryStore,
    private val index: PersistentVectorIndex,
    private val settings: () -> MemoryEmbeddingSettings,
    private val scope: CoroutineScope,
    private val embed: suspend (MemoryEmbeddingSettings, List<String>) -> List<FloatArray>,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) : MemoryStore by notes {
    private val _progress = MutableStateFlow(VectorIndexProgress())
    val progress = _progress.asStateFlow()
    private var selected: MemoryEmbeddingSettings? = null
    private var generation = 0L
    private var refresh: Job? = null
    private var nextRefreshAt: Long? = null

    /** A first lookup remains usable while the documents are embedded off the chat path. */
    @Synchronized
    fun prepare() {
        val config = settings()
        if (selected != config) {
            refresh?.cancel()
            refresh = null
            nextRefreshAt = null
            selected = config
            generation += 1
            _progress.value = VectorIndexProgress()
        }
        if (!config.active || refresh?.isActive == true) return
        if (nextRefreshAt?.let { clock() < it } == true) return

        _progress.value = _progress.value.copy(running = true)
        val key = identity(config)
        val pass = generation
        refresh = scope.launch(Dispatchers.IO) {
            var completed = false
            try {
                completed = withTimeoutOrNull(INDEX_BUDGET_MS) {
                    var shouldContinue: Boolean
                    var allIndexed: Boolean
                    do {
                        val result = index.refresh(notes, key) { documents -> request(config, documents) }
                        updateProgress(pass, VectorIndexProgress(result.indexed, result.total, running = true))
                        allIndexed = result.indexed >= result.total
                        shouldContinue = !allIndexed && result.newlyIndexed > 0
                    } while (shouldContinue)
                    allIndexed
                } == true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The next eligible lookup can retry without holding up the current reply.
            } finally {
                finishRefresh(pass, completed)
            }
        }
    }

    override suspend fun search(query: String, limit: Int): List<SearchHit> {
        if (limit <= 0) return emptyList()
        val text = query.trim()
        val config = settings()
        if (!config.active || text.length < 2) return notes.search(query, limit)

        prepare()
        val pool = maxOf(limit, MATCH_POOL)
        val lexical = notes.search(query, pool)
        val semantic = try {
            withTimeoutOrNull(QUERY_BUDGET_MS) {
                index.search(text.take(MAX_QUERY_CHARS), notes, identity(config), { request(config, it) }, pool)
            }.orEmpty().filter { it.score >= MIN_SIMILARITY }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }

        if (settings() != config || semantic.isEmpty()) return lexical.take(limit)
        return combine(lexical, semantic, limit)
    }

    private suspend fun request(config: MemoryEmbeddingSettings, texts: List<String>): List<FloatArray> {
        if (settings() != config || !config.active) throw IOException("Memory embedding configuration changed.")
        return embed(config, texts)
    }

    @Synchronized
    private fun updateProgress(pass: Long, progress: VectorIndexProgress) {
        if (generation == pass) _progress.value = progress
    }

    @Synchronized
    private fun finishRefresh(pass: Long, completed: Boolean) {
        if (generation != pass) return
        _progress.value = _progress.value.copy(running = false, unavailable = !completed)
        nextRefreshAt = clock() + if (completed) REFRESH_INTERVAL_MS else RETRY_INTERVAL_MS
        refresh = null
    }

    private fun identity(config: MemoryEmbeddingSettings): String =
        MessageDigest.getInstance("SHA-256")
            .digest("${config.requestUrl}\n${config.model.trim()}".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    // BM25 and cosine scores use different scales, so only their ranks are fused.
    private fun combine(lexical: List<SearchHit>, semantic: List<SearchHit>, limit: Int): List<SearchHit> {
        val hits = linkedMapOf<String, SearchHit>()
        val rankScores = mutableMapOf<String, Double>()
        for (source in listOf(lexical, semantic)) {
            source.forEachIndexed { rank, hit ->
                hits.putIfAbsent(hit.permalink, hit)
                rankScores.merge(hit.permalink, 1.0 / (RANK_OFFSET + rank), Double::plus)
            }
        }
        return hits.values.sortedByDescending { rankScores.getValue(it.permalink) }
            .take(limit).map { hit -> hit.copy(score = rankScores.getValue(hit.permalink)) }
    }

    private companion object {
        const val QUERY_BUDGET_MS = 2_500L
        const val INDEX_BUDGET_MS = 40_000L
        const val REFRESH_INTERVAL_MS = 5_000L
        const val RETRY_INTERVAL_MS = 30_000L
        const val MAX_QUERY_CHARS = 2_000
        const val MATCH_POOL = 12
        const val MIN_SIMILARITY = 0.40
        const val RANK_OFFSET = 60
    }
}
