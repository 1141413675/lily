package dev.xiaoman.app.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import dev.xiaoman.app.media.SceneryStore
import dev.xiaoman.app.persona.Gender
import dev.xiaoman.app.persona.PersonaSettings
import dev.xiaoman.app.persona.VoiceOption
import dev.xiaoman.app.persona.Voices
import dev.xiaoman.app.portrait.PortraitStore
import dev.xiaoman.app.settings.AppSettings
import dev.xiaoman.app.voice.Speaker
import dev.xiaoman.app.voice.VoiceApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.roundToInt

/** 试听与样本共用同一句，换音色时听到的差异只来自音色本身。 */
private const val PREVIEW_LINE = "今天有点累，不过看到你就好了。"

/** 从单独对话进入，编辑当前角色的资料、生活和声音。 */
@Composable
fun PersonaScreen(
    graph: AppGraph,
    settings: AppSettings,
    portraitStore: PortraitStore,
    sceneryStore: SceneryStore,
    onSaveSettings: (AppSettings) -> Boolean,
    onBack: () -> Unit,
    isPrimary: Boolean = false,
    onOpenVoiceClone: (() -> Unit)? = null,
    onOpenRelation: (() -> Unit)? = null,
) {
    val persona = settings.persona
    val appName = stringResource(R.string.app_name)
    val displayName = persona.nameOr(appName)
    var editing by rememberSaveable { mutableStateOf<AgentProfileField?>(null) }
    var editingAvatar by rememberSaveable { mutableStateOf(false) }
    var resetConfirm by remember { mutableStateOf(false) }
    var clearLifeConfirm by remember { mutableStateOf(false) }
    var previewVoice by remember { mutableStateOf<String?>(null) }
    var hint by remember { mutableStateOf<String?>(null) }
    var speed by remember(settings.tts.speed) { mutableStateOf(settings.tts.speed) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val speaker = remember { Speaker(File(context.cacheDir, "voice").apply { mkdirs() }) }
    val api = remember { VoiceApi() }

    fun save(next: AppSettings): Boolean {
        if (onSaveSettings(next)) return true
        Toast.makeText(context, "保存失败，请重试", Toast.LENGTH_SHORT).show()
        return false
    }

    fun update(p: PersonaSettings) = save(settings.copy(persona = p))

    DisposableEffect(speaker) { onDispose { speaker.stop() } }
    val palette = LocalXiaomanPalette.current
    if (editingAvatar) {
        SettingsSubpage("角色头像", onBack = { editingAvatar = false }) {
            PortraitCard(graph, store = portraitStore)
        }
        return
    }

    val today = LocalDate.now()
    val birthday = persona.profileBirthdayText()
    val age = persona.ageAt(today)
    SettingsSubpage("${displayName}的设置", onBack) {
        ProfileGroup("基本资料") {
            ProfileRow(
                "头像",
                value = null,
                trailingContent = { PortraitAvatar(graph, SizeTokens.avatarHeader, store = portraitStore) },
                onClick = { editingAvatar = true },
            )
            AgentProfileRow(AgentProfileField.NICKNAME, settings) { editing = it }
            AgentProfileRow(AgentProfileField.GENDER, settings) { editing = it }
            ProfileRow(
                "生日",
                birthday,
                hint = if (birthday != null) "年龄按生日实时算" else "1998-03-05，留空用固定年龄",
                onClick = { editing = AgentProfileField.BIRTHDAY },
            )
            if (birthday != null) {
                ProfileRow("年龄", age?.let { "$it 岁 · 由生日计算" }, editable = false)
            } else {
                ProfileRow("年龄", age?.let { "$it 岁" }, onClick = { editing = AgentProfileField.AGE })
            }
            AgentProfileRow(AgentProfileField.HOMETOWN, settings) { editing = it }
            AgentProfileRow(AgentProfileField.CITY, settings) { editing = it }
        }
        ProfileGroup("生活") {
            AgentProfileRow(AgentProfileField.EDUCATION, settings) { editing = it }
            AgentProfileRow(AgentProfileField.PET, settings) { editing = it }
            AgentProfileRow(AgentProfileField.RELATIONSHIP, settings) { editing = it }
            AgentProfileRow(AgentProfileField.LIFESTYLE, settings) { editing = it }
        }
        ProfileGroup("关于角色") {
            AgentProfileRow(AgentProfileField.APPEARANCE, settings) { editing = it }
            AgentProfileRow(AgentProfileField.PERSONALITY, settings) { editing = it }
            AgentProfileRow(AgentProfileField.SPEECH_STYLE, settings) { editing = it }
            AgentProfileRow(AgentProfileField.BACKGROUND, settings) { editing = it }
        }
        ProfileGroup("角色设定") {
            AgentProfileRow(AgentProfileField.BIO, settings) { editing = it }
        }

        if (isPrimary && settings.personaVoiceNotice) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Radius.lg),
                colors = CardDefaults.cardColors(containerColor = palette.accentSoft),
            ) {
                Column(Modifier.padding(Space.lg)) {
                    Text(stringResource(R.string.persona_voice_migrated), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { graph.settings.clearVoiceNotice() }) {
                        Text(stringResource(R.string.persona_voice_migrated_ok))
                    }
                }
            }
        }

        PersonaCard(stringResource(R.string.settings_emoji)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                EmojiStyle.entries.forEach { style ->
                    FilterChip(
                        selected = settings.emojiStyle == style,
                        onClick = { save(settings.copy(emojiStyle = style)) },
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

        ProfileGroup("生活细节") {
            AgentProfileRow(AgentProfileField.DOING, settings) { editing = it }
            AgentProfileRow(AgentProfileField.ROUTINE, settings) { editing = it }
            AgentProfileRow(AgentProfileField.MEDIA, settings) { editing = it }
            AgentProfileRow(AgentProfileField.LIFE_BIO, settings) { editing = it }
        }
        PersonaCard("生活图库") {
            if (isPrimary) {
                Text(
                    "新动态会参考这些生活设定，时间按手机时区计算。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ScenerySection(sceneryStore, pronoun = persona.pronoun)
            TextButton(
                enabled = !settings.life.isEmpty,
                onClick = { clearLifeConfirm = true },
            ) { Text("清空生活设定") }
        }

        PersonaCard(stringResource(R.string.persona_voice)) {
            val clones by graph.clones.voices.collectAsStateWithLifecycle()
            // 实际音色：显式选择优先；留空时按性别取默认（硅基 claire/alex，豆包 Vivi/云舟）。
            val provider = settings.tts.provider
            val current = when {
                provider == dev.xiaoman.app.settings.TtsProvider.DOUBAO && Voices.isDoubao(persona.voice.trim()) ->
                    persona.voice.trim()
                provider == dev.xiaoman.app.settings.TtsProvider.DOUBAO -> Voices.doubaoDefault(persona.gender)
                else -> Voices.effective(persona.voice, persona.gender)
            }
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
                TextButton(
                    onClick = {
                        val fallback = if (provider == dev.xiaoman.app.settings.TtsProvider.DOUBAO) {
                            Voices.doubaoDefault(persona.gender)
                        } else {
                            Voices.defaultVoice(persona.gender)
                        }
                        update(persona.copy(voice = fallback, voiceChosen = true))
                    },
                    modifier = Modifier.tapTarget(),
                ) { Text("换成${persona.gender.label}声") }
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
                        // 试听同样走当前引擎，音色按供应商换算，切到豆包后不会再把硅基 id 发过去。
                        val voice = settings.tts.request(current, persona.gender)
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
            // 音色列表跟着引擎走：两套 voice id 不通用，混着列出来点了也会报错。
            val femaleVoices = if (provider == dev.xiaoman.app.settings.TtsProvider.DOUBAO) Voices.DOUBAO_FEMALE else Voices.FEMALE
            val maleVoices = if (provider == dev.xiaoman.app.settings.TtsProvider.DOUBAO) Voices.DOUBAO_MALE else Voices.MALE
            VoiceGroup("女声", femaleVoices, current, previewVoice, settings.tts.configured,
                onSelect = { update(persona.copy(voice = it, voiceChosen = true)) },
                onNeedTts = { hint = context.getString(R.string.persona_voice_preview_need_tts) },
                onPreview = { option ->
                    if (previewVoice == option.id) {
                        speaker.stop()
                        previewVoice = null
                    } else {
                        hint = null
                        previewVoice = option.id
                        val voice = settings.tts.request(option.id, persona.gender)
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
            VoiceGroup("男声", maleVoices, current, previewVoice, settings.tts.configured,
                onSelect = { update(persona.copy(voice = it, voiceChosen = true)) },
                onNeedTts = { hint = context.getString(R.string.persona_voice_preview_need_tts) },
                onPreview = { option ->
                    if (previewVoice == option.id) {
                        speaker.stop()
                        previewVoice = null
                    } else {
                        hint = null
                        previewVoice = option.id
                        val voice = settings.tts.request(option.id, persona.gender)
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
                            // 语速两套引擎共用：保存时同时写进硅基与豆包 的配置。
                            if (!save(settings.copy(tts = settings.tts.withSpeed(speed)))) {
                                speed = settings.tts.speed
                            }
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
            if (onOpenVoiceClone != null) {
                TextButton(onClick = onOpenVoiceClone, modifier = Modifier.tapTarget()) {
                    Text("声音克隆 · 录制与管理")
                }
            }
            Text(
                stringResource(R.string.persona_voice_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }

        if (onOpenRelation != null) {
            PersonaCard("关系档案") {
                Text(
                    "共同经历、未完成的话题、习惯打卡与边界，都在关系档案里。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onOpenRelation, modifier = Modifier.tapTarget()) { Text("打开关系档案") }
            }
        }

        TextButton(onClick = { resetConfirm = true }) { Text(stringResource(R.string.persona_reset)) }
    }

    editing?.let { field ->
        AgentProfileEditDialog(
            field = field,
            settings = settings,
            today = today,
            onDismiss = { editing = null },
            onSave = { next -> if (save(next)) editing = null },
        )
    }
    if (clearLifeConfirm) {
        AlertDialog(
            onDismissRequest = { clearLifeConfirm = false },
            title = { Text("清空生活设定") },
            text = { Text("会清空常驻城市、宠物和生活细节。") },
            confirmButton = {
                TextButton(onClick = {
                    if (save(settings.copy(life = LifeSettings.EMPTY))) clearLifeConfirm = false
                }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { clearLifeConfirm = false }) { Text("取消") } },
        )
    }
    if (resetConfirm) {
        AlertDialog(
            onDismissRequest = { resetConfirm = false },
            title = { Text(stringResource(R.string.persona_reset)) },
            text = { Text("会清空填写的角色资料和人设，并恢复默认性别和表达方式。") },
            confirmButton = {
                TextButton(onClick = {
                    val resetName = if (isPrimary) appName else persona.nickname
                    if (update(PersonaSettings.reset(persona).copy(nickname = resetName))) {
                        resetConfirm = false
                    }
                }) { Text(stringResource(R.string.persona_reset_yes)) }
            },
            dismissButton = {
                TextButton(onClick = { resetConfirm = false }) { Text(stringResource(R.string.persona_reset_no)) }
            },
        )
    }
}

@Composable
private fun PersonaCard(title: String, content: @Composable () -> Unit) {
    val palette = LocalXiaomanPalette.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.lg),
        color = palette.surface,
        border = BorderStroke(1.dp, palette.line2),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = palette.ink)
            content()
        }
    }
}

@Composable
private fun AgentProfileRow(
    field: AgentProfileField,
    settings: AppSettings,
    onEdit: (AgentProfileField) -> Unit,
) {
    ProfileRow(field.label, field.value(settings).takeIf(String::isNotBlank), onClick = { onEdit(field) })
}

@Composable
private fun AgentProfileEditDialog(
    field: AgentProfileField,
    settings: AppSettings,
    today: LocalDate,
    onDismiss: () -> Unit,
    onSave: (AppSettings) -> Unit,
) {
    val current = field.value(settings)
    fun submit(value: String) {
        if (value == current) onDismiss()
        else onSave(field.withText(settings, value))
    }

    if (field == AgentProfileField.GENDER) {
        ProfileGenderEditDialog(
            initial = settings.persona.gender,
            options = Gender.entries,
            label = { it.label },
            onDismiss = onDismiss,
            onConfirm = { onSave(settings.copy(persona = settings.persona.copy(gender = it))) },
        )
    } else {
        ProfileTextEditDialog(
            title = field.label,
            initial = current,
            hint = field.hint,
            maxLength = field.maxLength,
            multiline = field.multiline,
            keyboardType = if (field == AgentProfileField.AGE) KeyboardType.Number else KeyboardType.Text,
            onDismiss = onDismiss,
            onConfirm = ::submit,
            validate = { raw -> field.validate(raw.trim(), current, today) },
        )
    }
}

private fun PersonaSettings.profileBirthdayText(): String? {
    birthdayDate()?.let { return it.toString() }
    if (birthdayDay != null) return null
    val year = birthdayYear?.takeIf { it in PersonaSettings.BIRTH_YEAR_RANGE } ?: return null
    val month = birthdayMonth?.takeIf { it in 1..12 } ?: return null
    return YearMonth.of(year, month).toString()
}

private enum class AgentProfileField(
    val label: String,
    val hint: String,
    val multiline: Boolean = false,
    val maxLength: Int = PersonaSettings.MAX_PROFILE_TEXT,
) {
    NICKNAME("昵称", "希望在聊天中怎么称呼这个角色", maxLength = PersonaSettings.MAX_NAME),
    GENDER("性别", ""),
    BIRTHDAY("生日", "1998-03-05；留空就用固定年龄", maxLength = 10),
    AGE("年龄", "没填生日时使用", maxLength = 3),
    HOMETOWN("家乡", "比如：杭州"),
    CITY("常驻城市", "现在住哪儿", maxLength = LifeSettings.MAX_CITY),
    EDUCATION("学历", "比如：本科 / 在读研究生"),
    PET("宠物", "比如：一只叫团子的猫", maxLength = LifeSettings.MAX_PET),
    RELATIONSHIP("感情状态", "比如：单身 / 有伴侣"),
    LIFESTYLE("生活偏好", "作息、饮食、喜欢的东西", multiline = true),
    APPEARANCE("外貌特征", "比如：短发、戴眼镜", multiline = true),
    PERSONALITY("性格设定", "比如：慢热、爱开玩笑", multiline = true),
    SPEECH_STYLE("语气设定", "比如：说话慢一点，常用短句", multiline = true),
    BACKGROUND("经历补充", "角色过去发生过的事", multiline = true, maxLength = PersonaSettings.MAX_BACKGROUND),
    BIO("人设", "写角色是谁、怎么说话、在意什么", multiline = true, maxLength = PersonaSettings.MAX_BIO),
    DOING("在做什么", "比如：在小书店选书，周三休息", maxLength = LifeSettings.MAX_DOING),
    ROUTINE("作息", "比如：七点起，十一点半睡", maxLength = LifeSettings.MAX_ROUTINE),
    MEDIA("在读 / 在追", "比如：在读《夜航西飞》", maxLength = LifeSettings.MAX_MEDIA),
    LIFE_BIO("一句自述", "比如：不太会安慰人，但会一直听", maxLength = LifeSettings.MAX_BIO);

    fun value(settings: AppSettings): String {
        val persona = settings.persona
        val life = settings.life
        return when (this) {
            NICKNAME -> persona.nickname
            GENDER -> persona.gender.label
            BIRTHDAY -> persona.profileBirthdayText().orEmpty()
            AGE -> persona.fixedAge?.toString().orEmpty()
            HOMETOWN -> persona.hometown
            CITY -> life.city
            EDUCATION -> persona.education
            PET -> life.pet
            RELATIONSHIP -> persona.relationshipStatus
            LIFESTYLE -> persona.lifestyle
            APPEARANCE -> persona.appearance
            PERSONALITY -> persona.personality
            SPEECH_STYLE -> persona.speechStyle
            BACKGROUND -> persona.background
            BIO -> persona.bio
            DOING -> life.doing
            ROUTINE -> life.routine
            MEDIA -> life.media
            LIFE_BIO -> life.bio
        }
    }

    fun validate(value: String, current: String, today: LocalDate): String? {
        if (value.isBlank() || value == current) return null
        return when (this) {
            BIRTHDAY -> {
                val date = runCatching { LocalDate.parse(value) }.getOrNull()
                if (date == null || date.isAfter(today) || date.year !in PersonaSettings.BIRTH_YEAR_RANGE) {
                    "按 1998-03-05 填写生日，或留空用固定年龄"
                } else {
                    null
                }
            }
            AGE -> {
                val age = value.toIntOrNull()
                if (age == null || age !in PersonaSettings.AGE_RANGE) "填 1–130 之间的年龄" else null
            }
            else -> null
        }
    }

    fun withText(settings: AppSettings, value: String): AppSettings {
        val persona = settings.persona
        val life = settings.life
        return when (this) {
            NICKNAME -> settings.copy(persona = persona.copy(nickname = value.ifBlank { PersonaSettings.DEFAULT_NICKNAME }))
            GENDER -> error("Gender uses the choice dialog.")
            BIRTHDAY -> {
                val date = value.takeIf(String::isNotBlank)?.let(LocalDate::parse)
                settings.copy(persona = persona.copy(
                    birthdayYear = date?.year,
                    birthdayMonth = date?.monthValue,
                    birthdayDay = date?.dayOfMonth,
                    fixedAge = if (date == null) persona.ageAt() ?: persona.fixedAge else null,
                ))
            }
            AGE -> settings.copy(persona = persona.copy(fixedAge = value.toIntOrNull()))
            HOMETOWN -> settings.copy(persona = persona.copy(hometown = value))
            CITY -> settings.copy(life = life.copy(city = value))
            EDUCATION -> settings.copy(persona = persona.copy(education = value))
            PET -> settings.copy(life = life.copy(pet = value))
            RELATIONSHIP -> settings.copy(persona = persona.copy(relationshipStatus = value))
            LIFESTYLE -> settings.copy(persona = persona.copy(lifestyle = value))
            APPEARANCE -> settings.copy(persona = persona.copy(appearance = value))
            PERSONALITY -> settings.copy(persona = persona.copy(personality = value))
            SPEECH_STYLE -> settings.copy(persona = persona.copy(speechStyle = value))
            BACKGROUND -> settings.copy(persona = persona.copy(background = value))
            BIO -> settings.copy(persona = persona.copy(bio = value))
            DOING -> settings.copy(life = life.copy(doing = value))
            ROUTINE -> settings.copy(life = life.copy(routine = value))
            MEDIA -> settings.copy(life = life.copy(media = value))
            LIFE_BIO -> settings.copy(life = life.copy(bio = value))
        }
    }
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
