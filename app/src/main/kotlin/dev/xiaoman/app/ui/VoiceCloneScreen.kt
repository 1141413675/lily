package dev.xiaoman.app.ui

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xiaoman.app.AppGraph
import dev.xiaoman.app.persona.Voices
import dev.xiaoman.app.voice.ClonedVoiceStore
import dev.xiaoman.app.voice.SampleCheck
import dev.xiaoman.app.voice.SampleRecorder
import dev.xiaoman.app.voice.Speaker
import dev.xiaoman.app.voice.VoiceApi
import dev.xiaoman.app.voice.VoiceRouting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 声音克隆（设计稿 09）：音色列表 + 录制/选文件 → 试听样本 → 起名并同意 → 上传生成 → 试听。
 *
 * SiliconFlow 的声音复刻接口在生成时必须带 `customName`，所以「起名」放在上传之前；
 * 样本只用于生成音色，生成成功后立刻删除本机文件；删除音色时先删云端再删本地索引。
 */
@Composable
fun VoiceCloneScreen(graph: AppGraph, onBack: () -> Unit) {
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val clones by graph.clones.voices.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember { VoiceApi() }
    val speaker = remember { Speaker(File(context.cacheDir, "voice").apply { mkdirs() }) }
    val recorder = remember { SampleRecorder(context) }
    val mic = rememberMicPermission()
    val tts = settings.tts
    val currentVoice = Voices.effective(settings.persona.voice, settings.persona.gender)

    var step by remember { mutableStateOf(CloneStep.SAMPLE) }
    var sample by remember { mutableStateOf<File?>(null) }
    var sampleMs by remember { mutableLongStateOf(0L) }
    var advice by remember { mutableStateOf<String?>(null) }
    var elapsed by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("我的声音 ${clones.size + 1}") }
    var consent by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var created by remember { mutableStateOf<ClonedVoiceStore.ClonedVoice?>(null) }
    var playing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ClonedVoiceStore.ClonedVoice?>(null) }

    fun setDefault(uri: String) {
        graph.settings.save(settings.copy(persona = settings.persona.copy(voice = uri, voiceChosen = true)))
        notice = if (VoiceRouting.realtimeSupports(settings.realtime.baseUrl, uri)) {
            "已设为默认音色 · 朗读与通话音色同步"
        } else {
            "已设为默认音色 · 实时通话仍用 TA 自己的音色"
        }
    }

    fun playSample() {
        val file = sample ?: return
        if (playing) {
            speaker.stop()
            playing = false
            return
        }
        playing = true
        speaker.play(file) { playing = false }
    }

    fun playCreated() {
        val voice = created ?: return
        if (!tts.configured) {
            error = "先在设置里填写语音朗读的接口与 Key，才能试听。"
            return
        }
        playing = true
        scope.launch {
            try {
                val audio = withContext(Dispatchers.IO) {
                    api.speak(tts.copy(voice = voice.uri), "我在听，你慢慢说。")
                }
                withContext(Dispatchers.Main) {
                    playing = true
                    speaker.play(audio) { playing = false }
                }
            } catch (e: Exception) {
                playing = false
                error = e.message ?: "试听失败"
            }
        }
    }

    fun finishRecording() {
        val ms = recorder.elapsedMs()
        val file = recorder.stop()
        if (file == null) {
            step = CloneStep.SAMPLE
            error = "没有录到声音，再试一次。"
            return
        }
        sample?.delete()
        sample = file
        sampleMs = ms
        step = CloneStep.READY
        advice = SampleCheck.advise(file, ms)
        error = if (ms < MIN_MS) "样本只有 ${ms / 1000} 秒；8–30 秒都可以，8–10 秒效果最好。" else null
    }

    fun discardSample() {
        recorder.cancel()
        speaker.stop()
        sample?.delete()
        sample = null
        sampleMs = 0
        elapsed = 0
        step = CloneStep.SAMPLE
        error = null
        advice = null
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val file = copyToCache(context, uri)
            if (file == null) {
                withContext(Dispatchers.Main) { error = "这个文件读不出来，换一个试试。" }
                return@launch
            }
            val ms = durationMs(file)
            withContext(Dispatchers.Main) {
                if (ms != null && ms !in MIN_MS..MAX_MS) {
                    file.delete()
                    error = "样本需要 8–30 秒；当前 ${ms / 1000} 秒。"
                    advice = null
                } else {
                    sample?.delete()
                    sample = file
                    sampleMs = ms ?: 0
                    step = CloneStep.READY
                    error = null
                    advice = SampleCheck.advise(file, ms ?: 0)
                }
            }
        }
    }

    BackHandler {
        if (step == CloneStep.RECORDING) {
            discardSample()
        } else {
            onBack()
        }
    }

    LaunchedEffect(step) {
        if (step != CloneStep.RECORDING) return@LaunchedEffect
        while (step == CloneStep.RECORDING) {
            delay(100)
            val ms = recorder.elapsedMs()
            elapsed = (ms / 1000).toInt()
            if (ms >= MAX_MS) {
                finishRecording()
                break
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            recorder.cancel()
            speaker.stop()
            sample?.delete()
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = Space.xl, end = Space.xl, bottom = Space.xl),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Space.xs)) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "返回",
                    tint = LocalXiaomanPalette.current.ink2,
                )
            }
            Column {
                Text("声音", style = MaterialTheme.typography.labelSmall, color = LocalXiaomanPalette.current.ink3)
                Text("声音克隆", style = MaterialTheme.typography.titleLarge)
            }
        }
        Text(
            "一男一女两个默认音色；克隆样本只用来生成音色，生成后本机不留副本。",
            style = MaterialTheme.typography.bodySmall,
            color = LocalXiaomanPalette.current.ink3,
        )

        // 音色列表
        Card(
            shape = RoundedCornerShape(Radius.lg),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.fillMaxWidth().padding(Space.lg), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("音色列表", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "朗读与通话共用",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                VoiceRow(
                    name = "claire · 柔和",
                    desc = "默认 · 女声 · 平缓，适合长句",
                    current = currentVoice == Voices.DEFAULT_FEMALE,
                    onSet = { setDefault(Voices.DEFAULT_FEMALE) },
                )
                VoiceRow(
                    name = "alex · 沉稳",
                    desc = "默认 · 男声 · 偏低，句尾稳",
                    current = currentVoice == Voices.DEFAULT_MALE,
                    onSet = { setDefault(Voices.DEFAULT_MALE) },
                )
                clones.forEach { clone ->
                    VoiceRow(
                        name = clone.name,
                        desc = "克隆音色 · 云端",
                        current = currentVoice == clone.uri,
                        onSet = { setDefault(clone.uri) },
                        onDelete = { deleting = clone },
                    )
                }
                Text(
                    "「设为默认」会同时替换朗读音色；仍然只有一个声音。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 克隆新音色
        Card(
            shape = RoundedCornerShape(Radius.lg),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.fillMaxWidth().padding(Space.lg), verticalArrangement = Arrangement.spacedBy(Space.md)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("克隆新音色", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "8–30 秒 · 8–10 秒效果最好",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                StepChips(step)
                when (step) {
                    CloneStep.SAMPLE -> {
                        SamplePrompt()
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    if (!mic.granted) {
                                        mic.request()
                                        error = "允许录音后才能采集样本。"
                                        return@Button
                                    }
                                    error = null
                                    if (recorder.start()) {
                                        elapsed = 0
                                        step = CloneStep.RECORDING
                                    } else {
                                        error = "打不开麦克风，检查录音权限。"
                                    }
                                },
                            ) { Text("开始录制") }
                            TextButton(onClick = { picker.launch("audio/*") }) { Text("选择文件") }
                        }
                    }

                    CloneStep.RECORDING -> {
                        Text(
                            "录音中 · 已录 ${elapsed} 秒",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        LinearProgressIndicator(
                            progress = { (elapsed * 1000f / MAX_MS).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = elapsed * 1000L >= MIN_MS,
                                onClick = { finishRecording() },
                            ) { Text(if (elapsed * 1000L >= MIN_MS) "停止并试听" else "还差 ${8 - elapsed} 秒") }
                            TextButton(onClick = { discardSample() }) { Text("取消") }
                        }
                    }

                    CloneStep.READY -> {
                        Text(
                            "样本已就绪 · ${sampleMs / 1000} 秒",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        advice?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        Text(
                            "克隆只应该有你一个人的声音；多人说话或背景人声会被一起学进去。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { playSample() }) { Text(if (playing) "停止" else "试听样本") }
                            TextButton(onClick = { discardSample() }) { Text("重录") }
                            Button(onClick = {
                                step = CloneStep.NAMING
                                error = null
                            }) { Text("起名") }
                        }
                    }

                    CloneStep.NAMING -> {
                        Text(
                            "给这个声音起个名字；生成后样本从本机删除。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = name,
                            onValueChange = { if (it.length <= 12) name = it },
                            singleLine = true,
                            label = { Text("音色名称") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = consent, onCheckedChange = { consent = it })
                            Text(
                                "这段样本是我本人的声音；不用于仿冒他人或公众人物。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(
                            "样本只用于生成音色；生成后本机不留副本。删除克隆音色时会同时删除云端音色与本机记录。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!tts.configured) {
                            Text(
                                "还没有配置语音朗读（Base URL / 模型 / Key），生成通道先接好，配好 Key 才能用。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = consent && name.trim().isNotEmpty() && tts.configured && sample != null,
                                onClick = {
                                    val file = sample ?: return@Button
                                    error = null
                                    step = CloneStep.UPLOADING
                                    scope.launch {
                                        try {
                                            val uri = withContext(Dispatchers.IO) {
                                                api.cloneVoice(tts, name.trim(), SAMPLE_TEXT, file)
                                            }
                                            val saved = graph.clones.add(uri, name.trim())
                                            withContext(Dispatchers.Main) {
                                                file.delete()
                                                sample = null
                                                created = saved
                                                step = CloneStep.DONE
                                            }
                                        } catch (e: Exception) {
                                            withContext(Dispatchers.Main) {
                                                error = e.message ?: "生成音色失败"
                                                step = CloneStep.NAMING
                                            }
                                        }
                                    }
                                },
                            ) { Text("生成音色") }
                            TextButton(onClick = { step = CloneStep.READY }) { Text("返回") }
                        }
                    }

                    CloneStep.UPLOADING -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("正在上传样本并生成音色…", style = MaterialTheme.typography.bodySmall)
                    }

                    CloneStep.DONE -> {
                        Text(
                            "已保存「${created?.name.orEmpty()}」",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            "可以在上面的列表里设为默认；朗读与通话仍然共用一个声音。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { playCreated() }) { Text(if (playing) "停止" else "试听") }
                            TextButton(onClick = {
                                created?.let { setDefault(it.uri) }
                            }) { Text("设为默认") }
                            Button(onClick = {
                                created = null
                                name = "我的声音 ${clones.size + 1}"
                                consent = false
                                sampleMs = 0
                                step = CloneStep.SAMPLE
                            }) { Text("再录一个") }
                        }
                    }
                }
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            }
        }

        Text(
            "样本仅用于生成音色 · 删除克隆会一起删云端音色与本机记录",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "朗读与语音消息是合成语音，不是真人录音。禁止仿冒他人或公众人物。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    deleting?.let { clone ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除「${clone.name}」？") },
            text = { Text("会先删除云端音色，再删本机记录；如果 TA 正在用这个声音，会回到默认音色。") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { api.deleteVoice(tts, clone.uri) }
                            withContext(Dispatchers.Main) {
                                if (settings.persona.voice == clone.uri) {
                                    graph.settings.save(
                                        settings.copy(
                                            persona = dev.xiaoman.app.persona.PersonaSettings.withoutVoice(settings.persona, clone.uri),
                                        ),
                                    )
                                }
                                graph.clones.remove(clone.id)
                                if (created?.id == clone.id) created = null
                                notice = "已删除克隆音色，云端与本机记录一起清理"
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                error = e.message ?: "删除云端音色失败，本机记录保留"
                            }
                        }
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}

