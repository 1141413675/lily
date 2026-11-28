package dev.lily.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.lily.app.LilyApp
import kotlinx.coroutines.launch

/**
 * Debug-build bridge for the `xiaoman` CLI:
 * `adb shell am broadcast -n dev.lily.app/.debug.DebugReceiver -a dev.lily.debug.RUN --es goal "打开设置" --es run_id r1`.
 *
 * RUN hands the task to the agent session (the foreground [dev.lily.app.AgentService] is
 * started for it); approvals and questions still go to the user through the app. Every RUN ends
 * with a `Failed` or `PhoneTaskFinished` logcat line for its run id.
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = (context.applicationContext as LilyApp).graph
        val command = DebugCommand.parse(intent.action, intent::getStringExtra, graph.runIds::generate)
        when (command) {
            null -> Log.w(TAG, "debug: unknown action ${intent.action}")
            is DebugCommand.Run -> {
                Log.i(TAG, "debug: run ${command.runId} skill=${command.skill ?: "-"}")
                graph.session.runPhoneTask(command.goal, command.skill, command.runId)
            }
            is DebugCommand.InvalidRun -> {
                Log.w(TAG, "debug: run rejected: ${command.message}")
                graph.session.fail(command.runId, command.message)
            }
            DebugCommand.Stop -> {
                Log.i(TAG, "debug: stop")
                graph.session.stop()
            }
            is DebugCommand.Say -> {
                Log.i(TAG, "debug: say")
                android.os.Handler(android.os.Looper.getMainLooper()).post { Log.i(TAG, "debug: say -> ${graph.session.submit(command.text)}") }
            }
            DebugCommand.CallNotification -> {
                // 走真实的通话服务发通知：系统的 CallStyle 检查要求通知来自前台服务，
                // 直接 notify 会被判为「不是通话通知」而拒收。
                val startedAt = System.currentTimeMillis()
                try {
                    androidx.core.content.ContextCompat.startForegroundService(
                        context,
                        android.content.Intent(context, dev.lily.app.voice.CallService::class.java)
                            .putExtra(dev.lily.app.voice.CallService.EXTRA_STARTED_AT, startedAt),
                    )
                    Log.i(TAG, "debug: call notification requested at $startedAt")
                } catch (e: Exception) {
                    Log.w(TAG, "debug: call notification failed: ${e.message}")
                }
                val manager = context.getSystemService(android.app.NotificationManager::class.java)
                val allowed = runCatching {
                    manager.javaClass.getMethod("canPostPromotedNotifications").invoke(manager) as? Boolean
                }.getOrNull()
                Log.i(TAG, "debug: canPostPromotedNotifications=$allowed")
            }
            DebugCommand.ReloadSkills -> {
                val pending = goAsync()
                graph.scope.launch {
                    try {
                        graph.startup.await()
                        val result = graph.skills.reload()
                        graph.logSkillProblems(result.skills.size, result.problems)
                    } catch (e: Exception) {
                        Log.w(TAG, "reload-skills: failed: ${e.message}")
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "Lily"
    }
}
