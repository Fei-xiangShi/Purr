package life.fxs.purr.domain.call.usecase

import com.google.common.truth.Truth.assertThat
import life.fxs.purr.core.common.AppResult
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
}
