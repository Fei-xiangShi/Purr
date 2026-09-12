package life.fxs.purr.core.media.screenshare

import android.content.Context
import android.media.AudioAttributes
import android.util.Log
import android.view.View
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import livekit.org.webrtc.AudioTrack
import livekit.org.webrtc.DataChannel
import livekit.org.webrtc.HardwareVideoDecoderFactory
import livekit.org.webrtc.DefaultVideoEncoderFactory
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.IceCandidate
import livekit.org.webrtc.MediaConstraints
import livekit.org.webrtc.MediaStream
import livekit.org.webrtc.MediaStreamTrack
import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.PeerConnectionFactory
import livekit.org.webrtc.RendererCommon
import livekit.org.webrtc.RtpReceiver
import livekit.org.webrtc.RtpTransceiver
import livekit.org.webrtc.SdpObserver
import livekit.org.webrtc.SessionDescription
import livekit.org.webrtc.SurfaceViewRenderer
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoSink
import livekit.org.webrtc.VideoTrack
import livekit.org.webrtc.audio.JavaAudioDeviceModule
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class WhepPlaybackRequest(
    val callId: String,
    val shareId: String,
    val url: String,
    val bearerToken: String,
    val expiresAtEpochMillis: Long,
)

sealed interface WhepPlaybackStatus {
    data object Idle : WhepPlaybackStatus
    data class Connecting(val request: WhepPlaybackRequest) : WhepPlaybackStatus
    data class Buffering(val request: WhepPlaybackRequest) : WhepPlaybackStatus
    data class Live(val request: WhepPlaybackRequest, val width: Int = 0, val height: Int = 0) : WhepPlaybackStatus
    data class Failed(val request: WhepPlaybackRequest, val message: String) : WhepPlaybackStatus
    data class Stopped(val shareId: String?) : WhepPlaybackStatus
}

interface WhepPlaybackController {
    val status: StateFlow<WhepPlaybackStatus>
    fun start(request: WhepPlaybackRequest)
    fun stop(shareId: String? = null)
    fun createVideoRenderer(context: Context): View
    fun releaseVideoRenderer(view: View)
}

class NativeWhepPlaybackController(
    context: Context,
    private val okHttpClient: OkHttpClient,
    private val applicationScope: CoroutineScope,
    diagnostics: ScreenShareDiagnosticsStore = ScreenShareDiagnosticsStore(),
    failures: ScreenShareFailureReporter = NoOpScreenShareFailureReporter,
) : WhepPlaybackController {
    private val appContext = context.applicationContext
    private val eglBase = EglBase.create()
    private val videoSink = SwitchableVideoSink()
    private val lifecycle = WhepPlaybackLifecycle(
        scope = CoroutineScope(applicationScope.coroutineContext + Dispatchers.IO),
        factory = { request, listener ->
            NativeWhepSession(
                context = appContext,
                okHttpClient = okHttpClient,
                eglContext = eglBase.eglBaseContext,
                request = request,
                videoSink = videoSink,
                listener = listener,
                scope = applicationScope,
                failures = failures,
            )
        },
        diagnostics = diagnostics,
        failures = failures,
    )
    override val status: StateFlow<WhepPlaybackStatus> = lifecycle.status

    override fun start(request: WhepPlaybackRequest) = lifecycle.start(request)

    override fun stop(shareId: String?) = lifecycle.stop(shareId)

    override fun createVideoRenderer(context: Context): View = SurfaceViewRenderer(context).apply {
        init(eglBase.eglBaseContext, null)
        setEnableHardwareScaler(true)
        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        setMirror(false)
        videoSink.target.set(this)
    }

    override fun releaseVideoRenderer(view: View) {
        val renderer = view as? SurfaceViewRenderer ?: return
        videoSink.target.compareAndSet(renderer, null)
        renderer.clearImage()
        renderer.release()
    }

}

private class SwitchableVideoSink : VideoSink {
    val target = AtomicReference<VideoSink?>(null)
    override fun onFrame(frame: VideoFrame) {
        target.get()?.onFrame(frame)
    }
}

