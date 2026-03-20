package life.fxs.purr.config

import life.fxs.purr.BuildConfig

object AppConfig {
    val baseUrl: String = BuildConfig.PURR_BASE_URL.trim()

    fun requireBaseUrl(): String {
        check(baseUrl.isNotBlank()) {
            "Missing purr base URL. Set app/config.properties (purr.baseUrl) or pass -PpurrBaseUrl=..."
        }
        return baseUrl
    }
}
