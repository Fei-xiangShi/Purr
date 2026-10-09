package life.fxs.purr.di

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ApplicationScopeExceptionHandlerTest {
    @Test
    fun `handler absorbs uncaught launch failure and scope stays usable`() = runBlocking {
        val seen = CompletableDeferred<Throwable>()
        val observing = CoroutineExceptionHandler { context, t ->
            applicationScopeExceptionHandler.handleException(context, t)
            seen.complete(t)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + observing)
        scope.launch { error("boom") }
        assertThat(seen.await()).hasMessageThat().isEqualTo("boom")
        val after = CompletableDeferred<Unit>()
        scope.launch { after.complete(Unit) }
        after.await()
    }
}
