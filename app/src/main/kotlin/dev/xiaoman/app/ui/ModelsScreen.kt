package dev.xiaoman.app.ui

import androidx.activity.compose.BackHandler
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xiaoman.app.AppGraph
import dev.xiaoman.app.R
import dev.xiaoman.app.settings.SettingsForm

/**
 * 「模型与接口」：聊天、操作、语音识别、语音朗读、实时通话的地址/模型名/Key 全收在这一页，
 * 不再散落在人格页与设置主页。性格与音色仍留在人格页。
 */
@Composable
fun ModelsScreen(graph: AppGraph, onBack: () -> Unit) {
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    // 已保存的基准值：每轮的输入都与它逐字段比较，改了没存就离开一定拦得住。
    val baseline = remember(settings) { SettingsForm.of(settings) }
    var form by remember { mutableStateOf(baseline) }
    var saved by remember { mutableStateOf(false) }
    var askSave by remember { mutableStateOf(false) }
    val parsed = form.toSettings()
    val dirty = form != baseline
    val palette = LocalXiaomanPalette.current

    fun save(): Boolean {
        val p = parsed ?: return false
        val base = graph.settings.current
        graph.settings.save(
            base.copy(
                chat = p.chat,
                operator = p.operator,
                asr = p.asr,
                tts = p.tts,
                realtime = p.realtime,
            ),
        )
        // 保存后按规范化结果刷新表单，避免紧接着又被判定为「有未保存改动」。
        form = SettingsForm.of(graph.settings.current)
        saved = true
        return true
    }

    // 返回箭头与系统返回共用同一条离开逻辑：有未保存改动就先问。
    fun leave() {
        if (dirty) askSave = true else onBack()
    }

    BackHandler(onBack = ::leave)

    Column(Modifier.fillMaxSize()) {
        // 返回与标题固定，滚到底部仍能返回。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = Space.page, end = Space.page, top = Space.xs, bottom = Space.sm),
        ) {
            IconButton(onClick = ::leave, modifier = Modifier.size(SizeTokens.touchTarget)) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = palette.ink2,
                )
            }
            Column {
                Text(stringResource(R.string.models_title), style = MaterialTheme.typography.titleLarge)
                SettingsNote(stringResource(R.string.models_subtitle))
            }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(start = Space.page, end = Space.page, bottom = Space.page),
            verticalArrangement = Arrangement.spacedBy(Space.block),
        ) {
        SettingsGroup(stringResource(R.string.settings_chat_model)) {
            KeyHint(stringResource(R.string.models_key_ark))
            ModelSection(form.chat) {
                form = form.copy(chat = it)
                saved = false
            }
        }

        SettingsGroup(stringResource(R.string.settings_operator_model)) {
            KeyHint(stringResource(R.string.models_key_ark))
            ModelSection(form.operator) {
                form = form.copy(operator = it)
                saved = false
            }
            TextButton(
                onClick = {
                    form = form.copy(operator = form.operator.copy(baseUrl = form.chat.baseUrl, apiKey = form.chat.apiKey))
                    saved = false
                },
                colors = ButtonDefaults.textButtonColors(contentColor = palette.ink2),
            ) { Text(stringResource(R.string.settings_copy_chat)) }
        }

        SettingsGroup(stringResource(R.string.settings_asr)) {
            KeyHint(stringResource(R.string.models_key_siliconflow))
            VoiceSection(form.asr, showVoice = false) {
                form = form.copy(asr = it)
                saved = false
            }
        }

        SettingsGroup(stringResource(R.string.settings_tts)) {
            KeyHint(stringResource(R.string.models_key_siliconflow))
            VoiceSection(form.tts, showVoice = false) {
                form = form.copy(tts = it)
                saved = false
            }
            SettingsNote(stringResource(R.string.models_voice_hint))
        }

        SettingsGroup(stringResource(R.string.settings_realtime)) {
            SettingsRow(
                title = stringResource(R.string.models_realtime_switch),
                trailing = {
                    Switch(
                        checked = form.realtimeEnabled,
                        onCheckedChange = {
                            form = form.copy(realtimeEnabled = it)
                            saved = false
                        },
                    )
                },
            )
            KeyHint(stringResource(R.string.models_key_dashscope))
            VoiceSection(form.realtime, showVoice = true) {
                form = form.copy(realtime = it)
                saved = false
            }
            SettingsNote(stringResource(R.string.settings_realtime_hint))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(enabled = parsed != null, onClick = { save() }) { Text(stringResource(R.string.settings_save)) }
            if (saved) SettingsValue(stringResource(R.string.settings_saved), Modifier.padding(start = Space.lg))
        }
        }
    }

    if (askSave) {
        AlertDialog(
            onDismissRequest = { askSave = false },
            title = { Text(stringResource(R.string.models_unsaved_title)) },
            text = { Text(stringResource(R.string.models_unsaved_body)) },
            confirmButton = {
                TextButton(
                    enabled = parsed != null,
                    onClick = { if (save()) { askSave = false; onBack() } },
                ) { Text(stringResource(R.string.models_unsaved_save)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { askSave = false; onBack() }) {
                        Text(stringResource(R.string.models_unsaved_discard))
                    }
                    TextButton(onClick = { askSave = false }) {
                        Text(stringResource(R.string.models_unsaved_stay))
                    }
                }
            },
        )
    }
}

/** 每张模型卡上方的 Key 归属提示：填之前就知道该用哪家的 Key。 */
@Composable
private fun KeyHint(text: String) = SettingsNote(text)
