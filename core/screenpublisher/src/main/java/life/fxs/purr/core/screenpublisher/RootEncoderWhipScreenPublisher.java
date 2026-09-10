package life.fxs.purr.core.screenpublisher;

import android.content.Context;
import android.media.projection.MediaProjection;

import androidx.annotation.NonNull;

import com.pedro.common.ConnectChecker;
import com.pedro.common.VideoCodec;
import com.pedro.common.socket.base.SocketType;
import com.pedro.encoder.input.sources.audio.InternalAudioSource;
import com.pedro.encoder.input.sources.video.ScreenSource;
import com.pedro.library.whip.WhipStream;

import life.fxs.purr.core.media.screenshare.ScreenSharePublishRequest;

/**
 * Java isolation boundary for RootEncoder. The public API contains no RootEncoder types,
 * keeping the rest of the application independent from the encoder implementation.
 */
public final class RootEncoderWhipScreenPublisher {
    public interface Listener {
        void onLive(@NonNull ScreenSharePublishRequest request);
        void onStopped(@NonNull ScreenSharePublishRequest request);
        void onFailed(@NonNull ScreenSharePublishRequest request, @NonNull String message);
    }

    private static final int TARGET_SHORT_SIDE = 720;
    private static final int MAX_LONG_SIDE = 1600;
    private static final int VIDEO_BITRATE = 4_000_000;
    private static final int VIDEO_FPS = 30;
    private static final int STATIC_SCREEN_FPS = 15;
    private static final int I_FRAME_INTERVAL_SECONDS = 2;
    private static final int AUDIO_SAMPLE_RATE = 48_000;
    private static final int AUDIO_BITRATE = 128_000;
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MILLIS = 1_000L;

    private final Context context;
    private final MediaProjection mediaProjection;
    private final ScreenSharePublishRequest request;
    private final Listener listener;
    private final WhipStream stream;
    private volatile boolean stopping;

    public RootEncoderWhipScreenPublisher(
            @NonNull Context context,
            @NonNull MediaProjection mediaProjection,
            @NonNull ScreenSharePublishRequest request,
            @NonNull Listener listener
    ) {
        this.context = context.getApplicationContext();
        this.mediaProjection = mediaProjection;
        this.request = request;
        this.listener = listener;
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
        VideoSize size = adaptive720pSize(context);
        stream.setVideoCodec(VideoCodec.H264);
        stream.getGlInterface().setForceRender(true, STATIC_SCREEN_FPS);
        // RootEncoder's Ktor transport depends on a newer kotlinx-io runtime than the
        // application stack. Its Java transport implements the same WHIP UDP/TCP socket
        // contract without introducing that process-wide Kotlin runtime dependency.
        stream.getStreamClient().setSocketType(SocketType.JAVA);
        stream.getStreamClient().setAuthorization(request.getBearerToken());
        stream.getStreamClient().setReTries(MAX_RETRIES);
        boolean videoPrepared = stream.prepareVideo(
                size.width,
                size.height,
                VIDEO_BITRATE,
                VIDEO_FPS,
                I_FRAME_INTERVAL_SECONDS,
                0
        );
        boolean audioPrepared = stream.prepareAudio(
                AUDIO_SAMPLE_RATE,
                true,
                AUDIO_BITRATE,
                false,
                false
        );
        if (!videoPrepared || !audioPrepared) {
            throw new IllegalStateException("设备无法初始化屏幕或系统音频编码器");
        }
        stream.startStream(request.getWhipUrl());
    }

    public synchronized void stop() {
        stopInternal(false);
    }

    private synchronized void stopInternal(boolean projectionRevoked) {
        if (stopping) return;
        stopping = true;
        releaseResources();
        if (projectionRevoked) {
            listener.onFailed(request, "系统已撤销屏幕录制权限");
        } else {
            listener.onStopped(request);
        }
    }

    private final class PublisherConnectChecker implements ConnectChecker {
        @Override public void onConnectionStarted(@NonNull String url) { }

        @Override public void onConnectionSuccess() {
            if (!stopping) listener.onLive(request);
        }

        @Override public void onNewBitrate(long bitrate) { }

        @Override public void onConnectionFailed(@NonNull String reason) {
            if (stopping) return;
            if (stream.getStreamClient().reTry(RETRY_DELAY_MILLIS, reason, null)) return;
            failInternal(reason.isBlank() ? "WHIP 推流连接失败" : reason);
        }

        @Override public void onDisconnect() {
            failInternal("WHIP 推流连接已断开");
        }

        @Override public void onAuthError() {
            failInternal("投屏凭证无效或已过期");
        }

        @Override public void onAuthSuccess() { }
    }

    private synchronized void failInternal(String message) {
        if (stopping) return;
        stopping = true;
        releaseResources();
        listener.onFailed(request, message);
    }

    private void releaseResources() {
        try {
            if (stream.isStreaming()) stream.stopStream();
        } catch (RuntimeException | LinkageError ignored) {
        }
        try {
            stream.release();
        } catch (RuntimeException | LinkageError ignored) {
        }
        try {
            mediaProjection.stop();
        } catch (RuntimeException | LinkageError ignored) {
        }
    }

    private static VideoSize adaptive720pSize(Context context) {
        int rawWidth = Math.max(2, context.getResources().getDisplayMetrics().widthPixels);
        int rawHeight = Math.max(2, context.getResources().getDisplayMetrics().heightPixels);
        int shortSide = Math.min(rawWidth, rawHeight);
        int longSide = Math.max(rawWidth, rawHeight);
        double scale = Math.min(1.0, Math.min(
                (double) TARGET_SHORT_SIDE / shortSide,
                (double) MAX_LONG_SIDE / longSide
        ));
        int width = makeEven((int) (rawWidth * scale));
        int height = makeEven((int) (rawHeight * scale));
        return new VideoSize(width, height);
    }

    private static int makeEven(int value) {
        return (Math.max(2, value) / 2) * 2;
    }

    private record VideoSize(int width, int height) { }
}
