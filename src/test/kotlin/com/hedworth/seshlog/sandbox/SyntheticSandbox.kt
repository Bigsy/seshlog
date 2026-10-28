package com.hedworth.seshlog.sandbox

import com.google.gson.GsonBuilder
import com.hedworth.seshlog.model.AgentKind
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Paths of one synthetic sandbox. The IDE directories are the Gradle `runSyntheticIde` sandbox,
 * normally `<root>/ide/{config,log,plugins}`.
 */
internal class SandboxLayout(root: Path, ideConfig: Path, ideLog: Path, idePlugins: Path) {
    val root: Path = root.toAbsolutePath().normalize()
    val ideConfig: Path = ideConfig.toAbsolutePath().normalize()
    val ideLog: Path = ideLog.toAbsolutePath().normalize()
    val idePlugins: Path = idePlugins.toAbsolutePath().normalize()
    val marker: Path = this.root.resolve(".seshlog-synthetic-sandbox")
    val manifest: Path = this.root.resolve("manifest.json")
    val bin: Path = this.root.resolve("bin")
    val workspace: Path = this.root.resolve("workspace")
    val claude: Path = this.root.resolve("agents/claude")
    val codex: Path = this.root.resolve("agents/codex")
    val opencode: Path = this.root.resolve("agents/opencode")
    val piAgent: Path = this.root.resolve("agents/pi")
    val piSessions: Path = piAgent.resolve("sessions")
    val settingsFile: Path = this.ideConfig.resolve("options/seshlog.xml")
    val projects = SandboxProjects(workspace, codex)

    fun executable(kind: AgentKind): Path = bin.resolve(command(kind))

    /** What each provider reports as `storagePath()`, hashed in `provider_scanned` diagnostics. */
    fun storage(kind: AgentKind): Path = when (kind) {
        AgentKind.CLAUDE_CODE -> claude.resolve("projects")
        AgentKind.CODEX -> codex.resolve("sessions")
        AgentKind.OPENCODE -> opencode.resolve("opencode.db")
        AgentKind.PI -> piSessions
    }

    /** Seshlog settings that keep every provider inside the sandbox, by `SeshlogSettings.State` name. */
    fun settings(): Map<String, String> = linkedMapOf(
        "claudeDataDir" to claude, "claudeExecutable" to executable(AgentKind.CLAUDE_CODE),
        "codexDataDir" to codex, "codexExecutable" to executable(AgentKind.CODEX),
        "opencodeDataDir" to opencode, "opencodeExecutable" to executable(AgentKind.OPENCODE),
        "piSessionsDir" to piSessions, "piExecutable" to executable(AgentKind.PI),
    ).mapValues { it.value.toString() }

    companion object {
        fun command(kind: AgentKind) = when (kind) {
            AgentKind.CLAUDE_CODE -> "claude"
            AgentKind.CODEX -> "codex"
            AgentKind.OPENCODE -> "opencode"
            AgentKind.PI -> "pi"
        }
    }
}

internal data class ManifestSession(
    val handle: String, val kind: AgentKind, val id: String, val title: String?, val cwd: String, val purpose: String, val listed: Boolean,
)

internal data class SandboxManifest(
    val version: Int,
    val created: String,
    val root: String,
    val node: String?,
    val projects: Map<String, String>,
    /** Sessions each provider scan returns straight after generation. */
    val expected: Map<AgentKind, Int>,
    val storage: Map<AgentKind, String>,
    val sessions: List<ManifestSession>,
)

/**
 * Builds a disposable, entirely fictional profile for manual checks: Git checkouts, histories for
 * all four agents, stub agent executables and Seshlog settings that point only inside the root.
 * It never reads or writes real agent data, and an existing sandbox is reused, never modified,
 * apart from refreshing the generated stubs and restoring missing Seshlog settings.
 */
