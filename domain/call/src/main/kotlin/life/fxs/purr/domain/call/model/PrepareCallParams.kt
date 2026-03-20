package life.fxs.purr.domain.call.model

data class PrepareCallParams(
    val pairId: String,
    val resumeCallId: String? = null,
)
