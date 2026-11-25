package dev.xiaoman.memory

import dev.xiaoman.api.MemoryStore
import dev.xiaoman.api.Note
import dev.xiaoman.api.SearchHit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.math.sqrt

/** The counts are indexed passages, including reused vectors, within the local cache capacity. */
data class VectorRefreshResult(val indexed: Int, val total: Int, val newlyIndexed: Int)

/**
 * Disposable embedding cache for one memory directory. Keep one instance and cache file per role.
 * Markdown is authoritative. The cache contains only fingerprints and normalized vectors.
 */
class PersistentVectorIndex(private val cacheFile: File) {
    private data class Snapshot(
        val fingerprint: String,
        val dimensions: Int,
        val vectors: Map<String, FloatArray>,
    )

    private data class Passage(val note: Note, val text: String, val body: String, val key: String)

    private val refreshing = Mutex()
    private val cacheLock = Mutex()
    private var snapshot: Snapshot? = null
    private var loaded = false
    private var dirty = false

    /**
     * Embeds up to [REFRESH_LIMIT] new or changed passages, preferring recent notes. Readers can
     * continue using existing vectors during network requests. Partial progress is persisted.
     */
    suspend fun refresh(
        memory: MemoryStore,
        embeddingIdentity: String,
        embed: suspend (List<String>) -> List<FloatArray>,
    ): VectorRefreshResult = withContext(Dispatchers.IO) {
        refreshing.withLock {
            val fingerprint = sha256(embeddingIdentity)
            val base = readSnapshot()
            val matching = base?.takeIf { it.fingerprint == fingerprint }
            val notes = memory.list()
            var dimensions = matching?.dimensions ?: 0
            val corpus = passages(notes).take(capacity(dimensions)).toList()
            val retained = corpus.mapNotNull { passage -> matching?.vectors?.get(passage.key)?.let { passage.key to it } }
                .toMap(LinkedHashMap())
            val added = mutableSetOf<String>()

            for (batch in corpus.filter { it.key !in retained }.take(REFRESH_LIMIT).chunked(BATCH_SIZE)) {
                val vectors = requestVectors(batch.map { it.text }, embed) ?: break
                val incomingDimensions = vectors.first().size
                if (dimensions != 0 && incomingDimensions != dimensions) {
                    if (added.isNotEmpty()) break
                    retained.clear()
                }
                dimensions = incomingDimensions
                batch.zip(vectors).forEach { (passage, vector) ->
                    retained[passage.key] = vector
                    added += passage.key
                }
            }

            // Check again after slow requests so deletions and edits cannot reappear in the cache.
            val latest = memory.list()
            val current = passages(latest).take(capacity(dimensions)).toList()
            val vectors = current.mapNotNull { passage -> retained[passage.key]?.let { passage.key to it } }.toMap()
            val progress = VectorRefreshResult(vectors.size, current.size, vectors.keys.count { it in added })
            if (dimensions != 0 || current.isEmpty()) {
                val saved = publish(base, Snapshot(fingerprint, dimensions, vectors))
                if (!saved) return@withLock progress.copy(indexed = 0, newlyIndexed = 0)
            }
            progress
        }
    }

