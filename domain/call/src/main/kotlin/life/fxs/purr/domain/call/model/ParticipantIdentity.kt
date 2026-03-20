package life.fxs.purr.domain.call.model

data class ParticipantIdentity(
    val local: String,
    val remote: String? = null,
)
