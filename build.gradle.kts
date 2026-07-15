plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.kapt) apply false
    alias(libs.plugins.google.services) apply false
}

val verifyCallAudioOwnership by tasks.registering {
    group = "verification"
    description = "Verifies fallback audio internals and production Telecom ownership remain separated."

    inputs.files(fileTree("core/media/src/main") { include("**/*.kt") })
    inputs.file("data/call/src/main/java/life/fxs/purr/data/call/livekit/LiveKitRoomFactory.kt")
    inputs.file("app/src/main/java/life/fxs/purr/di/MediaModule.kt")

    doLast {
        val audioSources = fileTree("core/media/src/main") { include("**/*.kt") }.files
        val modeWriters = audioSources.filter { it.readText().contains("audioManager.mode =") }
        check(modeWriters.map { it.name } == listOf("CallAudioModeController.kt")) {
            "Only CallAudioModeController may write AudioManager.mode: ${modeWriters.joinToString()}"
        }

        val focusWriters = audioSources.filter {
            val source = it.readText()
            source.contains("requestAudioFocus(") || source.contains("abandonAudioFocusRequest(")
        }
        check(focusWriters.map { it.name } == listOf("CallAudioFocusManager.kt")) {
            "Only CallAudioFocusManager may own Android audio focus: ${focusWriters.joinToString()}"
        }

        val routeWriters = audioSources.filter {
            val source = it.readText()
            source.contains("setCommunicationDevice(") ||
                source.contains("clearCommunicationDevice(") ||
                source.contains("startBluetoothSco(") ||
                source.contains("stopBluetoothSco(")
        }
        check(routeWriters.map { it.name } == listOf("AudioRouteController.kt")) {
            "Only AudioRouteController may own Android communication routing: ${routeWriters.joinToString()}"
        }

        val liveKitRoomFactory = file(
            "data/call/src/main/java/life/fxs/purr/data/call/livekit/LiveKitRoomFactory.kt",
        ).readText()
        check(
            liveKitRoomFactory.contains("NoAudioHandler") &&
                liveKitRoomFactory.contains("disableCommunicationModeWorkaround = true"),
        ) {
            "LiveKit must not compete with the application-owned call audio session controller"
        }

        val mediaModule = file("app/src/main/java/life/fxs/purr/di/MediaModule.kt").readText()
        check(
            mediaModule.contains("TelecomManagedCallAudioSessionController") &&
                !mediaModule.contains("AndroidAudioRouteController") &&
                !mediaModule.contains("CoordinatedCallAudioSessionController") &&
                !mediaModule.contains("AudioManager"),
        ) {
            "Production call audio must be Telecom-managed; direct AudioManager adapters are fallback-only"
        }
    }
}

val verifyTelecomOwnership by tasks.registering {
    group = "verification"
    description = "Verifies that Core-Telecom is isolated behind core media ports."

    val telecomSources = fileTree("platform/telecom/src/main") { include("**/*.kt") }
    val telecomManifest = file("platform/telecom/src/main/AndroidManifest.xml")
    val appManifest = file("app/src/main/AndroidManifest.xml")
    inputs.files(telecomSources, telecomManifest)
    inputs.file("app/src/main/java/life/fxs/purr/service/CallForegroundService.kt")
    inputs.file(appManifest)

    doLast {
        val boundaryLeaks = telecomSources.files.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                line.takeIf {
                    it.startsWith("import life.fxs.purr.data.") ||
                        it.startsWith("import life.fxs.purr.domain.") ||
                        it.startsWith("import life.fxs.purr.feature.") ||
                        it.startsWith("import life.fxs.purr.core.network.")
                }?.let { "${file.relativeTo(rootDir)}:${index + 1}: $it" }
            }
        }
        check(boundaryLeaks.isEmpty()) {
            "platform:telecom must depend only on core media/model ports:\n${boundaryLeaks.joinToString("\n")}"
        }

        val telecomApiOwners = fileTree(rootDir) {
            include("**/src/main/**/*.kt")
            exclude("platform/telecom/**")
            exclude("**/build/**")
        }.files.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                line.takeIf { it.startsWith("import androidx.core.telecom.") }
                    ?.let { "${file.relativeTo(rootDir)}:${index + 1}: $it" }
            }
        }
        check(telecomApiOwners.isEmpty()) {
            "Core-Telecom APIs may only be owned by platform:telecom:\n${telecomApiOwners.joinToString("\n")}"
        }

        val service = file(
            "app/src/main/java/life/fxs/purr/service/CallForegroundService.kt",
        ).readText()
        check(service.contains("NotificationCompat.CallStyle.forOngoingCall")) {
            "Connected Telecom calls must publish a CallStyle notification within five seconds"
        }
        val telecomManifestSource = telecomManifest.readText()
        check(
            telecomManifestSource.contains("android.permission.MANAGE_OWN_CALLS") &&
                telecomManifestSource.contains("android.permission.FOREGROUND_SERVICE_PHONE_CALL"),
        ) {
            "platform:telecom must declare own-call and phone-call foreground-service permissions"
        }
        val appManifestSource = appManifest.readText()
        check(
            appManifestSource.contains("android.permission.FOREGROUND_SERVICE_PHONE_CALL") &&
                appManifestSource.contains("android.permission.MANAGE_OWN_CALLS") &&
                appManifestSource.contains("android:foregroundServiceType=\"microphone|phoneCall\""),
        ) {
            "The app call service must declare phone-call permissions and microphone|phoneCall types"
        }
    }
}

