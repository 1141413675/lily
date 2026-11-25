package dev.xiaoman.app.dream

import android.content.Context
import dev.xiaoman.api.MemoryStore
import dev.xiaoman.api.Note
import dev.xiaoman.api.Observation
import dev.xiaoman.app.settings.SettingsStore
import dev.xiaoman.llm.LlmClientFactory
import dev.xiaoman.memory.FileMemoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DreamMergeTest {
    @get:Rule
    val files = TemporaryFolder()

    @Test
    fun backupFailureLeavesSourcesAndTargetUntouched() = runBlocking {
        val memory = FileMemoryStore(files.newFolder("memory"))
        val dreamRoot = files.newFolder("dream")
        val store = DreamStore(dreamRoot)
        val engine = engine(memory, store, this)
        val original = writeNotes(memory)
        val day = proposal(store)
        val obstruction = File(dreamRoot, DreamStore.TRASH).apply { writeText("not a directory") }

        waitForChange(engine) { engine.apply(day.date, setOf("merge"), auto = true) }

        val failed = store.day(day.date)!!.items.single()
        assertFalse(failed.applied)
        assertNotNull(failed.error)
        assertNull(failed.undo)
        assertEquals(original, memory.list())
        assertTrue(store.trashed().isEmpty())

        assertTrue(obstruction.delete())
        waitForChange(engine) { engine.apply(day.date, setOf("merge"), auto = true) }
        val retried = store.day(day.date)!!.items.single()
        assertTrue(retried.applied)
        assertNull(retried.error)
        assertEquals(original.first { it.permalink == "target" }.observations.size + 1, memory.read("target")!!.observations.size)
        assertEquals(2, retried.undo!!.trashFiles.size)
    }

    @Test
    fun failureAfterDeletingFirstSourceRollsBackAndRetryCanBeUndone() = runBlocking {
        val disk = FileMemoryStore(files.newFolder("memory"))
        var refuseSecondDelete = true
        val memory = object : MemoryStore by disk {
            override suspend fun delete(identifier: String): Boolean =
                if (refuseSecondDelete && identifier == "second") false else disk.delete(identifier)
        }
        val store = DreamStore(files.newFolder("dream"))
        val engine = engine(memory, store, this)
        val original = writeNotes(memory)
        val day = proposal(store)

        waitForChange(engine) { engine.apply(day.date, setOf("merge"), auto = true) }

        val failed = store.day(day.date)!!.items.single()
        assertFalse(failed.applied)
        assertNotNull(failed.error)
        assertNull(failed.undo)
        assertEquals(original.map { it.withoutTime() }, memory.list().map { it.withoutTime() })
        assertTrue(store.trashed().isEmpty())

        refuseSecondDelete = false
        waitForChange(engine) { engine.apply(day.date, setOf("merge"), auto = true) }
        val applied = store.day(day.date)!!.items.single()
        assertTrue(applied.applied)
        assertNull(applied.error)
        assertNull(memory.read("first"))
        assertNull(memory.read("second"))
        assertEquals(2, applied.undo!!.trashFiles.size)

        waitForChange(engine) { engine.undoAuto(day.date) }
        val undone = store.day(day.date)!!.items.single()
        assertFalse(undone.applied)
        assertNull(undone.undo)
        assertEquals(original.map { it.withoutTime() }, memory.list().map { it.withoutTime() })
        assertTrue(store.trashed().isEmpty())
    }

    private fun engine(memory: MemoryStore, store: DreamStore, scope: CoroutineScope): DreamEngine {
        val context = RuntimeEnvironment.getApplication()
        val settings = SettingsStore(
            context.getSharedPreferences("dream-plain-test", Context.MODE_PRIVATE),
            context.getSharedPreferences("dream-secret-test", Context.MODE_PRIVATE),
        )
        return DreamEngine(memory, { settings.current }, LlmClientFactory { error("Merge must work offline") }, store, RuleStore(memory), scope)
    }

    private suspend fun writeNotes(memory: MemoryStore): List<Note> {
        val notes = mapOf("first" to "来自第一条的细节", "second" to "来自第二条的细节", "target" to "目标原有内容")
        for ((id, text) in notes) {
            memory.write(Note(id, id, observations = listOf(Observation("note", text))))
        }
        return memory.list()
    }

    private fun proposal(store: DreamStore): DreamDay = DreamDay(
        date = store.today(),
        createdAt = 1L,
        items = listOf(
            DreamItem(
                id = "merge",
                type = DreamTypes.MERGE,
                title = "目标",
                target = "target",
                sources = listOf("first", "second", "first"),
                observations = listOf(DreamObservation("note", "合并观察")),
            ),
        ),
    ).also(store::saveDay)

    private suspend fun waitForChange(engine: DreamEngine, action: () -> Unit) {
        val before = engine.revision.value
        action()
        withTimeout(10_000) { engine.revision.first { it > before } }
    }

    private fun Note.withoutTime() = copy(updatedAtMillis = 0)
}
