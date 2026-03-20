package life.fxs.purr.domain.account.model

import life.fxs.purr.core.model.SelfProfile

data class AuthSession(
    val accessToken: String,
    val refreshToken: String,
    val self: SelfProfile,
)
