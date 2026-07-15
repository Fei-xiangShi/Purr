package life.fxs.purr.platform.incomingcall

import android.app.Notification
import android.app.NotificationManager
import com.google.common.truth.Truth.assertThat
import life.fxs.purr.feature.incomingcall.IncomingCallReminderContent
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
        val reminder = reminder()

        reminder.replace(incomingCall("call-1"))
        reminder.replace(incomingCall("call-2"))

        assertThat(notificationManager.activeNotifications).hasLength(1)
    }

    @Test
    fun `incoming call notification owns full screen and call actions`() {
        reminder(canUseFullScreenIntent = true).replace(incomingCall("call-1"))

        val notification = notificationManager.activeNotifications.single().notification

        assertThat(notification.fullScreenIntent).isNotNull()
        assertThat(notification.contentIntent).isNotNull()
        assertThat(notification.actions).hasLength(2)
        assertThat(notification.category).isEqualTo(Notification.CATEGORY_CALL)
        assertThat(notification.flags and Notification.FLAG_ONGOING_EVENT).isNotEqualTo(0)
    }

    @Test
    fun `full screen denial falls back to call notification`() {
        reminder(canUseFullScreenIntent = false).replace(incomingCall("call-1"))

        val notification = notificationManager.activeNotifications.single().notification

        assertThat(notification.fullScreenIntent).isNull()
        assertThat(notification.contentIntent).isNotNull()
        assertThat(notification.actions).hasLength(2)
    }

    @Test
    fun `replacement removes the untagged legacy notification slot`() {
        notificationManager.notify(1002, Notification())

        reminder().replace(incomingCall("call-1"))

        assertThat(notificationManager.activeNotifications).hasLength(1)
        assertThat(notificationManager.activeNotifications.single().tag).isEqualTo("incoming-call")
    }

    @Test
    fun `dismiss removes the single reminder slot`() {
        val reminder = reminder()
        reminder.replace(incomingCall("call-1"))

        reminder.dismiss()

        assertThat(notificationManager.activeNotifications).isEmpty()
    }

    private fun reminder(canUseFullScreenIntent: Boolean = true) = AndroidIncomingCallReminder(
        context = context,
        intentFactory = IncomingCallIntentFactory(context),
        fullScreenIntentCapability = FullScreenIntentCapability { canUseFullScreenIntent },
    )

    private fun incomingCall(callId: String) = IncomingCallReminderContent(
        callId = callId,
        pairId = "pair-1",
        callerName = "Partner",
        callerAvatarUrl = null,
        startedAtEpochMillis = 1L,
    )
}