val verifyIncomingCallOwnership by tasks.registering {
    group = "verification"
    description = "Verifies that incoming-call Android entry points remain isolated in platform:incomingcall."

    val featureSources = fileTree("feature/incomingcall/src/main") { include("**/*.kt") }
    val platformSources = fileTree("platform/incomingcall/src/main") { include("**/*.kt") }
    val platformManifest = file("platform/incomingcall/src/main/AndroidManifest.xml")
    inputs.files(featureSources, platformSources, platformManifest)

    doLast {
        val featurePlatformLeaks = featureSources.files.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                line.takeIf {
                    it.startsWith("import android.app.Notification") ||
                        it.startsWith("import android.app.PendingIntent") ||
                        it.contains("class IncomingCallActivity") ||
                        it.contains("class AndroidIncomingCallReminder")
                }?.let { "${file.relativeTo(rootDir)}:${index + 1}: $it" }
            }
        }
        check(featurePlatformLeaks.isEmpty()) {
            "Incoming-call feature code must not own Android notification or Activity entry points:\n" +
                featurePlatformLeaks.joinToString("\n")
        }

        val fullScreenIntentOwners = platformSources.files.filter {
            it.readText().contains("setFullScreenIntent(")
        }
        check(fullScreenIntentOwners.map { it.name } == listOf("AndroidIncomingCallReminder.kt")) {
            "AndroidIncomingCallReminder must be the only full-screen intent owner: " +
                fullScreenIntentOwners.joinToString()
        }

        val reminderSource = file(
            "platform/incomingcall/src/main/java/life/fxs/purr/platform/incomingcall/" +
                "AndroidIncomingCallReminder.kt",
        ).readText()
        check(
            reminderSource.contains("NotificationCompat.CallStyle.forIncomingCall") &&
                reminderSource.contains("fullScreenIntentCapability.canUse()"),
        ) {
            "Incoming-call notifications must use CallStyle and capability-gated full-screen intents"
        }

        val manifestSource = platformManifest.readText()
        check(
            manifestSource.contains("android.permission.USE_FULL_SCREEN_INTENT") &&
                manifestSource.contains("android:showWhenLocked=\"true\"") &&
                manifestSource.contains("android:turnScreenOn=\"true\"") &&
                manifestSource.contains("android:exported=\"false\""),
        ) {
            "platform:incomingcall must declare a private lock-screen Activity and full-screen permission"
        }
    }
}

