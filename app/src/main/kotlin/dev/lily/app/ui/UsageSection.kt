package dev.lily.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lily.app.R
import dev.lily.app.usage.UsageStore
import dev.lily.app.usage.estimateCost
import kotlinx.coroutines.launch

/**
 * 「用量与花费」：这个 App 花了多少。
 *
 * 数据来自本机记账——每次模型调用返回的 `usage` 都被记了一笔，
 * 按天、按模型分开。只有本机有这份数据，不上传。
 *
 * 花费按价格表估算。只有价格表里有的模型才算得出钱；算不出来的只显示 token，
 * 不给一个看着像那么回事的错数字。
 */
@Composable
internal fun UsageSection(
    store: UsageStore,
    ark: dev.lily.app.settings.ArkSettings,
) {
    val revision by store.revision.collectAsStateWithLifecycle()
    val today = remember(revision) { store.today() }
    val month = remember(revision) { store.thisMonth() }
    val monthTotal = remember(month) { store.totalOf(month) }
    var expanded by remember { mutableStateOf(false) }

    SettingsNote(stringResource(R.string.models_usage_hint))

    UsageRow(
        title = stringResource(R.string.models_usage_today),
        value = costText(estimateCost(today.byModel)),
        detail = tokenText(today.inputTokens, today.outputTokens, today.requests),
    )
    UsageRow(
        title = stringResource(R.string.models_usage_month),
        value = costText(estimateCost(month.fold(emptyMap()) { acc, day -> acc + day.byModel })),
        detail = tokenText(monthTotal.inputTokens, monthTotal.outputTokens, monthTotal.requests),
    )

    val ranked = month.flatMap { it.ranked() }
        .groupBy({ it.first }, { it.second })
        .map { (model, entries) ->
            model to UsageStore.Entry(
                inputTokens = entries.sumOf { it.inputTokens },
                outputTokens = entries.sumOf { it.outputTokens },
                requests = entries.sumOf { it.requests },
            )
        }
        .sortedByDescending { it.second.totalTokens }

    if (ranked.isNotEmpty()) {
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.padding(top = 2.dp),
        ) {
            Text(
                if (expanded) stringResource(R.string.models_usage_hide_models)
                else stringResource(R.string.models_usage_by_model),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (expanded) {
            ranked.take(MAX_MODELS_SHOWN).forEach { (model, entry) ->
                UsageRow(
                    title = model,
                    value = costText(estimateCost(model, entry)),
                    detail = tokenText(entry.inputTokens, entry.outputTokens, entry.requests),
                )
            }
        }
    } else {
        SettingsNote(stringResource(R.string.models_usage_empty))
    }

    OfficialUsageBlock(ark)
}

/**
 * 官方账单口径的用量。
 *
 * 本地那份只算这个 App 花了多少；官方那份是整个账号的用量，
 * 包括别的程序调的。两者对不上很正常——官方那份还含缓存命中的折扣价。
 */
@Composable
private fun OfficialUsageBlock(ark: dev.lily.app.settings.ArkSettings) {
    if (!ark.hasAccount) return
    var usage by remember { mutableStateOf<dev.lily.app.volc.OfficialUsage?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    HorizontalDivider(color = LocalLilyPalette.current.line, modifier = Modifier.padding(vertical = 6.dp))
    SettingsNote(stringResource(R.string.models_usage_official_hint))

    TextButton(
        enabled = !loading,
        onClick = {
            loading = true
            error = null
            scope.launch {
                val result = runCatching {
                    dev.lily.app.volc.ArkControlPlane().inferenceUsage(
                        credential = dev.lily.app.volc.VolcCredential(
                            ark.accessKeyId.trim(),
                            ark.secretAccessKey.trim(),
                        ),
                        startDate = startOfMonth(),
                        endDate = dev.lily.app.usage.UsageStore.dateOf(System.currentTimeMillis()),
                    )
                }
                loading = false
                result.fold(
                    onSuccess = { usage = it; error = null },
                    onFailure = { error = it.message ?: context.getString(R.string.models_usage_official_failed) },
                )
            }
        },
    ) {
        Text(
            if (loading) stringResource(R.string.models_usage_official_loading)
            else stringResource(R.string.models_usage_official_query),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }

    val current = usage
    if (current != null) {
        UsageRow(
            title = stringResource(R.string.models_usage_official_title),
            value = "",
            detail = tokenText(current.inputTokens, current.outputTokens, current.requests),
        )
    }
    if (error != null) SettingsNote(error!!)
}

/** 本月的第一天，`yyyy-MM-dd`。 */
private fun startOfMonth(): String {
    val cal = java.util.Calendar.getInstance()
    cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
    return dev.lily.app.usage.UsageStore.dateOf(cal.timeInMillis)
}

private const val MAX_MODELS_SHOWN = 8

@Composable
private fun UsageRow(title: String, value: String, detail: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** 花费；没有价格数据时说「暂无价格」，不编数字。 */
private fun costText(cost: Double?): String = if (cost == null) "—" else "¥${money(cost)}"

/** token 明细。 */
private fun tokenText(input: Long, output: Long, requests: Long): String =
    "输入 $input · 输出 $output · $requests 次"

private fun money(value: Double): String = if (value >= 0.01) {
    String.format("%.2f", value)
} else {
    String.format("%.4f", value)
}