private enum class CloneStep(val label: String) {
    SAMPLE("朗读"),
    RECORDING("录音"),
    READY("试听"),
    NAMING("起名"),
    UPLOADING("生成"),
    DONE("保存"),
}

@Composable
private fun StepChips(step: CloneStep) {
    val order = listOf(CloneStep.SAMPLE, CloneStep.RECORDING, CloneStep.READY, CloneStep.NAMING, CloneStep.DONE)
    val active = when (step) {
        CloneStep.UPLOADING -> CloneStep.NAMING
        else -> step
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        order.forEachIndexed { i, s ->
            val on = s == active
            val done = order.indexOf(active) > i
            Text(
                "${i + 1} ${s.label}",
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    on -> MaterialTheme.colorScheme.onPrimaryContainer
                    done -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .background(
                        if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                        RoundedCornerShape(Radius.sm),
                    )
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun SamplePrompt() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "「今天有点累，但我还是想听你说完。你先说，我不急。」",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "用平时的语速读一遍；环境安静一点，8–30 秒都可以，8–10 秒效果最好。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun VoiceRow(
    name: String,
    desc: String,
    current: Boolean,
    onSet: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(desc, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (current) {
            Text("当前", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        } else {
            TextButton(onClick = onSet) { Text("设为默认") }
        }
        if (onDelete != null) {
            TextButton(onClick = onDelete) { Text("删除", color = MaterialTheme.colorScheme.error) }
        }
    }
}

/** 把选中的音频复制到缓存，保留扩展名（供应商按扩展名判断编码）。 */
private fun copyToCache(context: android.content.Context, uri: Uri): File? = try {
    val display = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
    val ext = display?.substringAfterLast('.', "")?.lowercase()?.takeIf { it in setOf("wav", "mp3", "pcm", "opus", "m4a", "aac", "ogg") } ?: "wav"
    val target = File(context.cacheDir, "voice-sample-${System.currentTimeMillis()}.$ext")
    context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
    target.takeIf { it.length() > 0 }
} catch (e: Exception) {
    null
}

private fun durationMs(file: File): Long? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
    } catch (e: Exception) {
        null
    } finally {
        retriever.release()
    }
}

private const val MIN_MS = 8_000L
private const val MAX_MS = 30_000L
private const val SAMPLE_TEXT = "今天有点累，但我还是想听你说完。你先说，我不急。"
