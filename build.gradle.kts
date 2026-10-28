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

// Synthetic manual-test profile (docs/synthetic-sandbox.md): fictional agent histories, stub agent
// commands and the IDE's own config/system/log/plugins directories, all under one root. It never
// reads real agent data. Override the root with -PsyntheticSandboxDir or SESHLOG_SANDBOX_DIR.
val syntheticSandboxRoot: Provider<File> = providers.gradleProperty("syntheticSandboxDir")
    .orElse(providers.environmentVariable("SESHLOG_SANDBOX_DIR"))
    .orElse(providers.systemProperty("user.home").map { home ->
        val os = System.getProperty("os.name").lowercase()
        when {
            os.contains("mac") -> "$home/Library/Caches/Seshlog/synthetic-sandbox"
            os.contains("win") -> "${System.getenv("LOCALAPPDATA") ?: "$home/AppData/Local"}/Seshlog/synthetic-sandbox"
            else -> "${System.getenv("XDG_CACHE_HOME") ?: "$home/.cache"}/seshlog/synthetic-sandbox"
        }
    })
    .map { File(it).absoluteFile }

val syntheticSandboxArguments: Provider<List<String>> = syntheticSandboxRoot.map { root ->
    listOf("--root", root.path, "--ide-config", File(root, "ide/config").path,
        "--ide-log", File(root, "ide/log").path, "--ide-plugins", File(root, "ide/plugins").path)
}
// The platform's jars, including the Kotlin stdlib, join the test source set as the test task does.
val syntheticSandboxClasspath = files(sourceSets.test.map { it.runtimeClasspath },
    configurations.named("intellijPlatformTestRuntimeClasspath"), configurations.named("intellijPlatformTestClasspath"))

val prepareSyntheticSandbox = tasks.register<JavaExec>("prepareSyntheticSandbox") {
    group = "seshlog"
    description = "Creates the synthetic sandbox profile if absent; -PsyntheticSandboxReset=true regenerates it."
    classpath(syntheticSandboxClasspath)
    mainClass = "com.hedworth.seshlog.sandbox.SyntheticSandboxCli"
    jvmArgs("--enable-native-access=ALL-UNNAMED") // sqlite-jdbc writes the opencode database.
    val arguments = syntheticSandboxArguments
    val reset = providers.gradleProperty("syntheticSandboxReset").map(String::toBoolean).orElse(false)
    argumentProviders.add(CommandLineArgumentProvider { listOf(if (reset.get()) "reset" else "prepare") + arguments.get() })
}

tasks.register<JavaExec>("verifySyntheticSandbox") {
    group = "seshlog"
    description = "Checks that the running synthetic sandbox IDE loaded this build and reads only synthetic data."
    classpath(syntheticSandboxClasspath)
    mainClass = "com.hedworth.seshlog.sandbox.SyntheticSandboxCli"
    jvmArgs("--enable-native-access=ALL-UNNAMED") // sqlite-jdbc writes the opencode database.
    val arguments = syntheticSandboxArguments
    val version = providers.gradleProperty("pluginVersion")
    val jar = layout.buildDirectory.file(version.map { "libs/seshlog-$it.jar" })
    val repository = layout.projectDirectory.asFile
    argumentProviders.add(CommandLineArgumentProvider {
        listOf("verify") + arguments.get() +
            listOf("--plugin-jar", jar.get().asFile.path, "--plugin-version", version.get(), "--repository", repository.path)
    })
}

intellijPlatformTesting {
    runIde {
        register("runSyntheticIde") {
            sandboxDirectory = layout.dir(syntheticSandboxRoot.map { File(it, "ide") })
            prepareSandboxTask {
                sandboxSuffix = ""
                // The generator refuses a non-empty root it did not create, so it runs first.
                dependsOn(prepareSyntheticSandbox)
            }
            task {
                group = "seshlog"
                description = "Runs the IDE on the synthetic sandbox profile; see docs/synthetic-sandbox.md."
                val root = syntheticSandboxRoot.get()
                environment("SESHLOG_SANDBOX", root.path)
                // Agent CLIs typed into a sandbox terminal stay inside the sandbox too.
                environment("CLAUDE_CONFIG_DIR", File(root, "agents/claude").path)
                environment("CODEX_HOME", File(root, "agents/codex").path)
                environment("PI_CODING_AGENT_DIR", File(root, "agents/pi").path)
                environment("PATH", File(root, "bin").path + File.pathSeparator + providers.environmentVariable("PATH").getOrElse(""))
                args(File(root, "workspace/harbor").path)
            }
        }
    }
}
