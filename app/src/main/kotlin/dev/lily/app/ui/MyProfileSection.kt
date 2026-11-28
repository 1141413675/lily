package dev.lily.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lily.app.AppGraph
import dev.lily.app.profile.MyProfile
import dev.lily.app.profile.ProfileGender
import java.time.LocalDate

/**
 * 「我的」页顶部的用户本人入口：只留头像、昵称与一行摘要，整卡点开进入资料页。
 * 字段本身与存储不动，首页不再重复罗列，统一收进 [MyProfileScreen] 编辑。
 */
@Composable
fun MyProfileSection(graph: AppGraph, onOpen: () -> Unit) {
    val profile by graph.myProfile.state.collectAsStateWithLifecycle()
    val pickAvatar = rememberPortraitPicker(graph, graph.myAvatar)
    val age = profile.ageAt(LocalDate.now())
    MeCard(
        graph = graph,
        profile = profile,
        age = age,
        onPickAvatar = pickAvatar,
        onTap = onOpen,
    )
}

/** 我的资料：基本资料、生活、关于你三组都在这里编辑，返回「我的」首页。 */
@Composable
fun MyProfileScreen(graph: AppGraph, onBack: () -> Unit) {
    val profile by graph.myProfile.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<MeEdit?>(null) }
    val today = LocalDate.now()
    val age = profile.ageAt(today)

    SettingsSubpage("我的资料", onBack) {
        ProfileGroup("基本资料") {
            ProfileRow("昵称", profile.nickname.trim().takeIf { it.isNotEmpty() }, onClick = { editing = MeEdit.Nickname })
            ProfileRow("性别", profile.gender.takeIf { it != ProfileGender.UNSET }?.label, onClick = { editing = MeEdit.Gender })
            ProfileRow(
                "生日",
                profile.birthday.trim().takeIf { it.isNotEmpty() },
                hint = if (profile.hasBirthday) "年龄按生日实时算" else "1998-03-05，留空用固定年龄",
                onClick = { editing = MeEdit.Birthday },
            )
            if (profile.hasBirthday) {
                ProfileRow("年龄", age?.let { "$it 岁 · 由生日计算" }, editable = false)
            } else {
                ProfileRow("年龄", age?.let { "$it 岁" }, onClick = { editing = MeEdit.Age })
            }
            ProfileRow("家乡", profile.hometown.takeIf { it.isNotBlank() }, onClick = { editing = MeEdit.text(MeTextField.HOMETOWN) })
            ProfileRow("常驻城市", profile.city.takeIf { it.isNotBlank() }, onClick = { editing = MeEdit.text(MeTextField.CITY) })
        }
        ProfileGroup("生活") {
            ProfileRow("学历", profile.education.takeIf { it.isNotBlank() }, onClick = { editing = MeEdit.text(MeTextField.EDUCATION) })
            ProfileRow("宠物", profile.pets.takeIf { it.isNotBlank() }, onClick = { editing = MeEdit.text(MeTextField.PETS) })
            ProfileRow("感情状态", profile.relationship.takeIf { it.isNotBlank() }, onClick = { editing = MeEdit.text(MeTextField.RELATIONSHIP) })
            ProfileRow("生活偏好", profile.lifestyle.takeIf { it.isNotBlank() }, onClick = { editing = MeEdit.text(MeTextField.LIFESTYLE) })
        }
        ProfileGroup("关于你") {
            ProfileRow("外貌特征", profile.appearance.takeIf { it.isNotBlank() }, onClick = { editing = MeEdit.text(MeTextField.APPEARANCE) })
            ProfileRow("性格设定", profile.personality.takeIf { it.isNotBlank() }, onClick = { editing = MeEdit.text(MeTextField.PERSONALITY) })
            ProfileRow("语气设定", profile.tone.takeIf { it.isNotBlank() }, onClick = { editing = MeEdit.text(MeTextField.TONE) })
            ProfileRow("经历补充", profile.background.takeIf { it.isNotBlank() }, onClick = { editing = MeEdit.text(MeTextField.BACKGROUND) })
        }
    }

    editing?.let { target ->
        MeEditDialog(
            target = target,
            profile = profile,
            today = today,
            onDismiss = { editing = null },
            onSave = { next ->
                graph.myProfile.save(next)
                editing = null
            },
        )
    }
}

