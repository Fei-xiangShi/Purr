package life.fxs.purr.domain.incomingcall

import com.google.common.truth.Truth.assertThat
import life.fxs.purr.core.model.PairedPartner
import life.fxs.purr.domain.account.model.IncomingCall
import org.junit.Test

class IncomingCallReminderPolicyTest {
    private val policy = IncomingCallReminderPolicy()

    @Test
    fun `matching caller data is copied into the platform contract`() {
        val target = policy.resolve(incomingCall(), partner("user-b"), isForeground = false)

        assertThat(target).isEqualTo(
            IncomingCallReminderTarget.Visible(
                IncomingCallReminderContent(
                    callId = "call-1",
                    pairId = "pair-1",
                    callerName = "Partner",
                    callerAvatarUrl = "https://example.test/avatar",
                    startedAtEpochMillis = 1L,
                ),
            ),
        )
    }

    @Test
    fun `mismatched caller data is not disclosed`() {
        val target = policy.resolve(incomingCall(), partner("another-user"), isForeground = false)
            as IncomingCallReminderTarget.Visible

        assertThat(target.content.callerName).isNull()
        assertThat(target.content.callerAvatarUrl).isNull()
    }

    @Test
    fun `foreground application keeps system reminder hidden`() {
        val target = policy.resolve(incomingCall(), partner("user-b"), isForeground = true)

        assertThat(target).isEqualTo(IncomingCallReminderTarget.Hidden)
    }

    private fun incomingCall() = IncomingCall("call-1", "pair-1", "user-b", 1L)

    private fun partner(userId: String) = PairedPartner(
        userId = userId,
        displayName = "Partner",
        avatarUrl = "https://example.test/avatar",
    )
}
