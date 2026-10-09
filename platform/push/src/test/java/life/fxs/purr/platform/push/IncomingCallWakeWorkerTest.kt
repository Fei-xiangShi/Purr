package life.fxs.purr.platform.push

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkerParameters
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IncomingCallWakeWorkerTest {
    @Test
    fun `foreground info carries a notification on the wake channel`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val worker = IncomingCallWakeWorker(context, mockk<WorkerParameters>(relaxed = true), mockk(relaxed = true))

        val info = worker.getForegroundInfo()

        assertThat(info.notificationId).isEqualTo(IncomingCallWakeWorker.WAKE_NOTIFICATION_ID)
        assertThat(info.notification.channelId).isEqualTo(IncomingCallWakeWorker.WAKE_CHANNEL_ID)
        val manager = context.getSystemService(NotificationManager::class.java)
        assertThat(manager.getNotificationChannel(IncomingCallWakeWorker.WAKE_CHANNEL_ID)).isNotNull()
    }
}