val verifyPushOwnership by tasks.registering {
    group = "verification"
    description = "Verifies that Firebase and incoming-call wake work remain isolated in platform:push."

    val pushSources = fileTree("platform/push/src/main") { include("**/*.kt") }
    val pushManifest = file("platform/push/src/main/AndroidManifest.xml")
    inputs.files(pushSources, pushManifest)

    doLast {
        val forbiddenImports = pushSources.files.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                line.takeIf {
                    it.startsWith("import life.fxs.purr.data.") ||
                        it.startsWith("import life.fxs.purr.feature.") ||
                        it.startsWith("import life.fxs.purr.core.network.")
                }?.let { "${file.relativeTo(rootDir)}:${index + 1}: $it" }
            }
        }
        check(forbiddenImports.isEmpty()) {
            "platform:push must depend on domain ports, not data, feature, or network implementations:\n" +
                forbiddenImports.joinToString("\n")
        }

        val firebaseOwners = fileTree(rootDir) {
            include("**/src/main/**/*.kt")
            exclude("platform/push/**")
            exclude("**/build/**")
        }.files.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                line.takeIf { it.startsWith("import com.google.firebase.") }
                    ?.let { "${file.relativeTo(rootDir)}:${index + 1}: $it" }
            }
        }
        check(firebaseOwners.isEmpty()) {
            "Firebase APIs may only be owned by platform:push:\n${firebaseOwners.joinToString("\n")}"
        }

        val scheduler = file(
            "platform/push/src/main/java/life/fxs/purr/platform/push/IncomingCallWakeScheduler.kt",
        ).readText()
        check(
            scheduler.contains("setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)") &&
                scheduler.contains("ExistingWorkPolicy.KEEP") &&
                scheduler.contains("uniqueWorkName(signal.callId)"),
        ) {
            "Incoming-call push must enqueue expedited KEEP-only unique work keyed by callId"
        }

        val manifest = pushManifest.readText()
        check(
            manifest.contains("firebase_messaging_installation_id_enabled") &&
                manifest.contains("com.google.firebase.MESSAGING_EVENT") &&
                manifest.contains("android:exported=\"false\""),
        ) {
            "platform:push must enable the FCM installation-ID API and expose only a private messaging service"
        }
    }
}

