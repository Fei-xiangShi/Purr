package life.fxs.purr.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.util.DisplayMetrics
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.hypot
import kotlin.math.roundToInt
import life.fxs.purr.MainActivity
import life.fxs.purr.domain.call.model.CallOverlayStyle

/** Owns only the system overlay window lifecycle and navigation interaction. */
@Singleton
class CallOverlayPresenter @Inject constructor(
    @ApplicationContext context: Context,
    private val viewFactory: CallOverlayViewFactory,
) {
    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(WindowManager::class.java)
    private var binding: CallOverlayViewBinding? = null
    private var latestPairId: String? = null
    private var canDrawOverlays = false
    private var lastPermissionCheckMillis = Long.MIN_VALUE
    private var compactPosition: OverlayPosition? = null

    fun render(model: CallOverlayRenderModel) {
        if (!hasOverlayPermission()) {
            hide()
            return
        }
        latestPairId = model.pairId
        if (binding?.style != model.style) rebuild(model.style)
        binding?.render(model)
    }

    fun hide() {
        binding?.let { current ->
            runCatching { windowManager.removeViewImmediate(current.root) }
            current.dispose()
        }
        binding = null
    }

    private fun rebuild(style: CallOverlayStyle) {
        hide()
        if (!hasOverlayPermission()) return
        val next = viewFactory.create(style)
        val params = layoutParams(style)
        configureInteraction(next.root, style, params)
        try {
            windowManager.addView(next.root, params)
            binding = next
        } catch (_: SecurityException) {
            next.dispose()
        } catch (_: WindowManager.BadTokenException) {
            next.dispose()
        }
    }

    internal fun layoutParams(style: CallOverlayStyle): WindowManager.LayoutParams {
        val compact = style == CallOverlayStyle.CompactSquare
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            if (compact) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return WindowManager.LayoutParams(
            if (compact) COMPACT_SIZE_DP.dp else WindowManager.LayoutParams.MATCH_PARENT,
            if (compact) COMPACT_SIZE_DP.dp else WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            alpha = if (compact) 1f else SPEAKER_WINDOW_ALPHA
            gravity = if (compact) {
                Gravity.START or Gravity.TOP
            } else {
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            }
            if (compact) {
                val position = compactDragBounds().constrain(
                    compactPosition ?: defaultCompactPosition(),
                )
                x = position.x
                y = position.y
            } else {
                x = 0
                y = 0
            }
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun configureInteraction(
        view: View,
        style: CallOverlayStyle,
        params: WindowManager.LayoutParams,
    ) {
        if (style == CallOverlayStyle.SpeakerNames) {
            view.isClickable = false
            view.setOnClickListener(null)
            view.setOnTouchListener(null)
            return
        }

        view.isClickable = true
        view.setOnClickListener { openCall() }
        view.setOnTouchListener(
            CompactOverlayTouchListener(
                currentPosition = { OverlayPosition(params.x, params.y) },
                bounds = { compactDragBounds(view) },
                touchSlop = ViewConfiguration.get(appContext).scaledTouchSlop,
                onMove = { position ->
                    params.x = position.x
                    params.y = position.y
                    runCatching { windowManager.updateViewLayout(view, params) }
                        .onSuccess { compactPosition = position }
                },
            ),
        )
    }

    private fun openCall() {
        appContext.startActivity(
            MainActivity.intent(appContext, latestPairId)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun defaultCompactPosition(): OverlayPosition {
        val bounds = compactDragBounds()
        return OverlayPosition(
            x = (bounds.maxX - COMPACT_EDGE_MARGIN_DP.dp).coerceAtLeast(bounds.minX),
            y = bounds.minY + (bounds.maxY - bounds.minY) / 2,
        )
    }

    @Suppress("DEPRECATION")
    private fun compactDragBounds(view: View? = null): OverlayDragBounds {
        val overlaySize = COMPACT_SIZE_DP.dp
        val displayMetrics = DisplayMetrics().also(windowManager.defaultDisplay::getRealMetrics)
        val rootInsets = view?.rootWindowInsets
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && rootInsets != null) {
            val insets = rootInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            return OverlayDragBounds(
                minX = insets.left,
                minY = insets.top,
                maxX = (displayMetrics.widthPixels - insets.right - overlaySize).coerceAtLeast(insets.left),
                maxY = (displayMetrics.heightPixels - insets.bottom - overlaySize).coerceAtLeast(insets.top),
            )
        }

        val minX = rootInsets?.stableInsetLeft ?: 0
        val minY = rootInsets?.stableInsetTop ?: 0
        return OverlayDragBounds(
            minX = minX,
            minY = minY,
            maxX = (displayMetrics.widthPixels - (rootInsets?.stableInsetRight ?: 0) - overlaySize)
                .coerceAtLeast(minX),
            maxY = (displayMetrics.heightPixels - (rootInsets?.stableInsetBottom ?: 0) - overlaySize)
                .coerceAtLeast(minY),
        )
    }

    private fun hasOverlayPermission(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (
            lastPermissionCheckMillis == Long.MIN_VALUE ||
            now - lastPermissionCheckMillis >= PERMISSION_CHECK_INTERVAL_MILLIS
        ) {
            canDrawOverlays = Settings.canDrawOverlays(appContext)
            lastPermissionCheckMillis = now
        }
        return canDrawOverlays
    }

    private val Int.dp: Int
        get() = (this * appContext.resources.displayMetrics.density).toInt()

    private companion object {
        const val COMPACT_SIZE_DP = 72
        const val COMPACT_EDGE_MARGIN_DP = 14
        const val SPEAKER_WINDOW_ALPHA = 0.79f
        const val PERMISSION_CHECK_INTERVAL_MILLIS = 1_000L
    }
}

internal data class OverlayPosition(val x: Int, val y: Int)

internal data class OverlayDragBounds(
    val minX: Int,
    val minY: Int,
    val maxX: Int,
    val maxY: Int,
) {
    fun constrain(position: OverlayPosition) = OverlayPosition(
        x = position.x.coerceIn(minX, maxX),
        y = position.y.coerceIn(minY, maxY),
    )
}

internal class CompactOverlayTouchListener(
    private val currentPosition: () -> OverlayPosition,
    private val bounds: () -> OverlayDragBounds,
    private val touchSlop: Int,
    private val onMove: (OverlayPosition) -> Unit,
) : View.OnTouchListener {
    private var downRawX = 0f
    private var downRawY = 0f
    private var startPosition = OverlayPosition(0, 0)
    private var dragging = false

    override fun onTouch(view: View, event: MotionEvent): Boolean = when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            downRawX = event.rawX
            downRawY = event.rawY
            startPosition = currentPosition()
            dragging = false
            true
        }
        MotionEvent.ACTION_MOVE -> {
            updateDrag(event)
            true
        }
        MotionEvent.ACTION_UP -> {
            updateDrag(event)
            if (!dragging) view.performClick()
            true
        }
        MotionEvent.ACTION_CANCEL -> {
            dragging = false
            true
        }
        else -> false
    }

    private fun updateDrag(event: MotionEvent) {
        val deltaX = event.rawX - downRawX
        val deltaY = event.rawY - downRawY
        if (!dragging && hypot(deltaX.toDouble(), deltaY.toDouble()) > touchSlop) {
            dragging = true
        }
        if (!dragging) return
        onMove(
            bounds().constrain(
                OverlayPosition(
                    x = startPosition.x + deltaX.roundToInt(),
                    y = startPosition.y + deltaY.roundToInt(),
                ),
            ),
        )
    }
}
