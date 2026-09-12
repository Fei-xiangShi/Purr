package life.fxs.purr.feature.call

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import life.fxs.purr.core.model.AudioRoute

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AudioRoutePicker(
    routes: List<AudioRoute>,
    activeRoute: AudioRoute,
    enabled: Boolean,
    onRouteSelect: (AudioRoute) -> Unit,
    onVisibilityChanged: (Boolean) -> Unit = {},
) {
    val availableRoutes = routes.distinct()
    val pickerEnabled = enabled && availableRoutes.isNotEmpty()
    var isSheetVisible by rememberSaveable { mutableStateOf(false) }
    var isDismissing by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()

    DisposableEffect(isSheetVisible) {
        onVisibilityChanged(isSheetVisible)
        onDispose { onVisibilityChanged(false) }
    }

    fun dismissSheet() {
        if (isDismissing) return
        isDismissing = true
        coroutineScope.launch {
            sheetState.hide()
            isSheetVisible = false
            isDismissing = false
        }
    }

    LaunchedEffect(pickerEnabled) {
        if (!pickerEnabled && isSheetVisible) {
            sheetState.hide()
            isSheetVisible = false
            isDismissing = false
        }
    }

    AudioRouteButton(
        activeRoute = activeRoute,
        enabled = pickerEnabled,
        onClick = {
            isDismissing = false
            isSheetVisible = true
        },
    )

    if (isSheetVisible) {
        AudioRouteSelectionSheet(
            routes = availableRoutes,
            activeRoute = activeRoute,
            enabled = pickerEnabled && !isDismissing,
            sheetState = sheetState,
            onDismissRequest = {
                isSheetVisible = false
                isDismissing = false
            },
            onClose = ::dismissSheet,
            onRouteSelect = { route ->
                if (!isDismissing) {
                    if (route != activeRoute) {
                        onRouteSelect(route)
                    }
                    dismissSheet()
                }
            },
        )
    }
}
