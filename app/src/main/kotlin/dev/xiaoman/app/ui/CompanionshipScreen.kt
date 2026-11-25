package dev.xiaoman.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Nightlight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xiaoman.app.AppGraph
import dev.xiaoman.app.R

@Composable
internal fun CompanionshipScreen(
    graph: AppGraph,
    conversation: AppGraph.Conversation,
    onBack: () -> Unit,
    onOpenRelationship: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenCallRecords: () -> Unit,
    onOpenDream: (() -> Unit)? = null,
) {
    val palette = LocalXiaomanPalette.current
    val settings by conversation.effectiveSettings.collectAsStateWithLifecycle()
    val name = settings.persona.nameOr(stringResource(R.string.app_name))
    SettingsSubpage(title = "陪伴与回忆", onBack = onBack) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.lg),
        ) {
            PortraitAvatar(graph, size = 48.dp, store = conversation.portrait)
            Text(
                name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = palette.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Radius.lg),
            color = palette.surface,
        ) {
            Column(Modifier.padding(horizontal = Space.md, vertical = Space.xs)) {
                SettingsHomeRow("关系", Icons.Outlined.FavoriteBorder, onClick = onOpenRelationship)
                SettingsHomeRow(
                    stringResource(R.string.nav_memory), Icons.AutoMirrored.Outlined.MenuBook, onClick = onOpenMemory,
                )
                if (onOpenDream != null) {
                    SettingsHomeRow(
                        stringResource(R.string.settings_dream_entry), Icons.Outlined.Nightlight, onClick = onOpenDream,
                    )
                }
                SettingsHomeRow(
                    stringResource(R.string.call_records_title), Icons.Outlined.Call, onClick = onOpenCallRecords,
                )
            }
        }
    }
}
