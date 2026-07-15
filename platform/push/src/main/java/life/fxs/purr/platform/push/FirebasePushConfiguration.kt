package life.fxs.purr.platform.push

internal data class FirebasePushConfiguration(
    val applicationId: String,
    val apiKey: String,
    val projectId: String,
    val senderId: String,
) {
    val isComplete: Boolean
        get() = listOf(applicationId, apiKey, projectId, senderId).all(String::isNotBlank)

    companion object {
        fun fromBuildConfig() = FirebasePushConfiguration(
            applicationId = BuildConfig.FIREBASE_APPLICATION_ID.trim(),
            apiKey = BuildConfig.FIREBASE_API_KEY.trim(),
            projectId = BuildConfig.FIREBASE_PROJECT_ID.trim(),
            senderId = BuildConfig.FIREBASE_SENDER_ID.trim(),
        )
    }
}
