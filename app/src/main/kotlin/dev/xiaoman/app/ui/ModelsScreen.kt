package dev.xiaoman.app.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xiaoman.app.AppGraph
import dev.xiaoman.app.R
import dev.xiaoman.app.settings.MemoryEmbeddingSettings
import dev.xiaoman.app.settings.SettingsForm

/**
 * 「模型与接口」汇集聊天、操作、记忆向量化和语音所用的连接设置。
 */
@Composable
fun ModelsScreen(graph: AppGraph, onBack: () -> Unit) {
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
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
        val persisted = graph.settings.save(
            base.copy(
                chat = p.chat,
                operator = p.operator,
                chatVendors = p.chatVendors,
                operatorVendors = p.operatorVendors,
                asr = p.asr,
                tts = p.tts,
                realtime = p.realtime,
                realtimeEnabled = p.realtimeEnabled,
                memoryEmbedding = p.memoryEmbedding,
            ),
        )
        if (!persisted) {
            Toast.makeText(context, R.string.models_save_failed, Toast.LENGTH_SHORT).show()
            return false
        }
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = Space.xl, end = Space.page, top = Space.xl),
        ) {
            IconButton(onClick = ::leave, modifier = Modifier.size(SizeTokens.touchTarget)) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = palette.ink2,
                )
            }
            Text(stringResource(R.string.models_title), style = MaterialTheme.typography.titleLarge)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(start = Space.page, end = Space.page, top = Space.sm, bottom = Space.page),
            verticalArrangement = Arrangement.spacedBy(Space.block),
        ) {
            SettingsNote(stringResource(R.string.models_subtitle))
            SettingsGroup(stringResource(R.string.settings_chat_model)) {
                KeyHint(stringResource(R.string.models_vendor_key_hint))
                ModelSection(
                    model = form.chat,
                    onChange = {
                        form = form.copy(chat = it)
                        saved = false
                    },
                    onVendor = {
                        form = form.withChatVendor(it)
                        saved = false
                    },
                )
            }

            SettingsGroup(stringResource(R.string.settings_operator_model)) {
                KeyHint(stringResource(R.string.models_vendor_key_hint))
                ModelSection(
                    model = form.operator,
                    onChange = {
                        form = form.copy(operator = it)
                        saved = false
                    },
                    onVendor = {
                        form = form.withOperatorVendor(it)
                        saved = false
                    },
                )
                TextButton(
                    onClick = {
                        // 操作模型沿用聊天模型的厂商与 Key，只把模型名换成视觉模型。
                        form = form.copy(
                            operator = form.operator.copy(
                                baseUrl = form.chat.baseUrl,
                                apiKey = form.chat.apiKey,
                                vendor = form.chat.vendor,
                                protocol = form.chat.protocol,
                            ),
                        )
                        saved = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = palette.ink2),
                ) { Text(stringResource(R.string.settings_copy_chat)) }
            }

            SettingsGroup(stringResource(R.string.models_embedding_title)) {
                MemoryEmbeddingSection(form.memoryEmbedding) {
                    form = form.copy(memoryEmbedding = it)
                    saved = false
                }
            }

            SettingsGroup(stringResource(R.string.settings_asr)) {
                KeyHint(stringResource(R.string.models_key_siliconflow))
                VoiceSection(form.asr, showVoice = false) {
                    form = form.copy(asr = it)
                    saved = false
                }
            }

            SettingsGroup(stringResource(R.string.settings_tts)) {
                SettingsNote(stringResource(R.string.models_tts_provider_hint))
                TtsSection(form.tts) {
                    form = form.copy(tts = it)
                    saved = false
                }
                SettingsNote(stringResource(R.string.models_voice_hint))
            }

            SettingsGroup(stringResource(R.string.settings_realtime)) {
                SettingsRow(
                    title = stringResource(R.string.models_realtime_switch),
                    trailing = {
                        XiaomanSwitch(
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
        }
        Surface(color = palette.surface) {
            Column {
                HorizontalDivider(color = palette.line)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = Space.sm),
                    horizontalArrangement = Arrangement.spacedBy(Space.lg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(enabled = parsed != null, onClick = { save() }) {
                        Text(stringResource(R.string.settings_save))
                    }
                    if (saved) {
                        SettingsValue(
                            stringResource(R.string.settings_saved),
                            Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
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

@Composable
private fun MemoryEmbeddingSection(
    value: MemoryEmbeddingSettings,
    onChange: (MemoryEmbeddingSettings) -> Unit,
) {
    SettingsRow(
        title = stringResource(R.string.models_embedding_switch),
        trailing = {
            XiaomanSwitch(
                checked = value.enabled,
                onCheckedChange = { onChange(value.copy(enabled = it)) },
            )
        },
    )
    SettingsNote(stringResource(R.string.models_embedding_disclosure))
    if (!value.enabled) return

    var showKey by remember { mutableStateOf(false) }
    val normalized = value.normalized()
    val urlError = value.baseUrl.isNotBlank() && normalized.requestUrl == null
    val modelError = value.model.isNotBlank() && !normalized.modelValid
    val keyError = !normalized.keyValid
    OutlinedTextField(
        value = value.baseUrl,
        onValueChange = { if (it.length <= MemoryEmbeddingSettings.MAX_BASE_URL) onChange(value.copy(baseUrl = it)) },
        label = { Text(stringResource(R.string.models_embedding_base_url)) },
        placeholder = { Text("https://api.example.com/v1") },
        supportingText = { Text(stringResource(R.string.models_embedding_base_hint)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        isError = urlError,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = value.model,
        onValueChange = { if (it.length <= MemoryEmbeddingSettings.MAX_MODEL) onChange(value.copy(model = it)) },
        label = { Text(stringResource(R.string.models_embedding_model)) },
        isError = modelError,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = value.apiKey,
        onValueChange = { if (it.length <= MemoryEmbeddingSettings.MAX_API_KEY) onChange(value.copy(apiKey = it)) },
        label = { Text(stringResource(R.string.models_embedding_api_key)) },
        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        trailingIcon = {
            IconButton(onClick = { showKey = !showKey }) {
                Icon(
                    if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = stringResource(if (showKey) R.string.settings_hide_key else R.string.settings_show_key),
                )
            }
        },
        supportingText = if (keyError) ({ Text(stringResource(R.string.models_embedding_key_invalid)) }) else null,
        isError = keyError,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    if (!normalized.configured) SettingsNote(stringResource(R.string.models_embedding_incomplete))
}

/** 每张模型卡上方的 Key 归属提示：填之前就知道该用哪家的 Key。 */
@Composable
private fun KeyHint(text: String) = SettingsNote(text)
