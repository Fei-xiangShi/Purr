import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

val appConfigProperties = Properties().apply {
    val appConfigFile = project.file("config.properties")
    if (appConfigFile.exists()) {
        appConfigFile.inputStream().use(::load)
    }
}

fun normalizedBaseUrl(value: String): String = value.trim().let {
    if (it.isNotEmpty() && !it.endsWith("/")) "$it/" else it
}

val purrBaseUrl = normalizedBaseUrl(
    providers.gradleProperty("purrBaseUrl")
        .orElse(appConfigProperties.getProperty("purr.baseUrl") ?: "")
        .get(),
)

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kapt)
}

android {
    namespace = "life.fxs.purr"
    compileSdk = 35

    defaultConfig {
        applicationId = "life.fxs.purr"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "PURR_BASE_URL", "\"$purrBaseUrl\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.model)
    implementation(projects.core.network)
    implementation(projects.core.media)
    implementation(projects.core.designsystem)
    implementation(projects.data.account)
    implementation(projects.data.call)
    implementation(projects.domain.account)
    implementation(projects.domain.call)
    implementation(projects.feature.auth)
    implementation(projects.feature.home)
    implementation(projects.feature.call)
    implementation(projects.feature.settings)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.hilt.android)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.retrofit.core)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp.core)
    implementation(libs.okhttp.logging)

    kapt(libs.hilt.compiler)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
