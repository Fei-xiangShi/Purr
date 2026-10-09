package life.fxs.purr.diagnostics

import android.app.ApplicationExitInfo
import android.os.Build
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProcessExitReasonReporterTest {
    private val cursor = FakeCursor()
    private val sink = FakeSink()
    private var records = emptyList<ExitRecord>()

    private fun reporter(sdk: Int = Build.VERSION_CODES.R) =
        ProcessExitReasonReporter({ records }, cursor, sink, sdk)

    private fun record(reason: Int, ts: Long) = ExitRecord(reason, 0, 100, 1, 2, ts, "p", "d")

    @Test
    fun `first run records baseline without reporting`() {
        records = listOf(record(ApplicationExitInfo.REASON_LOW_MEMORY, 10), record(ApplicationExitInfo.REASON_SIGNALED, 20))
        reporter().reportNewExits()
        assertThat(cursor.value).isEqualTo(20L)
        assertThat(sink.reported).isEmpty()
    }

    @Test
    fun `reports only newer records and filters by reason`() {
        cursor.value = 10
        records = listOf(
            record(ApplicationExitInfo.REASON_LOW_MEMORY, 5),
            record(ApplicationExitInfo.REASON_SIGNALED, 30),
            record(ApplicationExitInfo.REASON_EXIT_SELF, 40),
            record(ApplicationExitInfo.REASON_USER_REQUESTED, 41),
            record(ApplicationExitInfo.REASON_CRASH, 50),
            record(ApplicationExitInfo.REASON_ANR, 51),
            record(ApplicationExitInfo.REASON_FREEZER, 60),
        )
        reporter().reportNewExits()
        assertThat(sink.reported).containsExactly("SIGNALED", "FREEZER").inOrder()
        assertThat(sink.breadcrumbs).containsExactly("CRASH", "ANR").inOrder()
        assertThat(cursor.value).isEqualTo(60L)
        reporter().reportNewExits()
        assertThat(sink.reported).hasSize(2)
    }

    @Test
    fun `below api 30 does nothing`() {
        records = listOf(record(ApplicationExitInfo.REASON_SIGNALED, 20))
        reporter(Build.VERSION_CODES.Q).reportNewExits()
        assertThat(cursor.value).isNull()
        assertThat(sink.reported).isEmpty()
    }

    private class FakeCursor : ExitCursorStore {
        var value: Long? = null
        override fun read() = value
        override fun write(timestamp: Long) {
            value = timestamp
        }
    }

    private class FakeSink : ExitReportSink {
        val reported = mutableListOf<String>()
        val breadcrumbs = mutableListOf<String>()
        override fun report(reasonName: String, record: ExitRecord) {
            reported += reasonName
        }

        override fun breadcrumb(reasonName: String, record: ExitRecord) {
            breadcrumbs += reasonName
        }
    }
}
