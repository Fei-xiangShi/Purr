package life.fxs.purr.feature.call

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import life.fxs.purr.core.model.AudioRoute

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AudioRouteSelectionSheet(
    routes: List<AudioRoute>,
    activeRoute: AudioRoute,
    enabled: Boolean,
    sheetState: SheetState,
    onDismissRequest: () -> Unit,
    onClose: () -> Unit,
    onRouteSelect: (AudioRoute) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 12.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "选择音频输出",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                IconButton(onClick = onClose, enabled = enabled) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "关闭音频输出选择",
                    )
                }
            }

            routes.forEachIndexed { index, route ->
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 24.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
                AudioRouteOption(
                    route = route,
                    selected = route == activeRoute,
                    enabled = enabled,
                    onClick = { onRouteSelect(route) },
                )
            }
        }
    }
}

@Composable
private fun AudioRouteOption(
    route: AudioRoute,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(
                text = route.displayLabel,
                style = MaterialTheme.typography.bodyLarge,
            )
        },
        leadingContent = {
            Surface(
                shape = CircleShape,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ) {
                Icon(
                    imageVector = route.displayIcon,
                    contentDescription = null,
                    tint = if (selected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier
                        .size(40.dp)
                        .padding(9.dp),
                )
            }
        },
        trailingContent = {
            RadioButton(
                selected = selected,
                onClick = null,
                enabled = enabled,
            )
        },
        colors = ListItemDefaults.colors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.32f)
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        modifier = Modifier.selectable(
            selected = selected,
            enabled = enabled,
            role = Role.RadioButton,
            onClick = onClick,
        ),
    )
}
