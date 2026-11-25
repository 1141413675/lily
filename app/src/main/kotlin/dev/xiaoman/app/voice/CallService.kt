package dev.xiaoman.app.voice

import android.app.Service
import android.content.Intent
import android.os.IBinder
import dev.xiaoman.app.Notifier

/**
 * 通话期间的前台服务：让麦克风与通话在后台继续，并在状态栏留一条通话类持续通知。
 * 通知点回 [dev.xiaoman.app.MainActivity]，由它转成 openCall；挂断时 [CallController] 停掉服务，
 * 通知随之消失。服务本身不持有通话状态，避免和 [CallController] 各存一份。
 */
class CallService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 进程被回收后重启时频道可能还没建，重复调用是幂等的。
        Notifier(this).createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val startedAt = intent?.getLongExtra(EXTRA_STARTED_AT, 0L)?.takeIf { it > 0 } ?: System.currentTimeMillis()
        startForeground(Notifier.ID_CALL, Notifier(this).call(startedAt))
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_STARTED_AT = "call_started_at"

        /** 通知点开时带给 MainActivity 的标记：回到正在进行的通话页。 */
        const val EXTRA_OPEN_CALL = "open_call"
    }
}
