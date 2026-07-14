package life.fxs.purr.overlay

import android.app.Application
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import com.google.common.truth.Truth.assertThat
import life.fxs.purr.domain.call.model.CallOverlayStyle
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CallOverlayViewFactoryTest {
    private val context = RuntimeEnvironment.getApplication()
    private val factory = CallOverlayViewFactory(context)

    @Test
    fun `compact overlay uses a 72 dp window`() {
        val presenter = CallOverlayPresenter(context, factory)

        val params = presenter.layoutParams(CallOverlayStyle.CompactSquare)
        val expectedSize = (72 * context.resources.displayMetrics.density).toInt()

        assertThat(params.width).isEqualTo(expectedSize)
        assertThat(params.height).isEqualTo(expectedSize)
    }

    @Test
    fun `speaker overlay is touch through while compact overlay accepts gestures`() {
        val presenter = CallOverlayPresenter(context, factory)

        val speakerParams = presenter.layoutParams(CallOverlayStyle.SpeakerNames)
        val speakerFlags = speakerParams.flags
        val compactFlags = presenter.layoutParams(CallOverlayStyle.CompactSquare).flags

        assertThat(speakerFlags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE).isNotEqualTo(0)
        assertThat(compactFlags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE).isEqualTo(0)
        assertThat(speakerParams.y).isEqualTo(0)
        assertThat(speakerParams.alpha).isLessThan(0.8f)
    }

    @Test
    fun `compact overlay tap clicks without moving`() {
        val view = View(context).apply { isClickable = true }
        var clickCount = 0
        var position = OverlayPosition(20, 30)
        view.setOnClickListener { clickCount++ }
        val listener = CompactOverlayTouchListener(
            currentPosition = { position },
            bounds = { OverlayDragBounds(0, 0, 100, 100) },
            touchSlop = 8,
            onMove = { position = it },
        )

        listener.dispatch(view, MotionEvent.ACTION_DOWN, 40f, 40f)
        listener.dispatch(view, MotionEvent.ACTION_UP, 43f, 43f)

        assertThat(clickCount).isEqualTo(1)
        assertThat(position).isEqualTo(OverlayPosition(20, 30))
    }

    @Test
    fun `compact overlay drag updates a constrained position without clicking`() {
        val view = View(context).apply { isClickable = true }
        var clickCount = 0
        var position = OverlayPosition(90, 50)
        view.setOnClickListener { clickCount++ }
        val listener = CompactOverlayTouchListener(
            currentPosition = { position },
            bounds = { OverlayDragBounds(0, 10, 100, 100) },
            touchSlop = 8,
            onMove = { position = it },
        )

        listener.dispatch(view, MotionEvent.ACTION_DOWN, 50f, 50f)
        listener.dispatch(view, MotionEvent.ACTION_MOVE, 90f, 20f)
        listener.dispatch(view, MotionEvent.ACTION_UP, 90f, 20f)

        assertThat(clickCount).isEqualTo(0)
        assertThat(position).isEqualTo(OverlayPosition(100, 20))
    }

    @Test
    fun `speaker overlay places a circular avatar before each name`() {
        val binding = factory.create(CallOverlayStyle.SpeakerNames)

        val localRow = binding.localAvatar?.parent as LinearLayout
        val remoteRow = binding.remoteAvatar?.parent as LinearLayout

        assertThat(localRow.indexOfChild(binding.localAvatar)).isLessThan(localRow.indexOfChild(binding.localName))
        assertThat(remoteRow.indexOfChild(binding.remoteAvatar)).isLessThan(remoteRow.indexOfChild(binding.remoteName))
        assertThat(binding.localAvatar?.clipToOutline).isTrue()
        assertThat(binding.remoteAvatar?.clipToOutline).isTrue()
    }

    @Test
    fun `speaker emphasis follows each participant audio level independently`() {
        val binding = factory.create(CallOverlayStyle.SpeakerNames)

        binding.render(
            CallOverlayRenderModel(
                pairId = "pair-1",
                style = CallOverlayStyle.SpeakerNames,
                localName = "本地",
                localAvatarUrl = null,
                remoteName = "对方",
                remoteAvatarUrl = null,
                durationSeconds = 5,
                localAudioLevel = 0.1f,
                remoteAudioLevel = 0f,
            ),
        )

        assertThat(binding.localName?.text.toString()).isEqualTo("本地")
        assertThat(binding.remoteName?.text.toString()).isEqualTo("对方")
        assertThat(binding.localName?.alpha).isEqualTo(1f)
        assertThat(binding.remoteName?.alpha).isLessThan(1f)
    }

    private fun CompactOverlayTouchListener.dispatch(
        view: View,
        action: Int,
        x: Float,
        y: Float,
    ) {
        MotionEvent.obtain(0L, 0L, action, x, y, 0).also { event ->
            onTouch(view, event)
            event.recycle()
        }
    }
}