private class NativeWhepSession(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val eglContext: EglBase.Context,
    private val request: WhepPlaybackRequest,
    private val videoSink: SwitchableVideoSink,
    private val listener: WhepSessionListener,
    private val scope: CoroutineScope,
    private val failures: ScreenShareFailureReporter,
) : WhepSession {

    private val closed = AtomicBoolean(false)
    private val iceGatheringComplete = CompletableDeferred<Unit>()
    private val connectionReady = CompletableDeferred<Unit>()
    private val firstFrame = CompletableDeferred<Unit>()
    private var resourceUrl: HttpUrl? = null
    private var peerConnection: PeerConnection? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var metricsJob: Job? = null
    private val frameSink = VideoSink { frame ->
        if (!closed.get() && listener.onFrame(frame.rotatedWidth, frame.rotatedHeight)) {
            firstFrame.complete(Unit)
            videoSink.onFrame(frame)
        }
    }
    private val videoTracks = java.util.concurrent.ConcurrentHashMap.newKeySet<VideoTrack>()

    override suspend fun connect() {
        check(!closed.get()) { "WHEP 会话已关闭" }
        logStage("ice_discovery_start")
        val iceServers = discoverIceServers()
        logStage("ice_discovery_complete", "servers=${iceServers.size}")
        createPeerConnection(iceServers)
        logStage("peer_connection_created")
        val connection = requireNotNull(peerConnection)
        connection.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
        )
        connection.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
        )

        logStage("offer_create_start")
        val offer = connection.createOfferAwait()
        if (!offer.description.contains(" H265/90000", ignoreCase = true)) throw ScreenShareFailureException(
            ScreenShareFailureCode.UnsupportedCodec, "当前设备无法协商 H.265 硬件解码，语音通话仍然可用")
        logStage("offer_create_complete")
        connection.setLocalDescriptionAwait(offer)
        logStage("local_description_set")
        if (connection.iceGatheringState() != PeerConnection.IceGatheringState.COMPLETE) {
            logStage("ice_gathering_wait")
            val completed = withTimeoutOrNull(ICE_GATHERING_GRACE_MILLIS) {
                iceGatheringComplete.await()
                true
            } == true
            if (!completed) logStage("ice_gathering_partial")
        }
        val gatheredOffer = requireNotNull(connection.localDescription) {
            "WHEP 本地 SDP 不可用"
        }
        check(SDP_CANDIDATE_MARKER in gatheredOffer.description) {
            "WHEP 未收集到可用的 ICE candidate"
        }
        logStage("ice_gathering_ready")
        logStage("offer_post_start")
        val answer = postOffer(gatheredOffer.description)
        logStage("offer_post_complete")
        listener.onBuffering()
        connection.setRemoteDescriptionAwait(
            SessionDescription(SessionDescription.Type.ANSWER, answer),
        )
        logStage("remote_description_set")
        withTimeout(CONNECTION_TIMEOUT_MILLIS) { connectionReady.await() }
        logStage("peer_connection_ready")
        if (withTimeoutOrNull(CONNECTION_TIMEOUT_MILLIS) { firstFrame.await(); true } != true) {
            throw ScreenShareFailureException(ScreenShareFailureCode.FirstFrameTimeout, "媒体已连接但没有收到可解码画面")
        }
        metricsJob = scope.launch(Dispatchers.IO) {
            val collector = WhepQualityCollector()
            while (isActive && !closed.get()) {
                val report = try { withTimeoutOrNull(2_000L) {
                    suspendCancellableCoroutine<livekit.org.webrtc.RTCStatsReport> { continuation ->
                        synchronized(this@NativeWhepSession) {
                            if (!closed.get()) connection.getStats { stats ->
                                if (continuation.isActive) continuation.resume(stats)
                            }
                        }
                    }
                } } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    null
                }
                if (report != null && !closed.get()) listener.onQuality(collector.collect(report))
                delay(1_000L)
            }
        }
    }

    private fun createPeerConnection(iceServers: List<PeerConnection.IceServer>) {
        val videoDecoderFactory = HevcHardwareDecoderFactory(
            HardwareVideoDecoderFactory(eglContext) { codec ->
                codec.isHardwareAccelerated && !codec.isSoftwareOnly
            },
        )
        if (videoDecoderFactory.supportedCodecs.isEmpty()) throw ScreenShareFailureException(
            ScreenShareFailureCode.UnsupportedCodec, "当前设备不支持 H.265 硬件解码，无法观看直播；语音通话仍然可用")
        val mediaAudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()
        val audioModule = JavaAudioDeviceModule.builder(context)
            .setAudioAttributes(mediaAudioAttributes)
            .setUseStereoOutput(true)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .createAudioDeviceModule()
        audioDeviceModule = audioModule

        // LiveKit owns process-global WebRTC initialization. This factory shares the exact
        // prefixed runtime version and deliberately never calls initialize()/shutdownInternalTracer().
        val factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioModule)
            .setVideoDecoderFactory(videoDecoderFactory)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglContext, true, true))
            .createPeerConnectionFactory()
        peerConnectionFactory = factory

        val configuration = PeerConnection.RTCConfiguration(iceServers).apply {
            // Native video jitter buffering stays adaptive. NetEq buffers program audio;
            // cap it at 50 packets (~1 s for 20 ms Opus), and catch up after bursts.
            // Never retain decoded hardware textures in an application frame queue.
            audioJitterBufferMaxPackets = 50
            audioJitterBufferFastAccelerate = true
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val connection = requireNotNull(factory.createPeerConnection(configuration, Observer())) {
            "设备无法创建 WHEP PeerConnection"
        }
        connection.setAudioRecording(false)
        connection.setAudioPlayout(true)
        peerConnection = connection
    }

    private suspend fun discoverIceServers(): List<PeerConnection.IceServer> {
        val options = Request.Builder()
            .url(request.url)
            .method("OPTIONS", null)
            .header("Authorization", "Bearer ${request.bearerToken}")
            .build()
        return okHttpClient.newCall(options).awaitResponse().use { response ->
            if (response.code == 401 || response.code == 403) {
                throw ScreenShareFailureException(ScreenShareFailureCode.CredentialsExpired, "WHEP 播放凭证无效或已过期")
            }
            if (!response.isSuccessful && response.code != 404 && response.code != 405) {
                throw ScreenShareFailureException(ScreenShareFailureCode.Signaling, "WHEP ICE 配置请求失败（${response.code}）")
            }
            response.headers.toIceServers()
        }
    }

    private suspend fun postOffer(sdp: String): String {
        val post = Request.Builder()
            .url(request.url)
            .post(sdp.toRequestBody(SDP_MEDIA_TYPE))
            .header("Authorization", "Bearer ${request.bearerToken}")
            .header("Accept", "application/sdp")
            .build()
        return okHttpClient.newCall(post).awaitResponse().use { response ->
            if (!response.isSuccessful) {
                if (response.code == 404) throw ScreenShareFailureException(ScreenShareFailureCode.StreamEnded, "直播已停止或尚未开始")
                if (response.code == 401 || response.code == 403) {
                    throw ScreenShareFailureException(ScreenShareFailureCode.CredentialsExpired, "WHEP 播放凭证无效或已过期")
                }
                throw ScreenShareFailureException(ScreenShareFailureCode.Signaling, "WHEP 协商失败（${response.code}）")
            }
            response.header("Location")?.let { location ->
                val endpoint = response.request.url
                val resource = endpoint.resolve(location)
                if (resource == null || resource.scheme != endpoint.scheme || resource.host != endpoint.host ||
                    resource.port != endpoint.port || !resource.encodedPath.startsWith(endpoint.encodedPath + "/")) {
                    throw ScreenShareFailureException(ScreenShareFailureCode.Signaling, "WHEP 会话地址无效")
                }
                resourceUrl = resource
            }
            response.body?.string()?.takeIf(String::isNotBlank)
                ?: error("WHEP 服务未返回 SDP answer")
        }
    }

    @Synchronized override fun close() {
        if (!closed.compareAndSet(false, true)) return
        metricsJob?.cancel()
        metricsJob = null
        logStage("close")
        resourceUrl?.let { url ->
            okHttpClient.newCall(
                Request.Builder()
                    .url(url)
                    .delete()
                    .header("Authorization", "Bearer ${request.bearerToken}")
                    .build(),
            ).enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) = Unit

                override fun onResponse(call: Call, response: Response) {
                    response.close()
                }
            })
        }
        resourceUrl = null
        var cleanupFailed = false
        videoTracks.forEach { track -> runCatching { track.removeSink(frameSink) }.onFailure { cleanupFailed = true } }
        videoTracks.clear()
        runCatching { peerConnection?.close() }.onFailure { cleanupFailed = true }
        runCatching { peerConnection?.dispose() }.onFailure { cleanupFailed = true }
        peerConnection = null
        runCatching { peerConnectionFactory?.dispose() }.onFailure { cleanupFailed = true }
        peerConnectionFactory = null
        runCatching { audioDeviceModule?.release() }.onFailure { cleanupFailed = true }
        audioDeviceModule = null
        if (cleanupFailed) runCatching { failures.report(request.callId, request.shareId, ScreenShareFailureCode.Cleanup) }
    }

    private fun attachRemoteTrack(track: MediaStreamTrack?) {
        if (closed.get()) return
        when (track) {
            is VideoTrack -> {
                logStage("remote_video_track")
                track.setEnabled(true)
                if (videoTracks.add(track)) track.addSink(frameSink)
            }
            is AudioTrack -> {
                logStage("remote_audio_track")
                track.setEnabled(true)
            }
        }
    }

    private fun logStage(stage: String, detail: String? = null) {
        val suffix = detail?.let { " $it" }.orEmpty()
        Log.d(TAG, "share=${request.shareId.takeLast(8)} stage=$stage$suffix")
    }

    private inner class Observer : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            logStage("ice_connection_state", "state=$state")
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED,
                -> connectionReady.complete(Unit)
                PeerConnection.IceConnectionState.FAILED -> fail("WHEP ICE 连接失败")
                PeerConnection.IceConnectionState.DISCONNECTED -> if (!closed.get()) listener.onBuffering()
                PeerConnection.IceConnectionState.CLOSED -> if (!closed.get()) fail("WHEP 连接已关闭")
                else -> Unit
            }
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            logStage("peer_connection_state", "state=$newState")
            when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED -> connectionReady.complete(Unit)
                PeerConnection.PeerConnectionState.FAILED -> fail("WHEP 媒体连接失败")
                PeerConnection.PeerConnectionState.DISCONNECTED -> if (!closed.get()) listener.onBuffering()
                PeerConnection.PeerConnectionState.CLOSED -> if (!closed.get()) fail("WHEP 媒体连接已关闭")
                else -> Unit
            }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            logStage("ice_gathering_state", "state=$state")
            if (state == PeerConnection.IceGatheringState.COMPLETE) iceGatheringComplete.complete(Unit)
        }

        override fun onIceCandidate(candidate: IceCandidate) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) {
            stream.videoTracks.forEach(::attachRemoteTrack)
            stream.audioTracks.forEach(::attachRemoteTrack)
        }
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(dataChannel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {
            attachRemoteTrack(receiver.track())
        }
        override fun onTrack(transceiver: RtpTransceiver) {
            attachRemoteTrack(transceiver.receiver.track())
        }

        private fun fail(message: String) {
            if (closed.get()) return
            connectionReady.completeExceptionally(IllegalStateException(message))
            listener.onFailed(message, ScreenShareFailureCode.IceConnection)
        }
    }

    private companion object {
        const val TAG = "WhepPlayback"
        val SDP_MEDIA_TYPE = "application/sdp".toMediaType()
        const val ICE_GATHERING_GRACE_MILLIS = 2_500L
        const val CONNECTION_TIMEOUT_MILLIS = 20_000L
        const val SDP_CANDIDATE_MARKER = "a=candidate:"
    }
}

