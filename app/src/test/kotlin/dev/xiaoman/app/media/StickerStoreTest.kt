package dev.xiaoman.app.media

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StickerStoreTest {
    @get:Rule val temp = TemporaryFolder()

    private fun source(name: String = "cat.png"): File {
        val f = File(temp.root, name)
        f.writeBytes(byteArrayOf(1, 2, 3, 4))
        return f
    }

    @Test fun addCopiesIntoLibraryAndStartsUnsorted() {
        val dir = File(temp.root, "stickers")
        val store = StickerStore(dir)
        val sticker = assertNotNull(store.add(source(), "一只很长的表情名字超过十个字", animated = true))
        assertTrue(File(sticker.file).isFile)
        assertEquals(10, sticker.name.length)
        assertEquals(listOf(StickerStore.UNSORTED), sticker.tags)
        assertTrue(sticker.animated)
        assertEquals(0, sticker.uses)
        assertEquals(1, store.stickers.value.size)
    }

    @Test fun usageAndTagsSurviveReload() {
        val dir = File(temp.root, "stickers")
        val store = StickerStore(dir)
        val sticker = assertNotNull(store.add(source(), "安抚", false))
        store.bumpUse(sticker.id)
        store.bumpUse(sticker.id)
        store.setTags(sticker.id, listOf("常用", "安抚"))

        val reopened = StickerStore(dir)
        val loaded = assertNotNull(reopened.stickers.value.firstOrNull { it.id == sticker.id })
        assertEquals(2, loaded.uses)
        assertEquals(listOf("常用", "安抚"), loaded.tags)
    }

    @Test fun emptyTagsFallBackToUnsorted() {
        val store = StickerStore(File(temp.root, "stickers"))
        val sticker = assertNotNull(store.add(source(), "x", false))
        store.setTags(sticker.id, emptyList())
        assertEquals(listOf(StickerStore.UNSORTED), store.stickers.value.single().tags)
    }

    @Test fun removeDeletesEntryAndFile() {
        val store = StickerStore(File(temp.root, "stickers"))
        val sticker = assertNotNull(store.add(source(), "x", false))
        val file = File(sticker.file)
        store.remove(sticker.id)
        assertTrue(store.stickers.value.isEmpty())
        assertFalse(file.exists())
    }

    @Test fun loadDropsEntriesWhoseFileIsGone() {
        val dir = File(temp.root, "stickers")
        val store = StickerStore(dir)
        val sticker = assertNotNull(store.add(source(), "x", false))
        File(sticker.file).delete()
        assertTrue(StickerStore(dir).stickers.value.isEmpty())
    }

    @Test fun tagsListPresetsFirst() {
        val store = StickerStore(File(temp.root, "stickers"))
        val a = assertNotNull(store.add(source("a.png"), "a", false))
        val b = assertNotNull(store.add(source("b.png"), "b", false))
        store.setTags(a.id, listOf("晚安", "自定义"))
        store.setTags(b.id, listOf("常用"))
        assertEquals(listOf("常用", "晚安", "自定义"), store.tags())
    }
}
