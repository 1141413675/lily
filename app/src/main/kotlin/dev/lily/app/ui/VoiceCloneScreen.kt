package dev.lily.app.ui

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedCard
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
import dev.lily.app.settings.AppSettings
import dev.lily.app.AppGraph
import dev.lily.app.persona.Voices
import dev.lily.app.voice.ClonedVoiceStore
import dev.lily.app.voice.DoubaoTts3
import dev.lily.app.voice.SampleCheck
import dev.lily.app.voice.SampleRecorder
import dev.lily.app.voice.Speaker
import dev.lily.app.voice.VoiceApi
import dev.lily.app.voice.VoiceRouting
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
internal fun VoiceCloneScreen(
    graph: AppGraph,
    settings: AppSettings,
    onSaveSettings: (AppSettings) -> Boolean,
    isVoiceUsedElsewhere: (String) -> Boolean,
    onBack: () -> Unit,
) {
    val clones by graph.clones.voices.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember { VoiceApi() }
    val speaker = remember { Speaker(File(context.cacheDir, "voice").apply { mkdirs() }) }
    val recorder = remember { SampleRecorder(context) }
    val mic = rememberMicPermission()
    // 声音复刻是硅基的能力，读写都用硅基那套配置，不受当前朗读引擎影响。
    val tts = settings.tts.configOf(dev.lily.app.settings.VoiceVendor.SILICONFLOW)
    val currentVoice = Voices.effective(settings.persona.voice, settings.persona.gender)

    var step by remember { mutableStateOf(CloneStep.SAMPLE) }
    // 复刻走哪家：硅基流动（上传录音）或火山方舟 3.0（上传录音后训练音色）。
    var cloneVendor by remember { mutableStateOf(dev.lily.app.settings.VoiceVendor.SILICONFLOW) }
    // 火山那套的配置单独拿：它的地址、模型与 Key 与朗读引擎无关。
    val volcano = settings.tts.configOf(dev.lily.app.settings.VoiceVendor.DOUBAO3)
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
    // 火山训练音色是异步的：这里显示"正在训练"，等轮询到训好再往下走。
    var training by remember { mutableStateOf(false) }
    var trainingHint by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<ClonedVoiceStore.ClonedVoice?>(null) }

    fun setDefault(uri: String) {
        if (!onSaveSettings(settings.copy(persona = settings.persona.copy(voice = uri, voiceChosen = true)))) {
            notice = "保存音色失败，请重试"
            return
        }
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
        error = if (ms < sampleRange(cloneVendor).first) {
            val r = sampleRange(cloneVendor)
            "样本只有 ${ms / 1000} 秒；${r.first / 1000}–${r.last / 1000} 秒都可以。"
        } else {
            null
        }
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

    /**
     * 等火山把音色训好。
     *
     * 训练是异步的：注册完只拿到 id，要用 get_voice 轮询到 status 为 2 或 4。
     * 最多等 [TRAIN_WAIT_MS]，超时就报错——不能让界面一直转。
     */
    suspend fun awaitVoiceReady(
        config: dev.lily.app.settings.VoiceSettings,
        speakerId: String,
    ): String {
        val deadline = System.currentTimeMillis() + TRAIN_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            delay(TRAIN_POLL_MS)
            val state = withContext(Dispatchers.IO) { DoubaoTts3.queryVoice(config, speakerId) }
            if (state.ready) return state.speakerId.ifBlank { speakerId }
        }
        throw IllegalStateException("音色还在训练，稍后再来生成一次。")
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
                val range = sampleRange(cloneVendor)
                if (ms != null && ms !in range) {
                    file.delete()
                    error = "样本需要 ${range.first / 1000}–${range.last / 1000} 秒；当前 ${ms / 1000} 秒。"
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

    LaunchedEffect(step) {
        if (step != CloneStep.RECORDING) return@LaunchedEffect
        while (step == CloneStep.RECORDING) {
            delay(100)
            val ms = recorder.elapsedMs()
            elapsed = (ms / 1000).toInt()
            if (ms >= sampleRange(cloneVendor).last) {
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

    val palette = LocalLilyPalette.current
    SettingsSubpage("声音克隆", onBack) {
        BackHandler(enabled = step == CloneStep.RECORDING) { discardSample() }
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Text("声音", style = MaterialTheme.typography.titleSmall, color = palette.ink)
            Text(
                "一男一女两个默认音色；克隆样本只用来生成音色，生成后本机不留副本。",
                style = MaterialTheme.typography.bodySmall,
                color = palette.ink2,
            )
        }

        // 音色列表
        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Radius.lg),
            colors = CardDefaults.outlinedCardColors(containerColor = palette.surface),
            border = BorderStroke(1.dp, palette.line2),
        ) {
            Column(Modifier.fillMaxWidth().padding(Space.xl), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text("音色列表", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "朗读与通话共用",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.ink2,
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

        // 设计音色：不用录音，用一段文字描述（或一张角色图）造一个声音。
        VoiceDesignCard(graph, settings)

        // 克隆新音色
        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Radius.lg),
            colors = CardDefaults.outlinedCardColors(containerColor = palette.surface),
            border = BorderStroke(1.dp, palette.line2),
        ) {
            Column(Modifier.fillMaxWidth().padding(Space.xl), verticalArrangement = Arrangement.spacedBy(Space.md)) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text("克隆新音色", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (cloneVendor == dev.lily.app.settings.VoiceVendor.DOUBAO3) {
                            "10–60 秒 · 火山方舟 3.0 训练出的音色只有 3.0 的引擎能用"
                        } else {
                            "8–30 秒 · 8–10 秒效果最好"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.ink2,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    dev.lily.app.settings.VoiceVendor.entries
                        .filter { it.supportsVoiceCloning || it == dev.lily.app.settings.VoiceVendor.SILICONFLOW }
                        .forEach { vendor ->
                            FilterChip(
                                selected = cloneVendor == vendor,
                                onClick = { cloneVendor = vendor; step = CloneStep.SAMPLE; error = null },
                                label = { Text(vendor.displayName) },
                            )
                        }
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
                            progress = { (elapsed * 1000f / sampleRange(cloneVendor).last).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = elapsed * 1000L >= sampleRange(cloneVendor).first,
                                onClick = { finishRecording() },
                            ) {
                                val r = sampleRange(cloneVendor)
                                Text(
                                    if (elapsed * 1000L >= r.first) "停止并试听"
                                    else "还差 ${(r.first / 1000 - elapsed).coerceAtLeast(0)} 秒",
                                )
                            }
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
                        val ready = if (cloneVendor == dev.lily.app.settings.VoiceVendor.DOUBAO3) {
                            volcano.apiKey.isNotBlank()
                        } else {
                            tts.configured
                        }
                        if (!ready) {
                            Text(
                                if (cloneVendor == dev.lily.app.settings.VoiceVendor.DOUBAO3) {
                                    "还没有填豆包 3.0 的 API Key；去「模型与接口 → 语音」里填上才能训练音色。"
                                } else {
                                    "还没有配置语音朗读（Base URL / 模型 / Key），生成通道先接好，配好 Key 才能用。"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = consent && name.trim().isNotEmpty() && ready && sample != null,
                                onClick = {
                                    val file = sample ?: return@Button
                                    error = null
                                    step = CloneStep.UPLOADING
                                    scope.launch {
                                        try {
                                            val uri = if (cloneVendor == dev.lily.app.settings.VoiceVendor.DOUBAO3) {
                                                // 火山这边是异步训练：拿到 id 还要等它训好。
                                                val first = withContext(Dispatchers.IO) {
                                                    DoubaoTts3.cloneVoice(
                                                        config = volcano,
                                                        audio = file.readBytes(),
                                                        promptText = SAMPLE_TEXT,
                                                    )
                                                }
                                                if (first.ready) {
                                                    first.speakerId
                                                } else {
                                                    withContext(Dispatchers.Main) {
                                                        training = true
                                                        trainingHint = "正在训练音色，稍等一下…"
                                                    }
                                                    awaitVoiceReady(volcano, first.speakerId)
                                                }
                                            } else {
                                                withContext(Dispatchers.IO) {
                                                    api.cloneVoice(tts, name.trim(), SAMPLE_TEXT, file)
                                                }
                                            }
                                            val saved = graph.clones.add(uri, name.trim(), vendor = cloneVendor)
                                            withContext(Dispatchers.Main) {
                                                training = false
                                                file.delete()
                                                sample = null
                                                created = saved
                                                step = CloneStep.DONE
                                            }
                                        } catch (e: Exception) {
                                            withContext(Dispatchers.Main) {
                                                training = false
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
                        // 上传是有方向的等待：一段滑块在轨道里来回，文字不必再说一遍。
                        SlidingBar(
                            modifier = Modifier.fillMaxWidth(),
                            description = "正在上传样本并生成音色",
                        )
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
            text = {
                Text(
                    if (clone.vendor == dev.lily.app.settings.VoiceVendor.DOUBAO3) {
                        "火山方舟的音色只能在控制台删除，这里只删本机记录。" +
                            "云端音色会留着，也还占着音色槽位——要彻底清掉请去火山控制台的音色管理里删。"
                    } else {
                        "会先删除云端音色，再删本机记录；如果 TA 正在用这个声音，会回到默认音色。"
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    if (isVoiceUsedElsewhere(clone.uri)) {
                        notice = "其他角色正在使用这个音色，请先为他们更换音色"
                        return@TextButton
                    }
                    scope.launch {
                        try {
                            // 火山那边没有删除接口，只删本机记录。
                            if (clone.vendor != dev.lily.app.settings.VoiceVendor.DOUBAO3) {
                                withContext(Dispatchers.IO) { api.deleteVoice(tts, clone.uri) }
                            }
                            withContext(Dispatchers.Main) {
                                if (settings.persona.voice == clone.uri) {
                                    onSaveSettings(
                                        settings.copy(
                                            persona = dev.lily.app.persona.PersonaSettings.withoutVoice(settings.persona, clone.uri),
                                        ),
                                    )
                                }
                                graph.clones.remove(clone.id)
                                if (created?.id == clone.id) created = null
                                notice = if (clone.vendor == dev.lily.app.settings.VoiceVendor.DOUBAO3) {
                                    "已删掉本机记录；云端音色还在火山控制台里，需要的话去那儿删。"
                                } else {
                                    "已删除克隆音色，云端与本机记录一起清理"
                                }
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
@OptIn(ExperimentalLayoutApi::class)
private fun StepChips(step: CloneStep) {
    val order = listOf(CloneStep.SAMPLE, CloneStep.RECORDING, CloneStep.READY, CloneStep.NAMING, CloneStep.DONE)
    val active = when (step) {
        CloneStep.UPLOADING -> CloneStep.NAMING
        else -> step
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
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
    val palette = LocalLilyPalette.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = SizeTokens.touchTarget),
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(desc, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (current) {
            Text(
                "当前",
                style = MaterialTheme.typography.labelMedium,
                color = palette.accentOnSoft,
                modifier = Modifier.background(palette.accentSoft, RoundedCornerShape(Radius.pill))
                    .padding(horizontal = Space.sm, vertical = Space.xs),
            )
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

/** 等音色训练：每 3 秒查一次，最多等 2 分钟。 */
private const val TRAIN_POLL_MS = 3_000L
private const val TRAIN_WAIT_MS = 120_000L

/** 样本时长：硅基 8–30 秒，火山 3.0 要 10–60 秒。 */
private fun sampleRange(vendor: dev.lily.app.settings.VoiceVendor): LongRange =
    if (vendor == dev.lily.app.settings.VoiceVendor.DOUBAO3) 10_000L..60_000L else MIN_MS..MAX_MS
private const val SAMPLE_TEXT = "今天有点累，但我还是想听你说完。你先说，我不急。"


/**
 * 「设计音色」：用文字描述或角色图造一个声音。
 *
 * 火山方舟 3.0 的能力：给一句描述（如「女性，语速中等偏快，语调低沉有力」），
 * 或者给一张角色图，它就画出一个新音色并给一段试听。
 *
 * 需要账号下有一个 `S_` 开头的**预付费音色 id**——这是火山那边要先买的，
 * App 里代劳不了，所以这里让用户自己填。
 */
@Composable
private fun VoiceDesignCard(graph: AppGraph, settings: AppSettings) {
    val palette = LocalLilyPalette.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val volcano = settings.tts.configOf(dev.lily.app.settings.VoiceVendor.DOUBAO3)
    var speakerId by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var designText by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var created by remember { mutableStateOf<ClonedVoiceStore.ClonedVoice?>(null) }

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.lg),
        colors = CardDefaults.outlinedCardColors(containerColor = palette.surface),
        border = BorderStroke(1.dp, palette.line2),
    ) {
        Column(Modifier.fillMaxWidth().padding(Space.xl), verticalArrangement = Arrangement.spacedBy(Space.md)) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Text("设计音色", style = MaterialTheme.typography.titleSmall)
                Text(
                    "不用录音：写一句描述，或用一张角色图，火山给你造一个声音。",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.ink2,
                )
            }
            if (volcano.apiKey.isBlank()) {
                Text(
                    "还没有填豆包 3.0 的 API Key；去「模型与接口 → 语音」里选「豆包 3.0」并填上 Key。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            OutlinedTextField(
                value = speakerId,
                onValueChange = { speakerId = it },
                label = { Text("预付费音色 ID（S_ 开头）") },
                placeholder = { Text("S_xxxxx") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text("想要的音色") },
                placeholder = { Text("女性，语速中等偏快，语调低沉有力") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = designText,
                onValueChange = { designText = it },
                label = { Text("试听文本") },
                placeholder = { Text("用这段话试一下声音") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !working && volcano.apiKey.isNotBlank() &&
                        speakerId.trim().startsWith("S_") && prompt.isNotBlank(),
                    onClick = {
                        working = true
                        message = null
                        scope.launch {
                            val result = runCatching {
                                withContext(Dispatchers.IO) {
                                    DoubaoTts3.designVoice(
                                        config = volcano,
                                        speakerId = speakerId.trim(),
                                        textPrompt = prompt.trim(),
                                        demoText = designText.trim(),
                                    )
                                }
                            }
                            working = false
                            result.fold(
                                onSuccess = { r ->
                                    if (r.ready) {
                                        val saved = graph.clones.add(
                                            r.speakerId,
                                            prompt.trim().take(12),
                                            vendor = dev.lily.app.settings.VoiceVendor.DOUBAO3,
                                        )
                                        created = saved
                                        message = "音色设计好了，已加进上面的列表。"
                                    } else {
                                        message = "还在设计${if (r.message.isNotBlank()) "：${r.message}" else ""}"
                                    }
                                },
                                onFailure = { message = it.message ?: "设计音色失败" },
                            )
                        }
                    },
                ) { Text(if (working) "正在设计…" else "设计音色") }
            }
            if (message != null) {
                Text(
                    message!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (created != null) {
                Text(
                    "已保存「${created?.name.orEmpty()}」；可以在上面的列表里设为默认。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
