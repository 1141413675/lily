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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch

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

    ArkAccountSection(form, onChange)
}

/**
 * 「火山引擎账号」：填了 AK/SK 才能查官方价格与官方用量。
 *
 * 数据面（模型调用）用 API Key 就够；管控面（查价格、查用量）只认 Access Key，
 * 所以这里是另一份凭证。Secret 只在本机加密保存。
 */
@Composable
private fun ArkAccountSection(
    form: SettingsForm.ArkForm,
    onChange: (SettingsForm.ArkForm) -> Unit,
) {
    var showSecret by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    SettingsNote(stringResource(R.string.models_ark_account_hint))

    OutlinedTextField(
        value = form.accessKeyId,
        onValueChange = { onChange(form.copy(accessKeyId = it)) },
        label = { Text(stringResource(R.string.models_ark_access_key_id)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = form.secretAccessKey,
        onValueChange = { onChange(form.copy(secretAccessKey = it)) },
        label = { Text(stringResource(R.string.models_ark_secret_access_key)) },
        visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        trailingIcon = {
            IconButton(onClick = { showSecret = !showSecret }) {
                Icon(
                    if (showSecret) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = stringResource(
                        if (showSecret) R.string.settings_hide_key else R.string.settings_show_key,
                    ),
                )
            }
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    val canRefresh = form.accessKeyId.isNotBlank() && form.secretAccessKey.isNotBlank()
    OutlinedButton(
        enabled = canRefresh && !refreshing,
        onClick = {
            refreshing = true
            message = null
            scope.launch {
                val result = runCatching { refreshOfficialPrices(form) }
                refreshing = false
                message = result.fold(
                    onSuccess = { context.getString(R.string.models_ark_price_refreshed, it) },
                    onFailure = { it.message ?: context.getString(R.string.models_ark_price_failed) },
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            if (refreshing) stringResource(R.string.models_ark_refreshing)
            else stringResource(R.string.models_ark_refresh_price),
        )
    }
    if (message != null) SettingsNote(message!!)
    if (dev.lily.app.volc.PriceBook.usingOfficial) {
        SettingsNote(
            stringResource(R.string.models_ark_price_official, dev.lily.app.volc.PriceBook.officialCount),
        )
    }
}

/**
 * 拉官方价格并覆盖价格表。
 *
 * 拉不到就保持内置快照——界面永远有价格，不会空着。
 */
private suspend fun refreshOfficialPrices(form: SettingsForm.ArkForm): Int {
    val credential = dev.lily.app.volc.VolcCredential(form.accessKeyId.trim(), form.secretAccessKey.trim())
    val client = dev.lily.app.volc.ArkControlPlane()
    // 接口按不带版本号的基础名查；把清单里的模型都算上。
    val names = (dev.lily.app.settings.ArkCatalog.chatModels + dev.lily.app.settings.ArkCatalog.imageModels)
        .map { it.id.substringBeforeLast('-') }
        .distinct()
    val prices = client.listModelPrices(credential, names)
    if (prices.isEmpty()) throw IllegalStateException("没有查到价格")
    dev.lily.app.volc.PriceBook.applyOfficial(prices)
    return prices.size
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
