package dev.lily.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lily.app.AppGraph
import dev.lily.app.R
import dev.lily.app.session.CallOutcome
import dev.lily.app.voice.CallLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 当前角色的通话记录：时间、方向、时长，点开看逐句往来。 */
@Composable
internal fun CallRecordsScreen(
    conversation: AppGraph.Conversation,
    onBack: () -> Unit,
    onCallBack: () -> Unit,
) {
    val palette = LocalLilyPalette.current
    val settings by conversation.effectiveSettings.collectAsStateWithLifecycle()
    val herName = settings.persona.nameOr(stringResource(R.string.app_name))
    // 进入页面时读一次：通话记录只增不改，不需要持续观察。
    val logs = remember(conversation.id) { conversation.callLogs.list() }
    val groups = remember(logs) { logs.groupBy { callDate(it.startedAt) } }
    var detail by remember(conversation.id) { mutableStateOf<CallLog?>(null) }
    SettingsSubpage(stringResource(R.string.call_records_title), onBack) {
        if (logs.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = Space.block),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                Box(Modifier.size(48.dp).background(palette.accentSoft, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Call, contentDescription = null, tint = palette.accentOnSoft)
                }
                Text(
                    stringResource(R.string.call_records_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.ink2,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            groups.forEach { (date, calls) ->
                Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Text(
                        date,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.ink2,
                        modifier = Modifier.padding(start = Space.sm),
                    )
                    Surface(shape = RoundedCornerShape(Radius.lg), color = palette.surface) {
                        Column {
                            calls.forEachIndexed { index, log ->
                                if (index > 0) HorizontalDivider(Modifier.padding(start = 58.dp), color = palette.line)
                                CallLogRow(log, herName) { detail = log }
                            }
                        }
                    }
                }
            }
        }
    }
    detail?.let { log ->
        CallDetailDialog(
            log = log,
            herName = herName,
            player = conversation.voice.player,
            onCallBack = { detail = null; onCallBack() },
            onDismiss = { detail = null },
        )
    }
}

/** 一行通话：方向图标 + 方向与结果 + 时间，整行可点，最小热区 44dp。 */
@Composable
private fun CallLogRow(log: CallLog, herName: String, onOpen: () -> Unit) {
    val palette = LocalLilyPalette.current
    val missed = log.outcome == CallOutcome.MISSED.name
    val tint = if (missed) palette.danger else palette.accent
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onOpen)
            .padding(horizontal = Space.md, vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(palette.accentSoft),
            contentAlignment = Alignment.Center,
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
            Text(callTime(log.startedAt), style = CaptionTextStyle, color = palette.ink2)
        }
        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = palette.ink3, modifier = Modifier.size(IconSize.md))
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

private fun callDate(at: Long): String =
    SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(Date(at))

private fun callTime(at: Long): String =
    SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(at))
