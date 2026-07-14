package life.fxs.purr.domain.call.usecase

import javax.inject.Inject
import life.fxs.purr.domain.call.repository.CallHistoryRepository

class LoadCallHistoryUseCase @Inject constructor(
    private val callHistoryRepository: CallHistoryRepository,
) {
    suspend operator fun invoke(
        fromEpochMillis: Long,
        toEpochMillis: Long,
        cursor: String? = null,
    ) = callHistoryRepository.loadDay(fromEpochMillis, toEpochMillis, cursor)
}
