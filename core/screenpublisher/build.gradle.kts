import org.gradle.api.tasks.compile.JavaCompile

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "life.fxs.purr.core.screenpublisher"
    compileSdk = 35

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    beforeVariants(selector().all()) { variantBuilder ->
        variantBuilder.enableAndroidTest = project.file("src/androidTest").isDirectory
    }
}

dependencies {
    api(projects.core.media)
    implementation(libs.androidx.annotation)
    implementation(libs.root.encoder) {
        // Kotlin/coroutines are version-aligned at the application level. Keep
        // RootEncoder's declarations from introducing a second version source.
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "org.jetbrains.kotlinx")
    }
}
