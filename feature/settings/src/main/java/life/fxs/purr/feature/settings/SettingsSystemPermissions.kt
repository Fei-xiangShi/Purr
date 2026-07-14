package life.fxs.purr.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
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
    val canDrawOverlays: Boolean,
    val ignoresBatteryOptimizations: Boolean,
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
    var canDrawOverlays by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var ignoresBatteryOptimizations by remember {
        mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName))
    }

    fun refresh() {
        canDrawOverlays = Settings.canDrawOverlays(context)
        ignoresBatteryOptimizations = powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

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
        canDrawOverlays = canDrawOverlays,
        ignoresBatteryOptimizations = ignoresBatteryOptimizations,
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
            batteryOptimizationLauncher.launch(intent.resolvableOrFallback(context))
        },
    )
}

private fun Intent.resolvableOrFallback(context: Context): Intent =
    takeIf { resolveActivity(context.packageManager) != null }
        ?: Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
