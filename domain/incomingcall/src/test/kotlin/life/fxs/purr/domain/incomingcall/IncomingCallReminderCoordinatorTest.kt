package life.fxs.purr.domain.incomingcall

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import life.fxs.purr.core.model.PairBond
import life.fxs.purr.core.model.PairedPartner
import life.fxs.purr.core.model.SelfProfile
import life.fxs.purr.domain.account.model.IncomingCall
import life.fxs.purr.domain.account.usecase.ObservePairBondUseCase
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IncomingCallReminderCoordinatorTest {
    @Test
    fun `background calls replace one reminder slot instead of stacking`() = runTest {
        val calls = MutableStateFlow<IncomingCall?>(null)
        val visibility = FakeVisibility(isForeground = false)
        val reminder = RecordingReminder()
        val coordinator = coordinator(calls, visibility, reminder)

        coordinator.start()
        runCurrent()
        calls.value = incomingCall("call-1")
        runCurrent()
        calls.value = incomingCall("call-2")
        runCurrent()

        assertThat(reminder.visibleContent?.callId).isEqualTo("call-2")
        assertThat(reminder.maximumSimultaneousReminders).isEqualTo(1)
    }

    @Test
    fun `foreground transition dismisses the background reminder`() = runTest {
        val calls = MutableStateFlow<IncomingCall?>(incomingCall("call-1"))
        val visibility = FakeVisibility(isForeground = false)
        val reminder = RecordingReminder()
        val coordinator = coordinator(calls, visibility, reminder)

        coordinator.start()
        runCurrent()
        visibility.state.value = true
        runCurrent()

        assertThat(reminder.visibleContent).isNull()
        assertThat(reminder.dismissCount).isEqualTo(1)
    }

    private fun kotlinx.coroutines.test.TestScope.coordinator(
        calls: MutableStateFlow<IncomingCall?>,
        visibility: FakeVisibility,
        reminder: RecordingReminder,
    ): IncomingCallReminderCoordinator {
        val observeCalls = mockk<ObservePresentableIncomingCallUseCase>()
        val observePairBond = mockk<ObservePairBondUseCase>()
        every { observeCalls() } returns calls
        every { observePairBond() } returns MutableStateFlow(pairBond())
        return IncomingCallReminderCoordinator(
            observePresentableIncomingCall = observeCalls,
            observePairBond = observePairBond,
            visibility = visibility,
            reminder = reminder,
            policy = IncomingCallReminderPolicy(),
            applicationScope = backgroundScope,
        )
    }

    private class FakeVisibility(isForeground: Boolean) : ApplicationVisibility {
        val state = MutableStateFlow(isForeground)
        override val isForeground = state
    }

    private class RecordingReminder : IncomingCallReminder {
        var visibleContent: IncomingCallReminderContent? = null
            private set
        var dismissCount = 0
            private set
        var maximumSimultaneousReminders = 0
            private set

        override fun replace(content: IncomingCallReminderContent) {
            visibleContent = content
            maximumSimultaneousReminders = maxOf(maximumSimultaneousReminders, 1)
        }

        override fun dismiss() {
            if (visibleContent != null) dismissCount++
            visibleContent = null
        }
    }

    private fun incomingCall(callId: String) = IncomingCall(callId, "pair-1", "user-b", 1L)

    private fun pairBond() = PairBond(
        pairId = "pair-1",
        self = SelfProfile("user-a", "User A"),
        partner = PairedPartner("user-b", "Partner"),
        bondedAtEpochMillis = 1L,
    )
}
