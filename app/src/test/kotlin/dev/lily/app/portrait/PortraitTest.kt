package dev.lily.app.portrait

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PortraitTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `saving a picked photo copies it and survives a reload`() {
        val dir = tmp.newFolder("portrait")
        val store = PortraitStore(dir)
        val picked = tmp.newFile("picked.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        assertTrue(store.saveAvatar(picked))
        assertTrue(store.displayFile()?.isFile == true)
        assertEquals(dir.resolve("current.jpg").absolutePath, store.displayFile()?.absolutePath)

        val reloaded = PortraitStore(dir)
        assertEquals(store.displayFile()?.absolutePath, reloaded.displayFile()?.absolutePath)
        assertTrue(reloaded.displayFile()?.isFile == true)
    }

    @Test
    fun `existing generated portrait keeps working`() {
        val dir = tmp.newFolder("portrait-legacy")
        val legacy = tmp.newFile("cand-old.jpg").apply { writeBytes(byteArrayOf(9, 9, 9)) }
        dir.resolve("portrait.json").writeText(
            """{"spec":{"gender":"女","age":"25–30","mood":"温和"},"current":{"path":"${legacy.absolutePath}","at":1}}""",
        )

        val store = PortraitStore(dir)
        assertEquals(legacy.absolutePath, store.state.value.current?.path)
        assertTrue(store.displayFile()?.isFile == true)
    }

    @Test
    fun `current jpg without json still shows`() {
        val dir = tmp.newFolder("portrait-file")
        dir.resolve("current.jpg").writeBytes(byteArrayOf(7))

        val store = PortraitStore(dir)
        assertTrue(store.displayFile()?.isFile == true)
    }

    @Test
    fun `empty folder means no avatar`() {
        val store = PortraitStore(tmp.newFolder("portrait-empty"))
        assertNull(store.state.value.current)
        assertNull(store.displayFile())
    }
}