internal object SyntheticSandbox {
    const val VERSION = 1
    const val DEFAULT_FILLER = 220
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    fun prepare(
        layout: SandboxLayout,
        filler: Int = DEFAULT_FILLER,
        now: Instant = Instant.now(),
        node: Path? = findNode(),
        env: Map<String, String> = System.getenv(),
        home: Path = Path.of(System.getProperty("user.home")),
    ): String {
        unsafeRootReason(layout.root, env, home)?.let { throw IllegalStateException(it) }
        if (Files.exists(layout.marker)) {
            val manifest = readManifest(layout)
            installStubs(layout, node)
            val seeded = seedSettings(layout)
            return buildString {
                appendLine("Using the existing synthetic sandbox at ${layout.root} (created ${manifest?.created ?: "unknown"}).")
                if (manifest?.version != VERSION) {
                    appendLine("It was made by generator version ${manifest?.version ?: "unknown"}; this checkout makes $VERSION. Run make sandbox-reset to regenerate it.")
                }
                if (seeded) appendLine("Restored missing Seshlog settings in ${layout.settingsFile}.")
                appendLine("Run make sandbox-reset to start again from a fresh profile.")
            }
        }
        Files.createDirectories(layout.root)
        Files.writeString(layout.marker, gson.toJson(mapOf("version" to VERSION, "created" to now.toString())))
        GitWorkspace(layout, now).create()
        val specs = SyntheticScenarios.sessions(layout.projects, filler)
        val ids = SyntheticHistories(layout, now).write(specs)
        Files.createDirectories(layout.claude.resolve("sessions"))
        installStubs(layout, node)
        seedSettings(layout)
        seedTrust(layout)
        val manifest = SandboxManifest(VERSION, now.toString(), layout.root.toString(), node?.toString(),
            layout.projects.named().mapValues { it.value.toString() },
            AgentKind.entries.associateWith { kind -> specs.count { it.kind == kind && it.listed } },
            AgentKind.entries.associateWith { layout.storage(it).toString() },
            specs.map { ManifestSession(it.handle, it.kind, ids.getValue(it.handle), it.customTitle ?: it.title, it.cwd.toString(), it.purpose, it.listed) })
        Files.writeString(layout.manifest, gson.toJson(manifest))
        Files.writeString(layout.root.resolve("README.txt"), """
            Seshlog synthetic sandbox. Everything here is fictional and safe to delete.
            Created by `make sandbox` from the Seshlog repository; see docs/synthetic-sandbox.md there.
            Regenerate with `make sandbox-reset` and check the running IDE with `make sandbox-verify`.

            bin/        stub claude, codex, opencode and pi commands (no model or network)
            agents/     synthetic agent data that the sandbox IDE reads
            workspace/  Git checkouts and plain directories the sessions refer to
            ide/        the sandbox IDE's own config, system, log and plugin directories
            """.trimIndent() + "\n")
        return buildString {
            appendLine("Created a synthetic sandbox at ${layout.root}.")
            manifest.expected.forEach { (kind, count) -> appendLine("  ${kind.displayName}: $count sessions") }
            if (node == null) appendLine("Node.js was not found on PATH: the stub agents will use '/usr/bin/env node'.")
        }
    }

