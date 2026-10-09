package life.fxs.purr.core.screenpublisher;

import android.content.Context;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;

import com.pedro.common.ConnectChecker;
import com.pedro.common.AudioCodec;
import com.pedro.common.VideoCodec;
import com.pedro.common.socket.base.SocketType;
import com.pedro.encoder.input.sources.audio.InternalAudioSource;
import com.pedro.encoder.input.sources.video.ScreenSource;
import com.pedro.encoder.utils.CodecUtil;
import com.pedro.library.whip.WhipStream;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import life.fxs.purr.core.media.screenshare.ScreenShareBitrateController;
import life.fxs.purr.core.media.screenshare.ScreenShareBitrateDecision;
import life.fxs.purr.core.media.screenshare.ScreenSharePublishRequest;
import life.fxs.purr.core.media.screenshare.ScreenShareVideoSize;
import life.fxs.purr.core.media.screenshare.ScreenShareFailureCode;
import life.fxs.purr.core.media.screenshare.ScreenShareFailureException;

/**
 * Java isolation boundary for RootEncoder. The public API contains no RootEncoder types,
 * keeping the rest of the application independent from the encoder implementation.
 */
public final class RootEncoderWhipScreenPublisher {
    public interface Listener {
        void onLive(@NonNull ScreenSharePublishRequest request);
        /** WHIP dropped; capture and encoders stay alive while attempt {@code attempt} (1-based) is made. */
        void onReconnecting(@NonNull ScreenSharePublishRequest request, int attempt);
        void onStopped(@NonNull ScreenSharePublishRequest request);
        void onFailed(@NonNull ScreenSharePublishRequest request, @NonNull String message, @NonNull ScreenShareFailureCode code);
        void onCleanupFailed(@NonNull ScreenSharePublishRequest request);
        void onQuality(@NonNull ScreenSharePublishRequest request, int width, int height,
                double fps, long bitrate, int targetBitrate, int queued, int capacity,
                long videoDrops, long audioDrops);
    }

    /** Fresh publish credentials for the same share, requested before a retry. */
    public static final class Credentials {
        public final String bearerToken;
        public final long expiresAtEpochMillis;

        public Credentials(@NonNull String bearerToken, long expiresAtEpochMillis) {
            this.bearerToken = bearerToken;
            this.expiresAtEpochMillis = expiresAtEpochMillis;
        }
    }

    /** The server definitively refused new credentials; recovery becomes terminal. */
    public static final class CredentialsRejectedException extends Exception {
        public CredentialsRejectedException(@NonNull String message) {
            super(message);
        }
    }

    public interface CredentialSupplier {
        /** Blocking; called on the recovery thread, never on the main thread. */
        @NonNull Credentials refresh(@NonNull ScreenSharePublishRequest request) throws Exception;
    }

    private static final String TAG = "ScreenShareTransport";
    private static final int I_FRAME_INTERVAL_SECONDS = 1;
    private static final int STATIC_SCREEN_FPS = 5;
    private static final long TRANSPORT_SAMPLE_MILLIS = 200L;
    private static final int AUDIO_SAMPLE_RATE = 48_000;
    private static final int AUDIO_BITRATE = 128_000;
    /** Delay before each bounded recovery attempt (about 45 s in total). */
    static final long[] RECOVERY_DELAYS_MILLIS = {1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 15_000L};
    private static final long TOKEN_REFRESH_MARGIN_MILLIS = 60_000L;
    private static final long ATTEMPT_TIMEOUT_MILLIS = 25_000L;

    private final Context context;
    private final MediaProjection mediaProjection;
    private final ScreenSharePublishRequest request;
    private final Listener listener;
    private final CredentialSupplier credentialSupplier;
    private final WhipStream stream;
    private ScheduledExecutorService recoveryExecutor;
    private long recoveryGeneration;
    private int recoveryAttempt;
    private boolean recoveryScheduled;
    private boolean attemptInFlight;
    private boolean forceRefresh;
    private boolean refreshedSinceSuccess;
    private String bearerToken;
    private long tokenExpiresAtEpochMillis;
    private ScreenShareBitrateController bitrateController;
    private ScheduledExecutorService transportMonitor;
    private int appliedBitrate;
    private long transportGeneration;
    private volatile double encodedFps = Double.NaN;
    private volatile long sentBitrate = -1L;
    private long lastQualityAt;
    private ScreenShareVideoSize videoSize;
    private volatile boolean stopping;

