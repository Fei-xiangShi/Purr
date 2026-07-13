import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.FileInputStream
import java.util.Properties

val appConfigProperties = Properties().apply {
    val appConfigFile = project.file("config.properties")
    if (appConfigFile.exists()) {
        appConfigFile.inputStream().use(::load)
    }
}

val signingProperties = Properties().apply {
    val signingFile = project.file("signing.properties")
    if (signingFile.exists()) {
        load(FileInputStream(signingFile))
    }
}
val releaseStoreFilePath = signingProperties.getProperty("storeFile", "").trim()

fun normalizedBaseUrl(value: String): String = value.trim().let {
    if (it.isNotEmpty() && !it.endsWith("/")) "$it/" else it
}

val purrProductionBaseUrl = normalizedBaseUrl(
    providers.gradleProperty("PURR_PRODUCTION_BASE_URL").get(),
)
val purrBaseUrl = normalizedBaseUrl(
    providers.gradleProperty("purrBaseUrl")
        .orElse(appConfigProperties.getProperty("purr.baseUrl") ?: purrProductionBaseUrl)
        .get(),
)
val purrVersionCode = providers.gradleProperty("PURR_VERSION_CODE").get().toInt()
val purrVersionName = providers.gradleProperty("PURR_VERSION_NAME").get()

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

    signingConfigs {
        create("release") {
            if (releaseStoreFilePath.isNotEmpty()) {
                storeFile = file(releaseStoreFilePath)
                storePassword = signingProperties.getProperty("storePassword", "")
                keyAlias = signingProperties.getProperty("keyAlias", "")
                keyPassword = signingProperties.getProperty("keyPassword", "")
            }
        }
    }

    defaultConfig {
        applicationId = "life.fxs.purr"
        minSdk = 29
        targetSdk = 35
        versionCode = purrVersionCode
        versionName = purrVersionName
        buildConfigField("String", "PURR_BASE_URL", "\"$purrBaseUrl\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        debug {
            manifestPlaceholders["usesCleartextTraffic"] = "true"
        }
        release {
            manifestPlaceholders["usesCleartextTraffic"] = "false"
            buildConfigField("String", "PURR_BASE_URL", "\"$purrProductionBaseUrl\"")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                rootProject.file("proguard-rules.pro"),
            )
            signingConfig = signingConfigs.getByName("release")
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

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

androidComponents {
    beforeVariants(selector().all()) { variantBuilder ->
        variantBuilder.enableAndroidTest = project.file("src/androidTest").isDirectory
    }
}

val validateReleaseConfiguration by tasks.registering {
    group = "verification"
    description = "Validates HTTPS API and signing inputs before packaging a release."
    doLast {
        require(purrProductionBaseUrl.startsWith("https://")) {
            "Release builds require PURR_PRODUCTION_BASE_URL to use https://"
        }
        require(releaseStoreFilePath.isNotEmpty()) {
            "Release builds require app/signing.properties with a storeFile"
        }
        require(file(releaseStoreFilePath).isFile) {
            "Release signing store does not exist: $releaseStoreFilePath"
        }
        listOf("storePassword", "keyAlias", "keyPassword").forEach { key ->
            require(!signingProperties.getProperty(key, "").isNullOrBlank()) {
                "Release signing property is missing: $key"
            }
        }
    }
}

tasks.matching { it.name == "packageRelease" || it.name == "bundleRelease" }.configureEach {
    dependsOn(validateReleaseConfiguration)
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
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
