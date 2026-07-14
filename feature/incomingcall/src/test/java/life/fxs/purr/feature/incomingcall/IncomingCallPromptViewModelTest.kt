package life.fxs.purr.feature.incomingcall

import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import life.fxs.purr.core.common.AppResult
import life.fxs.purr.domain.account.model.IncomingCall
import life.fxs.purr.domain.account.usecase.ConsumeIncomingCallUseCase
import life.fxs.purr.domain.account.usecase.DeclineIncomingCallUseCase
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IncomingCallPromptViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val presentableCalls = MutableStateFlow<IncomingCall?>(null)
    private val observePresentableIncomingCall = mockk<ObservePresentableIncomingCallUseCase>()
    private val consumeIncomingCall = mockk<ConsumeIncomingCallUseCase>()
    private val declineIncomingCall = mockk<DeclineIncomingCallUseCase>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { observePresentableIncomingCall.invoke() } returns presentableCalls
        every { consumeIncomingCall.invoke(any()) } just Runs
        coEvery { declineIncomingCall.invoke(any()) } returns AppResult.Success(Unit)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `accept consumes exactly that call and navigates`() = runTest(dispatcher) {
        presentableCalls.value = incomingCall()
        withViewModel { viewModel ->
            val effect = async { viewModel.effects.first() }
            viewModel.state.first { it.call != null }

            viewModel.onIntent(IncomingCallPromptIntent.Accept)
            runCurrent()

            verify(exactly = 1) { consumeIncomingCall.invoke("call-1") }
            assertThat(effect.await()).isEqualTo(IncomingCallPromptEffect.NavigateToCall("pair-1"))
        }
    }

    @Test
    fun `decline ends exactly that call`() = runTest(dispatcher) {
        presentableCalls.value = incomingCall()
        withViewModel { viewModel ->
            viewModel.state.first { it.call != null }

            viewModel.onIntent(IncomingCallPromptIntent.Decline)
            runCurrent()

            coVerify(exactly = 1) { declineIncomingCall.invoke("call-1") }
        }
    }

    @Test
    fun `rapid repeated response is serialized`() = runTest(dispatcher) {
        presentableCalls.value = incomingCall()
        withViewModel { viewModel ->
            val effect = async { viewModel.effects.first() }
            viewModel.state.first { it.call != null }

            viewModel.onIntent(IncomingCallPromptIntent.Accept)
            viewModel.onIntent(IncomingCallPromptIntent.Accept)
            runCurrent()
            effect.await()

            verify(exactly = 1) { consumeIncomingCall.invoke("call-1") }
        }
    }

    private suspend inline fun withViewModel(block: suspend (IncomingCallPromptViewModel) -> Unit) {
        val viewModel = IncomingCallPromptViewModel(
            observePresentableIncomingCall = observePresentableIncomingCall,
            consumeIncomingCall = consumeIncomingCall,
            declineIncomingCall = declineIncomingCall,
        )
        try {
            block(viewModel)
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    private fun incomingCall() = IncomingCall(
        callId = "call-1",
        pairId = "pair-1",
        callerUserId = "user-b",
        startedAtEpochMillis = 1L,
    )
}
