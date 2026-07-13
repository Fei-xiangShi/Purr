package life.fxs.purr.data.call.livekit

import life.fxs.purr.data.call.runtime.MediaCallPort

/**
 * LiveKit adapter marker. Its public contract is [MediaCallPort], which contains
 * no domain session or provider-specific state.
 */
interface LiveKitCallDataSource : MediaCallPort
