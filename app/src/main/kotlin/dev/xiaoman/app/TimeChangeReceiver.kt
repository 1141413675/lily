package dev.xiaoman.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 系统时区或时间被改动：立即按新的本地时间重排墙钟闹钟，避免沿用旧时区的时间点。 */
class TimeChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_DATE_CHANGED,
            -> (context.applicationContext as? XiaomanApp)?.graph?.rearmSchedules()
        }
    }
}
