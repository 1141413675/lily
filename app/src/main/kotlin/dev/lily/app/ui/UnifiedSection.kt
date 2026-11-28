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
import androidx.compose.material3.FilterChip
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
import dev.lily.app.settings.ModelVendor
import dev.lily.app.settings.ProvisioningMode
import dev.lily.app.settings.SettingsForm
import dev.lily.app.volc.ArkControlPlane
import dev.lily.app.volc.PriceBook
import dev.lily.app.volc.VolcCredential
import kotlinx.coroutines.launch

/**
 * 「模型接法」：一体化还是自定义。
 *
 * - **一体化**：挑一个服务方，一个 Key 打通它的全部模型。适合火山方舟这种
 *   「一个账号什么都有」的服务。
 * - **自定义**：语言、语音、通话各挑各的厂家。适合各家各有优势的情况。
 */
@Composable
internal fun ProvisioningSection(
    mode: ProvisioningMode,
    onMode: (ProvisioningMode) -> Unit,
) {
    SettingsNote(stringResource(R.string.models_provisioning_hint))
    Row(
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
        modifier = Modifier.fillMaxWidth(),
    ) {
        FilterChip(
            selected = mode == ProvisioningMode.UNIFIED,
            onClick = { onMode(ProvisioningMode.UNIFIED) },
            label = { Text(stringResource(R.string.models_provisioning_unified)) },
        )
        FilterChip(
            selected = mode == ProvisioningMode.CUSTOM,
            onClick = { onMode(ProvisioningMode.CUSTOM) },
            label = { Text(stringResource(R.string.models_provisioning_custom)) },
        )
    }
    SettingsNote(
        stringResource(
            if (mode == ProvisioningMode.UNIFIED) {
                R.string.models_provisioning_unified_hint
            } else {
                R.string.models_provisioning_custom_hint
            },
        ),
    )
}

/**
 * 一体化面板：一个服务方、一个 Key、几个模型。
 *
 * 结构是：先选服务方，填它的 Key，再挑模型——模型下面直接写着价格，
 * 选之前就知道大概要花多少钱。
 */
@Composable
internal fun UnifiedSection(
    form: SettingsForm.UnifiedForm,
    onChange: (SettingsForm.UnifiedForm) -> Unit,
) {
    var vendorMenu by remember { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(false) }
    val provider = form.toSettings()

    FieldLabel(stringResource(R.string.models_unified_vendor)) {
        OutlinedButton(onClick = { vendorMenu = true }, modifier = Modifier.fillMaxWidth()) {
            Text(form.vendor.displayName, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = stringResource(R.string.models_pick_vendor))
        }
    }
    if (vendorMenu) {
        VendorPickerSheet(
            current = form.vendor,
            onPick = { onChange(form.copy(vendor = it)) },
            onDismiss = { vendorMenu = false },
        )
    }

    OutlinedTextField(
        value = form.apiKey,
        onValueChange = { onChange(form.copy(apiKey = it)) },
        label = { Text(stringResource(R.string.models_unified_api_key)) },
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
    SettingsNote(stringResource(R.string.models_unified_key_hint))

    if (provider.isVolcano) {
        SettingsNote(stringResource(R.string.models_ark_key_hint))
        if (provider.looksLikeArkOnlyKey) {
            SettingsNote(stringResource(R.string.models_ark_key_ark_only))
        }
    }

    UnifiedModelField(
        label = stringResource(R.string.models_ark_model_chat),
        hint = stringResource(R.string.models_ark_model_chat_hint),
        value = form.chatModel,
        vendor = form.vendor,
        onChange = { onChange(form.copy(chatModel = it)) },
    )
    UnifiedModelField(
        label = stringResource(R.string.models_ark_model_operator),
        hint = stringResource(R.string.models_ark_model_operator_hint),
        value = form.operatorModel,
        vendor = form.vendor,
        requireVision = true,
        onChange = { onChange(form.copy(operatorModel = it)) },
    )
    if (form.vendor == ModelVendor.ARK) {
        UnifiedModelField(
            label = stringResource(R.string.models_ark_model_image),
            value = form.imageModel,
            vendor = form.vendor,
            imageOnly = true,
            onChange = { onChange(form.copy(imageModel = it)) },
        )
        // 语音与识别也归方舟：豆包语音的模型在这一起挑。
        UnifiedVoiceModelField(
            label = stringResource(R.string.models_ark_model_tts),
            value = form.ttsModel,
            choices = dev.lily.app.settings.VoiceVendor.DOUBAO.ttsModels,
            onChange = { onChange(form.copy(ttsModel = it)) },
        )
        UnifiedVoiceModelField(
            label = stringResource(R.string.models_ark_model_asr),
            value = form.asrModel,
            choices = dev.lily.app.settings.VoiceVendor.DOUBAO.asrModels,
            onChange = { onChange(form.copy(asrModel = it)) },
        )
    } else {
        SettingsNote(stringResource(R.string.models_unified_no_image_hint))
    }

    if (form.vendor == ModelVendor.ARK) {
        ArkAccountSection(form, onChange)
    }
}

/**
 * 火山引擎账号：填了 AK/SK 才能查官方价格与官方用量。
 *
 * 数据面（模型调用）用 API Key 就够；管控面（查价格、查用量）只认 Access Key，
 * 所以这里是另一份凭证。Secret 只在本机加密保存。
 */
@Composable
private fun ArkAccountSection(
    form: SettingsForm.UnifiedForm,
    onChange: (SettingsForm.UnifiedForm) -> Unit,
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
    if (PriceBook.usingOfficial) {
        SettingsNote(stringResource(R.string.models_ark_price_official, PriceBook.officialCount))
    }
}

/**
 * 语音模型的选择。
 *
 * 豆包语音的模型不多，直接列出即可；没选就用那家的默认。
 */
@Composable
private fun UnifiedVoiceModelField(
    label: String,
    value: String,
    choices: List<String>,
    onChange: (String) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    FieldLabel(label) {
        Box {
            OutlinedTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = { menu = true }, enabled = choices.isNotEmpty()) {
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = stringResource(R.string.models_pick_model))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                choices.forEach { id ->
                    DropdownMenuItem(
                        text = { Text(id) },
                        onClick = {
                            menu = false
                            onChange(id)
                        },
                    )
                }
            }
        }
    }
}

