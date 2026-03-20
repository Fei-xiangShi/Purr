package life.fxs.purr.domain.call.usecase

import com.google.common.truth.Truth.assertThat
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.model.AudioRoute
import life.fxs.purr.domain.call.repository.CallRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class UseCasesTest {
    private val repository = mockk<CallRepository>()

    @Test
    fun `toggle mute inverses current state`() = runTest {
        coEvery { repository.setMuted(true) } returns AppResult.Success(Unit)

        val useCase = ToggleMuteUseCase(repository)
        val result = useCase(currentMuted = false)

        assertThat(result).isEqualTo(AppResult.Success(Unit))
        coVerify { repository.setMuted(true) }
    }

    @Test
    fun `select audio route delegates to repository`() = runTest {
        coEvery { repository.selectAudioRoute(AudioRoute.Speaker) } returns AppResult.Success(Unit)

        val useCase = SelectAudioRouteUseCase(repository)
        val result = useCase(AudioRoute.Speaker)

        assertThat(result).isEqualTo(AppResult.Success(Unit))
        coVerify { repository.selectAudioRoute(AudioRoute.Speaker) }
    }
}
