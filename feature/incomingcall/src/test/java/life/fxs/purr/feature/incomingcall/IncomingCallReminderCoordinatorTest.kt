package life.fxs.purr.feature.incomingcall

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import life.fxs.purr.domain.account.model.IncomingCall
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

        assertThat(reminder.visibleCall?.callId).isEqualTo("call-2")
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

        assertThat(reminder.visibleCall).isNull()
        assertThat(reminder.dismissCount).isEqualTo(1)
    }

    @Test
    fun `starting twice does not create duplicate reminder owners`() = runTest {
        val calls = MutableStateFlow<IncomingCall?>(incomingCall("call-1"))
        val visibility = FakeVisibility(isForeground = false)
        val reminder = RecordingReminder()
        val coordinator = coordinator(calls, visibility, reminder)

        coordinator.start()
        coordinator.start()
        runCurrent()

        assertThat(reminder.replaceCount).isEqualTo(1)
    }

    private fun kotlinx.coroutines.test.TestScope.coordinator(
        calls: MutableStateFlow<IncomingCall?>,
        visibility: FakeVisibility,
        reminder: RecordingReminder,
    ) = IncomingCallReminderCoordinator(
        source = IncomingCallSource { calls },
        visibility = visibility,
        reminder = reminder,
        policy = IncomingCallReminderPolicy(),
        applicationScope = backgroundScope,
    )

    private class FakeVisibility(isForeground: Boolean) : ApplicationVisibility {
        val state = MutableStateFlow(isForeground)
        override val isForeground = state
    }

    private class RecordingReminder : IncomingCallReminder {
        var visibleCall: IncomingCall? = null
            private set
        var replaceCount = 0
            private set
        var dismissCount = 0
            private set
        var maximumSimultaneousReminders = 0
            private set

        override fun replace(call: IncomingCall) {
            visibleCall = call
            replaceCount++
            maximumSimultaneousReminders = maxOf(maximumSimultaneousReminders, 1)
        }

        override fun dismiss() {
            if (visibleCall != null) dismissCount++
            visibleCall = null
        }
    }

    private fun incomingCall(callId: String) = IncomingCall(
        callId = callId,
        pairId = "pair-1",
        callerUserId = "user-b",
        startedAtEpochMillis = 1L,
    )
}
