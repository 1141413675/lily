package dev.lily.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import dev.lily.app.session.PendingApproval
import dev.lily.app.session.PendingQuestion

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

    /**
     * 通话中的持续通知：点回通话页；挂断、未接后由 [CallService] 撤掉。
     *
     * 样式走 [NotificationCompat.CallStyle]：系统会把它认成「正在进行的通话」，
     * 再配合 [requestPromotedOngoing] 申请上岛 —— Android 16 起这类通知会被系统提升成
     * 状态栏胶囊（国产 ROM 上叫灵动岛/流体云/超级岛），退到桌面也能看到通话还在继续。
     */
    fun call(startedAt: Long): Notification {
        val caller = Person.Builder()
            .setName(name())
            .setKey(dev.lily.app.persona.AgentDirectory.PRIMARY_ID)
            .setImportant(true)
            .build()
        return builder(CHANNEL_CALL)
            .setContentTitle(context.getString(R.string.notification_call))
            .setContentText(context.getString(R.string.notification_call_return))
            .setStyle(
                NotificationCompat.CallStyle.forOngoingCall(caller, openCall())
                    .setIsVideo(false),
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setWhen(startedAt)
            .setUsesChronometer(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openCall())
            .apply { requestPromotedOngoing(this) }
            .build()
    }

    /**
     * 申请把通知提升成「实时更新」：这是上岛的那一步。
     *
     * androidx.core 1.17 的 `setRequestPromotedOngoing` 就是往通知 extras 里写一个布尔，
     * 这里直接写同样的键，就不必把整个依赖树升到需要 JDK 21 的版本。低版本系统读到
     * 不认识的键会直接忽略，不会有副作用。
     */
    private fun requestPromotedOngoing(builder: NotificationCompat.Builder) {
        builder.extras.putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)
    }

    fun status(text: String?): Notification = builder(CHANNEL_STATUS)
        .setContentTitle(context.getString(R.string.notification_running))
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setContentIntent(openChat())
        .addAction(0, context.getString(R.string.chat_stop), action(NotificationActionReceiver.ACTION_STOP, null))
        .build()

    /** Refreshes the foreground notification posted by [AgentService]. */
    fun updateStatus(text: String?) {
        if (allowed()) manager.notify(ID_STATUS, status(text))
    }

    fun showApproval(pending: PendingApproval?) {
        if (pending == null) return manager.cancel(ID_APPROVAL)
        if (!allowed()) return
        val notification = builder(CHANNEL_ATTENTION)
            .setContentTitle(context.getString(R.string.approval_title))
            .setContentText(pending.request.prompt)
            .setStyle(NotificationCompat.BigTextStyle().bigText(pending.request.prompt))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOngoing(true)
            .setContentIntent(openChat())
            .addAction(0, context.getString(R.string.approval_once), action(NotificationActionReceiver.ACTION_APPROVE_ONCE, pending.id))
            .addAction(0, context.getString(R.string.approval_task), action(NotificationActionReceiver.ACTION_APPROVE_TASK, pending.id))
            .addAction(0, context.getString(R.string.approval_deny), action(NotificationActionReceiver.ACTION_DENY, pending.id))
            .build()
        manager.notify(ID_APPROVAL, notification)
    }

    fun showQuestion(pending: PendingQuestion?) {
        if (pending == null) return manager.cancel(ID_QUESTION)
        if (!allowed()) return
        val notification = builder(CHANNEL_ATTENTION)
            .setContentTitle(context.getString(if (pending.takeover) R.string.chat_takeover_label else R.string.notification_question))
            .setContentText(pending.question)
            .setStyle(NotificationCompat.BigTextStyle().bigText(pending.question))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openChat())
            .build()
        manager.notify(ID_QUESTION, notification)
    }

    /** Heads-up notification posted when a reminder_set alarm fires. */
    fun showReminder(id: Int, text: String) {
        if (!allowed()) return
        val notification = builder(CHANNEL_REMINDERS)
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
    fun showProactive(text: String, agentId: String? = null, agentName: String = name()) {
        if (!allowed()) return
        val notification = builder(CHANNEL_REMINDERS)
            .setContentTitle(agentName)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openChat(agentId))
            .build()
        if (agentId == null) manager.notify(ID_PROACTIVE, notification)
        else manager.notify(agentId, ID_PROACTIVE, notification)
    }

    /** 消息和待处理事项直接打开会话，复用活动以保留会话草稿。 */
    private fun openChat(agentId: String? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_CHAT, true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (agentId != null) {
            intent.identifier = agentId
            intent.putExtra(MainActivity.EXTRA_OPEN_CHAT_AGENT, agentId)
        }
        return PendingIntent.getActivity(
            context, 1, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun allowed(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** 通知右侧的大图用新版应用图标；小图标只能是单色剪影，两者各司其职。 */
    private val largeIcon: Bitmap? by lazy {
        runCatching { BitmapFactory.decodeResource(context.resources, R.drawable.ic_launcher_art) }.getOrNull()
    }

    private fun builder(channel: String): NotificationCompat.Builder {
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification_lily)
            .setColor(ContextCompat.getColor(context, R.color.lily_notification_accent))
        largeIcon?.let(builder::setLargeIcon)
        return builder
    }

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
            .putExtra(dev.lily.app.voice.CallService.EXTRA_OPEN_CALL, true)
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

        /** Live Updates（实时更新）的请求键：系统据此把通知提升成状态栏胶囊。 */
        const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
    }
}
