package dev.xiaoman.app.ui

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xiaoman.app.AppGraph
import dev.xiaoman.app.profile.MyProfile
import dev.xiaoman.app.profile.ProfileGender
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
        MeGroup("基本资料") {
            MeRow("昵称", profile.nickname.trim().takeIf { it.isNotEmpty() }, onClick = { editing = MeEdit.Nickname })
            MeRow("性别", profile.gender.takeIf { it != ProfileGender.UNSET }?.label, onClick = { editing = MeEdit.Gender })
            MeRow(
                "生日",
                profile.birthday.trim().takeIf { it.isNotEmpty() },
                hint = if (profile.hasBirthday) "年龄按生日实时算" else "1998-03-05，留空用固定年龄",
                onClick = { editing = MeEdit.Birthday },
            )
            if (profile.hasBirthday) {
                MeRow("年龄", age?.let { "$it 岁 · 由生日计算" }, editable = false)
            } else {
                MeRow("年龄", age?.let { "$it 岁" }, onClick = { editing = MeEdit.Age })
            }
            MeRow("家乡", profile.hometown.takeIf { it.isNotBlank() }, onClick = { editing = MeEdit.text(MeTextField.HOMETOWN) })
            MeRow("常驻城市", profile.city.takeIf { it.isNotBlank() }, onClick = { editing = MeEdit.text(MeTextField.CITY) })
        }
        MeGroup("生活") {
            MeRow("学历", profile.education.takeIf { it.isNotBlank() }, onClick = { editing = MeEdit.text(MeTextField.EDUCATION) })
            MeRow("宠物", profile.pets.takeIf { it.isNotBlank() }, onClick = { editing = MeEdit.text(MeTextField.PETS) })
            MeRow("感情状态", profile.relationship.takeIf { it.isNotBlank() }, onClick = { editing = MeEdit.text(MeTextField.RELATIONSHIP) })
            MeRow("生活偏好", profile.lifestyle.takeIf { it.isNotBlank() }, onClick = { editing = MeEdit.text(MeTextField.LIFESTYLE) })
        }
        MeGroup("关于你") {
            MeRow("外貌特征", profile.appearance.takeIf { it.isNotBlank() }, onClick = { editing = MeEdit.text(MeTextField.APPEARANCE) })
            MeRow("性格设定", profile.personality.takeIf { it.isNotBlank() }, onClick = { editing = MeEdit.text(MeTextField.PERSONALITY) })
            MeRow("语气设定", profile.tone.takeIf { it.isNotBlank() }, onClick = { editing = MeEdit.text(MeTextField.TONE) })
            MeRow("经历补充", profile.background.takeIf { it.isNotBlank() }, onClick = { editing = MeEdit.text(MeTextField.BACKGROUND) })
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
    val palette = LocalXiaomanPalette.current
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
                        "点这里填一下自己，她说话会更像对着你。",
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
private fun MeGroup(title: String, content: @Composable () -> Unit) {
    val palette = LocalXiaomanPalette.current
    Column(Modifier.fillMaxWidth()) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = palette.ink2,
            modifier = Modifier.padding(start = Space.xs, bottom = Space.sm),
        )
        Surface(
            shape = RoundedCornerShape(Radius.lg),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = Elevation.card,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column { content() }
        }
    }
}

/** 一行一个字段：左标签、右值、箭头；整行至少 44dp 热区。 */
@Composable
private fun MeRow(
    label: String,
    value: String?,
    hint: String? = null,
    editable: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val palette = LocalXiaomanPalette.current
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .heightIn(min = 48.dp)
                .then(
                    if (onClick != null) {
                        Modifier.pressClick(pressedScale = 0.995f, onClick = onClick)
                    } else {
                        Modifier
                    },
                )
                // 不再画分隔线：靠行高与上下留白分层，读起来不像后台设置表。
                .padding(horizontal = Space.lg, vertical = Space.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = palette.ink, modifier = Modifier.weight(1f))
            Text(
                value ?: "未填",
                style = MaterialTheme.typography.bodySmall,
                color = if (value == null) palette.ink3 else palette.ink2,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 190.dp),
            )
            if (editable) {
                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = palette.ink3,
                    modifier = Modifier.padding(start = Space.xs).size(IconSize.md),
                )
            }
        }
        if (hint != null) {
            Text(
                hint,
                style = MaterialTheme.typography.labelSmall,
                color = palette.ink3,
                modifier = Modifier.padding(start = Space.lg, end = Space.lg, bottom = Space.lg),
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
    TONE("语气设定", "希望她怎么跟你说话；她仍会保留自己的口吻", multiline = true),
    LIFESTYLE("生活偏好", "作息、饮食、喜欢的东西", multiline = true),
    BACKGROUND("经历补充", "想让她记住的事", multiline = true, maxLength = 300),
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
        MeEdit.Nickname -> EditTextDialog(
            title = "昵称",
            initial = profile.nickname,
            hint = "她怎么叫你",
            maxLength = 12,
            onDismiss = onDismiss,
            onConfirm = { onSave(profile.copy(nickname = it)) },
        )
        MeEdit.Birthday -> EditTextDialog(
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
        MeEdit.Age -> EditTextDialog(
            title = "年龄",
            initial = profile.fixedAge?.toString().orEmpty(),
            hint = "没填生日时使用",
            maxLength = 3,
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
        MeEdit.Gender -> GenderDialog(
            initial = profile.gender,
            onDismiss = onDismiss,
            onConfirm = { onSave(profile.copy(gender = it)) },
        )
        is MeEdit.Text -> EditTextDialog(
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
private fun EditTextDialog(
    title: String,
    initial: String,
    hint: String,
    maxLength: Int,
    multiline: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    validate: (String) -> String? = { null },
) {
    var text by remember(title) { mutableStateOf(initial) }
    var error by remember(title) { mutableStateOf<String?>(null) }
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
private fun GenderDialog(
    initial: ProfileGender,
    onDismiss: () -> Unit,
    onConfirm: (ProfileGender) -> Unit,
) {
    var choice by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("性别", style = MaterialTheme.typography.titleMedium) },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                ProfileGender.entries.forEach { gender ->
                    FilterChip(
                        selected = choice == gender,
                        onClick = { choice = gender },
                        label = { Text(gender.label) },
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
