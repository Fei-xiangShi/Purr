package life.fxs.purr.core.presentation

import com.google.common.truth.Truth.assertThat
import life.fxs.purr.core.common.AppError
import org.junit.Test

class AppErrorMessageTest {
    @Test
    fun `maps stable and fallback messages`() {
        assertThat(AppError.Network().toUserMessage()).isEqualTo("网络错误")
        assertThat(AppError.Network("offline").toUserMessage()).isEqualTo("offline")
        assertThat(AppError.Unauthorized("expired").toUserMessage()).isEqualTo("expired")
        assertThat(AppError.Validation("invalid").toUserMessage()).isEqualTo("invalid")
        assertThat(AppError.Unexpected().toUserMessage()).isEqualTo("发生未知错误")
        assertThat(AppError.Unexpected(IllegalStateException("broken")).toUserMessage()).isEqualTo("broken")
    }
}
