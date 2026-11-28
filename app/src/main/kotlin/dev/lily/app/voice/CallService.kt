package dev.lily.app.voice

import android.app.Service
import android.content.Intent
import android.os.IBinder
import dev.lily.app.Notifier

/**
 * 通话期间的前台服务：让麦克风与通话在后台继续，并在状态栏留一条通话类持续通知。
 * 通知点回 [dev.lily.app.MainActivity]，由它转成 openCall；挂断时 [CallController] 停掉服务，
 * 通知随之消失。服务本身不持有通话状态，避免和 [CallController] 各存一份。
 *
 * 通知用 `CallStyle` + `setRequestPromotedOngoing`，系统会把它提升成状态栏胶囊（灵动岛），
 * 所以退到桌面、锁屏都还能看到「通话中」。
 *
 * 返回 [START_NOT_STICKY]：麦克风采集在 App 进程里，进程一旦被回收通话本身就没了，
 * 让系统重建服务只会留下一条「通话中」的空通知，骗用户。
 */
class CallService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 进程被回收后重启时频道可能还没建，重复调用是幂等的。
        Notifier(this).createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val startedAt = intent?.getLongExtra(EXTRA_STARTED_AT, 0L)?.takeIf { it > 0 }
            ?: System.currentTimeMillis()
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
