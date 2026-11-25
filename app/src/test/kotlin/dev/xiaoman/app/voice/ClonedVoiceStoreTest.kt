package dev.xiaoman.app.voice

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClonedVoiceStoreTest {
    @get:Rule val temp = TemporaryFolder()

    private fun store() = ClonedVoiceStore(File(temp.root, "voices"))

    @Test fun addPersistsUriAndNameButNoSampleFile() {
        val dir = File(temp.root, "voices")
        val voice = ClonedVoiceStore(dir).add("speech:mine:a:b", "我的声音")
        val reopened = ClonedVoiceStore(dir)
        assertEquals(voice.id, reopened.voices.value.single().id)
        assertEquals("speech:mine:a:b", reopened.voices.value.single().uri)
        assertEquals("我的声音", reopened.voices.value.single().name)
        // 只留索引，不留样本。
        assertTrue(dir.listFiles()!!.all { it.name == "index.json" })
    }

    @Test fun duplicateNamesGetASuffix() {
        val store = store()
        val first = store.add("speech:1", "夜")
        val second = store.add("speech:2", "夜")
        assertEquals("夜", first.name)
        assertNotEquals(first.name, second.name)
        assertEquals("夜 · 2", second.name)
    }

    @Test fun removalOnlyTouchesTheIndex() {
        val store = store()
        val voice = store.add("speech:1", "夜")
        store.remove(voice.id)
        assertTrue(store.voices.value.isEmpty())
        assertNull(store.findById(voice.id))
    }

    @Test fun blankUriIsDroppedOnRead() {
        val dir = File(temp.root, "voices").apply { mkdirs() }
        File(dir, "index.json").writeText("""{"voices":[{"id":"a","uri":"","name":"x"}]}""")
        assertTrue(ClonedVoiceStore(dir).voices.value.isEmpty())
    }
}
