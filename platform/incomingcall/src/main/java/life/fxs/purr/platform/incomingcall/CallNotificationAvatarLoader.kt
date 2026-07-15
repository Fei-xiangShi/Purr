package life.fxs.purr.platform.incomingcall

import android.content.Context
import android.net.Uri
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import coil.ImageLoader
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.transform.CircleCropTransformation
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/** Loads a remote profile image into the bounded bitmap form accepted by call notifications. */
interface CallNotificationAvatarLoader {
    suspend fun load(url: String): IconCompat?
}

@Singleton
internal class CoilCallNotificationAvatarLoader @Inject constructor(
    @ApplicationContext private val context: Context,
) : CallNotificationAvatarLoader {
    private val imageLoader: ImageLoader by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        context.imageLoader
    }

    override suspend fun load(url: String): IconCompat? {
        val normalizedUrl = url.trim().takeIf(::isSupportedRemoteUrl) ?: return null
        return try {
            val request = ImageRequest.Builder(context)
                .data(normalizedUrl)
                .size(AVATAR_SIZE_PX)
                .allowHardware(false)
                .transformations(CircleCropTransformation())
                .build()
            val drawable = (imageLoader.execute(request) as? SuccessResult)?.drawable ?: return null
            IconCompat.createWithBitmap(drawable.toBitmap())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
    }

    private fun isSupportedRemoteUrl(url: String): Boolean =
        Uri.parse(url).scheme?.lowercase() in SUPPORTED_SCHEMES

    private companion object {
        const val AVATAR_SIZE_PX = 256
        val SUPPORTED_SCHEMES = setOf("https", "http")
    }
}
