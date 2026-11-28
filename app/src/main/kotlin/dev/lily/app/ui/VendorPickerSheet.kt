package dev.lily.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import dev.lily.app.R
import dev.lily.app.settings.ModelVendor
import dev.lily.app.settings.VendorKind

/**
 * 厂商选择器：先分组，再搜索。
 *
 * 厂商已经有二十多家，摊平成一个下拉菜单既长又难找，所以这里做成一张底部抽屉：
 * 顶部是搜索框，下面按「自家模型厂商 / 聚合平台 / 本机与自建」三段排开，
 * 当前选中的那一项打勾并置顶可见。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VendorPickerSheet(
    current: ModelVendor,
    onPick: (ModelVendor) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalLilyPalette.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }

    val grouped = remember(query) {
        val keyword = query.trim().lowercase()
        val filtered = if (keyword.isEmpty()) {
            ModelVendor.entries
        } else {
            ModelVendor.entries.filter {
                it.displayName.lowercase().contains(keyword) ||
                    it.name.lowercase().contains(keyword) ||
                    it.baseUrl.lowercase().contains(keyword)
            }
        }
        filtered.groupBy { it.kind }
            .toList()
            .sortedBy { (_, list) -> list.first().kindOrder }
            .map { (kind, list) -> kind to list }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = palette.bg,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Space.page)) {
            Text(
                stringResource(R.string.models_vendor_sheet_title),
                style = MaterialTheme.typography.titleLarge,
                color = palette.ink,
                modifier = Modifier.padding(bottom = Space.md),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.models_vendor_search_hint)) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                shape = RoundedCornerShape(Radius.md),
                modifier = Modifier.fillMaxWidth(),
            )
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 460.dp).padding(top = Space.md),
            ) {
                grouped.forEach { (kind, vendors) ->
                    item(key = "head-${kind.name}") {
                        Text(
                            stringResource(kindLabel(kind)),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.ink3,
                            modifier = Modifier.padding(top = Space.md, bottom = Space.sm),
                        )
                    }
                    items(vendors, key = { it.name }) { vendor ->
                        VendorRow(
                            vendor = vendor,
                            selected = vendor == current,
                            onClick = {
                                onPick(vendor)
                                onDismiss()
                            },
                        )
                    }
                }
                if (grouped.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            stringResource(R.string.models_vendor_none),
                            style = MaterialTheme.typography.bodyLarge,
                            color = palette.ink2,
                            modifier = Modifier.padding(vertical = Space.page),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VendorRow(vendor: ModelVendor, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalLilyPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .clickable(onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = Space.sm, vertical = Space.sm),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                vendor.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = palette.ink,
            )
            if (vendor.baseUrl.isNotBlank()) {
                Text(
                    vendor.baseUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.ink3,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = stringResource(R.string.models_vendor_selected),
                tint = palette.accent,
                modifier = Modifier.size(IconSize.lg),
            )
        }
    }
}

/** 分组标题：说清这一段里的服务方有什么区别。 */
private fun kindLabel(kind: VendorKind): Int = when (kind) {
    VendorKind.SELF -> R.string.models_vendor_group_self
    VendorKind.AGGREGATOR -> R.string.models_vendor_group_aggregator
    VendorKind.LOCAL -> R.string.models_vendor_group_local
}

/** 选中态的浅紫底，用在只读的状态条上。 */
@Composable
internal fun SelectedChip(text: String) {
    val palette = LocalLilyPalette.current
    Box(
        Modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(palette.accentSoft)
            .padding(horizontal = Space.md, vertical = Space.xs),
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = palette.accentOnSoft)
    }
}
