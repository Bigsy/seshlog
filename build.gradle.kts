import org.jetbrains.intellij.platform.gradle.TestFrameworkType

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
        val platformType = providers.gradleProperty("platformType").map { type ->
            org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.fromCode(type)
        }
        // -PplatformLocalPath=/Applications/IntelliJ\ IDEA.app runs tests against an installed IDE.
        val localPath = providers.gradleProperty("platformLocalPath").orNull
        if (localPath != null) local(localPath) else create(platformType, platformVersion)

        // Needed to open shell tabs in the Terminal tool window and run `claude --resume`.
        bundledPlugin("org.jetbrains.plugins.terminal")
        bundledModule("intellij.terminal.frontend")
        // Platform Markdown parser; independent of the optional Markdown editor plugin.
        bundledModule("intellij.libraries.markdown")

        pluginVerifier()
        zipSigner()
        testFramework(TestFrameworkType.Platform)
    }

    // opencode keeps its sessions in a SQLite database; the driver bundles native libraries for
    // every platform, which is most of the plugin zip's size.
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")

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
            val localPath = providers.gradleProperty("platformLocalPath").orNull
            if (localPath != null) local(localPath) else recommended()
        }
    }
}

kotlin {
    jvmToolchain(providers.gradleProperty("javaVersion").get().toInt())

    compilerOptions {
        // The 2026.2 platform bundles Kotlin 2.4; compile against the newest API supported by
        // this build's Kotlin plugin while retaining the platform's stdlib dependency.
        apiVersion = org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_3
        // Emit JVM default methods instead of DefaultImpls stubs; otherwise every Kotlin class
        // implementing a platform interface (ToolWindowFactory) "overrides" its internal defaults
        // and the plugin verifier flags INTERNAL_API_USAGES.
        jvmDefault = org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY
    }
}