    /** Embeds only [query]. Index construction stays out of the conversation's search path. */
    suspend fun search(
        query: String,
        memory: MemoryStore,
        embeddingIdentity: String,
        embed: suspend (List<String>) -> List<FloatArray>,
        limit: Int,
    ): List<SearchHit> = withContext(Dispatchers.IO) {
        if (query.isBlank() || limit <= 0) return@withContext emptyList()
        val active = readSnapshot()?.takeIf {
            it.vectors.isNotEmpty() && it.fingerprint == sha256(embeddingIdentity)
        } ?: return@withContext emptyList()
        val before = readNotes(memory) ?: return@withContext emptyList()
        if (passages(before).none { it.key in active.vectors }) return@withContext emptyList()
        val queryVector = requestVectors(listOf(clipInput(query.trim(), QUERY_LENGTH)), embed)?.single()
            ?: return@withContext emptyList()
        if (queryVector.size != active.dimensions) {
            invalidate(active)
            return@withContext emptyList()
        }

        val notes = readNotes(memory) ?: return@withContext emptyList()
        val best = HashMap<String, Pair<Passage, Double>>()
        for (passage in passages(notes)) {
            val vector = active.vectors[passage.key] ?: continue
            val score = vector.indices.sumOf { i -> queryVector[i].toDouble() * vector[i] }.coerceIn(-1.0, 1.0)
            if (score > 0 && score > (best[passage.note.permalink]?.second ?: 0.0)) {
                best[passage.note.permalink] = passage to score
            }
        }
        best.values.sortedWith(compareByDescending<Pair<Passage, Double>> { it.second }
            .thenByDescending { it.first.note.updatedAtMillis }.thenBy { it.first.note.permalink })
            .take(limit)
            .map { (passage, score) ->
                SearchHit(passage.note.permalink, passage.note.title, Snippets.excerpt(passage.note.copy(body = passage.body)), score)
            }
    }

