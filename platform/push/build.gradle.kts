import org.jetbrains.kotlin.gradle.dsl.JvmTarget

fun buildConfigString(value: String): String = "\"" + value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"") + "\""

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kapt)
}

android {
    namespace = "life.fxs.purr.platform.push"
    compileSdk = 35

    defaultConfig {
        minSdk = 29
        buildConfigField(
            "String",
            "FIREBASE_APPLICATION_ID",
            buildConfigString(providers.gradleProperty("PURR_FIREBASE_APPLICATION_ID").orElse("").get()),
        )
        buildConfigField(
            "String",
            "FIREBASE_API_KEY",
            buildConfigString(providers.gradleProperty("PURR_FIREBASE_API_KEY").orElse("").get()),
        )
        buildConfigField(
            "String",
            "FIREBASE_PROJECT_ID",
            buildConfigString(providers.gradleProperty("PURR_FIREBASE_PROJECT_ID").orElse("").get()),
        )
        buildConfigField(
            "String",
            "FIREBASE_SENDER_ID",
            buildConfigString(providers.gradleProperty("PURR_FIREBASE_SENDER_ID").orElse("").get()),
        )
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
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

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.domain.account)
    implementation(projects.domain.incomingcall)

    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.hilt.android)
    implementation(libs.kotlinx.coroutines.android)

    kapt(libs.androidx.hilt.compiler)
    kapt(libs.hilt.compiler)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(projects.core.model)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
}
