package life.fxs.purr.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import androidx.core.view.setPadding
import coil.dispose
import coil.load
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import life.fxs.purr.domain.call.model.CallOverlayStyle

/** Builds and updates overlay content without owning the system window lifecycle. */
class CallOverlayViewFactory @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val appContext = context.applicationContext

    internal fun create(style: CallOverlayStyle): CallOverlayViewBinding = when (style) {
        CallOverlayStyle.CompactSquare -> compactBinding()
        CallOverlayStyle.SpeakerNames -> speakerNamesBinding()
    }

    private fun compactBinding(): CallOverlayViewBinding {
        lateinit var duration: TextView
        val root = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(8.dp)
            background = roundedBackground(Color.argb(232, 32, 25, 39), 16.dp.toFloat())
            elevation = 12.dp.toFloat()
            addView(ImageView(appContext).apply {
                setImageResource(android.R.drawable.sym_action_call)
                setColorFilter(Color.WHITE)
            }, LinearLayout.LayoutParams(26.dp, 26.dp))
            duration = TextView(appContext).apply {
                text = 0L.toDurationLabel()
                setTextColor(Color.WHITE)
                textSize = 12f
                gravity = Gravity.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            addView(
                duration,
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        return CallOverlayViewBinding(style = CallOverlayStyle.CompactSquare, root = root, duration = duration)
    }

    private fun speakerNamesBinding(): CallOverlayViewBinding {
        val local = participantView(Gravity.END)
        val remote = participantView(Gravity.START)
        val root = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(16.dp)
            addView(
                local.root,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(Space(appContext), LinearLayout.LayoutParams(36.dp, 1))
            addView(
                remote.root,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
        return CallOverlayViewBinding(
            style = CallOverlayStyle.SpeakerNames,
            root = root,
            localName = local.name,
            remoteName = remote.name,
            localAvatar = local.avatar,
            remoteAvatar = remote.avatar,
        )
    }

    private fun participantView(horizontalGravity: Int): ParticipantView {
        val avatar = ImageView(appContext).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = roundedBackground(Color.argb(112, 255, 255, 255), 12.dp.toFloat())
            clipToOutline = true
            contentDescription = null
        }
        val name = TextView(appContext).apply {
            gravity = Gravity.CENTER
            textSize = 15f
            setTextColor(Color.WHITE)
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val root = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or horizontalGravity
            addView(avatar, LinearLayout.LayoutParams(24.dp, 24.dp))
            addView(Space(appContext), LinearLayout.LayoutParams(7.dp, 1))
            addView(name, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        return ParticipantView(root, avatar, name)
    }

    private fun roundedBackground(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        setStroke(1.dp, Color.argb(44, 255, 255, 255))
    }

    private val Int.dp: Int
        get() = (this * appContext.resources.displayMetrics.density).toInt()

    private data class ParticipantView(
        val root: View,
        val avatar: ImageView,
        val name: TextView,
    )
}

internal class CallOverlayViewBinding(
    val style: CallOverlayStyle,
    val root: View,
    private val duration: TextView? = null,
    internal val localName: TextView? = null,
    internal val remoteName: TextView? = null,
    internal val localAvatar: ImageView? = null,
    internal val remoteAvatar: ImageView? = null,
) {
    private var boundLocalAvatarUrl: String? = null
    private var boundRemoteAvatarUrl: String? = null
    private var avatarsBound = false

    fun render(model: CallOverlayRenderModel) {
        duration?.text = model.durationSeconds.toDurationLabel()
        localName?.applySpeakerState(model.localName, model.localAudioLevel)
        remoteName?.applySpeakerState(model.remoteName, model.remoteAudioLevel)
        bindAvatars(model)
    }

    fun dispose() {
        localAvatar?.dispose()
        remoteAvatar?.dispose()
    }

    private fun bindAvatars(model: CallOverlayRenderModel) {
        if (
            avatarsBound &&
            boundLocalAvatarUrl == model.localAvatarUrl &&
            boundRemoteAvatarUrl == model.remoteAvatarUrl
        ) return

        localAvatar?.loadAvatar(model.localAvatarUrl)
        remoteAvatar?.loadAvatar(model.remoteAvatarUrl)
        boundLocalAvatarUrl = model.localAvatarUrl
        boundRemoteAvatarUrl = model.remoteAvatarUrl
        avatarsBound = true
    }

    private fun ImageView.loadAvatar(url: String?) {
        load(url?.takeIf(String::isNotBlank)) {
            crossfade(true)
            placeholder(android.R.drawable.ic_menu_myplaces)
            fallback(android.R.drawable.ic_menu_myplaces)
            error(android.R.drawable.ic_menu_myplaces)
        }
    }

    private fun TextView.applySpeakerState(name: String, level: Float) {
        if (text.toString() != name) text = name
        val speaking = level >= SPEAKING_LEVEL
        if (tag == speaking) return
        tag = speaking
        alpha = if (speaking) 1f else 0.43f
        typeface = Typeface.create(
            Typeface.DEFAULT,
            if (speaking) Typeface.BOLD else Typeface.NORMAL,
        )
    }

    private companion object {
        const val SPEAKING_LEVEL = 0.035f
    }
}

private fun Long.toDurationLabel(): String {
    val hours = this / 3_600
    val minutes = (this % 3_600) / 60
    val seconds = this % 60
    return if (hours > 0) String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds)
    else String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
}
