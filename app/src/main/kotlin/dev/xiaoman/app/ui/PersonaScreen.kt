package dev.xiaoman.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xiaoman.app.AppGraph
import dev.xiaoman.app.R
import dev.xiaoman.app.chat.EmojiStyle
import dev.xiaoman.app.life.LifeSettings
import dev.xiaoman.app.persona.Gender
import dev.xiaoman.app.persona.PersonaSettings
import dev.xiaoman.app.persona.VoiceOption
import dev.xiaoman.app.persona.Voices
import dev.xiaoman.app.voice.Speaker
import dev.xiaoman.app.voice.VoiceApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/** 试听与样本共用同一句，换音色时听到的差异只来自音色本身。 */
private const val PREVIEW_LINE = "今天有点累，不过看到你就好了。"

/**
 * 「小满人格与声音」：档案、表情、表达、生活图库、音色与关系档案，全部收在这一页。
 * 地址、模型名与 Key 不在这里，统一在「设置 · 模型与接口」。
 * 只写设置；历史记忆、关系档案与做梦记录都不改写。
 */
@Composable
fun PersonaScreen(
    graph: AppGraph,
    onBack: () -> Unit,
    onOpenVoiceClone: () -> Unit = {},
    onOpenRelation: () -> Unit = {},
) {
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val persona = settings.persona
    val appName = stringResource(R.string.app_name)
    // 昵称为空时显示应用名，不再落到写死的「小满」。
    val displayName = persona.nameOr(appName)
    var nickname by remember(persona.nickname) { mutableStateOf(persona.nickname) }
    var bioDraft by remember(persona.bio) { mutableStateOf(persona.bio) }
    var resetConfirm by remember { mutableStateOf(false) }
    var previewVoice by remember { mutableStateOf<String?>(null) }
    var hint by remember { mutableStateOf<String?>(null) }
    var speed by remember(settings.tts.speed) { mutableStateOf(settings.tts.speed) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val speaker = remember { Speaker(File(context.cacheDir, "voice").apply { mkdirs() }) }
    val api = remember { VoiceApi() }

    fun update(p: PersonaSettings) = graph.settings.save(settings.copy(persona = p))

    BackHandler(onBack = onBack)
    val palette = LocalXiaomanPalette.current
    Column(Modifier.fillMaxSize()) {
        // 返回与标题固定，长页面滚到底也还能返回。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = Space.xs, end = Space.page, top = Space.xs),
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(SizeTokens.touchTarget)) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = palette.ink2,
                )
            }
            Column(Modifier.padding(start = Space.sm)) {
                Text(stringResource(R.string.persona_title, displayName), style = MaterialTheme.typography.titleLarge)
                SettingsNote("$displayName · ${persona.summary()}")
            }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(start = Space.page, end = Space.page, bottom = Space.page),
            verticalArrangement = Arrangement.spacedBy(Space.block),
        ) {

        if (settings.personaVoiceNotice) {
            Card(
                shape = RoundedCornerShape(Radius.lg),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Column(Modifier.padding(Space.lg)) {
                    Text(stringResource(R.string.persona_voice_migrated), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { graph.settings.clearVoiceNotice() }) {
                        Text(stringResource(R.string.persona_voice_migrated_ok))
                    }
                }
            }
        }

        PersonaCard(stringResource(R.string.persona_nickname)) {
            OutlinedTextField(
                value = nickname,
                onValueChange = { if (it.length <= PersonaSettings.MAX_NAME) { nickname = it; hint = null } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.persona_nickname)) },
            )
            if (nickname.trim() != persona.nickname) {
                Button(
                    onClick = { update(persona.copy(nickname = nickname.trim().ifBlank { appName })) },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.accentInk),
                ) {
                    Text(stringResource(R.string.relation_save))
                }
            }
            Text(
                stringResource(R.string.persona_nickname_hint, persona.pronoun),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        PersonaCard("人设") {
            Text(
                "整段写你希望她是谁、怎么说话、在意什么。会原样进入对话设定；写具体比写形容词更稳。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = bioDraft,
                onValueChange = { if (it.length <= PersonaSettings.MAX_BIO) bioDraft = it },
                minLines = 6,
                maxLines = 14,
                label = { Text("人设") },
                // 空着时给一句可以直接照着写的引导，不写也能保存。
                placeholder = {
                    Text(
                        "比如：她说话慢一点，会先接住你的情绪再给建议；喜欢在晚上问你今天累不累；不喜欢被叫全名。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                Button(
                    enabled = bioDraft.trim() != persona.bio,
                    onClick = { update(persona.copy(bio = bioDraft.trim())) },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.accentInk),
                ) { Text("保存人设") }
                Text(
                    "${bioDraft.length} / ${PersonaSettings.MAX_BIO}",
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalXiaomanPalette.current.ink3,
                )
            }
        }

        PersonaCard(stringResource(R.string.persona_gender)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                Gender.entries.forEach { g ->
                    FilterChip(
                        selected = persona.gender == g,
                        onClick = { update(persona.copy(gender = g)); hint = null },
                        label = { Text(g.label) },
                    )
                }
            }
            Text(
                stringResource(R.string.persona_pronoun_now, persona.pronoun),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.persona_gender_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 只提示、不静默改：用户自己点一下才换音色。
            val voiceGender = Voices.of(Voices.effective(persona.voice, persona.gender))?.gender
            if (voiceGender != null && persona.gender != Gender.UNSPECIFIED && voiceGender != persona.gender) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "声音还是${voiceGender.label}声，要不要顺手换成${persona.gender.label}声？",
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalXiaomanPalette.current.ink3,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            update(persona.copy(voice = Voices.defaultVoice(persona.gender), voiceChosen = true))
                            hint = null
                        },
                        modifier = Modifier.tapTarget(),
                    ) {
                        Text("换成${persona.gender.label}声", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            // 生日只存年、月，年龄每次显示时实时算；不填则沿用设置首页的固定年龄。
            var birthYear by remember(persona.birthdayYear) {
                mutableStateOf(persona.birthdayYear?.toString().orEmpty())
            }
            var birthMonth by remember(persona.birthdayMonth) {
                mutableStateOf(persona.birthdayMonth?.toString().orEmpty())
            }
            fun applyBirthday(yearText: String, monthText: String) {
                if (yearText.isBlank() && monthText.isBlank()) {
                    if (persona.birthdayYear != null) {
                        update(
                            persona.copy(
                                birthdayYear = null,
                                birthdayMonth = null,
                                fixedAge = persona.ageAt() ?: persona.fixedAge,
                            ),
                        )
                    }
                    return
                }
                val y = yearText.toIntOrNull()
                val m = monthText.toIntOrNull()
                if (y != null && m != null && y in PersonaSettings.BIRTH_YEAR_RANGE && m in 1..12) {
                    update(persona.copy(birthdayYear = y, birthdayMonth = m, fixedAge = null))
                }
            }
            Text("生日（年 / 月）", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                OutlinedTextField(
                    value = birthYear,
                    onValueChange = { raw ->
                        if (raw.length <= 4 && raw.all(Char::isDigit)) {
                            birthYear = raw
                            applyBirthday(raw, birthMonth)
                        }
                    },
                    label = { Text("年") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(104.dp),
                )
                OutlinedTextField(
                    value = birthMonth,
                    onValueChange = { raw ->
                        if (raw.length <= 2 && raw.all(Char::isDigit)) {
                            birthMonth = raw
                            applyBirthday(birthYear, raw)
                        }
                    },
                    label = { Text("月") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(80.dp),
                )
            }
            Text(
                if (persona.birthdayYear != null && persona.birthdayMonth != null) {
                    "现在 ${persona.ageAt()} 岁 · 每年按生日自动增长"
                } else {
                    "不填生日时，用下面的固定年龄"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (persona.birthdayYear == null || persona.birthdayMonth == null) {
                var ageDraft by remember(persona.fixedAge) {
                    mutableStateOf(persona.fixedAge?.toString().orEmpty())
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    OutlinedTextField(
                        value = ageDraft,
                        onValueChange = { raw ->
                            if (raw.length <= 3 && raw.all(Char::isDigit)) {
                                ageDraft = raw
                                val parsed = raw.toIntOrNull()
                                update(
                                    persona.copy(
                                        fixedAge = parsed?.takeIf { it in PersonaSettings.AGE_RANGE },
                                    ),
                                )
                            }
                        },
                        label = { Text("年龄") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(88.dp),
                    )
                    Text(
                        "只显示数字，不参与对话",
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalXiaomanPalette.current.ink3,
                    )
                }
            }
        }

        PortraitCard(graph)

        PersonaCard(stringResource(R.string.settings_emoji)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                EmojiStyle.entries.forEach { style ->
                    FilterChip(
                        selected = settings.emojiStyle == style,
                        onClick = { graph.settings.save(settings.copy(emojiStyle = style)) },
                        label = { Text(style.label) },
                    )
                }
            }
            Text(
                stringResource(R.string.settings_emoji_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        PersonaCard("${displayName}的生活") {
            LifeFields(
                life = settings.life,
                name = displayName,
                onSave = { graph.settings.save(settings.copy(life = it)) },
            )
            ScenerySection(graph.scenery, pronoun = persona.pronoun)
        }

        PersonaCard(stringResource(R.string.persona_voice)) {
            val clones by graph.clones.voices.collectAsStateWithLifecycle()
            // 实际音色：显式选择优先；留空时按性别取默认（女 claire / 男 alex）。
            val current = Voices.effective(persona.voice, persona.gender)
            val currentLabel = Voices.of(current)?.let { "${it.label}（${it.desc}）" }
                ?: clones.firstOrNull { it.uri == current }?.let { "克隆 · ${it.name}" }
                ?: Voices.label(current)
            val currentGender = Voices.of(current)?.gender
            if (currentGender != null && persona.gender != Gender.UNSPECIFIED && currentGender != persona.gender) {
                Text(
                    stringResource(R.string.persona_voice_group_changed, currentGender.label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (dev.xiaoman.app.voice.VoiceRouting.isClone(current) &&
                !dev.xiaoman.app.voice.VoiceRouting.realtimeSupports(settings.realtime.baseUrl, current)
            ) {
                Text(
                    "克隆音色用于朗读、语音消息与免提通话；实时通话仍用 TA 自己的音色。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(currentLabel, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "合成语音，不是真人录音",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    enabled = previewVoice == null,
                    onClick = {
                        if (!settings.tts.configured) {
                            hint = context.getString(R.string.persona_voice_preview_need_tts)
                            return@TextButton
                        }
                        hint = null
                        previewVoice = current
                        val voice = settings.tts.copy(voice = current, speed = speed)
                        scope.launch {
                            try {
                                val audio = withContext(Dispatchers.IO) { api.speak(voice, PREVIEW_LINE) }
                                withContext(Dispatchers.Main) { speaker.play(audio) { previewVoice = null } }
                            } catch (e: Exception) {
                                previewVoice = null
                                hint = e.message ?: context.getString(R.string.persona_voice_preview_need_tts)
                            }
                        }
                    },
                ) {
                    Text(stringResource(if (previewVoice == current) R.string.persona_voice_previewing else R.string.persona_voice_preview))
                }
            }
            Text(
                "语音朗读 · 默认音色",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            VoiceGroup("女声", Voices.FEMALE, current, previewVoice, settings.tts.configured,
                onSelect = { update(persona.copy(voice = it, voiceChosen = true)) },
                onNeedTts = { hint = context.getString(R.string.persona_voice_preview_need_tts) },
                onPreview = { option ->
                    if (previewVoice == option.id) {
                        speaker.stop()
                        previewVoice = null
                    } else {
                        hint = null
                        previewVoice = option.id
                        val voice = settings.tts.copy(voice = option.id, speed = speed)
                        scope.launch {
                            try {
                                val audio = withContext(Dispatchers.IO) { api.speak(voice, PREVIEW_LINE) }
                                withContext(Dispatchers.Main) { speaker.play(audio) { previewVoice = null } }
                            } catch (e: Exception) {
                                previewVoice = null
                                hint = e.message ?: context.getString(R.string.persona_voice_preview_need_tts)
                            }
                        }
                    }
                })
            VoiceGroup("男声", Voices.MALE, current, previewVoice, settings.tts.configured,
                onSelect = { update(persona.copy(voice = it, voiceChosen = true)) },
                onNeedTts = { hint = context.getString(R.string.persona_voice_preview_need_tts) },
                onPreview = { option ->
                    if (previewVoice == option.id) {
                        speaker.stop()
                        previewVoice = null
                    } else {
                        hint = null
                        previewVoice = option.id
                        val voice = settings.tts.copy(voice = option.id, speed = speed)
                        scope.launch {
                            try {
                                val audio = withContext(Dispatchers.IO) { api.speak(voice, PREVIEW_LINE) }
                                withContext(Dispatchers.Main) { speaker.play(audio) { previewVoice = null } }
                            } catch (e: Exception) {
                                previewVoice = null
                                hint = e.message ?: context.getString(R.string.persona_voice_preview_need_tts)
                            }
                        }
                    }
                })
            Column(Modifier.fillMaxWidth().padding(top = Space.sm)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("语速", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(
                        String.format(java.util.Locale.ROOT, "%.1f×", speed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Slider(
                    value = speed,
                    onValueChange = { speed = ((it * 10).roundToInt() / 10f).coerceIn(0.8f, 1.2f) },
                    valueRange = 0.8f..1.2f,
                    steps = 3,
                    onValueChangeFinished = {
                        if (settings.tts.speed != speed) {
                            graph.settings.save(settings.copy(tts = settings.tts.copy(speed = speed)))
                        }
                    },
                )
                Row(Modifier.fillMaxWidth()) {
                    Text("0.8×", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Text("1.2×", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (clones.isNotEmpty()) {
                Text(
                    "我的克隆音色",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                clones.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        row.forEach { clone ->
                            FilterChip(
                                selected = current == clone.uri,
                                onClick = { update(persona.copy(voice = clone.uri, voiceChosen = true)) },
                                label = { Text(clone.name) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (row.size == 1) Box(Modifier.weight(1f))
                    }
                }
            }
            TextButton(onClick = onOpenVoiceClone, modifier = Modifier.tapTarget()) { Text("声音克隆 · 录制与管理") }
            Text(
                stringResource(R.string.persona_voice_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }

        PersonaCard("关系档案") {
            Text(
                "共同经历、未完成的话题、习惯打卡与边界，都在关系档案里。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onOpenRelation, modifier = Modifier.tapTarget()) { Text("打开关系档案") }
        }

        TextButton(onClick = { resetConfirm = true }) { Text(stringResource(R.string.persona_reset)) }
        if (resetConfirm) {
            Card(shape = RoundedCornerShape(Radius.lg)) {
                Column(Modifier.padding(Space.lg)) {
                    Text(stringResource(R.string.persona_reset_confirm), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        Button(onClick = {
                            update(PersonaSettings.reset(persona).copy(nickname = appName))
                            nickname = appName
                            bioDraft = ""
                            resetConfirm = false
                        }) { Text(stringResource(R.string.persona_reset_yes)) }
                        TextButton(onClick = { resetConfirm = false }) { Text(stringResource(R.string.persona_reset_no)) }
                    }
                }
            }
        }
        Text(
            "旧对话、记忆、关系档案与做梦记录里的历史内容不会被改写。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        }
    }
}

@Composable
private fun PersonaCard(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = LocalXiaomanPalette.current.ink2,
        )
        content()
    }
}

/** 六个字段与设计稿一致；保存后下一条动态立刻使用新设定。 */
@Composable
private fun LifeFields(life: LifeSettings, name: String, onSave: (LifeSettings) -> Unit) {
    var draft by remember(life) { mutableStateOf(life) }
    val hint = "会出现在「我们」的动态里；时间仍按你手机的时区，不按城市换算。"
    Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (draft.isEmpty) {
        Text(
            "$name 还没有自己的生活。填两笔，动态流才不会是空的。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    LifeInput("城市", draft.city, "例如：杭州", LifeSettings.MAX_CITY) { draft = draft.copy(city = it) }
    LifeInput("在做什么", draft.doing, "例如：在小书店选书，周三休息", LifeSettings.MAX_DOING) { draft = draft.copy(doing = it) }
    LifeInput("作息", draft.routine, "例如：七点起，十一点半睡", LifeSettings.MAX_ROUTINE) { draft = draft.copy(routine = it) }
    LifeInput("宠物", draft.pet, "例如：一只叫年糕的橘猫", LifeSettings.MAX_PET) { draft = draft.copy(pet = it) }
    LifeInput("在读 / 在追", draft.media, "例如：在读《夜航西飞》", LifeSettings.MAX_MEDIA) { draft = draft.copy(media = it) }
    LifeInput("一句自述", draft.bio, "例如：不太会安慰人，但会一直听", LifeSettings.MAX_BIO) { draft = draft.copy(bio = it) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(enabled = draft.trimmed() != life, onClick = { onSave(draft.trimmed()) }) { Text("保存生活") }
        TextButton(onClick = {
            draft = LifeSettings.EMPTY
            onSave(LifeSettings.EMPTY)
        }) { Text("清空") }
        Text(
            "改完点保存",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LifeInput(label: String, value: String, placeholder: String, max: Int, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= max) onChange(it) },
        label = { Text(label) },
        placeholder = { Text(placeholder, style = MaterialTheme.typography.bodySmall) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 一组音色：整行点选，右侧只负责试听，两个动作互不打断。 */
@Composable
private fun VoiceGroup(
    title: String,
    options: List<VoiceOption>,
    current: String,
    previewVoice: String?,
    configured: Boolean,
    onSelect: (String) -> Unit,
    onNeedTts: () -> Unit,
    onPreview: (VoiceOption) -> Unit,
) {
    Text(
        title,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Space.sm),
    )
    options.forEach { option ->
        val selected = option.id == current
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.sm))
                .clickable { onSelect(option.id) }
                .padding(vertical = 6.dp, horizontal = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                if (selected) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Column(Modifier.weight(1f).padding(start = Space.sm)) {
                Text(
                    "${option.label} · ${option.id.substringAfter(':')}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    option.desc,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = { if (configured) onPreview(option) else onNeedTts() },
                modifier = Modifier.tapTarget(),
            ) {
                Text(
                    stringResource(if (previewVoice == option.id) R.string.persona_voice_previewing else R.string.persona_voice_preview),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
