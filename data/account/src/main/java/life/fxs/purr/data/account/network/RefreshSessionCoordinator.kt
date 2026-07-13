package life.fxs.purr.data.account.network

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import life.fxs.purr.core.network.api.PurrAuthApi
import life.fxs.purr.core.network.model.RefreshRequestDto
import retrofit2.HttpException

@Singleton
class RefreshSessionCoordinator @Inject constructor(
    private val authApi: PurrAuthApi,
    private val sessionTokenHolder: SessionTokenHolder,
    private val sessionWriter: SessionWriter,
) {
    private val inFlightLock = Any()
    private val inFlightRefreshes = mutableMapOf<String, CompletableDeferred<RefreshSessionResult>>()

    suspend fun refresh(refreshToken: String): RefreshSessionResult {
        return when (val reservation = reserveRefresh(refreshToken)) {
            is RefreshReservation.Completed -> reservation.result
            is RefreshReservation.Join -> reservation.result.await()
            is RefreshReservation.Start -> executeRefresh(refreshToken, reservation.result)
        }
    }

    private fun reserveRefresh(refreshToken: String): RefreshReservation = synchronized(inFlightLock) {
        val currentSession = sessionTokenHolder.snapshot()
        val latestRefreshToken = currentSession.refreshToken
            ?: return@synchronized RefreshReservation.Completed(
                RefreshSessionResult.NoActiveSession,
            )
        if (latestRefreshToken != refreshToken) {
            return@synchronized RefreshReservation.Completed(currentSession.toRefreshResult())
        }

        inFlightRefreshes[refreshToken]
            ?.let(RefreshReservation::Join)
            ?: CompletableDeferred<RefreshSessionResult>().let { result ->
                inFlightRefreshes[refreshToken] = result
                RefreshReservation.Start(result)
            }
    }

    private suspend fun executeRefresh(
        refreshToken: String,
        sharedResult: CompletableDeferred<RefreshSessionResult>,
    ): RefreshSessionResult {
        return try {
            val result = requestRefresh(refreshToken)
            sharedResult.complete(result)
            result
        } catch (cancellation: CancellationException) {
            sharedResult.complete(RefreshSessionResult.TemporarilyUnavailable)
            throw cancellation
        } catch (fatalFailure: Throwable) {
            sharedResult.completeExceptionally(fatalFailure)
            throw fatalFailure
        } finally {
            synchronized(inFlightLock) {
                inFlightRefreshes.remove(refreshToken, sharedResult)
            }
        }
    }

    private suspend fun requestRefresh(refreshToken: String): RefreshSessionResult {
        return try {
            val session = authApi.refresh(RefreshRequestDto(refreshToken = refreshToken)).toDomain()
            if (sessionWriter.persistIfRefreshTokenMatches(refreshToken, session)) {
                RefreshSessionResult.Authenticated(session.accessToken)
            } else {
                currentSessionResult()
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            if (failure is HttpException && failure.code() == HTTP_UNAUTHORIZED) {
                handleRejectedRefreshToken(refreshToken)
            } else {
                RefreshSessionResult.TemporarilyUnavailable
            }
        }
    }

    private suspend fun handleRejectedRefreshToken(
        rejectedRefreshToken: String,
    ): RefreshSessionResult {
        return try {
            if (sessionWriter.clearIfRefreshTokenMatches(rejectedRefreshToken)) {
                RefreshSessionResult.RefreshTokenRejected
            } else {
                currentSessionResult()
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            RefreshSessionResult.TemporarilyUnavailable
        }
    }

    private fun currentSessionResult(): RefreshSessionResult {
        return sessionTokenHolder.snapshot().toRefreshResult()
    }

    private fun SessionTokenHolder.SessionSnapshot.toRefreshResult(): RefreshSessionResult {
        return accessToken
            ?.let(RefreshSessionResult::Authenticated)
            ?: RefreshSessionResult.NoActiveSession
    }

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
    }

    private sealed interface RefreshReservation {
        data class Completed(val result: RefreshSessionResult) : RefreshReservation

        data class Join(
            val result: CompletableDeferred<RefreshSessionResult>,
        ) : RefreshReservation

        data class Start(
            val result: CompletableDeferred<RefreshSessionResult>,
        ) : RefreshReservation
    }
}
