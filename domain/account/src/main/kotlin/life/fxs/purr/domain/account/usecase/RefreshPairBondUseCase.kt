package life.fxs.purr.domain.account.usecase

import javax.inject.Inject
import life.fxs.purr.domain.account.repository.PairRepository

class RefreshPairBondUseCase @Inject constructor(
    private val pairRepository: PairRepository,
) {
    suspend operator fun invoke() = pairRepository.refreshPairBond()
}