@Composable
private fun MeCard(
    graph: AppGraph,
    profile: MyProfile,
    age: Int?,
    onPickAvatar: () -> Unit,
    onTap: () -> Unit,
) {
    val palette = LocalLilyPalette.current
    val summary = remember(profile, age) {
        buildList {
            if (profile.gender != ProfileGender.UNSET) add(profile.gender.label)
            age?.let { add("$it 岁") }
            profile.birthday.trim().takeIf { it.isNotEmpty() }?.let { add("生日 $it") }
        }.joinToString(" · ").ifBlank { "性别、年龄、生日还没填" }
    }
    Surface(
        shape = RoundedCornerShape(Radius.lg),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = Elevation.card,
        modifier = Modifier.fillMaxWidth().pressClick(pressedScale = 0.985f, onClick = onTap),
    ) {
        Row(Modifier.fillMaxWidth().padding(Space.lg), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(72.dp)) {
                Box(
                    Modifier.fillMaxSize().clip(CircleShape)
                        .pressClick(pressedScale = 0.94f, onClick = onPickAvatar),
                ) {
                    PortraitAvatar(graph, 72.dp, store = graph.myAvatar)
                }
                Box(
                    Modifier.align(Alignment.BottomEnd).size(24.dp).clip(CircleShape)
                        .background(palette.accentSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.PhotoCamera,
                        contentDescription = null,
                        tint = palette.accent,
                        modifier = Modifier.size(IconSize.sm),
                    )
                }
            }
            Column(
                Modifier.weight(1f).padding(start = Space.lg),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    profile.nickname.trim().ifBlank { "还没填昵称" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(summary, style = MaterialTheme.typography.bodySmall, color = palette.ink2)
                if (profile.isEmpty) {
                    Text(
                        "点这里填一下自己，让聊天更贴近你。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Text(
                        "点头像换照片 · 点开修改资料",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.ink3,
                    )
                }
            }
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = palette.ink3,
                modifier = Modifier.size(IconSize.md),
            )
        }
    }
}

@Composable
internal fun ProfileGroup(title: String, content: @Composable () -> Unit) {
    val palette = LocalLilyPalette.current
    Column(Modifier.fillMaxWidth()) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = palette.ink2,
            modifier = Modifier.padding(start = Space.xs, bottom = Space.sm),
        )
        Surface(
            shape = RoundedCornerShape(Radius.lg),
            color = palette.surface,
            border = BorderStroke(1.dp, palette.line2),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column { content() }
        }
    }
}

/** 一行一个字段：左标签、右值和编辑入口。 */
@Composable
internal fun ProfileRow(
    label: String,
    value: String?,
    hint: String? = null,
    editable: Boolean = true,
    trailingContent: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val palette = LocalLilyPalette.current
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .heightIn(min = SizeTokens.touchTarget)
                .then(
                    if (onClick != null) {
                        Modifier.pressClick(pressedScale = 0.995f, onClick = onClick)
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = Space.xl, vertical = Space.sm),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = palette.ink, modifier = Modifier.weight(1f))
            if (trailingContent != null) {
                Box(Modifier.weight(1.3f), contentAlignment = Alignment.CenterEnd) {
                    trailingContent()
                }
            } else {
                Text(
                    value ?: "未填",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (value == null) palette.ink3 else palette.ink2,
                    textAlign = TextAlign.End,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1.3f),
                )
            }
            if (editable) {
                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = palette.ink3,
                    modifier = Modifier.size(IconSize.md),
                )
            }
        }
        if (hint != null) {
            Text(
                hint,
                style = MaterialTheme.typography.labelSmall,
                color = palette.ink3,
                modifier = Modifier.padding(start = Space.xl, end = Space.xl, bottom = Space.sm),
            )
        }
    }
}

private sealed interface MeEdit {
    data object Nickname : MeEdit
    data object Gender : MeEdit
    data object Birthday : MeEdit
    data object Age : MeEdit
    data class Text(val field: MeTextField) : MeEdit

    companion object {
        fun text(field: MeTextField): MeEdit = Text(field)
    }
}

private enum class MeTextField(
    val label: String,
    val hint: String,
    val multiline: Boolean = false,
    val maxLength: Int = 120,
) {
    HOMETOWN("家乡", "比如：杭州"),
    CITY("常驻城市", "现在住哪儿"),
    EDUCATION("学历", "比如：本科 / 在读研究生"),
    PETS("宠物", "比如：一只叫团子的猫"),
    RELATIONSHIP("感情状态", "比如：单身 / 有伴侣"),
    APPEARANCE("外貌特征", "一两句就够，比如：短发、戴眼镜", multiline = true),
    PERSONALITY("性格设定", "比如：慢热、爱开玩笑、怕麻烦别人", multiline = true),
    TONE("语气设定", "你喜欢的说话方式，角色仍会保留自己的口吻", multiline = true),
    LIFESTYLE("生活偏好", "作息、饮食、喜欢的东西", multiline = true),
    BACKGROUND("经历补充", "想让角色记住的事", multiline = true, maxLength = 300),
}

