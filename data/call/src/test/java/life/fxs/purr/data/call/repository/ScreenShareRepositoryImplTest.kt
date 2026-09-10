package life.fxs.purr.data.call.repository

import com.google.common.truth.Truth.assertThat
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.media.screenshare.ScreenSharePublisherController
import life.fxs.purr.core.media.screenshare.ScreenSharePublisherStatus
import life.fxs.purr.core.model.SelfProfile
import life.fxs.purr.core.network.api.PurrCallApi
import life.fxs.purr.core.network.model.CreateScreenShareRequestDto
import life.fxs.purr.core.network.model.ScreenShareDto
import life.fxs.purr.core.network.model.ScreenShareEnvelopeDto
import life.fxs.purr.core.network.model.ScreenShareMediaEndpointDto
import life.fxs.purr.core.network.model.ScreenSharePublishingDto
import life.fxs.purr.core.network.realtime.ScreenShareRealtimeInvalidation
import life.fxs.purr.core.network.realtime.ScreenShareRealtimeInvalidations
import life.fxs.purr.domain.account.model.AuthSession
import life.fxs.purr.domain.account.repository.AuthRepository
import life.fxs.purr.domain.call.model.ScreenShareSource
import life.fxs.purr.domain.call.model.ScreenShareStatus
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScreenShareRepositoryImplTest {
    private val api = mockk<PurrCallApi>()
    private val authRepository = mockk<AuthRepository>()
    private val invalidations = ScreenShareRealtimeInvalidations()
    private val publisherController = mockk<ScreenSharePublisherController>()
    private val publisherStatus = MutableStateFlow<ScreenSharePublisherStatus>(
        ScreenSharePublisherStatus.Idle,
    )

    init {
        every { authRepository.currentSession() } returns AuthSession(
            accessToken = "access",
            refreshToken = "refresh",
            self = SelfProfile(userId = "self", displayName = "Self"),
        )
        every { publisherController.status } returns publisherStatus
        every { publisherController.stop(any()) } just Runs
    }

    @Test
    fun `create maps mobile publishing credentials into domain session`() = runTest {
        coEvery {
            api.createScreenShare("call-1", CreateScreenShareRequestDto("mobile"))
        } returns ScreenShareEnvelopeDto(sampleDto())
        val repository = createRepository()

        val result = repository.create("call-1", ScreenShareSource.Mobile) as AppResult.Success

        assertThat(result.value.source).isEqualTo(ScreenShareSource.Mobile)
        assertThat(result.value.status).isEqualTo(ScreenShareStatus.Authorized)
        assertThat(result.value.publishing?.whip?.url).isEqualTo("https://media.test/whip")
        assertThat(result.value.publishing?.whip?.bearerToken).isEqualTo("publish-token")
    }

    @Test
    fun `refresh preserves one-time publishing credentials for the same share`() = runTest {
        coEvery { api.createScreenShare(any(), any()) } returns
            ScreenShareEnvelopeDto(sampleDto())
        coEvery { api.getScreenShare("call-1") } returns ScreenShareEnvelopeDto(
            sampleDto(
                status = "live",
                publishing = null,
                playback = sampleEndpoint("https://media.test/whep", "play-token"),
            ),
        )
        val repository = createRepository()
        repository.create("call-1", ScreenShareSource.Mobile)

        val refreshed = repository.refresh("call-1") as AppResult.Success

        assertThat(refreshed.value?.publishing?.whip?.bearerToken).isEqualTo("publish-token")
        assertThat(refreshed.value?.playback?.url).isEqualTo("https://media.test/whep")
    }

    @Test
    fun `publisher failure automatically stops matching server share once`() = runTest {
        coEvery { api.stopScreenShare("call-1", "share-1") } returns ScreenShareEnvelopeDto(
            sampleDto(status = "stopped", publishing = null),
        )
        createRepository()
        runCurrent()

        publisherStatus.value = ScreenSharePublisherStatus.Failed(
            callId = "call-1",
            shareId = "share-1",
            message = "encoder failed",
        )
        runCurrent()
        publisherStatus.value = ScreenSharePublisherStatus.Failed(
            callId = "call-1",
            shareId = "share-1",
            message = "encoder failed again",
        )
        runCurrent()

        coVerify(exactly = 1) { api.stopScreenShare("call-1", "share-1") }
    }

    @Test
    fun `realtime invalidation triggers immediate server refresh`() = runTest {
        coEvery { api.getScreenShare("call-1") } returnsMany listOf(
            ScreenShareEnvelopeDto(null),
            ScreenShareEnvelopeDto(
                sampleDto(
                    status = "live",
                    publishing = null,
                    playback = sampleEndpoint("https://media.test/whep", "play-token"),
                ),
            ),
        )
        val repository = createRepository()
        val observation = backgroundScope.launch {
            repository.observe("call-1").collect()
        }
        runCurrent()

        invalidations.publish(
            ScreenShareRealtimeInvalidation(
                callId = "call-1",
                shareId = "share-1",
                status = "live",
                source = "mobile",
                ownerUserId = "partner",
            ),
        )
        runCurrent()

        coVerify(exactly = 2) { api.getScreenShare("call-1") }
        observation.cancel()
    }

    private fun kotlinx.coroutines.test.TestScope.createRepository() = ScreenShareRepositoryImpl(
        api = api,
        authRepository = authRepository,
        invalidations = invalidations,
        publisherController = publisherController,
        applicationScope = backgroundScope,
    )

    private fun sampleDto(
        status: String = "authorized",
        publishing: ScreenSharePublishingDto? = ScreenSharePublishingDto(
            whip = sampleEndpoint("https://media.test/whip", "publish-token"),
        ),
        playback: ScreenShareMediaEndpointDto? = null,
    ) = ScreenShareDto(
        shareId = "share-1",
        callId = "call-1",
        ownerUserId = "self",
        source = "mobile",
        status = status,
        mediaPath = "calls/call-1/share-1",
        createdAtEpochMillis = 1_000L,
        expiresAtEpochMillis = 61_000L,
        publishing = publishing,
        playback = playback,
    )

    private fun sampleEndpoint(url: String, token: String) = ScreenShareMediaEndpointDto(
        url = url,
        bearerToken = token,
        expiresAtEpochMillis = 61_000L,
    )
}
