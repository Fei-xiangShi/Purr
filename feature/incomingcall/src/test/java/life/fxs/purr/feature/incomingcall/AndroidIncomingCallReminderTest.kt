package life.fxs.purr.feature.incomingcall

import android.app.Notification
import android.app.NotificationManager
import com.google.common.truth.Truth.assertThat
import life.fxs.purr.domain.account.model.IncomingCall
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class AndroidIncomingCallReminderTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val notificationManager get() = context.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        notificationManager.cancelAll()
    }

    @After
    fun tearDown() {
        notificationManager.cancelAll()
    }

    @Test
    fun `replacing calls keeps exactly one platform notification`() {
        val reminder = AndroidIncomingCallReminder(context)

        reminder.replace(incomingCall("call-1"))
        reminder.replace(incomingCall("call-2"))

        assertThat(notificationManager.activeNotifications).hasLength(1)
    }

    @Test
    fun `replacement removes the untagged legacy notification slot`() {
        notificationManager.notify(1002, Notification())
        val reminder = AndroidIncomingCallReminder(context)

        reminder.replace(incomingCall("call-1"))

        assertThat(notificationManager.activeNotifications).hasLength(1)
        assertThat(notificationManager.activeNotifications.single().tag).isEqualTo("incoming-call")
    }

    @Test
    fun `dismiss removes the single reminder slot`() {
        val reminder = AndroidIncomingCallReminder(context)
        reminder.replace(incomingCall("call-1"))

        reminder.dismiss()

        assertThat(notificationManager.activeNotifications).isEmpty()
    }

    private fun incomingCall(callId: String) = IncomingCall(
        callId = callId,
        pairId = "pair-1",
        callerUserId = "user-b",
        startedAtEpochMillis = 1L,
    )
}
