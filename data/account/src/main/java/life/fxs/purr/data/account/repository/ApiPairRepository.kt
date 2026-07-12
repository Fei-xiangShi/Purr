package life.fxs.purr.data.account.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.common.asAppError
import life.fxs.purr.core.model.PairBond
import life.fxs.purr.core.network.api.PurrAccountApi
import life.fxs.purr.domain.account.repository.PairRepository

@Singleton
class ApiPairRepository @Inject constructor(
    private val api: PurrAccountApi,
) : PairRepository {
    private val pairBond = MutableStateFlow<PairBond?>(null)

    override fun observePairBond(): Flow<PairBond?> = pairBond.asStateFlow()

    override suspend fun refreshPairBond(): AppResult<PairBond> {
        return try {
            val bond = api.getPair()
            pairBond.emit(bond)
            AppResult.Success(bond)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            AppResult.Failure(throwable.asAppError())
        }
    }
}
