package life.fxs.purr.platform.push

import javax.inject.Inject

class IncomingCallPushHandler @Inject internal constructor(
    private val parser: IncomingCallPushPayloadParser,
    private val scheduler: IncomingCallWakeScheduler,
) {
    fun handle(data: Map<String, String>) {
        parser.parse(data)?.let(scheduler::schedule)
    }
}
