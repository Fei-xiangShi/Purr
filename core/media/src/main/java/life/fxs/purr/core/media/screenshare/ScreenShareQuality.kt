package life.fxs.purr.core.media.screenshare

/** Encoding targets; preserve the source aspect ratio and never upscale a smaller display. */
enum class ScreenShareQuality(
    val label: String,
    val shortSide: Int,
    val framesPerSecond: Int,
    val bitrate: Int,
) {
    HD30("720P/30fps", 720, 30, 6_000_000),
    HD60("720P/60fps", 720, 60, 9_000_000),
    FULL_HD30("1080P/30fps", 1080, 30, 10_000_000),
    FULL_HD60("1080P/60fps", 1080, 60, 16_000_000),
    QHD60("2K/60fps", 1440, 60, 24_000_000);

    fun sizeFor(sourceWidth: Int, sourceHeight: Int): ScreenShareVideoSize {
        val width = sourceWidth.coerceAtLeast(2)
        val height = sourceHeight.coerceAtLeast(2)
        val scale = (shortSide.toDouble() / minOf(width, height)).coerceAtMost(1.0)
        fun even(value: Int) = (value.coerceAtLeast(2) / 2) * 2
        return ScreenShareVideoSize(even((width * scale).toInt()), even((height * scale).toInt()))
    }
}

data class ScreenShareVideoSize(val width: Int, val height: Int)
