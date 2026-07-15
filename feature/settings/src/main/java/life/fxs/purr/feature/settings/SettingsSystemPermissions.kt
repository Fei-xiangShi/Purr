package life.fxs.purr.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.app.NotificationManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Stable
internal class SettingsSystemPermissions internal constructor(
    val notificationsEnabled: Boolean,
    val canUseFullScreenIntent: Boolean,
    val canDrawOverlays: Boolean,
    val ignoresBatteryOptimizations: Boolean,
    val openNotificationSettings: () -> Unit,
    val openFullScreenIntentPermission: () -> Unit,
    val openOverlayPermission: () -> Unit,
    val openBatteryOptimizationPermission: () -> Unit,
)

@Composable
internal fun rememberSettingsSystemPermissions(): SettingsSystemPermissions {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val powerManager = remember(context.applicationContext) {
        context.applicationContext.getSystemService(PowerManager::class.java)
    }
    val notificationManager = remember(context.applicationContext) {
        context.applicationContext.getSystemService(NotificationManager::class.java)
    }
    var notificationsEnabled by remember {
        mutableStateOf(notificationManager.areNotificationsEnabled())
    }
    var canUseFullScreenIntent by remember {
        mutableStateOf(notificationManager.canUseFullScreenIntentCompat())
    }
    var canDrawOverlays by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var ignoresBatteryOptimizations by remember {
        mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName))
    }

    fun refresh() {
        notificationsEnabled = notificationManager.areNotificationsEnabled()
        canUseFullScreenIntent = notificationManager.canUseFullScreenIntentCompat()
        canDrawOverlays = Settings.canDrawOverlays(context)
        ignoresBatteryOptimizations = powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    val notificationSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { refresh() }
    val fullScreenIntentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { refresh() }
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { refresh() }
    val batteryOptimizationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { refresh() }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return SettingsSystemPermissions(
        notificationsEnabled = notificationsEnabled,
        canUseFullScreenIntent = canUseFullScreenIntent,
        canDrawOverlays = canDrawOverlays,
        ignoresBatteryOptimizations = ignoresBatteryOptimizations,
        openNotificationSettings = {
            notificationSettingsLauncher.launch(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                }.resolvableOrFallback(context),
            )
        },
        openFullScreenIntentPermission = {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                Intent(
                    Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                    Uri.parse("package:${context.packageName}"),
                )
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            }
            fullScreenIntentLauncher.launch(intent.resolvableOrFallback(context))
        },
        openOverlayPermission = {
            overlayPermissionLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                ),
            )
        },
        openBatteryOptimizationPermission = {
            val intent = if (ignoresBatteryOptimizations) {
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            } else {
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}"),
                )
            }
            batteryOptimizationLauncher.launch(
                intent.resolvableOrFallback(
                    context = context,
                    fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                ),
            )
        },
    )
}

private fun Intent.resolvableOrFallback(
    context: Context,
    fallback: Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}"),
    ),
): Intent =
    takeIf { resolveActivity(context.packageManager) != null }
        ?: fallback

private fun NotificationManager.canUseFullScreenIntentCompat(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || canUseFullScreenIntent()