private fun MyProfile.valueOf(field: MeTextField): String = when (field) {
    MeTextField.HOMETOWN -> hometown
    MeTextField.CITY -> city
    MeTextField.EDUCATION -> education
    MeTextField.PETS -> pets
    MeTextField.RELATIONSHIP -> relationship
    MeTextField.APPEARANCE -> appearance
    MeTextField.PERSONALITY -> personality
    MeTextField.TONE -> tone
    MeTextField.LIFESTYLE -> lifestyle
    MeTextField.BACKGROUND -> background
}

private fun MyProfile.with(field: MeTextField, value: String): MyProfile = when (field) {
    MeTextField.HOMETOWN -> copy(hometown = value)
    MeTextField.CITY -> copy(city = value)
    MeTextField.EDUCATION -> copy(education = value)
    MeTextField.PETS -> copy(pets = value)
    MeTextField.RELATIONSHIP -> copy(relationship = value)
    MeTextField.APPEARANCE -> copy(appearance = value)
    MeTextField.PERSONALITY -> copy(personality = value)
    MeTextField.TONE -> copy(tone = value)
    MeTextField.LIFESTYLE -> copy(lifestyle = value)
    MeTextField.BACKGROUND -> copy(background = value)
}

@Composable
private fun MeEditDialog(
    target: MeEdit,
    profile: MyProfile,
    today: LocalDate,
    onDismiss: () -> Unit,
    onSave: (MyProfile) -> Unit,
) {
    when (target) {
        MeEdit.Nickname -> ProfileTextEditDialog(
            title = "昵称",
            initial = profile.nickname,
            hint = "希望在聊天中怎么称呼你",
            maxLength = 12,
            onDismiss = onDismiss,
            onConfirm = { onSave(profile.copy(nickname = it)) },
        )
        MeEdit.Birthday -> ProfileTextEditDialog(
            title = "生日",
            initial = profile.birthday,
            hint = "1998-03-05；留空就用固定年龄",
            maxLength = 10,
            onDismiss = onDismiss,
            validate = { raw ->
                if (raw.isBlank()) {
                    null
                } else {
                    val date = runCatching { LocalDate.parse(raw.trim()) }.getOrNull()
                    if (date == null || date.year !in MyProfile.BIRTH_YEAR_RANGE || date.isAfter(today)) {
                        "按 1998-03-05 填，或留空用固定年龄"
                    } else {
                        null
                    }
                }
            },
            onConfirm = { onSave(profile.copy(birthday = it)) },
        )
        MeEdit.Age -> ProfileTextEditDialog(
            title = "年龄",
            initial = profile.fixedAge?.toString().orEmpty(),
            hint = "没填生日时使用",
            maxLength = 3,
            keyboardType = KeyboardType.Number,
            onDismiss = onDismiss,
            validate = { raw ->
                if (raw.isBlank()) {
                    null
                } else {
                    val value = raw.toIntOrNull()
                    if (value == null || value !in MyProfile.AGE_RANGE) "填 0–130 之间的数字" else null
                }
            },
            onConfirm = { raw ->
                onSave(profile.copy(fixedAge = raw.toIntOrNull()))
            },
        )
        MeEdit.Gender -> ProfileGenderEditDialog(
            initial = profile.gender,
            options = ProfileGender.entries,
            label = { it.label },
            onDismiss = onDismiss,
            onConfirm = { onSave(profile.copy(gender = it)) },
        )
        is MeEdit.Text -> ProfileTextEditDialog(
            title = target.field.label,
            initial = profile.valueOf(target.field),
            hint = target.field.hint,
            maxLength = target.field.maxLength,
            multiline = target.field.multiline,
            onDismiss = onDismiss,
            onConfirm = { onSave(profile.with(target.field, it)) },
        )
    }
}

/** 文本编辑统一弹层：单行/多行、长度限制、可选校验；保存前 trim。 */
@Composable
internal fun ProfileTextEditDialog(
    title: String,
    initial: String,
    hint: String,
    maxLength: Int,
    multiline: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    validate: (String) -> String? = { null },
) {
    var text by rememberSaveable(title) { mutableStateOf(initial) }
    var error by rememberSaveable(title) { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        if (it.length <= maxLength) {
                            text = it
                            error = null
                        }
                    },
                    placeholder = { Text(hint, style = MaterialTheme.typography.bodySmall) },
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                    isError = error != null,
                    minLines = if (multiline) 3 else 1,
                    maxLines = if (multiline) 6 else 1,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val message = validate(text)
                    if (message != null) {
                        error = message
                    } else {
                        onConfirm(text.trim())
                    }
                },
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
internal fun <T> ProfileGenderEditDialog(
    initial: T,
    options: List<T>,
    label: (T) -> String,
    onDismiss: () -> Unit,
    onConfirm: (T) -> Unit,
) {
    var choice by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("性别", style = MaterialTheme.typography.titleMedium) },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                options.forEach { gender ->
                    FilterChip(
                        selected = choice == gender,
                        onClick = { choice = gender },
                        label = { Text(label(gender)) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(choice) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
