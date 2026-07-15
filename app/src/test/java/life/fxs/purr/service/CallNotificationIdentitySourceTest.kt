package life.fxs.purr.service

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.model.PairBond
import life.fxs.purr.core.model.PairedPartner
import life.fxs.purr.core.model.SelfProfile
import life.fxs.purr.domain.account.repository.PairRepository
import life.fxs.purr.domain.account.usecase.ObservePairBondUseCase
import org.junit.Test

class CallNotificationIdentitySourceTest {
    private val bonds = MutableStateFlow<PairBond?>(null)
    private val source = PairBondCallNotificationIdentitySource(
        ObservePairBondUseCase(
            object : PairRepository {
                override fun observePairBond(): Flow<PairBond?> = bonds

                override suspend fun refreshPairBond(): AppResult<PairBond> =
                    error("Not used by this identity source test")
            },
        ),
    )

    @Test
    fun `matching pair exposes partner notification identity`() = runTest {
        bonds.value = pairBond("pair-1")

        assertThat(source.observe("pair-1").first()).isEqualTo(
            CallNotificationIdentity(
                displayName = "Partner",
                avatarUrl = "https://example.test/avatar.png",
            ),
        )
    }

    @Test
    fun `mismatched pair does not expose partner notification identity`() = runTest {
        bonds.value = pairBond("pair-1")

        assertThat(source.observe("pair-2").first()).isNull()
    }

    private fun pairBond(pairId: String) = PairBond(
        pairId = pairId,
        self = SelfProfile(userId = "user-a", displayName = "Self"),
        partner = PairedPartner(
            userId = "user-b",
            displayName = "Partner",
            avatarUrl = "https://example.test/avatar.png",
        ),
        bondedAtEpochMillis = 1L,
    )
}
