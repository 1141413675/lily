package dev.xiaoman.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallMade
import androidx.compose.material.icons.automirrored.outlined.CallMissed
import androidx.compose.material.icons.automirrored.outlined.CallReceived
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xiaoman.app.AppGraph
import dev.xiaoman.app.R
import dev.xiaoman.app.session.CallOutcome
import dev.xiaoman.app.voice.CallLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 全部通话记录：时间、方向、时长，点开看逐句往来；入口挂在「我的」里，不新增顶级导航。 */
@Composable
internal fun CallRecordsScreen(graph: AppGraph, onBack: () -> Unit, onCallBack: () -> Unit) {
    val palette = LocalXiaomanPalette.current
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val herName = settings.persona.nameOr(stringResource(R.string.app_name))
    // 进入页面时读一次：通话记录只增不改，不需要持续观察。
    val logs = remember { graph.callLogs.list() }
    var detail by remember { mutableStateOf<CallLog?>(null) }
    SettingsSubpage(stringResource(R.string.call_records_title), onBack) {
        if (logs.isEmpty()) {
            SettingsNote(stringResource(R.string.call_records_empty))
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                logs.forEach { log ->
                    CallLogRow(log, herName) { detail = log }
                }
            }
        }
    }
    detail?.let { log ->
        CallDetailDialog(
            log = log,
            herName = herName,
            player = graph.voice.player,
            onCallBack = { detail = null; onCallBack() },
            onDismiss = { detail = null },
        )
    }
}

/** 一行通话：方向图标 + 方向与结果 + 时间，整行可点，最小热区 44dp。 */
@Composable
private fun CallLogRow(log: CallLog, herName: String, onOpen: () -> Unit) {
    val palette = LocalXiaomanPalette.current
    val missed = log.outcome == CallOutcome.MISSED.name
    val tint = if (missed) palette.danger else palette.accent
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(Radius.lg),
        color = palette.surface,
        shadowElevation = Elevation.card,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.heightIn(min = SizeTokens.touchTarget).padding(horizontal = Space.md, vertical = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                Modifier.size(36.dp).clip(CircleShape).background(palette.surface2),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    when {
                        missed -> Icons.AutoMirrored.Outlined.CallMissed
                        log.incoming -> Icons.AutoMirrored.Outlined.CallReceived
                        else -> Icons.AutoMirrored.Outlined.CallMade
                    },
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(IconSize.md),
                )
            }
            Column(Modifier.weight(1f).padding(horizontal = Space.md)) {
                Text(
                    callLine(log, herName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (missed) palette.danger else palette.ink,
                )
                Text(
                    callTime(log.startedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.ink3,
                )
            }
        }
    }
}

@Composable
private fun callLine(log: CallLog, herName: String): String {
    val direction = if (log.incoming) stringResource(R.string.call_direction_in, herName) else stringResource(R.string.call_direction_out)
    val result = when (log.outcome) {
        CallOutcome.CONNECTED.name -> stringResource(R.string.call_record_connected, callDuration(log.durationMs / 1000))
        CallOutcome.MISSED.name -> stringResource(R.string.call_record_missed)
        CallOutcome.CANCELED.name -> stringResource(R.string.call_record_canceled)
        else -> stringResource(R.string.call_record_failed)
    }
    return "$direction · $result"
}

private fun callDuration(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)

private fun callTime(at: Long): String =
    SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(at))