/**
 * 拉官方价格并覆盖价格表。
 *
 * 拉不到就保持内置快照——界面永远有价格，不会空着。
 */
private suspend fun refreshOfficialPrices(form: SettingsForm.UnifiedForm): Int {
    val credential = VolcCredential(form.accessKeyId.trim(), form.secretAccessKey.trim())
    val client = ArkControlPlane()
    // 接口按不带版本号的基础名查；把清单里的模型都算上。
    val names = (ArkCatalog.chatModels + ArkCatalog.imageModels)
        .map { it.id.substringBeforeLast('-') }
        .distinct()
    val prices = client.listModelPrices(credential, names)
    if (prices.isEmpty()) throw IllegalStateException("没有查到价格")
    PriceBook.applyOfficial(prices)
    return prices.size
}

/**
 * 模型选择：清单下拉 + 可以直接改字。
 *
 * 下拉里每条都带价格（只有方舟的模型有价格）；选完以后下面再写一遍价格与
 * 一轮对话的估算，让「换模型要多花多少钱」在点保存之前就看得见。
 */
@Composable
private fun UnifiedModelField(
    label: String,
    value: String,
    vendor: ModelVendor,
    hint: String = "",
    imageOnly: Boolean = false,
    requireVision: Boolean = false,
    onChange: (String) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val choices = remember(vendor, imageOnly) {
        if (vendor == ModelVendor.ARK) {
            if (imageOnly) ArkCatalog.imageModels.map { it.id } else ArkCatalog.chatModels.map { it.id }
        } else {
            vendor.models
        }
    }
    val selected = ArkCatalog.byId(value)

    FieldLabel(label) {
        Box {
            OutlinedTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = { menu = true }, enabled = choices.isNotEmpty()) {
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = stringResource(R.string.models_pick_model))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                choices.forEach { id ->
                    val model = ArkCatalog.byId(id)
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(model?.displayName ?: id)
                                if (model != null) {
                                    Text(model.priceText, style = MaterialTheme.typography.bodySmall)
                                    if (!model.vision) {
                                        Text(
                                            stringResource(R.string.models_ark_text_only),
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                }
                            }
                        },
                        onClick = {
                            menu = false
                            onChange(id)
                        },
                    )
                }
            }
        }
    }
    if (hint.isNotBlank()) SettingsNote(hint)

    if (selected != null) {
        SettingsNote(selected.priceText)
        if (selected.note.isNotBlank()) SettingsNote(selected.note)
        val cost = selected.estimate(
            dev.lily.app.settings.ArkModel.TYPICAL_INPUT_TOKENS,
            dev.lily.app.settings.ArkModel.TYPICAL_OUTPUT_TOKENS,
        )
        SettingsNote(stringResource(R.string.models_ark_estimate, cost))
        // 操作模型必须能看图，否则它看不清屏幕，操作会一直失败。
        if (requireVision && !selected.vision) {
            SettingsNote(stringResource(R.string.models_ark_needs_vision))
        }
    } else if (value.isNotBlank()) {
        SettingsNote(stringResource(R.string.models_ark_price_unknown))
    }
}
