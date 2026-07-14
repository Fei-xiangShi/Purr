package life.fxs.purr.domain.call.repository

import kotlinx.coroutines.flow.StateFlow
import life.fxs.purr.domain.call.model.CallOverlayStyle

interface CallOverlayStyleRepository {
    val style: StateFlow<CallOverlayStyle>

    fun setStyle(style: CallOverlayStyle)
}
