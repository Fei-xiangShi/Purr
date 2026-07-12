package life.fxs.purr.feature.settings

import java.nio.charset.StandardCharsets

internal object PasswordChangeValidator {
    const val MIN_PASSWORD_LENGTH = 8
    const val MAX_PASSWORD_BYTES = 72

    fun validate(currentPassword: String, newPassword: String, confirmPassword: String): String? {
        return when {
            currentPassword.isEmpty() -> "请输入当前密码"
            newPassword.length < MIN_PASSWORD_LENGTH -> "新密码至少需要 8 个字符"
            newPassword.toByteArray(StandardCharsets.UTF_8).size > MAX_PASSWORD_BYTES ->
                "新密码不能超过 72 个 UTF-8 字节"
            newPassword == currentPassword -> "新密码不能与当前密码相同"
            confirmPassword != newPassword -> "两次输入的新密码不一致"
            else -> null
        }
    }
}
