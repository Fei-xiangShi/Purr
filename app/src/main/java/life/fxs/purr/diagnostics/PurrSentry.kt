package life.fxs.purr.diagnostics

import android.app.Application
import android.util.Log
import io.sentry.Breadcrumb
import io.sentry.ITransaction
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.SpanStatus
import io.sentry.android.core.SentryAndroid
import java.security.MessageDigest
import life.fxs.purr.BuildConfig
import life.fxs.purr.core.media.telemetry.CallInterruptionTelemetry
import life.fxs.purr.core.media.telemetry.CallInterruptionTelemetryOperation
import life.fxs.purr.core.media.telemetry.CallInterruptionTransitionContext
import life.fxs.purr.core.media.telemetry.CallInterruptionTransitionPhase
import life.fxs.purr.core.media.telemetry.NoOpCallInterruptionTelemetryOperation
import life.fxs.purr.core.media.screenshare.ScreenShareFailureReporter
import life.fxs.purr.core.media.screenshare.ScreenShareFailureCode

/** Optional crash and call-chain reporting, enabled only when a DSN is supplied. */
object PurrSentry : CallInterruptionTelemetry, ScreenShareFailureReporter {
    private const val LOG_TAG = "PurrSentry"
    private const val REDACTED = "<redacted>"
    private val callIdPattern = Regex("(?i)\\b(callId|call_id)\\s*[:=]\\s*\\\"?([^\\\"\\s,}]+)")
    private val callRefPattern = Regex("\\bcall_ref=([^\\s]+)")
    private val secretPattern = Regex(
        "(?i)\\b(accessToken|refreshToken|bearerToken|token|authorization|apiKey|apiSecret|secret|password|passphrase|streamid)" +
            "\\s*[:=]\\s*\\\"?([^\\\"\\s,}]+)",
    )
    private val bearerPattern = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/-]+=*")
    private val phoneFieldPattern = Regex(
        "(?i)\\b(phone|phoneNumber|mobile|contact|contactNumber)\\s*[:=]\\s*\\\"?([^\\\"\\s,}]+)",
    )
    private val phoneLikePattern = Regex("(?<![A-Za-z0-9])\\+?\\d[\\d ()-]{5,}\\d(?![A-Za-z0-9])")
    private val sdpIcePattern = Regex(
        "(?im)^(a=(?:ice-ufrag|ice-pwd|candidate|fingerprint):).+$|\\b(sdp|iceCredential|icePassword)\\s*[:=]\\s*\\\"?([^\\\"\\r\\n,}]+)",
    )
    private val interruptionAttributePattern = Regex(
        "(?i)(life\\.fxs\\.purr\\.call\\.interruption)\\s*[:=]\\s*.+",
    )
    private val sensitiveKeyPattern = Regex(
        "(?i)(authorization|token|secret|password|passphrase|streamid|sdp|ice|phone|mobile|contact|call_id|callId|" +
            "life\\.fxs\\.purr\\.call\\.interruption)",
    )

    @Volatile
    private var enabled = false
    private val mediaFailures = linkedMapOf<String, Long>()
    private val transportUrlPattern = Regex("(?i)\\b(?:https?|wss?|srt)://[^\\s\\\"<>]+")

    fun initialize(application: Application) {
        val dsn = BuildConfig.PURR_SENTRY_DSN.trim()
        if (dsn.isBlank()) {
            Log.i(LOG_TAG, "Sentry disabled: PURR_SENTRY_DSN is not configured")
            return
        }
        runCatching {
            SentryAndroid.init(application) { options ->
                options.dsn = dsn
                options.isSendDefaultPii = false
                options.environment = if (BuildConfig.DEBUG) "debug" else "production"
                options.release = "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
                options.tracesSampleRate = if (BuildConfig.DEBUG) 1.0 else 0.10
                options.setBeforeBreadcrumb { breadcrumb, _ -> sanitizeBreadcrumb(breadcrumb) }
                options.setBeforeSend { event, _ -> sanitizeEvent(event) }
            }
            Sentry.configureScope { scope ->
                scope.setTag("app_version", BuildConfig.VERSION_NAME)
                scope.setTag("build_type", if (BuildConfig.DEBUG) "debug" else "release")
            }
            enabled = true
            Log.i(LOG_TAG, "Sentry initialized")
        }.onFailure { error ->
            enabled = false
            Log.e(LOG_TAG, "Sentry initialization failed", error)
        }
    }

    fun breadcrumb(tag: String, message: String) {
        if (!enabled) return
        if (tag == "CallTelemetry" && "event=sample" in message) return
        runCatching {
            Sentry.addBreadcrumb(
                Breadcrumb().apply {
                    category = "purr.${sanitizeForSentry(tag)}"
                    this.message = sanitizeForSentry(message)
                    level = SentryLevel.INFO
                },
            )
        }
    }

    fun error(tag: String, throwable: Throwable?, message: String) {
        if (!enabled || !shouldReportHandledError(tag, throwable, message)) return
        val sanitized = sanitizeForSentry(message)
        runCatching {
            Sentry.withScope { scope ->
                scope.setTag("log_tag", sanitizeForSentry(tag))
                val phase = Regex("\\bphase=([^\\s]+)").find(message)?.groupValues?.get(1)
                if (tag.startsWith("Call")) {
                    scope.fingerprint = listOf("purr-call", tag, phase ?: "unknown", throwable?.javaClass?.simpleName ?: "failure")
                }
                callRefPattern.find(sanitized)?.groupValues?.getOrNull(1)?.let { callRef ->
                    scope.setTag("call_ref", callRef)
                }
                scope.setExtra("log_message", sanitized)
                if (throwable != null) {
                    Sentry.captureException(throwable)
                } else {
                    Sentry.captureMessage(sanitized, SentryLevel.ERROR)
                }
            }
        }
    }

    override fun report(callId: String?, shareId: String?, code: ScreenShareFailureCode) {
        if (!enabled || !code.reportable) return
        val key = "${shareId ?: callId}:$code"
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(mediaFailures) {
            if (mediaFailures[key]?.let { now - it < 30_000L } == true) return
            mediaFailures[key] = now
            while (mediaFailures.size > 128) mediaFailures.remove(mediaFailures.keys.first())
        }
        runCatching {
            Sentry.withScope { scope ->
                scope.setTag("media", "screen-share")
                scope.setTag("failure_code", code.name)
                callId?.let { scope.setTag("call_ref", callReference(it)) }
                shareId?.let { scope.setTag("share_ref", callReference(it)) }
                scope.fingerprint = listOf("purr-screen-share", code.name)
                Sentry.captureMessage("Screen share failed: ${code.name}", SentryLevel.ERROR)
            }
        }
    }

    override fun recordTelecomCallback(
        callId: String,
        operationId: String,
        sequence: Long,
        callback: String,
        result: String,
        elapsedMillis: Long,
    ) {
        if (!enabled) return
        val callRef = callReference(callId)
        runCatching {
            Sentry.addBreadcrumb(
                Breadcrumb().apply {
                    category = "purr.call.system_interruption.telecom"
                    message = "Telecom interruption callback"
                    level = SentryLevel.INFO
                    setData("call_ref", callRef)
                    setData("operation_id", operationId)
                    setData("sequence", sequence)
                    setData("callback", callback)
                    setData("result", result)
                    setData("elapsed_ms", elapsedMillis)
                },
            )
        }
    }

    override fun startTransition(
        context: CallInterruptionTransitionContext,
    ): CallInterruptionTelemetryOperation {
        if (!enabled) return NoOpCallInterruptionTelemetryOperation
        return runCatching {
            val phase = context.phase.name.lowercase()
            val transaction = Sentry.startTransaction(
                "call.system_interruption.$phase",
                "call.system_interruption",
            )
            val callRef = callReference(context.callId)
            transaction.setTag("call_ref", callRef)
            transaction.setTag("operation_id", context.operationId)
            transaction.setTag("phase", phase)
            transaction.setData("lifecycle_generation", context.lifecycleGeneration)
            transaction.setData("media_generation", context.mediaGeneration)
            SentryCallInterruptionOperation(
                transaction = transaction,
                callRef = callRef,
                operationId = context.operationId,
                phase = context.phase,
            )
        }.getOrElse { NoOpCallInterruptionTelemetryOperation }
    }

    internal fun callReference(callId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(callId.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    internal fun sanitizeForSentry(value: String): String {
        var sanitized = callIdPattern.replace(transportUrlPattern.replace(value, REDACTED)) { match ->
            "call_ref=${callReference(match.groupValues[2])}"
        }
        sanitized = secretPattern.replace(sanitized) { match ->
            "${match.groupValues[1]}=$REDACTED"
        }
        sanitized = bearerPattern.replace(sanitized, "Bearer $REDACTED")
        sanitized = phoneFieldPattern.replace(sanitized) { match ->
            "${match.groupValues[1]}=$REDACTED"
        }
        sanitized = sdpIcePattern.replace(sanitized, REDACTED)
        sanitized = interruptionAttributePattern.replace(sanitized) { match ->
            "${match.groupValues[1]}=$REDACTED"
        }
        return phoneLikePattern.replace(sanitized, REDACTED)
    }

    private fun sanitizeBreadcrumb(breadcrumb: Breadcrumb): Breadcrumb {
        breadcrumb.message = breadcrumb.message?.let(::sanitizeForSentry)
        breadcrumb.category = breadcrumb.category?.let(::sanitizeForSentry)
        breadcrumb.data.toMap().forEach { (key, value) ->
            breadcrumb.setData(
                key,
                if (sensitiveKeyPattern.containsMatchIn(key)) REDACTED else sanitizeStructuredValue(value),
            )
        }
        return breadcrumb
    }

    private fun sanitizeEvent(event: SentryEvent): SentryEvent {
        event.message?.let { message ->
            message.formatted = message.formatted?.let(::sanitizeForSentry)
            message.message = message.message?.let(::sanitizeForSentry)
            message.params = message.params?.map(::sanitizeForSentry)
        }
        event.breadcrumbs?.forEach(::sanitizeBreadcrumb)
        event.exceptions?.forEach { exception ->
            exception.value = exception.value?.let(::sanitizeForSentry)
        }
        event.tags?.toMap()?.forEach { (key, value) ->
            when {
                key.equals("call_id", ignoreCase = true) || key.equals("callId", ignoreCase = true) -> {
                    event.removeTag(key)
                    event.setTag("call_ref", callReference(value))
                }
                sensitiveKeyPattern.containsMatchIn(key) -> event.setTag(key, REDACTED)
                else -> event.setTag(key, sanitizeForSentry(value))
            }
        }
        event.extras?.toMap()?.forEach { (key, value) ->
            event.setExtra(
                key,
                if (sensitiveKeyPattern.containsMatchIn(key)) REDACTED else sanitizeStructuredValue(value),
            )
        }
        event.request?.let { request ->
            request.url = request.url?.let(::sanitizeForSentry)
            request.queryString = request.queryString?.let(::sanitizeForSentry)
            request.data = request.data?.let(::sanitizeStructuredValue)
            request.cookies = null
            request.headers = request.headers?.mapValues { (key, value) ->
                if (sensitiveKeyPattern.containsMatchIn(key)) REDACTED else sanitizeForSentry(value)
            }
        }
        return event
    }

    private fun sanitizeStructuredValue(value: Any?): Any? = when (value) {
        null -> null
        is String -> sanitizeForSentry(value)
        is Map<*, *> -> value.entries.associate { (key, nested) ->
            val keyString = key?.toString().orEmpty()
            keyString to if (sensitiveKeyPattern.containsMatchIn(keyString)) {
                REDACTED
            } else {
                sanitizeStructuredValue(nested)
            }
        }
        is Iterable<*> -> value.map(::sanitizeStructuredValue)
        else -> value
    }

    private class SentryCallInterruptionOperation(
        private val transaction: ITransaction,
        private val callRef: String,
        private val operationId: String,
        private val phase: CallInterruptionTransitionPhase,
    ) : CallInterruptionTelemetryOperation {
        override fun recordAttempt(
            attempt: Int,
            retriesRemaining: Int,
            result: String,
            reasonCode: String?,
        ) {
            runCatching {
                // Called after an attempt completes, so a span started here would
                // invent a zero-duration timing measurement. Keep actual outcome data.
                transaction.setData("last_attempt", attempt)
                transaction.setData("retries_remaining", retriesRemaining)
                transaction.setData("last_attempt_result", result)
                reasonCode?.let { transaction.setData("last_attempt_reason", sanitizeForSentry(it)) }
            }
        }

        override fun finish(
            result: String,
            reasonCode: String?,
            throwable: Throwable?,
            finalFailure: Boolean,
        ) {
            if (transaction.isFinished) return
            runCatching {
                transaction.setTag("result", result)
                reasonCode?.let { transaction.setData("reason_code", sanitizeForSentry(it)) }
                transaction.finish(
                    interruptionSpanStatus(result),
                )
                if (finalFailure) {
                    error(
                        tag = "CallInterruption",
                        throwable = throwable,
                        message = "call_ref=$callRef operation_id=$operationId phase=${phase.name.lowercase()} " +
                            "result=$result reason=${reasonCode?.let(::sanitizeForSentry) ?: "unknown"}",
                    )
                }
            }
        }
    }
}