val verifyArchitecture by tasks.registering {
    group = "verification"
    description = "Verifies client dependency direction and presentation error mapping boundaries."
    dependsOn(
        verifyCallAudioOwnership,
        verifyIncomingCallOwnership,
        verifyPushOwnership,
        verifyTelecomOwnership,
    )

    val sourceGroups = mapOf(
        "core" to fileTree("core") { include("*/src/main/**/*.kt") },
        "domain" to fileTree("domain") { include("*/src/main/**/*.kt") },
        "data" to fileTree("data") { include("*/src/main/**/*.kt") },
        "feature" to fileTree("feature") { include("*/src/main/**/*.kt") },
    )
    inputs.files(sourceGroups.values)
    inputs.file("core/media/build.gradle.kts")

    doLast {
        val forbiddenImports = mapOf(
            "core" to listOf(
                "life.fxs.purr.data",
                "life.fxs.purr.domain",
                "life.fxs.purr.feature",
            ),
            "domain" to listOf(
                "android.",
                "androidx.",
                "life.fxs.purr.core.network",
                "life.fxs.purr.data",
                "life.fxs.purr.feature",
                "okhttp3.",
                "retrofit2.",
            ),
            "data" to listOf(
                "life.fxs.purr.feature",
            ),
            "feature" to listOf(
                "io.livekit.",
                "life.fxs.purr.core.network",
                "life.fxs.purr.data",
                "okhttp3.",
                "retrofit2.",
            ),
        )
        val violations = sourceGroups.flatMap { (group, sources) ->
            sources.files.flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    val imported = line.removePrefix("import ").takeIf { line.startsWith("import ") }
                    imported
                        ?.takeIf { candidate -> forbiddenImports.getValue(group).any(candidate::startsWith) }
                        ?.let { "${file.relativeTo(rootDir)}:${index + 1}: $line" }
                }
            }
        }
        check(violations.isEmpty()) {
            "Client architecture boundary violations:\n${violations.joinToString("\n")}"
        }

        val commonExternalDependencies = fileTree("core/common") {
            include("src/main/**/*.kt")
        }.files.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                line.takeIf {
                    it.startsWith("import retrofit2.") ||
                        it.startsWith("import okhttp3.") ||
                        it.startsWith("import io.ktor.") ||
                        it.startsWith("import io.livekit.") ||
                        it.startsWith("import android.") ||
                        it.startsWith("import androidx.")
                }?.let { "${file.relativeTo(rootDir)}:${index + 1}: $it" }
            }
        }
        check(commonExternalDependencies.isEmpty()) {
            "core:common must remain transport- and platform-independent:\n" +
                commonExternalDependencies.joinToString("\n")
        }

        val coreMediaVendorLeaks = fileTree("core/media") {
            include("src/main/**/*.kt")
        }.files.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                line.takeIf { it.startsWith("import io.livekit.") }
                    ?.let { "${file.relativeTo(rootDir)}:${index + 1}: $it" }
            }
        }
        check(coreMediaVendorLeaks.isEmpty() && !file("core/media/build.gradle.kts").readText().contains("livekit")) {
            "core:media must expose vendor-neutral Android media contracts; LiveKit belongs in data:call:\n" +
                coreMediaVendorLeaks.joinToString("\n")
        }

        val callRepositoryPlatformLeaks = file(
            "data/call/src/main/java/life/fxs/purr/data/call/repository/CallRepositoryImpl.kt",
        ).readLines().mapIndexedNotNull { index, line ->
            line.takeIf {
                it.startsWith("import android.") ||
                    it.startsWith("import io.livekit.") ||
                    it.startsWith("import life.fxs.purr.core.media.")
            }?.let { "data/call/CallRepositoryImpl.kt:${index + 1}: $it" }
        }
        check(callRepositoryPlatformLeaks.isEmpty()) {
            "CallRepositoryImpl must orchestrate data-layer collaborators, not Android or media vendor adapters:\n" +
                callRepositoryPlatformLeaks.joinToString("\n")
        }

        val mediaAdapterDomainLeaks = fileTree("data/call/src/main") {
            include("**/livekit/**/*.kt")
            include("**/runtime/**/*.kt")
        }.files.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                line.takeIf {
                    it.startsWith("import life.fxs.purr.domain.call") ||
                        it.contains("CallSession") ||
                        it.contains("updateSession(")
                }?.let { "${file.relativeTo(rootDir)}:${index + 1}: $it" }
            }
        }
        check(mediaAdapterDomainLeaks.isEmpty()) {
            "Media ports and provider adapters must exchange MediaCallCommand/MediaCallEvent, not domain sessions:\n" +
                mediaAdapterDomainLeaks.joinToString("\n")
        }

        val domainCallCredentialLeaks = fileTree("domain/call") {
            include("src/main/**/*.kt")
        }.files.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                line.takeIf {
                    it.contains("wsUrl: String") ||
                        it.contains("token: String")
                }?.let { "${file.relativeTo(rootDir)}:${index + 1}: $it" }
            }
        }
        check(domainCallCredentialLeaks.isEmpty()) {
            "domain:call must not expose media transport credentials:\n" +
                domainCallCredentialLeaks.joinToString("\n")
        }

        val duplicateErrorMappers = sourceGroups.getValue("feature").files.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                line.takeIf { it.contains("fun AppError.") }
                    ?.let { "${file.relativeTo(rootDir)}:${index + 1}: $line" }
            }
        }
        check(duplicateErrorMappers.isEmpty()) {
            "Feature modules must use core:presentation error mapping:\n${duplicateErrorMappers.joinToString("\n")}"
        }

        val featureDependencyViolations = fileTree("feature") {
            include("*/build.gradle.kts")
        }.files.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                line.takeIf {
                    it.contains("projects.core.media") ||
                        it.contains("projects.core.network") ||
                        it.contains("projects.data.")
                }?.let { "${file.relativeTo(rootDir)}:${index + 1}: $it" }
            }
        }
        check(featureDependencyViolations.isEmpty()) {
            "Feature modules must depend on domain contracts, not media/network/data implementations:\n" +
                featureDependencyViolations.joinToString("\n")
        }

        val oversizedFeatureFiles = sourceGroups.getValue("feature").files.mapNotNull { file ->
            val lineCount = file.readLines().size
            lineCount.takeIf { it > MAX_FEATURE_SOURCE_LINES }
                ?.let { "${file.relativeTo(rootDir)}: $it lines" }
        }
        check(oversizedFeatureFiles.isEmpty()) {
            "Feature source files exceed the SRP review threshold ($MAX_FEATURE_SOURCE_LINES lines):\n" +
                oversizedFeatureFiles.joinToString("\n")
        }
    }
}

val MAX_FEATURE_SOURCE_LINES = 450

tasks.register("check") {
    group = "verification"
    dependsOn(verifyArchitecture)
}
