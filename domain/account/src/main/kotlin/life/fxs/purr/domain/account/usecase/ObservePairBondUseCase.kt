package life.fxs.purr.domain.account.usecase

import javax.inject.Inject
import life.fxs.purr.domain.account.repository.PairRepository

class ObservePairBondUseCase @Inject constructor(
    private val pairRepository: PairRepository,
) {
    operator fun invoke() = pairRepository.observePairBond()
}
