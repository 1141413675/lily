package dev.lily.app.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lily.app.media.SceneryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 生活分享图库：导入、按地点打标签、删除。只存本机；图库为空时聊天侧静默跳过。
 * 设计稿暂无这一屏，按设置页现有区块样式实现（见报告）。
 */
@Composable
internal fun ScenerySection(store: dev.lily.app.media.SceneryStore, pronoun: String = "TA") {
    val items by store.items.collectAsStateWithLifecycle()
    val city by store.city.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var place by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<SceneryStore.Scenery?>(null) }
    // 删除不可恢复：先确认再删。
    var pendingRemove by remember { mutableStateOf<SceneryStore.Scenery?>(null) }
    // 默认收起：空库时不主动提示用户去添加图片或写标签。
    var open by remember { mutableStateOf(false) }

    fun copyToCache(uri: Uri): File? = try {
        val temp = File(context.cacheDir, "scenery-${System.currentTimeMillis()}-${(0..999).random()}")
        context.contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { input.copyTo(it) } }
        temp.takeIf { it.length() > 0 }
    } catch (e: Exception) {
        null
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            uris.forEach { uri ->
                val temp = copyToCache(uri) ?: return@forEach
                store.add(temp, place)
                temp.delete()
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "放进来了 ${uris.size} 张", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Row(
            Modifier.fillMaxWidth().tapTarget().clickable { open = !open },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("备用图库（可选）", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Text(
                if (items.isEmpty()) "未使用" else "${items.size} 张",
                style = MaterialTheme.typography.labelSmall,
                color = LocalLilyPalette.current.ink3,
            )
            Icon(
                if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = LocalLilyPalette.current.ink3,
                modifier = Modifier.size(IconSize.md),
            )
        }
        if (open || items.isNotEmpty()) {
        Text(
            "只在没有 Key 或生成失败时兜底，平时不会用到；空着也没关系。",
            style = MaterialTheme.typography.bodySmall,
            color = LocalLilyPalette.current.ink3,
        )
        OutlinedTextField(
            value = place,
            onValueChange = { place = it },
            label = { Text("地点") },
            singleLine = true,
            shape = RoundedCornerShape(Radius.md),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { importer.launch("image/*") }) { Text("选图片") }
            Text(
                if (items.isEmpty()) "" else "${items.size} 张 · ${store.places().size} 个地点",
                style = MaterialTheme.typography.labelSmall,
                color = LocalLilyPalette.current.ink3,
                modifier = Modifier.padding(start = Space.md),
            )
        }
        OutlinedTextField(
            value = city,
            onValueChange = { store.setCity(it) },
            label = { Text("${pronoun}在哪座城市（可选）") },
            singleLine = true,
            shape = RoundedCornerShape(Radius.md),
            modifier = Modifier.fillMaxWidth(),
        )
        if (items.isNotEmpty()) {
            items.forEach { s ->
                Surface(
                    onClick = { editing = s },
                    shape = RoundedCornerShape(Radius.md),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(start = Space.md, end = Space.xs, top = Space.sm, bottom = Space.sm), verticalAlignment = Alignment.CenterVertically) {
                        val bitmap = remember(s.file) {
                            runCatching { android.graphics.BitmapFactory.decodeFile(s.file)?.asImageBitmap() }.getOrNull()
                        }
                        Box(
                            Modifier.size(44.dp).clip(RoundedCornerShape(Radius.sm))
                                .background(MaterialTheme.colorScheme.surfaceContainerLow),
                        ) {
                            if (bitmap != null) {
                                Image(bitmap, contentDescription = s.place, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            }
                        }
                        Column(Modifier.weight(1f).padding(horizontal = Space.md)) {
                            Text(s.place.ifBlank { "未标地点" }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(
                                "点一下改地点或删除",
                                style = MaterialTheme.typography.labelSmall,
                                color = LocalLilyPalette.current.ink3,
                            )
                        }
                        IconButton(onClick = { pendingRemove = s }) {
                            Icon(
                                Icons.Outlined.Delete,
                                "删除这张图",
                                tint = LocalLilyPalette.current.ink3,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
        Text(
            "只存本机，不联网；删除只影响本机图库。",
            style = MaterialTheme.typography.labelSmall,
            color = LocalLilyPalette.current.ink3,
        )
        }
    }

    pendingRemove?.let { s ->
        ConfirmDangerDialog(
            title = "删掉这张图？",
            text = "删掉就找不回来了，聊天里引用过它的动态也会失效。",
            onConfirm = { store.remove(s.id) },
            onDismiss = { pendingRemove = null },
        )
    }

    editing?.let { s ->
        var draft by remember(s.id) { mutableStateOf(s.place) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("这张图的地点") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("地点标签") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    store.setPlace(s.id, draft)
                    editing = null
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = {
                    store.remove(s.id)
                    editing = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
        )
    }
}
