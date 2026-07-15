package life.fxs.purr.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import life.fxs.purr.feature.settings.avatar.AvatarCropArea
import life.fxs.purr.feature.settings.avatar.DecodedAvatarImage

private const val SETTINGS_OVERVIEW_ROUTE = "overview"
private const val AVATAR_CROP_ROUTE = "avatar-crop"

/** Owns full-screen navigation that is internal to the settings feature. */
@Composable
internal fun SettingsNavHost(
    state: SettingsState,
    onIntent: (SettingsIntent) -> Unit,
    onPickAvatar: () -> Unit,
    isPreparingAvatar: Boolean,
    avatarProcessingError: String?,
    decodedAvatar: DecodedAvatarImage?,
    hasPendingAvatarSelection: Boolean,
    isExportingAvatar: Boolean,
    onCancelPendingAvatar: () -> Unit,
    onCropDisposed: (DecodedAvatarImage) -> Unit,
    onCropConfirm: suspend (DecodedAvatarImage, AvatarCropArea) -> Boolean,
    notificationsEnabled: Boolean,
    onOpenNotificationSettings: () -> Unit,
    fullScreenIntentGranted: Boolean,
    onRequestFullScreenIntent: () -> Unit,
    overlayPermissionGranted: Boolean,
    onRequestOverlayPermission: () -> Unit,
    batteryOptimizationIgnored: Boolean,
    onRequestIgnoreBatteryOptimizations: () -> Unit,
) {
    val navController = rememberNavController()
    val coroutineScope = rememberCoroutineScope()
    val currentOnCropDisposed by rememberUpdatedState(onCropDisposed)
    val currentDecodedAvatar by rememberUpdatedState(decodedAvatar)
    val currentHasPendingAvatarSelection by rememberUpdatedState(hasPendingAvatarSelection)
    val currentOnCancelPendingAvatar by rememberUpdatedState(onCancelPendingAvatar)
    var isCropConfirming by remember { mutableStateOf(false) }

    LaunchedEffect(decodedAvatar) {
        if (decodedAvatar != null) {
            navController.navigate(AVATAR_CROP_ROUTE) {
                launchSingleTop = true
            }
        }
    }

    // The feature stack owns avatar-crop back transitions; the app stack owns Settings itself.
    NavHost(
        navController = navController,
        startDestination = SETTINGS_OVERVIEW_ROUTE,
        modifier = Modifier.fillMaxSize(),
    ) {
        composable(SETTINGS_OVERVIEW_ROUTE) {
            SettingsScreen(
                state = state,
                onIntent = onIntent,
                onPickAvatar = onPickAvatar,
                isPreparingAvatar = isPreparingAvatar,
                avatarProcessingError = avatarProcessingError,
                notificationsEnabled = notificationsEnabled,
                onOpenNotificationSettings = onOpenNotificationSettings,
                fullScreenIntentGranted = fullScreenIntentGranted,
                onRequestFullScreenIntent = onRequestFullScreenIntent,
                overlayPermissionGranted = overlayPermissionGranted,
                onRequestOverlayPermission = onRequestOverlayPermission,
                batteryOptimizationIgnored = batteryOptimizationIgnored,
                onRequestIgnoreBatteryOptimizations = onRequestIgnoreBatteryOptimizations,
            )
        }
        composable(AVATAR_CROP_ROUTE) {
            val image = decodedAvatar
            if (image == null) {
                DisposableEffect(Unit) {
                    onDispose {
                        if (currentDecodedAvatar == null && currentHasPendingAvatarSelection) {
                            currentOnCancelPendingAvatar()
                        }
                    }
                }
                LaunchedEffect(isPreparingAvatar, hasPendingAvatarSelection) {
                    if (!isPreparingAvatar && !hasPendingAvatarSelection) {
                        navController.popBackStack()
                    }
                }
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else {
                DisposableEffect(image) {
                    onDispose {
                        isCropConfirming = false
                        currentOnCropDisposed(image)
                    }
                }
                AvatarCropScreen(
                    bitmap = image.bitmap,
                    isExporting = isExportingAvatar || isCropConfirming,
                    errorMessage = avatarProcessingError,
                    onCancel = { navController.popBackStack() },
                    onConfirm = { cropArea ->
                        if (isCropConfirming || isExportingAvatar) return@AvatarCropScreen
                        isCropConfirming = true
                        coroutineScope.launch {
                            var keepLockedUntilDestinationDisposes = false
                            try {
                                if (onCropConfirm(image, cropArea)) {
                                    keepLockedUntilDestinationDisposes = navController.popBackStack()
                                }
                            } finally {
                                if (!keepLockedUntilDestinationDisposes) {
                                    isCropConfirming = false
                                }
                            }
                        }
                    },
                )
            }
        }
    }
}
