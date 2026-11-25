package dev.xiaoman.app.dream

import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DreamConversationLogTest {
    @get:Rule
    val files = TemporaryFolder()

    @Test fun `recent conversation reads both formats and recall only removes the intended record`() {
        val store = DreamStore(files.root)
        val now = System.currentTimeMillis()
        val file = File(files.root, "${DreamStore.LOGS}/${store.today()}.jsonl")
        check(file.parentFile?.mkdirs() == true)
        file.writeText(
            """
            {
                "t": ${now - 1000},
                "r": "user",
                "x": "旧内容\n第二行"
            }
            {
                "t": ${now - 500},
                "r": "assistant",
                "x": "另一条旧内容"
            }
            {
                "t": $now
            """.trimIndent() + "\n",
        )
        store.record("assistant", "这是一条新记录")
        val recent = store.recentConversation()
        assertTrue("旧内容\n第二行" in recent)
        assertTrue("另一条旧内容" in recent)
        assertTrue("这是一条新记录" in recent)
        assertTrue(file.readLines().last().startsWith("{\"t\""))

        store.forget("user", "旧内容\n第二行")
        val afterRecall = store.recentConversation()
        assertFalse("第二行" in afterRecall)
        assertTrue("另一条旧内容" in afterRecall)
        assertTrue("这是一条新记录" in afterRecall)
        assertTrue("\"t\": $now" in file.readText())
    }
}
