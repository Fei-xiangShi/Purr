pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "purr"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(
    ":app",
    ":core:common",
    ":core:model",
    ":core:network",
    ":core:presentation",
    ":core:media",
    ":core:designsystem",
    ":domain:account",
    ":domain:call",
    ":domain:incomingcall",
    ":data:account",
    ":data:call",
    ":feature:auth",
    ":feature:home",
    ":feature:incomingcall",
    ":feature:call",
    ":feature:settings",
    ":platform:incomingcall",
    ":platform:push",
    ":platform:telecom",
)
