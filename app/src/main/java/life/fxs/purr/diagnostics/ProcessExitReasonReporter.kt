package life.fxs.purr.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log

internal data class ExitRecord(
    val reason: Int,
    val status: Int,
    val importance: Int,
    val pssKb: Long,
    val rssKb: Long,
    val timestamp: Long,
    val processName: String?,
    val description: String?,
)

internal fun interface ExitInfoSource {
    fun load(maxRecords: Int): List<ExitRecord>
}

internal interface ExitCursorStore {
    fun read(): Long?
    fun write(timestamp: Long)
}

internal interface ExitReportSink {
    fun report(reasonName: String, record: ExitRecord)
    fun breadcrumb(reasonName: String, record: ExitRecord)
}

/** Reports why the OS ended earlier processes, once per record, without reading trace streams. */
internal class ProcessExitReasonReporter(
    private val source: ExitInfoSource,
    private val cursor: ExitCursorStore,
    private val sink: ExitReportSink,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) {
    fun reportNewExits() {
        if (sdkInt < Build.VERSION_CODES.R) return
        val records = source.load(MAX_RECORDS)
        val last = cursor.read()
        if (last == null) {
            // First run: record a baseline instead of reporting the backlog.
            cursor.write(records.maxOfOrNull { it.timestamp } ?: 0L)
            return
        }
        val fresh = records.filter { it.timestamp > last }.sortedBy { it.timestamp }
        for (record in fresh) {
            val name = reasonName(record.reason)
            when (classify(record.reason)) {
                Action.Report -> sink.report(name, record)
                Action.BreadcrumbOnly -> sink.breadcrumb(name, record)
                Action.Ignore -> Unit
            }
        }
        fresh.lastOrNull()?.let { cursor.write(it.timestamp) }
    }

    enum class Action { Report, BreadcrumbOnly, Ignore }

    companion object {
        const val MAX_RECORDS = 16
        private const val CURSOR_KEY = "last_exit_timestamp"

        fun classify(reason: Int): Action = when (reason) {
            ApplicationExitInfo.REASON_EXIT_SELF,
            ApplicationExitInfo.REASON_USER_REQUESTED,
            ApplicationExitInfo.REASON_USER_STOPPED,
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> Action.Ignore
            // Sentry already captures Java crashes and ANRs.
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_ANR -> Action.BreadcrumbOnly
            else -> Action.Report
        }

        fun reasonName(reason: Int): String = when (reason) {
            ApplicationExitInfo.REASON_ANR -> "ANR"
            ApplicationExitInfo.REASON_CRASH -> "CRASH"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
            ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
            ApplicationExitInfo.REASON_OTHER -> "OTHER"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
            ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
            ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
            ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
            else -> "UNKNOWN"
        }

        /** Builds the production reporter; callers must run [reportNewExits] off the main thread. */
        fun create(context: Context): ProcessExitReasonReporter {
            val app = context.applicationContext
            val prefs = app.getSharedPreferences("purr_diagnostics", Context.MODE_PRIVATE)
            return ProcessExitReasonReporter(
                source = AndroidExitInfoSource(app),
                cursor = object : ExitCursorStore {
                    override fun read(): Long? =
                        if (prefs.contains(CURSOR_KEY)) prefs.getLong(CURSOR_KEY, 0L) else null

                    override fun write(timestamp: Long) {
                        prefs.edit().putLong(CURSOR_KEY, timestamp).apply()
                    }
                },
                sink = PurrExitReportSink,
            )
        }
    }
}

private class AndroidExitInfoSource(private val context: Context) : ExitInfoSource {
    override fun load(maxRecords: Int): List<ExitRecord> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        val manager = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        return manager.getHistoricalProcessExitReasons(context.packageName, 0, maxRecords).map {
            ExitRecord(
                reason = it.reason,
                status = it.status,
                importance = it.importance,
                pssKb = it.pss,
                rssKb = it.rss,
                timestamp = it.timestamp,
                processName = it.processName,
                description = it.description,
            )
        }
    }
}

private object PurrExitReportSink : ExitReportSink {
    private const val TAG = "ProcessExit"

    override fun report(reasonName: String, record: ExitRecord) {
        Log.w(TAG, "Process exited: $reasonName status=${record.status} importance=${record.importance}")
        PurrSentry.processExit(
            reasonName,
            buildMap {
                put("status", record.status.toString())
                put("importance", record.importance.toString())
                put("pss_kb", record.pssKb.toString())
                put("rss_kb", record.rssKb.toString())
                put("timestamp", record.timestamp.toString())
                record.processName?.let { put("process_name", it) }
                record.description?.let { put("description", PurrSentry.sanitizeForSentry(it).take(200)) }
            },
        )
    }

    override fun breadcrumb(reasonName: String, record: ExitRecord) {
        Log.i(TAG, "Process exited: $reasonName (captured natively) timestamp=${record.timestamp}")
        PurrSentry.breadcrumb(TAG, "Process exited: $reasonName")
    }
}
