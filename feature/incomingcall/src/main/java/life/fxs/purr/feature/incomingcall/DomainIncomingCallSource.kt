package life.fxs.purr.feature.incomingcall

import javax.inject.Inject
internal class DomainIncomingCallSource @Inject constructor(
    private val observePresentableIncomingCall: ObservePresentableIncomingCallUseCase,
) : IncomingCallSource {
    override fun observe() = observePresentableIncomingCall()
}
