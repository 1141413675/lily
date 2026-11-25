package dev.xiaoman.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.xiaoman.app.R
import dev.xiaoman.app.session.CallOutcome
import dev.xiaoman.app.voice.CallLog
import androidx.compose.ui.res.stringResource
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一条通话记录点开的详情：方向、状态、时长、时间、没接的原因，以及这通电话的逐句往来。
 * 用户自己的句子保留原始录音，可以在这里回放；数据来自独立的通话明细，不在聊天列表里出现。
 */
@Composable
internal fun CallDetailDialog(
    log: CallLog,
    herName: String,
    player: dev.xiaoman.app.voice.AudioPlayer,
    onCallBack: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalXiaomanPalette.current
    val outcome = log.outcome
    val playingId by player.playingId.collectAsState()

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(Radius.lg),
            color = palette.surface,
            shadowElevation = Elevation.floating,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.lg).heightIn(max = 640.dp),
        ) {
            Column(Modifier.padding(top = Space.sm)) {
                Row(
                    Modifier.fillMaxWidth().padding(start = Space.page, end = Space.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.call_detail_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = palette.ink,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(SizeTokens.touchTarget)) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.call_detail_close), tint = palette.ink3)
                    }
                }
                Column(Modifier.padding(horizontal = Space.page, vertical = Space.md)) {
                    Text(
                        when (outcome) {
                            CallOutcome.CONNECTED.name -> stringResource(R.string.call_record_connected, duration(log.durationMs / 1000))
                            CallOutcome.MISSED.name -> stringResource(R.string.call_record_missed)
                            CallOutcome.CANCELED.name -> stringResource(R.string.call_record_canceled)
                            else -> stringResource(R.string.call_record_failed)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = if (outcome == CallOutcome.MISSED.name) palette.danger else palette.ink,
                    )
                    Text(
                        (if (log.incoming) {
                            stringResource(R.string.call_direction_in, herName)
                        } else {
                            stringResource(R.string.call_direction_out)
                        }) + " · " + time(log.startedAt),
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.ink3,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    log.reason?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.ink2,
                            modifier = Modifier.padding(top = Space.sm),
                        )
                    }
                }
                HorizontalDivider(color = palette.line)
                val turns = log.turns
                if (turns.isEmpty()) {
                    Text(
                        stringResource(
                            if (outcome == CallOutcome.MISSED.name) R.string.call_detail_missed_empty else R.string.call_detail_empty,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.ink3,
                        modifier = Modifier.padding(horizontal = Space.page, vertical = Space.lg),
                    )
                } else {
                    LazyColumn(
                        Modifier.weight(1f, fill = false),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = Space.page, end = Space.page, top = Space.lg, bottom = Space.lg,
                        ),
                        verticalArrangement = Arrangement.spacedBy(Space.lg),
                    ) {
                        itemsIndexed(turns) { index, turn ->
                            val id = playbackId(log, index)
                            val playing = playingId == id
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        if (turn.fromMe) stringResource(R.string.call_detail_me) else herName,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (turn.fromMe) palette.ink2 else palette.accent,
                                    )
                                    Text(
                                        " · ${time(turn.at, withDate = false)}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = palette.ink3,
                                    )
                                    if (turn.audio != null) {
                                        val audio = File(turn.audio)
                                        IconButton(
                                            onClick = { player.toggle(id, audio) },
                                            modifier = Modifier.size(SizeTokens.touchTarget),
                                            enabled = audio.isFile,
                                        ) {
                                            Icon(
                                                if (playing) Icons.Outlined.Stop else Icons.Filled.PlayArrow,
                                                contentDescription = stringResource(
                                                    if (playing) R.string.call_detail_stop else R.string.call_detail_play,
                                                ),
                                                tint = palette.accent,
                                                modifier = Modifier.size(IconSize.md),
                                            )
                                        }
                                    }
                                }
                                Text(
                                    turn.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = palette.ink,
                                    modifier = Modifier.padding(top = Space.xs),
                                )
                            }
                        }
                    }
                }
                HorizontalDivider(color = palette.line)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = Space.sm),
                    horizontalArrangement = Arrangement.spacedBy(Space.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = onCallBack, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.Call, contentDescription = null, modifier = Modifier.size(IconSize.md))
                        Text(
                            stringResource(R.string.call_record_call_back),
                            modifier = Modifier.padding(start = Space.xs),
                        )
                    }
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.call_detail_close))
                    }
                }
            }
        }
    }
}

private fun playbackId(log: CallLog, index: Int): Long = -log.startedAt - index

private fun duration(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)

private fun time(at: Long, withDate: Boolean = true): String =
    SimpleDateFormat(if (withDate) "M月d日 HH:mm" else "HH:mm", Locale.CHINA).format(Date(at))
