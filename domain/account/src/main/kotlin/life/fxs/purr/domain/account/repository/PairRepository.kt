package life.fxs.purr.domain.account.repository

import life.fxs.purr.core.common.AppResult
import life.fxs.purr.core.model.PairBond
import kotlinx.coroutines.flow.Flow

interface PairRepository {
    fun observePairBond(): Flow<PairBond?>
    suspend fun refreshPairBond(): AppResult<PairBond>
}
