package life.fxs.purr.data.account.network

sealed interface RefreshSessionResult {
    data class Authenticated(val accessToken: String) : RefreshSessionResult

    data object NoActiveSession : RefreshSessionResult

    data object RefreshTokenRejected : RefreshSessionResult

    data object TemporarilyUnavailable : RefreshSessionResult
}
