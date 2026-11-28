package dev.lily.app.media

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SceneryTest {
    @get:Rule val temp = TemporaryFolder()

    private fun source(name: String): File = File(temp.root, name).apply { writeBytes(byteArrayOf(1, 2, 3)) }

    private fun store(): SceneryStore = SceneryStore(File(temp.root, "scenery"))

    @Test fun picksByPlaceMentionedInChat() {
        val store = store()
        val westLake = assertNotNull(store.add(source("a.jpg"), "杭州西湖"))
        val forbidden = assertNotNull(store.add(source("b.jpg"), "北京故宫"))
        assertEquals(westLake.id, SceneryPicker.pick(store.items.value, "周末想去西湖走走", "")?.id)
        assertEquals(forbidden.id, SceneryPicker.pick(store.items.value, "故宫开门了吗", "")?.id)
        assertNull(SceneryPicker.pick(store.items.value, "今天好累", ""))
    }

    @Test fun fallsBackToHerCity() {
        val store = store()
        store.add(source("a.jpg"), "杭州西湖")
        store.setCity("杭州")
        assertNotNull(SceneryPicker.pick(store.items.value, "在干嘛", store.city.value))
        assertNull(SceneryPicker.pick(store.items.value, "在干嘛", "上海"))
    }

    @Test fun longestPlaceWins() {
        val store = store()
        val broad = store.add(source("a.jpg"), "北京")
        val exact = assertNotNull(store.add(source("b.jpg"), "北京西站"))
        assertEquals(exact.id, SceneryPicker.pick(store.items.value, "我在北京西站", "")?.id)
        assertEquals(broad?.id, SceneryPicker.pick(store.items.value, "我在北京", "")?.id)
    }

    @Test fun emptyLibraryIsSilent() {
        assertNull(store().maybePick("西湖"))
    }

    @Test fun cooldownBlocksSecondShare() {
        val store = store()
        store.add(source("a.jpg"), "西湖")
        val now = 1_000_000_000_000L
        assertNotNull(store.maybePick("西湖", now))
        assertNull(store.maybePick("又说到西湖", now + 60_000))
        assertNotNull(store.maybePick("第三天了，西湖", now + 7 * 3_600_000L))
    }

    @Test fun editsPersistAndRemovalDeletesFile() {
        val dir = File(temp.root, "scenery")
        val store = SceneryStore(dir)
        val item = assertNotNull(store.add(source("a.jpg"), "西湖"))
        store.setPlace(item.id, "杭州西湖")
        store.setCity("杭州")
        val reopened = SceneryStore(dir)
        assertEquals("杭州西湖", reopened.items.value.single().place)
        assertEquals("杭州", reopened.city.value)
        val file = File(item.file)
        reopened.remove(item.id)
        assertTrue(reopened.items.value.isEmpty())
        assertTrue(!file.exists())
    }
}
