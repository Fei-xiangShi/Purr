package life.fxs.purr.core.media.screenshare

import java.net.URLEncoder
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Never include WHIP/SRT publishing credentials. The fragment is not sent to the web server. */
fun browserWatchLink(playbackUrl: String, readToken: String, expiresAtEpochMillis: Long): String? {
    val url = playbackUrl.toHttpUrlOrNull() ?: return null
    if (!url.isHttps || !Regex("/screen-[A-Za-z0-9-]+/whep").matches(url.encodedPath) || readToken.isBlank()) return null
    fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    return url.newBuilder().encodedPath("/watch").query(null)
        .encodedFragment("path=${encode(url.encodedPath)}&token=${encode(readToken)}&expires=$expiresAtEpochMillis")
        .build().toString()
}
