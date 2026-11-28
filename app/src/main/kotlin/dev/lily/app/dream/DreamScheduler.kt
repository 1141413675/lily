package dev.lily.app.dream

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * Arms one inexact alarm a day at the configured local time (default 23:30). The alarm wakes the
 * process and [DreamReceiver] runs the dream; the receiver arms the next one.
 *
 * Alarms do not survive a reboot unless the app starts again; a fresh app start also re-arms it.
 */
class DreamScheduler(
    private val context: Context,
    private val agentId: String? = null,
    private val config: () -> DreamConfig,
) {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    fun schedule() {
        val current = config()
        if (!current.enabled) {
            cancel()
            return
        }
        val pending = pendingIntent()
        alarms.cancel(pending)
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTriggerAt(current.time), pending)
    }

    fun cancel() = alarms.cancel(pendingIntent())

    fun nextTriggerAt(time: String, now: Long = System.currentTimeMillis()): Long {
        val local = runCatching { LocalTime.parse(time.trim()) }.getOrDefault(LocalTime.of(23, 30))
        val zone = ZoneId.systemDefault()
        val nowTime = Instant.ofEpochMilli(now).atZone(zone)
        var next = nowTime.toLocalDate().atTime(local).atZone(zone)
        if (!next.toInstant().isAfter(nowTime.toInstant())) next = next.plusDays(1)
        return next.toInstant().toEpochMilli()
    }

    private fun pendingIntent(): PendingIntent {
        val intent = Intent(context, DreamReceiver::class.java).setAction(DreamReceiver.ACTION_DREAM)
        if (agentId != null) {
            intent.identifier = agentId
            intent.putExtra(DreamReceiver.EXTRA_AGENT_ID, agentId)
        }
        return PendingIntent.getBroadcast(
            context,
            0x0D2E,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
