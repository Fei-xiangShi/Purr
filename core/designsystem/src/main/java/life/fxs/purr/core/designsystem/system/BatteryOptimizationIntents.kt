package life.fxs.purr.core.designsystem.system

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/** Shared battery-optimization checks and system intents used by Home and Settings. */
object BatteryOptimizationIntents {
    fun isIgnoring(context: Context): Boolean =
        context.applicationContext.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)

    /**
     * Intent that asks to be exempted, or opens the exemption list when already exempted. Falls back to the list
     * screen when the direct request is not resolvable.
     */
    fun requestIntent(context: Context, ignoring: Boolean = isIgnoring(context)): Intent {
        val intent = if (ignoring) {
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        } else {
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}"),
            )
        }
        return if (intent.resolveActivity(context.packageManager) != null) {
            intent
        } else {
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        }
    }
}
