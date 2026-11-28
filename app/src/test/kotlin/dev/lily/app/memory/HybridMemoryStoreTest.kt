package dev.lily.app.memory

import dev.lily.api.Note
import dev.lily.app.settings.MemoryEmbeddingSettings
import dev.lily.memory.FileMemoryStore
import dev.lily.memory.PersistentVectorIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HybridMemoryStoreTest {
    @get:Rule
    val files = TemporaryFolder()

    @Test fun `semantic only matches are found inside the current role`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val config = MemoryEmbeddingSettings(true, "https://embeddings.example/v1", "model")
            val first = FileMemoryStore(files.newFolder("first"))
            val second = FileMemoryStore(files.newFolder("second"))
            first.write(Note("市图书馆", "places/library", body = "适合一个人翻书"))
            second.write(Note("游泳馆", "places/library", body = "有很大的泳池"))
            val query = "想找个安静角落"
            assertTrue(first.search(query).isEmpty())
            val embed: suspend (MemoryEmbeddingSettings, List<String>) -> List<FloatArray> = { _, values ->
                values.map { value ->
                    if (value.contains("图书馆") || value == query) floatArrayOf(1f, 0f, 0f) else floatArrayOf(0f, 1f, 0f)
                }
            }
            val firstSearch = HybridMemoryStore(first, PersistentVectorIndex(File(files.root, "first.bin")), { config }, scope, embed)
            val secondSearch = HybridMemoryStore(second, PersistentVectorIndex(File(files.root, "second.bin")), { config }, scope, embed)
            firstSearch.prepare()
            secondSearch.prepare()
            withTimeout(10_000) {
                firstSearch.progress.first { it.indexed > 0 && !it.running }
                secondSearch.progress.first { it.indexed > 0 && !it.running }
            }
            assertEquals(listOf("市图书馆"), firstSearch.search(query).map { it.title })
            assertTrue(secondSearch.search(query).isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test fun `disabled stays local and provider failure preserves keyword results`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val config = AtomicReference(MemoryEmbeddingSettings(false, "https://embeddings.example/v1", "model"))
            val remoteRequests = AtomicInteger()
            val notes = FileMemoryStore(files.newFolder("notes"))
            notes.write(Note("图书馆", "library", body = "借书"))
            val search = HybridMemoryStore(
                notes, PersistentVectorIndex(File(files.root, "index.bin")), config::get, scope,
                embed = { _, _ -> remoteRequests.incrementAndGet(); throw IOException("offline") },
            )
            search.prepare()
            val keywords = notes.search("图书馆")
            assertTrue(keywords.isNotEmpty())
            assertEquals(keywords, search.search("图书馆"))
            assertEquals(0, remoteRequests.get())
            config.updateAndGet { it.copy(enabled = true) }
            search.prepare()
            withTimeout(10_000) { search.progress.first { it.unavailable && !it.running } }
            assertTrue(remoteRequests.get() > 0)
            assertEquals(keywords, search.search("图书馆"))
            assertFalse(search.progress.value.running)
        } finally {
            scope.cancel()
        }
    }
}
