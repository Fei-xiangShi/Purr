package life.fxs.purr.domain.call.usecase

import javax.inject.Inject
import life.fxs.purr.domain.call.repository.CallCalendarRepository

class LoadCallCalendarUseCase @Inject constructor(
    private val repository: CallCalendarRepository,
) {
    suspend operator fun invoke(fromEpochMillis: Long, toEpochMillis: Long, zoneId: String) =
        repository.loadMonth(fromEpochMillis, toEpochMillis, zoneId)
}
