package dev.lily.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.lily.app.R
import dev.lily.app.settings.ArkCatalog
import dev.lily.app.settings.ArkModel
import dev.lily.app.settings.ArkModality
import dev.lily.app.settings.SettingsForm

/**
 * 「火山方舟一体」面板：一个 Key，多项能力。
 *
 * 结构是：先填 Key，再逐项打开要接管的开关，打开的那几项各自挑一个模型，
 * 模型下面直接写着官方价格——选之前就知道大概要花多少钱。
 */
@Composable
internal fun ArkSection(
    form: SettingsForm.ArkForm,
    onChange: (SettingsForm.ArkForm) -> Unit,
) {
    var showKey by remember { mutableStateOf(false) }
    val hasKey = form.apiKey.isNotBlank()
    val arkOnly = form.apiKey.trim().startsWith("ark-", ignoreCase = true)

    SettingsNote(stringResource(R.string.models_ark_hint))

    OutlinedTextField(
        value = form.apiKey,
        onValueChange = { onChange(form.copy(apiKey = it)) },
        label = { Text(stringResource(R.string.models_ark_key)) },
        placeholder = { Text("ark-…") },
        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        trailingIcon = {
            IconButton(onClick = { showKey = !showKey }) {
                Icon(
                    if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = stringResource(
                        if (showKey) R.string.settings_hide_key else R.string.settings_show_key,
                    ),
                )
            }
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    SettingsNote(stringResource(R.string.models_ark_key_hint))

    if (arkOnly && form.useVoice) {
        SettingsNote(stringResource(R.string.models_ark_key_ark_only))
    }

    if (!hasKey) return

    ArkCapabilityRow(
        title = stringResource(R.string.models_ark_use_chat),
        on = form.useChat,
    ) { onChange(form.copy(useChat = it)) }

    ArkCapabilityRow(
        title = stringResource(R.string.models_ark_use_operator),
        on = form.useOperator,
    ) { onChange(form.copy(useOperator = it)) }

    ArkCapabilityRow(
        title = stringResource(R.string.models_ark_use_image),
        on = form.useImage,
    ) { onChange(form.copy(useImage = it)) }

    ArkCapabilityRow(
        title = stringResource(R.string.models_ark_use_voice),
        on = form.useVoice,
    ) { onChange(form.copy(useVoice = it)) }
    if (form.useVoice) SettingsNote(stringResource(R.string.models_ark_use_voice_hint))

    if (form.useChat) {
        ArkModelField(
            label = stringResource(R.string.models_ark_model_chat),
            value = form.chatModel,
            modality = ArkModality.CHAT,
            onChange = { onChange(form.copy(chatModel = it)) },
        )
    }
    if (form.useOperator) {
        ArkModelField(
            label = stringResource(R.string.models_ark_model_operator),
            value = form.operatorModel,
            modality = ArkModality.CHAT,
            onChange = { onChange(form.copy(operatorModel = it)) },
        )
    }
    if (form.useImage) {
        ArkModelField(
            label = stringResource(R.string.models_ark_model_image),
            value = form.imageModel,
            modality = ArkModality.IMAGE,
            onChange = { onChange(form.copy(imageModel = it)) },
        )
    }
}

/** 一行能力开关。 */
@Composable
private fun ArkCapabilityRow(
    title: String,
    on: Boolean,
    onChange: (Boolean) -> Unit,
) {
    SettingsRow(
        title = title,
        trailing = {
            LilySwitch(checked = on, onCheckedChange = onChange)
        },
    )
}

/**
 * 模型选择：清单下拉 + 可以直接改字。
 *
 * 下拉里每条都带价格；选完以后下面再写一遍价格与一轮对话的估算，
 * 让「换模型要多花多少钱」这件事在点保存之前就看得见。
 */
@Composable
private fun ArkModelField(
    label: String,
    value: String,
    modality: ArkModality,
    onChange: (String) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val choices = remember(modality) {
        if (modality == ArkModality.CHAT) ArkCatalog.chatModels else ArkCatalog.imageModels
    }
    val selected = ArkCatalog.byId(value)

    FieldLabel(label) {
        Box {
            OutlinedTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = stringResource(R.string.models_pick_model))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                choices.forEach { model ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(model.displayName)
                                Text(
                                    model.priceText,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        },
                        onClick = {
                            menu = false
                            onChange(model.id)
                        },
                    )
                }
            }
        }
    }

    if (selected != null) {
        SettingsNote(selected.priceText)
        if (selected.note.isNotBlank()) SettingsNote(selected.note)
        if (selected.modality == ArkModality.CHAT) {
            val cost = selected.estimate(
                ArkModel.TYPICAL_INPUT_TOKENS,
                ArkModel.TYPICAL_OUTPUT_TOKENS,
            )
            SettingsNote(stringResource(R.string.models_ark_estimate, cost))
        }
    } else {
        SettingsNote(stringResource(R.string.models_ark_price_unknown))
    }
}
