package dev.lily.sentinel

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ConfirmWordsTest {
    @Test
    fun `irreversible actions require confirmation`() {
        listOf("删除", "卸载", "清空", "重置", "发送", "发布", "上传", "分享", "注销", "解绑").forEach { word ->
            assertNotNull(ConfirmWords.longestIn("$word 按钮", ConfirmWords.BUILT_IN), word)
        }
    }

    @Test
    fun `plain navigation and queries do not`() {
        listOf("搜索", "返回", "查看", "下一页", "打开", "播放", "刷新").forEach { word ->
            assertNull(ConfirmWords.longestIn(word, ConfirmWords.BUILT_IN), word)
            assertFalse(ConfirmWords.matches("send", "sender"))
        }
    }
}
