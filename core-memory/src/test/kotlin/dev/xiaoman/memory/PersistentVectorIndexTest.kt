package dev.xiaoman.memory

import dev.xiaoman.api.Note
import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PersistentVectorIndexTest {
    private val directory = createTempDirectory("vector-index-test").toFile()
    private val memory = FileMemoryStore(File(directory, "notes"))
    private val cache = File(directory, "index/vectors.bin")
    private val index = PersistentVectorIndex(cache)

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun reopensPersistedPassagesAndEmbedsOnlyTheQueryAtSearchTime() = runTest {
        memory.write(Note("海边", "beach", body = "休闲".repeat(510)))
        memory.write(Note("咖啡", "coffee", body = "每天手冲。"))
        val progress = index.refresh(memory, "model-a") { texts ->
            texts.map { if ("海边" in it) floatArrayOf(3f, 0f) else floatArrayOf(0f, 3f) }
        }
        assertEquals(VectorRefreshResult(indexed = 3, total = 3, newlyIndexed = 3), progress)
        assertTrue(cache.isFile)
        val reopened = PersistentVectorIndex(cache)
        val hits = reopened.search("去哪里度假", memory, "model-a", { texts ->
            assertEquals(listOf("去哪里度假"), texts)
            listOf(floatArrayOf(1f, 0f))
        }, limit = 5)
        assertEquals(listOf("beach"), hits.map { it.permalink })
        assertEquals(1.0, hits.single().score)
    }

    @Test
    fun editedAndDeletedNotesStopMatchingBeforeTheNextRefresh() = runTest {
        memory.write(Note("尺寸", "shoe", body = "平时穿 38 码。"))
        memory.write(Note("路线", "route", body = "从东门走。"))
        index.refresh(memory, "model-a", ::sameVectors)
        memory.write(Note("尺寸", "shoe", body = "改穿 39 码了。"))
        memory.delete("route")

        var requested = false
        val stale = index.search("尺码", memory, "model-a", { texts ->
            requested = true
            sameVectors(texts)
        }, limit = 5)
        assertTrue(stale.isEmpty())
        assertTrue(!requested)
        val progress = index.refresh(memory, "model-a") { texts ->
            assertEquals(1, texts.size)
            assertTrue("39" in texts.single())
            sameVectors(texts)
        }
        assertEquals(VectorRefreshResult(indexed = 1, total = 1, newlyIndexed = 1), progress)
        assertEquals(listOf("shoe"), index.search("尺码", memory, "model-a", ::sameVectors, limit = 5).map { it.permalink })
    }

    @Test
    fun refreshMakesBoundedProgressAndKeepsExistingVectorsWhenOffline() = runTest {
        repeat(36) { n -> memory.write(Note("第 ${n + 1} 条", "note-$n", body = "单独的事实 $n")) }
        var requests = 0
        val embed: suspend (List<String>) -> List<FloatArray> = { texts ->
            requests++
            assertTrue(texts.size in 1..8)
            sameVectors(texts)
        }
        assertEquals(VectorRefreshResult(32, 36, 32), index.refresh(memory, "model-a", embed))
        assertEquals(4, requests)
        val offline = index.refresh(memory, "model-a") { throw IOException("Disconnected") }
        assertEquals(VectorRefreshResult(32, 36, 0), offline)
        assertEquals(32, index.search("事实", memory, "model-a", ::sameVectors, limit = 40).size)
        assertEquals(VectorRefreshResult(36, 36, 4), index.refresh(memory, "model-a", embed))
        assertEquals(5, requests)
    }

    @Test
    fun modelAndDimensionMismatchesCannotReuseOldVectors() = runTest {
        memory.write(Note("海边", "beach"))
        index.refresh(memory, "model-a", ::sameVectors)

        var requestCount = 0
        val newEmbed: suspend (List<String>) -> List<FloatArray> = { texts ->
            requestCount++
            texts.map { floatArrayOf(1f, 0f, 0f) }
        }
        assertTrue(index.search("度假", memory, "model-b", newEmbed, limit = 5).isEmpty())
        assertEquals(0, requestCount)
        assertTrue(index.search("度假", memory, "model-a", newEmbed, limit = 5).isEmpty())
        assertEquals(1, requestCount)
        assertEquals(VectorRefreshResult(1, 1, 1), index.refresh(memory, "model-a", newEmbed))
        assertEquals(listOf("beach"), index.search("度假", memory, "model-a", newEmbed, limit = 5).map { it.permalink })
    }

    private fun sameVectors(texts: List<String>) = texts.map { floatArrayOf(1f, 0f) }
}
