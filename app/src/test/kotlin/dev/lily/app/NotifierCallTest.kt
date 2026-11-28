package dev.lily.app

import android.app.NotificationManager
import android.content.Context
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 通话通知要能被系统提升成状态栏胶囊（灵动岛）。
 *
 * 关键两点：样式必须是 `CallStyle`（系统才认成「正在进行的通话」），
 * extras 里必须带 `android.requestPromotedOngoing`（系统才提升）。少一个都不上岛。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotifierCallTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test fun `call notification asks the system to promote it`() {
        val notification = Notifier(context) { "Lily" }.call(startedAt = 1_700_000_000_000L)

        assertEquals(
            true,
            notification.extras.getBoolean(Notifier.EXTRA_REQUEST_PROMOTED_ONGOING),
            "通话通知缺少上岛请求标记",
        )
        assertTrue(
            notification.extras.getString("android.template")?.contains("CallStyle") == true,
            "通话通知样式不是 CallStyle：${notification.extras.getString("android.template")}",
        )
    }

    @Test fun `call notification is ongoing so it survives the call`() {
        val notification = Notifier(context) { "Lily" }.call(startedAt = 1_700_000_000_000L)
        // FLAG_ONGOING_EVENT 是平台公开的「常驻」标志；系统判断能不能上岛也看它。
        assertTrue(
            notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0,
            "通话通知应当是常驻，flags=0x${Integer.toHexString(notification.flags)}",
        )
    }

    @Test fun `promoted ongoing only exists on api 36 and above`() {
        // 低版本系统读到不认识的 extras 键会直接忽略，所以这条在所有版本上都安全。
        val notification = Notifier(context) { "Lily" }.call(startedAt = 1_700_000_000_000L)
        assertEquals(true, notification.extras.getBoolean(Notifier.EXTRA_REQUEST_PROMOTED_ONGOING))
        // 频道必须是常驻的低打扰渠道，否则通话中会一直响。
        val notifier = Notifier(context) { "Lily" }
        notifier.createChannels()
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(Notifier.CHANNEL_CALL)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
    }
}