private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, error: IOException) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }

        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }
    })
}

private suspend fun PeerConnection.createOfferAwait(): SessionDescription =
    suspendCancellableCoroutine { continuation ->
        createOffer(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) {
                if (continuation.isActive) continuation.resume(description)
            }
            override fun onSetSuccess() = Unit
            override fun onCreateFailure(message: String) {
                if (continuation.isActive) continuation.resumeWithException(
                    IllegalStateException(message.ifBlank { "无法创建 WHEP offer" }),
                )
            }
            override fun onSetFailure(message: String) = Unit
        }, MediaConstraints())
    }

private suspend fun PeerConnection.setLocalDescriptionAwait(description: SessionDescription) =
    setDescriptionAwait(description, remote = false)

private suspend fun PeerConnection.setRemoteDescriptionAwait(description: SessionDescription) =
    setDescriptionAwait(description, remote = true)

private suspend fun PeerConnection.setDescriptionAwait(
    description: SessionDescription,
    remote: Boolean,
): Unit = suspendCancellableCoroutine { continuation ->
    val observer = object : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onSetSuccess() {
            if (continuation.isActive) continuation.resume(Unit)
        }
        override fun onCreateFailure(message: String) = Unit
        override fun onSetFailure(message: String) {
            if (continuation.isActive) continuation.resumeWithException(
                IllegalStateException(message.ifBlank { "无法应用 WHEP SDP" }),
            )
        }
    }
    if (remote) setRemoteDescription(observer, description) else setLocalDescription(observer, description)
}

internal fun Headers.toIceServers(): List<PeerConnection.IceServer> = values("Link")
    .flatMap { header ->
        ICE_LINK_REGEX.findAll(header).mapNotNull { match ->
            val url = match.groupValues[1]
            val parameters = ICE_LINK_PARAMETER_REGEX.findAll(match.groupValues[2])
                .associate { parameter ->
                    parameter.groupValues[1].lowercase() to
                        (parameter.groupValues[2].ifBlank { parameter.groupValues[3] })
                }
            if (parameters["rel"] != "ice-server") return@mapNotNull null
            PeerConnection.IceServer.builder(url).apply {
                parameters["username"]?.let(::setUsername)
                parameters["credential"]?.let(::setPassword)
            }.createIceServer()
        }.toList()
    }

private val ICE_LINK_REGEX = Regex("<([^>]+)>((?:\\s*;[^,]*)*)")
private val ICE_LINK_PARAMETER_REGEX = Regex(
    ";\\s*([A-Za-z0-9_-]+)=(?:\"([^\"]*)\"|([^;\\s,]+))",
)