    private suspend fun readNotes(memory: MemoryStore): List<Note>? = try {
        memory.list()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun passages(notes: List<Note>): Sequence<Passage> = notes
        .sortedWith(compareByDescending<Note> { it.updatedAtMillis }.thenBy { it.permalink })
        .asSequence().flatMap { note ->
            val prefix = clipInput(listOf(note.title.trim(), note.tags.joinToString(", ")).filter { it.isNotBlank() }.joinToString("\n"), PREFIX_LENGTH)
            val body = note.body.trim().ifEmpty { note.observations.joinToString("\n", transform = BasicMemoryFormat::renderObservation) }
            val chunks = if (body.isBlank()) sequenceOf("") else splitBody(body)
            chunks.mapNotNull { chunk ->
                val text = listOf(prefix, chunk).filter { it.isNotBlank() }.joinToString("\n")
                if (text.isBlank()) null else Passage(note, text, chunk, sha256("${note.permalink}\u0000$text"))
            }
        }.distinctBy { it.key }

    private fun splitBody(body: String): Sequence<String> = sequence {
        var start = 0
        while (start < body.length) {
            var end = minOf(start + PASSAGE_LENGTH, body.length)
            if (end < body.length) {
                val newline = body.lastIndexOf('\n', end - 1)
                if (newline >= start + PASSAGE_LENGTH / 2) end = newline + 1
                if (body[end - 1].isHighSurrogate()) end--
            }
            yield(body.substring(start, end).trim())
            start = end
        }
    }.filter { it.isNotBlank() }

    private suspend fun requestVectors(
        texts: List<String>,
        embed: suspend (List<String>) -> List<FloatArray>,
    ): List<FloatArray>? {
        return try {
            val vectors = embed(texts)
            val dimensions = vectors.firstOrNull()?.size ?: 0
            if (vectors.size != texts.size || dimensions !in 1..MAX_DIMENSIONS || vectors.any { it.size != dimensions }) return null
            vectors.map { normalize(it) ?: return null }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun normalize(vector: FloatArray): FloatArray? {
        val squared = vector.sumOf { it.toDouble() * it }
        if (!squared.isFinite() || squared <= 0) return null
        val norm = sqrt(squared)
        return FloatArray(vector.size) { (vector[it] / norm).toFloat() }
    }

    private suspend fun readSnapshot(): Snapshot? = cacheLock.withLock {
        if (!loaded) {
            snapshot = readCacheFile()
            loaded = true
        }
        snapshot
    }

    private suspend fun invalidate(expected: Snapshot): Unit = cacheLock.withLock {
        if (snapshot === expected) saveSnapshot(expected.copy(dimensions = 0, vectors = emptyMap()))
    }

    private suspend fun publish(base: Snapshot?, updated: Snapshot): Boolean = cacheLock.withLock {
        // A concurrent query may already have found that the provider no longer uses these vectors.
        if (snapshot !== base && snapshot?.fingerprint == updated.fingerprint && updated.dimensions == base?.dimensions) {
            return@withLock false
        }
        if (dirty || snapshot != updated) saveSnapshot(updated)
        true
    }

    private fun saveSnapshot(updated: Snapshot) {
        snapshot = updated
        dirty = !writeCacheFile(updated)
    }

    private fun readCacheFile(): Snapshot? {
        return runCatching {
            if (!cacheFile.isFile || cacheFile.length() > MAX_CACHE_BYTES) return null
            DataInputStream(BufferedInputStream(cacheFile.inputStream())).use { input ->
                if (input.readInt() != FILE_MAGIC || input.readInt() != FILE_VERSION) return null
                val fingerprint = input.readUTF()
                val dimensions = input.readInt()
                val size = input.readInt()
                if (fingerprint.length != HASH_LENGTH || dimensions !in 0..MAX_DIMENSIONS ||
                    size !in 0..capacity(dimensions) || (dimensions == 0 && size != 0)
                ) return null
                val vectors = buildMap {
                    repeat(size) {
                        val key = input.readUTF()
                        if (key.length != HASH_LENGTH || key in this) return null
                        val vector = FloatArray(dimensions) { input.readFloat() }
                        val lengthSquared = vector.sumOf { it.toDouble() * it }
                        if (lengthSquared !in 0.99..1.01) return null
                        put(key, vector)
                    }
                }
                if (input.read() != -1) return null
                Snapshot(fingerprint, dimensions, vectors)
            }
        }.getOrNull()
    }

    private fun writeCacheFile(data: Snapshot): Boolean = runCatching {
        val dest = cacheFile.absoluteFile
        val folder = checkNotNull(dest.parentFile)
        check(folder.isDirectory || folder.mkdirs())
        val temp = File.createTempFile(".vector-", ".tmp", folder)
        try {
            FileOutputStream(temp).use { file ->
                val output = DataOutputStream(BufferedOutputStream(file))
                output.writeInt(FILE_MAGIC)
                output.writeInt(FILE_VERSION)
                output.writeUTF(data.fingerprint)
                output.writeInt(data.dimensions)
                output.writeInt(data.vectors.size)
                data.vectors.forEach { (key, vector) ->
                    output.writeUTF(key)
                    vector.forEach(output::writeFloat)
                }
                output.flush()
                file.fd.sync()
            }
            Files.move(temp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            temp.delete()
        }
    }.isSuccess

    private companion object {
        const val FILE_MAGIC = 0x4C564958
        const val FILE_VERSION = 1
        const val MAX_DIMENSIONS = 8192
        const val HASH_LENGTH = 64
        const val MAX_CACHE_BYTES = 32L * 1024 * 1024
        const val MAX_PASSAGES = 8_000
        const val REFRESH_LIMIT = 32
        const val BATCH_SIZE = 8
        const val PREFIX_LENGTH = 320
        const val PASSAGE_LENGTH = 800
        const val QUERY_LENGTH = 1_200

        fun capacity(dimensions: Int): Int =
            minOf(MAX_PASSAGES, ((MAX_CACHE_BYTES - 128) / (HASH_LENGTH + 2 + dimensions * Float.SIZE_BYTES)).toInt())

        fun clipInput(text: String, limit: Int): String {
            if (text.length <= limit) return text
            val end = if (text[limit - 1].isHighSurrogate()) limit - 1 else limit
            return text.substring(0, end)
        }

        fun sha256(value: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            val hex = "0123456789abcdef"
            return buildString(HASH_LENGTH) {
                bytes.forEach { byte ->
                    val code = byte.toInt() and 0xff
                    append(hex[code shr 4]).append(hex[code and 15])
                }
            }
        }
    }
}
