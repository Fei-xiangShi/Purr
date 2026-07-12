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

        val duplicateErrorMappers = sourceGroups.getValue("feature").files.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                line.takeIf { it.contains("fun AppError.") }
                    ?.let { "${file.relativeTo(rootDir)}:${index + 1}: $line" }
            }
        }
        check(duplicateErrorMappers.isEmpty()) {
            "Feature modules must use core:presentation error mapping:\n${duplicateErrorMappers.joinToString("\n")}"
        }
    }
}

tasks.register("check") {
    group = "verification"
    dependsOn(verifyArchitecture)
}
