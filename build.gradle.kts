plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.kapt) apply false
}

val verifyArchitecture by tasks.registering {
    group = "verification"
    description = "Verifies client dependency direction and presentation error mapping boundaries."

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
