package life.fxs.purr.domain.call.model

data class CallCalendarDay(
    val date: String,
    val callCount: Int,
    val totalDurationMillis: Long,
)
