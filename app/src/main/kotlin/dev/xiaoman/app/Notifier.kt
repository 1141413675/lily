package dev.xiaoman.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.xiaoman.app.session.PendingApproval
import dev.xiaoman.app.session.PendingQuestion

/** Notification channels and the four notifications: status (foreground service), approval, question, reminder. */
class Notifier(private val context: Context, private val name: () -> String = { context.getString(R.string.app_name) }) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun createChannels() {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, context.getString(R.string.channel_status), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.channel_status_description)
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ATTENTION, context.getString(R.string.channel_attention), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.channel_attention_description)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDERS, context.getString(R.string.channel_reminders), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.channel_reminders_description)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_CALL, context.getString(R.string.channel_call), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.channel_call_description)
                setShowBadge(false)
                setSound(null, null)
            },
        )
    }

    /** 通话中的持续通知：点回通话页；挂断、未接后由 [CallService] 撤掉。 */
    fun call(startedAt: Long): Notification = NotificationCompat.Builder(context, CHANNEL_CALL)
        .setSmallIcon(R.drawable.ic_stat_xiaoman)
        .setContentTitle(context.getString(R.string.notification_call))
        .setContentText(context.getString(R.string.notification_call_return))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setCategory(NotificationCompat.CATEGORY_CALL)
        .setWhen(startedAt)
        .setUsesChronometer(true)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setContentIntent(openCall())
        .build()

    fun status(text: String?): Notification = NotificationCompat.Builder(context, CHANNEL_STATUS)
        .setSmallIcon(R.drawable.ic_stat_xiaoman)
        .setContentTitle(context.getString(R.string.notification_running))
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setContentIntent(openApp())
        .addAction(0, context.getString(R.string.chat_stop), action(NotificationActionReceiver.ACTION_STOP, null))
        .build()

    /** Refreshes the foreground notification posted by [AgentService]. */
    fun updateStatus(text: String?) {
        if (allowed()) manager.notify(ID_STATUS, status(text))
    }

    fun showApproval(pending: PendingApproval?) {
        if (pending == null) return manager.cancel(ID_APPROVAL)
        if (!allowed()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_ATTENTION)
            .setSmallIcon(R.drawable.ic_stat_xiaoman)
            .setContentTitle(context.getString(R.string.approval_title))
            .setContentText(pending.request.prompt)
            .setStyle(NotificationCompat.BigTextStyle().bigText(pending.request.prompt))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOngoing(true)
            .setContentIntent(openApp())
            .addAction(0, context.getString(R.string.approval_once), action(NotificationActionReceiver.ACTION_APPROVE_ONCE, pending.id))
            .addAction(0, context.getString(R.string.approval_task), action(NotificationActionReceiver.ACTION_APPROVE_TASK, pending.id))
            .addAction(0, context.getString(R.string.approval_deny), action(NotificationActionReceiver.ACTION_DENY, pending.id))
            .build()
        manager.notify(ID_APPROVAL, notification)
    }

    fun showQuestion(pending: PendingQuestion?) {
        if (pending == null) return manager.cancel(ID_QUESTION)
        if (!allowed()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_ATTENTION)
            .setSmallIcon(R.drawable.ic_stat_xiaoman)
            .setContentTitle(context.getString(if (pending.takeover) R.string.chat_takeover_label else R.string.notification_question))
            .setContentText(pending.question)
            .setStyle(NotificationCompat.BigTextStyle().bigText(pending.question))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        manager.notify(ID_QUESTION, notification)
    }

    /** Heads-up notification posted when a reminder_set alarm fires. */
    fun showReminder(id: Int, text: String) {
        if (!allowed()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_xiaoman)
            .setContentTitle(context.getString(R.string.reminder_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        manager.notify(id, notification)
    }

    /** 主动开口：复用提醒渠道，系统勿扰（DND）照常生效。 */
    fun showProactive(text: String) {
        if (!allowed()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_xiaoman)
            .setContentTitle(name())
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openChat())
            .build()
        manager.notify(ID_PROACTIVE, notification)
    }

    /** 主动开口点开必须落在聊天：清掉当前导航栈重进，会话内容在进程内存里不受影响。 */
    private fun openChat(): PendingIntent = PendingIntent.getActivity(
        context,
        1,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun allowed(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** 点通知回到正在进行的通话，由 MainActivity 转成 openCall 事件。 */
    private fun openCall(): PendingIntent = PendingIntent.getActivity(
        context,
        5,
        Intent(context, MainActivity::class.java)
            .putExtra(dev.xiaoman.app.voice.CallService.EXTRA_OPEN_CALL, true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun action(action: String, approvalId: Long?): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).setAction(action)
        if (approvalId != null) intent.putExtra(NotificationActionReceiver.EXTRA_APPROVAL_ID, approvalId)
        return PendingIntent.getBroadcast(context, action.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        const val CHANNEL_STATUS = "status"
        const val CHANNEL_ATTENTION = "attention"
        const val CHANNEL_REMINDERS = "reminders"
        const val CHANNEL_CALL = "calls"
        const val ID_STATUS = 1
        const val ID_APPROVAL = 2
        const val ID_QUESTION = 3
        const val ID_PROACTIVE = 4
        const val ID_CALL = 5
    }
}