    /** Deletes a sandbox made by [prepare], then makes a fresh one. Refuses anything else. */
    fun reset(layout: SandboxLayout, running: Boolean, filler: Int = DEFAULT_FILLER, now: Instant = Instant.now(),
              node: Path? = findNode(), env: Map<String, String> = System.getenv(),
              home: Path = Path.of(System.getProperty("user.home"))): String {
        unsafeRootReason(layout.root, env, home)?.let { throw IllegalStateException(it) }
        check(!running) { "The sandbox IDE is still running. Close it, then run make sandbox-reset again." }
        if (Files.exists(layout.root, LinkOption.NOFOLLOW_LINKS)) {
            check(Files.isRegularFile(layout.marker)) { "${layout.root} has no ${layout.marker.fileName}; not deleting it." }
            Files.walk(layout.root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
        return prepare(layout, filler, now, node, env, home)
    }

    /**
     * The root must be dedicated: not a home or agent data directory, and not inside a Git
     * checkout, where plain-directory scenarios would silently resolve to that checkout.
     */
    fun unsafeRootReason(root: Path, env: Map<String, String>, home: Path): String? {
        val target = root.toAbsolutePath().normalize()
        val userHome = home.toAbsolutePath().normalize()
        if (target.nameCount < 2) return "$target is too close to the filesystem root for a sandbox."
        if (userHome.startsWith(target)) return "$target contains the home directory."
        val agentRoots = listOfNotNull(userHome.resolve(".claude"), userHome.resolve(".codex"), userHome.resolve(".pi"),
            userHome.resolve(".local/share/opencode"), env["CLAUDE_CONFIG_DIR"], env["CODEX_HOME"], env["PI_CODING_AGENT_DIR"],
            env["PI_CODING_AGENT_SESSION_DIR"], env["XDG_DATA_HOME"]?.let { "$it/opencode" })
            .mapNotNull { runCatching { Path.of(it.toString()).toAbsolutePath().normalize() }.getOrNull() }
        agentRoots.firstOrNull { target.startsWith(it) || it.startsWith(target) }?.let {
            return "$target overlaps the agent data directory $it."
        }
        generateSequence(target.parent) { it.parent }.firstOrNull { Files.exists(it.resolve(".git")) }?.let {
            return "$target is inside the Git checkout $it; choose a directory outside any repository."
        }
        // The Gradle sandbox task may create ide/ first; anything else means the directory is in use.
        if (Files.isDirectory(target) && !Files.exists(target.resolve(".seshlog-synthetic-sandbox")) &&
            Files.list(target).use { entries -> entries.anyMatch { it.fileName.toString() != "ide" } }) {
            return "$target already exists and is not a Seshlog synthetic sandbox."
        }
        return null
    }

    fun readManifest(layout: SandboxLayout): SandboxManifest? =
        runCatching { gson.fromJson(Files.readString(layout.manifest), SandboxManifest::class.java) }.getOrNull()

    fun findNode(path: String? = System.getenv("PATH")): Path? = path.orEmpty().split(java.io.File.pathSeparator)
        .filter { it.isNotBlank() }
        .map { Path.of(it, "node") }
        .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }

    /** Regenerated every time, so a sandbox picks up stub fixes and a moved Node.js. */
    private fun installStubs(layout: SandboxLayout, node: Path?) {
        val lib = layout.bin.resolve("lib")
        Files.createDirectories(lib)
        javaClass.getResourceAsStream("/sandbox/stub-agent.cjs")!!.use { Files.write(lib.resolve("stub-agent.cjs"), it.readAllBytes()) }
        Files.writeString(lib.resolve("sandbox.json"), gson.toJson(mapOf("root" to layout.root.toString(),
            "claude" to layout.claude.toString(), "codex" to layout.codex.toString(),
            "opencode" to layout.opencode.toString(), "pi" to layout.piSessions.toString())))
        // Seshlog recognises a Node agent by its script name, so the launcher is named after the CLI.
        val interpreter = node?.toString()?.takeIf { it.none(Char::isWhitespace) } ?: "/usr/bin/env node"
        for (kind in AgentKind.entries) {
            val launcher = layout.executable(kind)
            Files.writeString(launcher, "#!$interpreter\n// Seshlog synthetic sandbox stub, regenerated by make sandbox.\n" +
                "require('./lib/stub-agent.cjs').main('${SandboxLayout.command(kind)}', require('./lib/sandbox.json'));\n")
            runCatching { Files.setPosixFilePermissions(launcher, PosixFilePermissions.fromString("rwxr-xr-x")) }
        }
    }

    /** Only when absent: the profile's settings are then the tester's, like any IDE profile. */
    private fun seedSettings(layout: SandboxLayout): Boolean {
        if (Files.exists(layout.settingsFile)) return false
        Files.createDirectories(layout.settingsFile.parent)
        val options = layout.settings().entries.joinToString("\n") { (name, value) ->
            "    <option name=\"$name\" value=\"${xml(value)}\" />"
        }
        Files.writeString(layout.settingsFile, "<application>\n  <component name=\"SeshlogSettings\">\n$options\n  </component>\n</application>\n")
        return true
    }

    /**
     * The IDE opens the main checkout on start, so it is trusted; every other checkout keeps the
     * trust prompt, which the worktree checks need.
     */
    private fun seedTrust(layout: SandboxLayout) {
        val file = layout.ideConfig.resolve("options/trusted-paths.xml")
        if (Files.exists(file)) return
        Files.createDirectories(file.parent)
        Files.writeString(file, "<application>\n  <component name=\"Trusted.Paths\">\n    <option name=\"TRUSTED_PROJECT_PATHS\">\n" +
            "      <map>\n        <entry key=\"${xml(layout.projects.harbor.toString())}\" value=\"true\" />\n      </map>\n" +
            "    </option>\n  </component>\n</application>\n")
    }

    private fun xml(value: String) = value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")

    /** Git with the user's global and system configuration, hooks and signing switched off. */
    private class GitWorkspace(private val layout: SandboxLayout, private val now: Instant) {
        private val config = layout.root.resolve(".gitconfig")
        private val p = layout.projects

        fun create() {
            Files.writeString(config, "")
            repository(p.harbor, mapOf(
                "README.md" to "# Harbor\n\nA fictional upload service for the Seshlog sandbox.\n",
                "src/retry/Backoff.kt" to "package harbor.retry\n\nfun delayMillis(attempt: Int) = 100L shl attempt.coerceAtMost(6)\n",
                "services/api/OrdersHandler.kt" to "package harbor.api\n\nclass OrdersHandler\n",
            ), now.minus(Duration.ofDays(14)))
            commit(p.harbor, mapOf("CHANGELOG.md" to "# Changelog\n\n- Retry uploads after server errors.\n"), now.minus(Duration.ofDays(2)))
            git(p.harbor, "worktree", "add", "-b", SyntheticScenarios.RETRY_BRANCH, p.harborRetry.toString())
            git(p.harbor, "worktree", "add", "--detach", p.harborHotfix.toString(), "HEAD~1")
            git(p.harbor, "worktree", "add", "-b", SyntheticScenarios.SPIKE_BRANCH, p.harborSpike.toString())
            git(p.harbor, "worktree", "remove", "--force", p.harborSpike.toString())
            repository(p.lantern, mapOf(
                "README.md" to "# Lantern\n\nA fictional theming library for the Seshlog sandbox.\n",
                "src/theme/tokens.ts" to "export type Token = { name: string; value: string };\n",
                "src/main/kotlin/com/lantern/colour/ColourParser.kt" to "package com.lantern.colour\n\nobject ColourParser\n",
                "db/migrations/V6__users.sql" to "CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL);\n",
            ), now.minus(Duration.ofDays(30)))
            for (plain in listOf(p.notesDrafts, p.archive)) Files.createDirectories(plain)
            Files.writeString(p.notes.resolve("README.md"), "Plain notes directory without Git.\n")
        }

        private fun repository(directory: Path, files: Map<String, String>, at: Instant) {
            Files.createDirectories(directory)
            git(directory, "init", "--quiet", "--template=")
            commit(directory, files, at)
        }

        private fun commit(directory: Path, files: Map<String, String>, at: Instant) {
            files.forEach { (name, text) -> directory.resolve(name).also { Files.createDirectories(it.parent) }.let { Files.writeString(it, text) } }
            git(directory, "add", "--all")
            git(directory, "commit", "--quiet", "-m", "Synthetic commit", date = at)
        }

        private fun git(directory: Path, vararg args: String, date: Instant = now) {
            val builder = ProcessBuilder(listOf("git", "-c", "init.defaultBranch=main", "-c", "commit.gpgsign=false",
                "-c", "core.autocrlf=false") + args).directory(directory.toFile()).redirectErrorStream(true)
            builder.environment().apply {
                keys.removeIf { it.startsWith("GIT_") }
                put("GIT_CONFIG_GLOBAL", config.toString())
                put("GIT_CONFIG_NOSYSTEM", "1")
                put("GIT_TERMINAL_PROMPT", "0")
                for (role in listOf("AUTHOR", "COMMITTER")) {
                    put("GIT_${role}_NAME", "Synthetic Tester")
                    put("GIT_${role}_EMAIL", "synthetic@example.invalid")
                    put("GIT_${role}_DATE", date.toString())
                }
            }
            val process = builder.start()
            val output = process.inputStream.readAllBytes().decodeToString()
            check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "git ${args.joinToString(" ")} failed: $output" }
        }
    }
}
