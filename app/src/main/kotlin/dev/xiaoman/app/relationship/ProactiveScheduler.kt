package dev.xiaoman.app.relationship

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * 主动开口的检查闹钟：每 [INTERVAL_MS] 响一次，[ProactiveReceiver] 检查后重排下一次。
 * 用 setAndAllowWhileIdle 避免精确闹钟权限；关掉主动时取消。
 */
class ProactiveScheduler(private val context: Context, private val enabled: () -> Boolean) {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    fun schedule() {
        if (!enabled()) {
            cancel()
            return
        }
        val pending = pendingIntent()
        alarms.cancel(pending)
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + INTERVAL_MS, pending)
    }

    fun cancel() = alarms.cancel(pendingIntent())

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        0x0AC7,
        Intent(context, ProactiveReceiver::class.java).setAction(ProactiveReceiver.ACTION_CHECK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val INTERVAL_MS = 90 * 60 * 1000L
    }
}
