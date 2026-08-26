import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

fun usesUnifiedIntelliJIdea(version: String): Boolean {
    fun numericPart(value: String): Int? = value.takeWhile(Char::isDigit).toIntOrNull()

    val parts = version.split('.')
    val first = parts.firstOrNull()?.let(::numericPart) ?: return false
    return if (first >= 2000) {
        first > 2025 || (first == 2025 && (parts.getOrNull(1)?.let(::numericPart) ?: 0) >= 3)
    } else {
        first >= 253
    }
}

// Seshlog — IntelliJ plugin: a tool window listing local AI coding-agent sessions (Claude Code first)
// with one click to resume any of them in a new terminal tab.
//
// Uses the IntelliJ Platform Gradle Plugin 2.x (not the legacy org.jetbrains.intellij 1.x plugin).

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.17.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        val platformVersion = providers.gradleProperty("platformVersion")
        val platformType = providers.gradleProperty("platformType").zip(platformVersion) { type, version ->
            val requested = IntelliJPlatformType.fromCode(type)
            if (requested == IntelliJPlatformType.IntellijIdeaCommunity && usesUnifiedIntelliJIdea(version)) {
                IntelliJPlatformType.IntellijIdea
            } else {
                requested
            }
        }
        create(platformType, platformVersion)

        // Needed to open shell tabs in the Terminal tool window and run `claude --resume`.
        bundledPlugin("org.jetbrains.plugins.terminal")

        pluginVerifier()
        zipSigner()
        testFramework(TestFrameworkType.Platform)
    }

    // BasePlatformTestCase is JUnit3/4-based; the platform test framework doesn't bring JUnit itself.
    testImplementation("junit:junit:4.13.2")
}

tasks.processResources {
    from("LICENSE") {
        into("META-INF")
    }
}

intellijPlatform {
    // No GUI forms or @NotNull instrumentation needed; skipping avoids resolving the Java compiler artefact.
    instrumentCode = false

    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

kotlin {
    jvmToolchain(providers.gradleProperty("javaVersion").get().toInt())

    compilerOptions {
        // Platform 2024.1 bundles the Kotlin 1.9 stdlib; pinning apiVersion makes 2.x-only stdlib
        // APIs fail at compile time instead of NoSuchMethodError at runtime on older IDEs.
        apiVersion = org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_1_9
        // Emit JVM default methods instead of DefaultImpls stubs; otherwise every Kotlin class
        // implementing a platform interface (ToolWindowFactory) "overrides" its internal defaults
        // and the plugin verifier flags INTERNAL_API_USAGES.
        jvmDefault = org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY
    }
}
