package life.fxs.purr.data.account.realtime

import javax.inject.Qualifier

/** OkHttp client derived from the shared client with WebSocket liveness pings. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class RealtimeClient
