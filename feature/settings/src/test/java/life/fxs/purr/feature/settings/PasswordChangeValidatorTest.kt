package life.fxs.purr.feature.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PasswordChangeValidatorTest {
    @Test
    fun `accepts a valid password change`() {
        assertThat(
            PasswordChangeValidator.validate(
                currentPassword = "old-password",
                newPassword = "new-password",
                confirmPassword = "new-password",
            ),
        ).isNull()
    }

    @Test
    fun `rejects short reused mismatched and oversized passwords`() {
        assertThat(PasswordChangeValidator.validate("old-password", "short", "short"))
            .isEqualTo("新密码至少需要 8 个字符")
        assertThat(PasswordChangeValidator.validate("old-password", "old-password", "old-password"))
            .isEqualTo("新密码不能与当前密码相同")
        assertThat(PasswordChangeValidator.validate("old-password", "new-password", "different"))
            .isEqualTo("两次输入的新密码不一致")
        val oversized = "\u5bc6".repeat(25)
        assertThat(PasswordChangeValidator.validate("old-password", oversized, oversized))
            .isEqualTo("新密码不能超过 72 个 UTF-8 字节")
    }
}
