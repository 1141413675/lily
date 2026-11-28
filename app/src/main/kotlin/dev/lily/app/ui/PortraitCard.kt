package dev.lily.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lily.app.AppGraph
import dev.lily.app.portrait.PortraitStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 头像统一入口：选过相册图就显示图，否则退回浅色圆形占位。
 * 聊天头部、通话页、动态流与设置入口都走这里，保证同一张脸。
 */
@Composable
fun PortraitAvatar(
    graph: AppGraph,
    size: Dp,
    modifier: Modifier = Modifier,
    /** 默认是她的头像；「我的」页传入 [AppGraph.myAvatar]。 */
    store: PortraitStore = graph.portrait,
    fallback: @Composable () -> Unit = {
        // 没有头像时统一用设计稿的渐变剪影，不出现字母或星形图标。
        Box(Modifier.size(size).clip(CircleShape)) {
            PortraitSilhouette(Modifier.fillMaxSize())
        }
    },
) {
    val state by store.state.collectAsStateWithLifecycle()
    val bitmap = rememberFileBitmap(state.current?.path)
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(CircleShape),
        )
    } else {
        fallback()
    }
}

/**
 * 设置首页头像的轻量入口：点头像直接打开相册，选完裁方覆写 `current.jpg`。
 */
@Composable
fun rememberPortraitPicker(graph: AppGraph, store: PortraitStore = graph.portrait): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val square = decodeSquare(context, uri, 512)
                    val tmp = File(context.cacheDir, "avatar-${System.currentTimeMillis()}.jpg")
                    try {
                        tmp.outputStream().use { out -> square.compress(Bitmap.CompressFormat.JPEG, 92, out) }
                        store.saveAvatar(tmp)
                    } finally {
                        tmp.delete()
                    }
                }
            }
        }
    }
    return {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
}

/**
 * 角色头像卡片：从相册选一张，居中裁成正方形，显示为圆形。
 * 图片只存本机；不调用任何生成接口。
 */
@Composable
fun PortraitCard(graph: AppGraph, store: PortraitStore = graph.portrait) {
    val state by store.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun import(uri: Uri) {
        if (saving) return
        saving = true
        error = null
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val square = decodeSquare(context, uri, 512)
                    val tmp = File.createTempFile("avatar-", ".jpg", context.cacheDir)
                    try {
                        tmp.outputStream().use { out -> square.compress(Bitmap.CompressFormat.JPEG, 92, out) }
                        store.saveAvatar(tmp)
                    } finally {
                        tmp.delete()
                    }
                }.getOrDefault(false)
            }
            saving = false
            if (!ok) error = "这张图打不开，换一张。"
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(::import)
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(
            Modifier.fillMaxWidth().padding(Space.lg),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Text("现在的样子", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.md), verticalAlignment = Alignment.CenterVertically) {
                PortraitAvatar(graph, 64.dp, store = store)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        if (state.current == null) "还没有放照片" else "这张在用",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "从相册选一张，裁成圆形；只存在这台手机上。换一张不会影响记忆。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        enabled = !saving,
                        onClick = {
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                    ) {
                        Text(
                            when {
                                saving -> "在弄…"
                                state.current == null -> "选一张"
                                else -> "换一张"
                            },
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
            error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** 解码时按 EXIF 方向摆正，居中裁成正方形，长边压到 [maxSize] 以内。 */
private fun decodeSquare(context: Context, uri: Uri, maxSize: Int): Bitmap {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        val width = info.size.width
        val height = info.size.height
        val side = minOf(width, height).coerceAtLeast(1)
        if (side > maxSize) {
            decoder.setTargetSize(
                (width * maxSize / side).coerceAtLeast(1),
                (height * maxSize / side).coerceAtLeast(1),
            )
        }
    }
    val side = minOf(bitmap.width, bitmap.height)
    val square = Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
    return if (square.width > maxSize) Bitmap.createScaledBitmap(square, maxSize, maxSize, true) else square
}

/** 设计稿的渐变剪影占位：没有头像时使用。 */
@Composable
private fun PortraitSilhouette(modifier: Modifier = Modifier) {
    val palette = LocalLilyPalette.current
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.4f
    val colors = if (dark) listOf(palette.surface2, palette.accentSoft) else listOf(palette.surface2, palette.line2)
    Canvas(modifier.background(Brush.linearGradient(colors))) {
        val white = Color.White.copy(alpha = 0.66f)
        drawCircle(white, radius = size.minDimension * 0.17f, center = Offset(size.width / 2f, size.height * 0.38f))
        drawOval(
            white,
            topLeft = Offset(size.width * 0.13f, size.height * 0.62f),
            size = Size(size.width * 0.74f, size.height * 0.58f),
        )
    }
}

/** 只按路径缓存解码结果，避免每次重组都读盘。 */
@Composable
private fun rememberFileBitmap(path: String?): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, path) {
        value = if (path == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
            }
        }
    }
    return bitmap
}
