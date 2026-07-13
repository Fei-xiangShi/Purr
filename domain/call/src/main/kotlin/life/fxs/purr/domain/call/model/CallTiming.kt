package life.fxs.purr.domain.call.model

data class CallTiming(
    val startedAtEpochMillis: Long? = null,
    val endedAtEpochMillis: Long? = null,
    val synchronizedDurationMillis: Long = 0L,
    val synchronizedAtMonotonicMillis: Long = 0L,
    val isRunning: Boolean = false,
) {
    fun durationAtMonotonicMillis(nowMillis: Long): Long {
        val synchronizedDuration = synchronizedDurationMillis.coerceAtLeast(0L)
        if (!isRunning) return synchronizedDuration
        return synchronizedDuration + (nowMillis - synchronizedAtMonotonicMillis).coerceAtLeast(0L)
    }
}
