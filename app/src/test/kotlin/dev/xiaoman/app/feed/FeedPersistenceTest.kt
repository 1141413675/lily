package dev.xiaoman.app.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class FeedPersistenceTest {
    @Test
    fun failedReplacementKeepsTimelineAndImageUntilRetrySucceeds() {
        val dir = createTempDirectory("feed-rollback").toFile()
        try {
            val store = FeedStore(dir)
            val image = File(store.imagesDir().apply { mkdirs() }, "photo.jpg").apply { writeText("photo") }
            val post = store.addMine("原动态", listOf(image.absolutePath), 1L)!!
            val disk = File(dir, "posts.json")
            val original = disk.readText()
            val backup = File(dir, "saved-posts.json")
            assertTrue(disk.renameTo(backup))
            assertTrue(disk.mkdir())

            assertFalse(store.toggleLike(post.id))
            assertNull(store.addComment(post.id, "你", "回复", 2L))
            assertNull(store.addMine("新动态", emptyList(), 3L))
            assertFalse(store.delete(post.id))
            assertEquals(listOf(post), store.posts.value)
            assertEquals(original, backup.readText())
            assertTrue(image.isFile)
            assertTrue(dir.listFiles().orEmpty().none { it.name.startsWith("feed-posts-") })

            assertTrue(disk.delete())
            assertTrue(backup.renameTo(disk))
            assertTrue(store.delete(post.id))
            assertTrue(FeedStore(dir).posts.value.isEmpty())
            assertFalse(image.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun malformedSnapshotRemainsUntouchedUntilReadableAgain() {
        val dir = createTempDirectory("feed-unreadable").toFile()
        try {
            val disk = File(dir, "posts.json").apply { writeText("{unfinished") }
            val store = FeedStore(dir)
            assertTrue(store.loadFailed.value)
            assertFalse(store.retryLoad())
            assertNull(store.addMine("新动态", emptyList(), 2L))
            assertEquals("{unfinished", disk.readText())

            disk.writeText("""{"posts":[{"id":"old","text":"原动态","at":1}]}""")
            assertTrue(store.retryLoad())
            assertFalse(store.loadFailed.value)
            assertEquals("old", store.posts.value.single().id)
            assertNotNull(store.addMine("新动态", emptyList(), 2L))
            assertEquals(2, FeedStore(dir).posts.value.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun postRemovalKeepsImageWhileAnotherPostStillReferencesIt() {
        val dir = createTempDirectory("feed-shared-photo").toFile()
        try {
            val store = FeedStore(dir)
            val image = File(store.imagesDir().apply { mkdirs() }, "photo.jpg").apply { writeText("photo") }
            val first = store.addMine("第一条", listOf(image.absolutePath), 1L)!!
            val second = store.addMine("第二条", listOf(image.absolutePath), 2L)!!

            assertTrue(store.delete(first.id))
            assertTrue(image.exists())
            assertTrue(store.delete(second.id))
            assertFalse(image.exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
