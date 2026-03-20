package life.fxs.purr.data.account.network

import life.fxs.purr.core.network.model.AuthSessionDto
import life.fxs.purr.domain.account.model.AuthSession

fun AuthSessionDto.toDomain(): AuthSession = AuthSession(
    accessToken = accessToken,
    refreshToken = refreshToken,
    self = self,
)