    public RootEncoderWhipScreenPublisher(
            @NonNull Context context,
            @NonNull MediaProjection mediaProjection,
            @NonNull ScreenSharePublishRequest request,
            @NonNull Listener listener,
            @NonNull CredentialSupplier credentialSupplier
    ) {
        this.context = context.getApplicationContext();
        this.mediaProjection = mediaProjection;
        this.request = request;
        this.listener = listener;
        this.credentialSupplier = credentialSupplier;
        this.bearerToken = request.getBearerToken();
        this.tokenExpiresAtEpochMillis = request.getExpiresAtEpochMillis();
        MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
            @Override
            public void onStop() {
                stopInternal(true);
            }
        };
        this.stream = new WhipStream(
                this.context,
                new PublisherConnectChecker(),
                new ScreenSource(this.context, mediaProjection, projectionCallback),
                new InternalAudioSource(mediaProjection, projectionCallback)
        );
    }

    public void start() {
        ScreenShareVideoSize size = request.getQuality().sizeFor(
                context.getResources().getDisplayMetrics().widthPixels,
                context.getResources().getDisplayMetrics().heightPixels
        );
        requireSupportedVideo(size);
        videoSize = size;
        stream.setFpsListener(fps -> encodedFps = fps);
        stream.setVideoCodec(VideoCodec.H265);
        stream.forceCodecType(CodecUtil.CodecType.HARDWARE, CodecUtil.CodecType.FIRST_COMPATIBLE_FOUND);
        stream.setAudioCodec(AudioCodec.OPUS);
        // This is an idle-screen keepalive, not the capture FPS limiter. Real screen
        // updates still run at the selected 30/60 fps without re-encoding 60 duplicates.
        stream.getGlInterface().setForceRender(true, STATIC_SCREEN_FPS);
        // RootEncoder's Ktor transport depends on a newer kotlinx-io runtime than the
        // application stack. Its Java transport implements the same WHIP UDP/TCP socket
        // contract without introducing that process-wide Kotlin runtime dependency.
        stream.getStreamClient().setSocketType(SocketType.JAVA);
        stream.getStreamClient().setAuthorization(bearerToken);
        stream.getStreamClient().setReTries(1);
        stream.getStreamClient().setLogs(false);
        stream.getStreamClient().setDelay(0L);
        // RootEncoder defaults to 400 queued audio/video frames. Keep roughly 250 ms
        // of frames (including ~50 Opus packets/s), rather than seconds of stale video.
        stream.getStreamClient().resizeCache((request.getQuality().getFramesPerSecond() + 50 + 3) / 4);
        boolean videoPrepared = stream.prepareVideo(
                size.getWidth(),
                size.getHeight(),
                request.getQuality().getBitrate(),
                request.getQuality().getFramesPerSecond(),
                I_FRAME_INTERVAL_SECONDS,
                0,
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain,
                -1
        );
        boolean audioPrepared = stream.prepareAudio(
                AUDIO_SAMPLE_RATE,
                true,
                AUDIO_BITRATE,
                false,
                false
        );
        if (!videoPrepared || !audioPrepared) {
            throw new IllegalStateException("设备无法以所选画质初始化 H.265 硬件编码器或系统音频，请降低画质后重试");
        }
        stream.startStream(request.getWhipUrl());
    }

    private synchronized void startTransportMonitor() {
        if (stopping) return;
        stopTransportMonitor();
        encodedFps = Double.NaN;
        sentBitrate = -1L;
        bitrateController = new ScreenShareBitrateController(request.getQuality().getBitrate());
        // Starts at half of the ceiling (controller default); it ramps up while the link is healthy.
        appliedBitrate = bitrateController.getBitrate();
        stream.setVideoBitrateOnFly(appliedBitrate);
        transportMonitor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "screen-share-transport");
            thread.setDaemon(true);
            return thread;
        });
        long generation = transportGeneration;
        transportMonitor.scheduleWithFixedDelay(
                () -> sampleTransport(generation),
                TRANSPORT_SAMPLE_MILLIS, TRANSPORT_SAMPLE_MILLIS, TimeUnit.MILLISECONDS
        );
    }

    private synchronized void sampleTransport(long generation) {
        if (stopping || generation != transportGeneration || !stream.isStreaming() || bitrateController == null) return;
        try {
            ScreenShareBitrateDecision decision = bitrateController.sample(
                    SystemClock.elapsedRealtime(),
                    stream.getStreamClient().getItemsInCache(),
                    stream.getStreamClient().getCacheSize(),
                    stream.getStreamClient().getDroppedVideoFrames(),
                    stream.getStreamClient().getDroppedAudioFrames()
            );
            if (decision.getBitrate() != appliedBitrate) {
                stream.setVideoBitrateOnFly(decision.getBitrate());
                appliedBitrate = decision.getBitrate();
                Log.d(TAG, "encoder_target_bps=" + appliedBitrate);
            }
            if (decision.getRequestKeyframe()) stream.requestKeyframe();
            long now = SystemClock.elapsedRealtime();
            if (now - lastQualityAt >= 1_000L && videoSize != null) {
                lastQualityAt = now;
                listener.onQuality(request, videoSize.getWidth(), videoSize.getHeight(), encodedFps,
                        sentBitrate, appliedBitrate, stream.getStreamClient().getItemsInCache(),
                        stream.getStreamClient().getCacheSize(), stream.getStreamClient().getDroppedVideoFrames(),
                        stream.getStreamClient().getDroppedAudioFrames());
            }
        } catch (RuntimeException error) {
            // Sampling must not kill the publisher, and no credentials/SDP enter logs.
            Log.w(TAG, "Transport sampling unavailable: " + error.getClass().getSimpleName());
        }
    }

    private void stopTransportMonitor() {
        transportGeneration++;
        if (transportMonitor != null) transportMonitor.shutdownNow();
        transportMonitor = null;
        bitrateController = null;
    }

    private void requireSupportedVideo(ScreenShareVideoSize size) {
        // Use RootEncoder's exact ordering: it selects the first hardware codec with
        // surface input. Finding a different capable encoder would give a false pass.
        for (MediaCodecInfo codec : CodecUtil.getAllHardwareEncoders(MediaFormat.MIMETYPE_VIDEO_HEVC, true)) {
            MediaCodecInfo.CodecCapabilities capabilities = CodecUtil.getCapabilities(codec, MediaFormat.MIMETYPE_VIDEO_HEVC);
            if (capabilities == null || capabilities.colorFormats == null) continue;
            boolean surfaceInput = false;
            for (int color : capabilities.colorFormats) {
                if (color == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) surfaceInput = true;
            }
            if (!surfaceInput) continue;
            boolean mainProfile = false;
            for (MediaCodecInfo.CodecProfileLevel profile : capabilities.profileLevels) {
                if (profile.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain) mainProfile = true;
            }
            MediaCodecInfo.VideoCapabilities video = capabilities.getVideoCapabilities();
            if (codec.isHardwareAccelerated() && !codec.isSoftwareOnly() && mainProfile && video != null &&
                    video.areSizeAndRateSupported(size.getWidth(), size.getHeight(), request.getQuality().getFramesPerSecond()) &&
                    video.getBitrateRange().contains(request.getQuality().getBitrate())) {
                Log.d(TAG, "hardware_encoder=" + codec.getName() + " codec=H265");
                return;
            }
            break;
        }
        throw new ScreenShareFailureException(ScreenShareFailureCode.UnsupportedCodec, "设备不支持 " + request.getQuality().getLabel()
                + " H.265 硬件编码，请降低画质或使用支持 HEVC 硬件编码的设备");
    }

    public synchronized void stop() {
        stopInternal(false);
    }

    private synchronized void stopInternal(boolean projectionRevoked) {
        if (stopping) return;
        stopping = true;
        releaseResources();
        if (projectionRevoked) {
            listener.onFailed(request, "系统已撤销屏幕录制权限", ScreenShareFailureCode.PermissionEnded);
        } else {
            listener.onStopped(request);
        }
    }

    private final class PublisherConnectChecker implements ConnectChecker {
        @Override public void onConnectionStarted(@NonNull String url) { }

        @Override public void onConnectionSuccess() {
            synchronized (RootEncoderWhipScreenPublisher.this) {
                if (stopping) return;
                resetRecovery();
                startTransportMonitor();
                listener.onLive(request);
            }
        }

        @Override public void onNewBitrate(long bitrate) { if (!stopping) sentBitrate = bitrate; }

        @Override public void onConnectionFailed(@NonNull String reason) {
            synchronized (RootEncoderWhipScreenPublisher.this) {
                handleConnectionLost(reason);
            }
        }

        @Override public void onDisconnect() {
            // RootEncoder only emits this after a full client teardown (including our own
            // stop). During a bounded recovery a duplicate signal is ignored; otherwise the
            // recovery path decides whether the client can still be re-armed.
            synchronized (RootEncoderWhipScreenPublisher.this) {
                handleConnectionLost("WHIP disconnected");
            }
        }

        @Override public void onAuthError() {
            synchronized (RootEncoderWhipScreenPublisher.this) {
                if (stopping) return;
                if (refreshedSinceSuccess) {
                    failInternal("投屏凭证无效或已过期", ScreenShareFailureCode.CredentialsExpired);
                    return;
                }
                forceRefresh = true;
                handleConnectionLost("WHIP authorization failed");
            }
        }

        @Override public void onAuthSuccess() { }
    }

    private void resetRecovery() {
        recoveryGeneration++;
        recoveryAttempt = 0;
        recoveryScheduled = false;
        attemptInFlight = false;
        forceRefresh = false;
        refreshedSinceSuccess = false;
    }

    private void handleConnectionLost(String reason) {
        if (stopping) return;
        stopTransportMonitor();
        if (attemptInFlight) {
            // The in-flight attempt failed; move on to the next one.
            attemptInFlight = false;
            recoveryScheduled = false;
        }
        scheduleRecovery(reason);
    }

    /** Single recovery owner: at most one pending delay or in-flight attempt exists. */
    private void scheduleRecovery(String reason) {
        if (stopping || recoveryScheduled) return;
        if (recoveryAttempt >= RECOVERY_DELAYS_MILLIS.length) {
            failInternal("WHIP 推流重试后仍无法连接，请检查网络和直播服务", ScreenShareFailureCode.PublishRetriesExhausted);
            return;
        }
        int attempt = ++recoveryAttempt;
        recoveryScheduled = true;
        long generation = ++recoveryGeneration;
        if (recoveryExecutor == null) {
            recoveryExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "screen-share-recovery");
                thread.setDaemon(true);
                return thread;
            });
        }
        listener.onReconnecting(request, attempt);
        try {
            recoveryExecutor.schedule(() -> runRecoveryAttempt(generation, reason),
                    RECOVERY_DELAYS_MILLIS[attempt - 1], TimeUnit.MILLISECONDS);
        } catch (RuntimeException rejected) {
            failInternal("WHIP 推流重试后仍无法连接，请检查网络和直播服务", ScreenShareFailureCode.PublishRetriesExhausted);
        }
    }

    private void runRecoveryAttempt(long generation, String reason) {
        boolean refresh;
        synchronized (this) {
            if (stopping || generation != recoveryGeneration) return;
            refresh = forceRefresh
                    || tokenExpiresAtEpochMillis - System.currentTimeMillis() < TOKEN_REFRESH_MARGIN_MILLIS;
        }
        Credentials credentials = null;
        if (refresh) {
            try {
                credentials = credentialSupplier.refresh(request);
            } catch (CredentialsRejectedException rejected) {
                synchronized (this) {
                    if (!stopping && generation == recoveryGeneration) {
                        failInternal("投屏凭证无效或已过期", ScreenShareFailureCode.CredentialsExpired);
                    }
                }
                return;
            } catch (Exception transientFailure) {
                Log.w(TAG, "Publish credential refresh failed: " + transientFailure.getClass().getSimpleName());
                synchronized (this) {
                    if (stopping || generation != recoveryGeneration) return;
                    recoveryScheduled = false;
                    scheduleRecovery(reason);
                }
                return;
            }
        }
        synchronized (this) {
            if (stopping || generation != recoveryGeneration) return;
            try {
                if (credentials != null) {
                    bearerToken = credentials.bearerToken;
                    tokenExpiresAtEpochMillis = credentials.expiresAtEpochMillis;
                    stream.getStreamClient().setAuthorization(bearerToken);
                    forceRefresh = false;
                    refreshedSinceSuccess = true;
                }
                // Re-arm the single RootEncoder retry. reTry keeps the screen source,
                // encoders and MediaProjection running; it only repeats the WHIP handshake.
                stream.getStreamClient().setReTries(1);
                attemptInFlight = true;
                if (!stream.getStreamClient().reTry(0L, reason, null)) {
                    failInternal("WHIP 推流连接已断开", ScreenShareFailureCode.PublishRetriesExhausted);
                    return;
                }
                recoveryExecutor.schedule(() -> attemptTimedOut(generation),
                        ATTEMPT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (RuntimeException | LinkageError error) {
                failInternal("WHIP 推流连接已断开", ScreenShareFailureCode.PublishRetriesExhausted);
            }
        }
    }

    private synchronized void attemptTimedOut(long generation) {
        if (stopping || generation != recoveryGeneration || !attemptInFlight) return;
        handleConnectionLost("WHIP reconnect timed out");
    }

    private synchronized void failInternal(String message, ScreenShareFailureCode code) {
        if (stopping) return;
        stopping = true;
        releaseResources();
        listener.onFailed(request, message, code);
    }

    private void releaseResources() {
        stopTransportMonitor();
        recoveryGeneration++;
        if (recoveryExecutor != null) recoveryExecutor.shutdownNow();
        recoveryExecutor = null;
        boolean cleanupFailed = false;
        try {
            if (stream.isStreaming()) stream.stopStream();
        } catch (RuntimeException | LinkageError ignored) {
            cleanupFailed = true;
        }
        try {
            stream.release();
        } catch (RuntimeException | LinkageError ignored) {
            cleanupFailed = true;
        }
        try {
            mediaProjection.stop();
        } catch (RuntimeException | LinkageError ignored) {
            cleanupFailed = true;
        }
        if (cleanupFailed) listener.onCleanupFailed(request);
    }

}
