package life.fxs.purr.platform.incomingcall

import android.Manifest
import android.app.Application
import android.app.PendingIntent
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.core.graphics.drawable.IconCompat
import androidx.core.os.BundleCompat
import com.google.common.truth.Truth.assertThat
import life.fxs.purr.domain.incomingcall.IncomingCallReminderContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34, 35])
class AndroidIncomingCallReminderTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val notificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before
    fun setUp() {
        notificationManager.cancelAll()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    @After
    fun tearDown() {
        notificationManager.cancelAll()
        scope.cancel()
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
    fun `incoming call notification publishes the loaded caller avatar`() {
        val avatar = IconCompat.createWithBitmap(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888))
        val loader = RecordingAvatarLoader(avatar)

        reminder(avatarLoader = loader).replace(
            incomingCall("call-1", callerAvatarUrl = "https://example.test/avatar.png"),
        )

        val notification = notificationManager.activeNotifications.single().notification
        val person = notification.callPerson()
        assertThat(loader.loadedUrls).containsExactly("https://example.test/avatar.png")
        assertThat(person?.icon).isNotNull()
    }

    @Test
    fun `avatar loading failure preserves the incoming call notification`() {
        reminder(avatarLoader = RecordingAvatarLoader(null)).replace(
            incomingCall("call-1", callerAvatarUrl = "https://example.test/missing.png"),
        )

        val notification = notificationManager.activeNotifications.single().notification
        val person = notification.callPerson()
        assertThat(notification.actions).hasLength(2)
        assertThat(person?.name.toString()).isEqualTo("Partner")
        assertThat(person?.icon).isNull()
    }

    @Test
    fun `stale avatar completion cannot overwrite a newer incoming call`() {
        val loader = DeferredAvatarLoader()
        val reminder = reminder(avatarLoader = loader)
        reminder.replace(
            incomingCall(
                callId = "call-old",
                callerName = "Old Partner",
                callerAvatarUrl = "https://example.test/old.png",
            ),
        )
        reminder.replace(incomingCall(callId = "call-new", callerName = "New Partner"))

        loader.complete(
            IconCompat.createWithBitmap(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)),
        )

        val notification = notificationManager.activeNotifications.single().notification
        val person = notification.callPerson()
        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            .isEqualTo("New Partner")
        assertThat(person?.icon).isNull()
    }

    @Test
    fun `dismiss removes the single reminder slot`() {
        val reminder = reminder()
        reminder.replace(incomingCall("call-1"))

        reminder.dismiss()

        assertThat(notificationManager.activeNotifications).isEmpty()
    }

    private fun reminder(
        canUseFullScreenIntent: Boolean = true,
        avatarLoader: CallNotificationAvatarLoader = RecordingAvatarLoader(null),
    ) = AndroidIncomingCallReminder(
        context = context,
        intentFactory = IncomingCallIntentFactory(
            context = context,
            uiPendingIntentFactory = object : IncomingCallUiPendingIntentFactory {
                override fun open(content: IncomingCallReminderContent): PendingIntent =
                    activityPendingIntent("open", content.callId)

                override fun answer(content: IncomingCallReminderContent): PendingIntent =
                    activityPendingIntent("answer", content.callId)
            },
        ),
        fullScreenIntentCapability = FullScreenIntentCapability { canUseFullScreenIntent },
        avatarLoader = avatarLoader,
        applicationScope = scope,
    )

    private fun activityPendingIntent(command: String, callId: String): PendingIntent =
        PendingIntent.getActivity(
            context,
            command.hashCode(),
            Intent("life.fxs.purr.test.$command.$callId").setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun incomingCall(
        callId: String,
        callerName: String = "Partner",
        callerAvatarUrl: String? = null,
    ) = IncomingCallReminderContent(
        callId = callId,
        pairId = "pair-1",
        callerName = callerName,
        callerAvatarUrl = callerAvatarUrl,
        startedAtEpochMillis = 1L,
    )

    private class RecordingAvatarLoader(
        private val result: IconCompat?,
    ) : CallNotificationAvatarLoader {
        val loadedUrls = mutableListOf<String>()

        override suspend fun load(url: String): IconCompat? {
            loadedUrls += url
            return result
        }
    }

    private class DeferredAvatarLoader : CallNotificationAvatarLoader {
        private var continuation: Continuation<IconCompat?>? = null

        override suspend fun load(url: String): IconCompat? = suspendCoroutine { continuation = it }

        fun complete(icon: IconCompat?) {
            continuation?.resume(icon)
            continuation = null
        }
    }

    private fun Notification.callPerson(): android.app.Person? =
        BundleCompat.getParcelable(extras, Notification.EXTRA_CALL_PERSON, android.app.Person::class.java)
}
