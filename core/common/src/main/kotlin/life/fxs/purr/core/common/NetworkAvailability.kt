package life.fxs.purr.core.common

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Signals that the default network became usable again or was switched to a different network.
 * Nothing is emitted for the network that is already active when collection starts.
 */
interface NetworkAvailability {
    val available: Flow<Unit>

    object None : NetworkAvailability {
        override val available: Flow<Unit> = emptyFlow()
    }
}
