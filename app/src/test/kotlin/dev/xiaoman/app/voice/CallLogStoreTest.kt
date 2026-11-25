package dev.xiaoman.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/** 通话记录：方向落盘、列表倒序、旧文件没有方向字段时按我拨出读。 */
class CallLogStoreTest {

    private fun log(startedAt: Long, incoming: Boolean) = CallLog(
        startedAt = startedAt,
        connectedAt = startedAt + 1_000,
        endedAt = startedAt + 61_000,
        outcome = dev.xiaoman.app.session.CallOutcome.CONNECTED.name,
        reason = null,
        turns = listOf(
            CallTurn(at = startedAt + 2_000, fromMe = true, text = "在吗"),
            CallTurn(at = startedAt + 4_000, fromMe = false, text = "在"),
        ),
        incoming = incoming,
    )

    @Test
    fun directionAndTurnsSurviveReload() {
        val dir = createTempDirectory("calls").toFile()
        val store = CallLogStore(dir)
        store.save(log(2_000, incoming = true))

        val reloaded = CallLogStore(dir).list().single()
        assertTrue(reloaded.incoming)
        assertEquals(60_000, reloaded.durationMs)
        assertEquals(2, reloaded.turns.size)
        assertEquals("在吗", reloaded.turns.first().text)
    }

    @Test
    fun listIsNewestFirst() {
        val dir = createTempDirectory("calls").toFile()
        val store = CallLogStore(dir)
        store.save(log(1_000, incoming = false))
        store.save(log(3_000, incoming = true))

        val all = store.list()
        assertEquals(listOf(3_000L, 1_000L), all.map { it.startedAt })
        assertTrue(all.first().incoming)
    }

    @Test
    fun oldLogWithoutIncomingReadsAsOutgoing() {
        val dir = createTempDirectory("calls").toFile()
        File(dir, "1000.json").writeText(
            """{"startedAt":1000,"connectedAt":2000,"endedAt":5000,"outcome":"CONNECTED","reason":null,"turns":[]}""",
        )

        val old = CallLogStore(dir).list().single()
        assertFalse(old.incoming)
        assertEquals(3_000, old.durationMs)
        assertTrue(old.turns.isEmpty())
    }
}
