package dev.lily.app.ui

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.lily.app.R
import dev.lily.app.media.StickerStore
import java.io.File

/** 输入区下方会展开的面板；同时只会开一个。 */
enum class ComposerTray { NONE, STICKERS, IMAGES }

/**
 * 表情面板：像微信一样贴着输入框下方展开，只有表情网格和底部两个入口，
 * 不加标题、不再按标签分层（标签只作为整理信息留在管理页）。
 */
@Composable
internal fun StickerTray(
    stickers: List<StickerStore.Sticker>,
    onSend: (StickerStore.Sticker) -> Unit,
    onAdd: () -> Unit,
    onManage: () -> Unit,
) {
    // 面板在输入框下方，贴屏幕底，自己让出导航条安全区。
    Surface(modifier = Modifier.fillMaxWidth().navigationBarsPadding(), color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.sm)) {
            if (stickers.isEmpty()) {
                Text(
                    "还没有表情。点下面的「添加」从相册导一张。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = Space.xl),
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                    horizontalArrangement = Arrangement.spacedBy(Space.sm),
                    verticalArrangement = Arrangement.spacedBy(Space.sm),
                ) {
                    items(stickers, key = { it.id }) { s ->
                        StickerTile(s.file, Modifier.fillMaxWidth().aspectRatio(1f)) { onSend(s) }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = Space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.heightIn(min = SizeTokens.touchTarget)
                        .clip(RoundedCornerShape(Radius.sm))
                        .clickable(onClick = onAdd)
                        .padding(horizontal = Space.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Add, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Text("添加", modifier = Modifier.padding(start = 4.dp),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "表情管理",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.heightIn(min = SizeTokens.touchTarget)
                        .clip(RoundedCornerShape(Radius.sm))
                        .clickable(onClick = onManage)
                        .padding(horizontal = Space.sm)
                        .wrapContentHeight(Alignment.CenterVertically),
                )
            }
        }
    }
}

/** 图片面板：微信附件面板那样两个方格，相册 / 拍照，没有标题和说明。 */
@Composable
internal fun ImageTray(onGallery: () -> Unit, onCamera: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().navigationBarsPadding(), color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.md),
            horizontalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            TrayAction(Icons.Outlined.PhotoLibrary, "相册", onGallery, Modifier)
            TrayAction(Icons.Outlined.PhotoCamera, "拍照", onCamera, Modifier)
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun TrayAction(icon: ImageVector, title: String, onClick: () -> Unit, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(Radius.md)).clickable(onClick = onClick)
            .padding(vertical = Space.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(Radius.md))
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
        }
        Text(title, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
    }
}

/** 表情素材库（设计稿 08 / 表情库）：导入、按标签筛选、看用量、改标签、删除。 */
@Composable
internal fun StickerLibraryScreen(
    store: StickerStore,
    stickers: List<StickerStore.Sticker>,
    onBack: () -> Unit,
    onImport: () -> Unit,
) {
    var editing by remember { mutableStateOf<StickerStore.Sticker?>(null) }

    Column(Modifier.fillMaxSize()) {
        // 顶栏照微信「表情管理」：返回、居中标题、右侧「完成」。
        Row(
            Modifier.fillMaxWidth().padding(start = Space.xs, end = Space.sm, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(SizeTokens.touchTarget)) {
                Icon(Icons.Outlined.ArrowBack, "返回", modifier = Modifier.size(18.dp))
            }
            Text(
                "表情管理",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onBack) { Text("完成") }
        }
        // 4 列纯网格，无卡片、无说明；最后一个格子是「＋」导入。
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(start = Space.xl, end = Space.xl, top = Space.md, bottom = Space.md),
            horizontalArrangement = Arrangement.spacedBy(Space.md),
            verticalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            items(stickers, key = { it.id }) { s ->
                StickerTile(s.file, Modifier.fillMaxWidth().aspectRatio(1f)) { editing = s }
            }
            item {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(1f)
                        .clip(RoundedCornerShape(Radius.md))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Radius.md))
                        .clickable(onClick = onImport),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Add,
                        "添加表情",
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
        }
        Text(
            "点表情可改标签或删除；删除只影响本机表情库，不涉及聊天记录。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Space.xl, vertical = Space.sm),
        )
    }
    editing?.let { s ->
        StickerEditDialog(s, onTags = { store.setTags(s.id, it); editing = store.stickers.value.firstOrNull { st -> st.id == s.id } },
            onDelete = { store.remove(s.id); editing = null }, onDismiss = { editing = null })
    }
}

