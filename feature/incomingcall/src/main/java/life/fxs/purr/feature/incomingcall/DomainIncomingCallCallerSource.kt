package life.fxs.purr.feature.incomingcall

import javax.inject.Inject
import life.fxs.purr.domain.account.usecase.ObservePairBondUseCase
import kotlinx.coroutines.flow.map

internal class DomainIncomingCallCallerSource @Inject constructor(
    private val observePairBond: ObservePairBondUseCase,
) : IncomingCallCallerSource {
    override fun observe() = observePairBond().map { it?.partner }
}
