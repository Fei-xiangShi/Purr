package life.fxs.purr.data.account.repository

import com.google.common.truth.Truth.assertThat
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.network.api.PurrPushApi
import org.junit.Test

class ApiPushRegistrationRepositoryTest {
    private val api = mockk<PurrPushApi>()
    private val repository = ApiPushRegistrationRepository(api)

    @Test
    fun `register sends the FCM provider and token`() = runTest {
        coEvery { api.register(INSTALLATION_ID, any()) } just Runs

        val result = repository.register(INSTALLATION_ID, TOKEN)

        assertThat(result).isEqualTo(AppResult.Success(Unit))
        coVerify(exactly = 1) {
            api.register(
                INSTALLATION_ID,
                match { it.provider == "FCM" && it.token == TOKEN },
            )
        }
    }

    @Test
    fun `unregister removes the current installation`() = runTest {
        coEvery { api.unregister(INSTALLATION_ID) } just Runs

        val result = repository.unregister(INSTALLATION_ID)

        assertThat(result).isEqualTo(AppResult.Success(Unit))
        coVerify(exactly = 1) { api.unregister(INSTALLATION_ID) }
    }

    private companion object {
        const val INSTALLATION_ID = "550e8400-e29b-41d4-a716-446655440000"
        const val TOKEN = "fcm-token-abcdefghijklmnopqrstuvwxyz-0123456789"
    }
}