@Composable
private fun StickerEditDialog(
    sticker: StickerStore.Sticker,
    onTags: (List<String>) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var tags by remember(sticker.id) { mutableStateOf(sticker.tags) }
    val options = (StickerStore.PRESET_TAGS + StickerStore.UNSORTED).distinct()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(sticker.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                Text("已发送 ${sticker.uses} 次 · ${if (sticker.animated) "动图" else "静图"}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("标签", style = MaterialTheme.typography.labelMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    options.forEach { t ->
                        FilterChip(
                            selected = t in tags,
                            onClick = {
                                val next = if (t in tags) tags - t else tags + t
                                tags = next.ifEmpty { listOf(StickerStore.UNSORTED) }
                                onTags(tags)
                            },
                            label = { Text(t) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        dismissButton = {
            TextButton(onClick = onDelete) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        },
    )
}

/** 表情格子：浅底、圆角、图片等比放入，无文字气泡。 */
@Composable
private fun StickerTile(path: String, modifier: Modifier, onClick: () -> Unit) {
    val bitmap = remember(path) { runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull() }
    Box(
        // 表情本身没有底板，和微信一样直接放在页面上。
        modifier.clip(RoundedCornerShape(Radius.md)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap, contentDescription = "表情", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(Space.sm))
        } else {
            Text("已删除", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 看图浮层：大图 + 关闭 / 保存到相册 / 删除（设计稿 viewer，全屏覆盖含底栏）。 */
@Composable
internal fun ImageViewer(path: String, onClose: () -> Unit, onSave: () -> Unit, onDelete: () -> Unit) {
    val bitmap = remember(path) { runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull() }
    // 删除不可恢复：先确认再删。
    var confirmDelete by remember { mutableStateOf(false) }
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.96f)).clickable(onClick = onClose)) {
        Column(Modifier.statusBarsPadding().fillMaxSize().padding(start = Space.xl, end = Space.xl, top = Space.xl)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("图片", style = MaterialTheme.typography.titleSmall, color = Color.White, modifier = Modifier.weight(1f))
                Text("关闭", style = MaterialTheme.typography.labelMedium, color = Color.White,
                    modifier = Modifier.clickable(onClick = onClose))
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (bitmap != null) {
                    Image(bitmap, contentDescription = "大图", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                } else {
                    Text("图片已删除", style = MaterialTheme.typography.bodyMedium, color = Color.White)
                }
            }
            Row(
                Modifier.navigationBarsPadding().fillMaxWidth().padding(top = Space.md, bottom = Space.xl),
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                OutlinedButton(
                    onClick = onSave,
                    modifier = Modifier.weight(1f).height(34.dp),
                    shape = RoundedCornerShape(Radius.sm),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color.White.copy(alpha = 0.07f),
                        contentColor = Color.White.copy(alpha = 0.9f),
                    ),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
                    contentPadding = PaddingValues(horizontal = Space.sm),
                ) { Text("保存到相册", style = MaterialTheme.typography.labelSmall) }
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.weight(1f).height(34.dp),
                    shape = RoundedCornerShape(Radius.sm),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color.White.copy(alpha = 0.07f),
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                    contentPadding = PaddingValues(horizontal = Space.sm),
                ) { Text("删除", style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
    if (confirmDelete) {
        ConfirmDangerDialog(
            title = "删掉这张图？",
            text = "删掉就找不回来了，聊天记录里的这张图也会失效。",
            onConfirm = onDelete,
            onDismiss = { confirmDelete = false },
        )
    }
}

/** 把照片写进 Lily 的系统相册；不需要存储权限（minSdk 30）。 */
internal fun saveToGallery(context: Context, path: String): Boolean {
    val file = File(path)
    if (!file.isFile) return false
    val mime = when (file.extension.lowercase()) {
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
        put(MediaStore.Images.Media.MIME_TYPE, mime)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + context.getString(R.string.app_name))
        }
    }
    val resolver = context.contentResolver
    val uri = runCatching { resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) }.getOrNull() ?: return false
    return try {
        resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: return false
        true
    } catch (e: Exception) {
        false
    }
}

/** 输入区左边的两个小入口：表情、图片。视觉 34dp，热区补到 44dp。 */
@Composable
internal fun TrayButton(icon: ImageVector, label: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.size(SizeTokens.touchTarget),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(Space.md))
                .background(if (active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                .pressClick(pressedScale = 0.90f, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}
