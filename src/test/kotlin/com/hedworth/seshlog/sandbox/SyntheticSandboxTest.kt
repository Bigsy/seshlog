package com.hedworth.seshlog.sandbox

import com.google.gson.Gson
import com.hedworth.seshlog.claude.ClaudeCodeSessionProvider
import com.hedworth.seshlog.codex.CodexSessionProvider
import com.hedworth.seshlog.diagnostics.DiagnosticRef
import com.hedworth.seshlog.index.ContentSearchIndex
import com.hedworth.seshlog.index.GitRepository
import com.hedworth.seshlog.model.Activity
import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.ProviderHealth
import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.model.SessionProvider
import com.hedworth.seshlog.opencode.OpenCodeSessionProvider
import com.hedworth.seshlog.pi.PiSessionProvider
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.concurrent.TimeUnit

class SyntheticSandboxTest {
    @get:Rule val tmp = TemporaryFolder()

    private val now = Instant.parse("2026-10-28T12:00:00Z")
    private val home: Path get() = tmp.root.toPath().resolve("home")
    private val layout by lazy {
        val root = tmp.root.toPath().resolve("sandbox")
        SandboxLayout(root, root.resolve("ide/config"), root.resolve("ide/log"), root.resolve("ide/plugins"))
    }

    @Before fun requireGit() = assumeTrue("git is needed to create the synthetic checkouts", run(listOf("git", "--version")) == 0)

    private fun prepare(filler: Int = 24) = SyntheticSandbox.prepare(layout, filler, now, SyntheticSandbox.findNode(), emptyMap(), home)

    private fun providers(): Map<AgentKind, SessionProvider> = mapOf(
        AgentKind.CLAUDE_CODE to ClaudeCodeSessionProvider({ layout.claude }, { layout.executable(AgentKind.CLAUDE_CODE).toString() }),
        AgentKind.CODEX to CodexSessionProvider({ layout.codex }, { layout.executable(AgentKind.CODEX).toString() }),
        AgentKind.OPENCODE to OpenCodeSessionProvider({ layout.opencode }, { layout.executable(AgentKind.OPENCODE).toString() }),
        AgentKind.PI to PiSessionProvider({ layout.piSessions }, { layout.executable(AgentKind.PI).toString() }),
    )

    private fun scan(): Map<String, Session> {
        val manifest = SyntheticSandbox.readManifest(layout)!!
        val handles = manifest.sessions.associate { it.id.let { id -> if (it.kind == AgentKind.PI) "pi:$id" else id } to it.handle }
        return providers().values.flatMap { it.scan(emptyMap()) }.associateBy { handles[it.id] ?: it.id }
    }

    @Test fun `every generated session scans with its provider inside the sandbox`() {
        prepare()
        val manifest = SyntheticSandbox.readManifest(layout)!!
        for ((kind, provider) in providers()) {
            val scan = provider.scanWithDiagnostics(emptyMap())
            assertEquals(kind.name, ProviderHealth.READY, scan.health)
            assertEquals(kind.name, manifest.expected.getValue(kind), scan.sessions.size)
            scan.sessions.forEach { session ->
                assertTrue(session.cwd.startsWith(layout.root))
                session.transcriptPath?.let { assertTrue(it.startsWith(layout.root)) }
            }
        }
        val sessions = scan()
        assertEquals("Retry backoff with jitter for the uploader", sessions.getValue("retry-backoff").title)
        assertEquals(SyntheticScenarios.RETRY_BRANCH, sessions.getValue("retry-backoff").gitBranch)
        assertEquals("release-checklist-0.9", sessions.getValue("custom-title").title)
        assertTrue(sessions.getValue("uncertain-location").location!!.uncertain)
        assertEquals(sessions.getValue("continued-successor").id, sessions.getValue("continued-original").continuationId)
        assertEquals(0, sessions.getValue("metadata-only").promptCount)
        assertTrue(sessions.getValue("metadata-only").hasExplicitTitle)
        assertEquals(1, sessions.getValue("subagent-review").subagentTranscriptPaths.size)
        assertEquals("Lantern schema migration", sessions.getValue("codex-lantern-migration").title)
        assertEquals(Activity.WAITING, sessions.getValue("codex-lantern-migration").activity)
        assertEquals("Lantern dark theme contrast", sessions.getValue("pi-dark-theme").title)
        assertEquals(Activity.WAITING, sessions.getValue("pi-dark-theme").activity)
        assertEquals("Draft onboarding notes for new contributors", sessions.getValue("opencode-notes").title)
        assertFalse(sessions.containsKey("opencode-archived") || sessions.containsKey("opencode-child"))
        assertEquals(now.minusSeconds(25 * 60), sessions.getValue("retry-backoff").lastActivityAt)

        val p = layout.projects
        val git = GitRepository.Snapshot()
        assertEquals("main", git.resolve(p.harbor)!!.branch)
        assertEquals(SyntheticScenarios.RETRY_BRANCH, git.resolve(p.harborRetry)!!.branch)
        assertTrue(git.resolve(p.harborRetry)!!.linked)
        git.resolve(p.harborHotfix)!!.let { assertNull(it.branch); assertNotNull(it.commit) }
        assertFalse(Files.exists(p.harborSpike))
        assertNull(git.resolve(p.notes))
        assertFalse(Files.exists(p.goneScratch))
    }

    @Test fun `the default profile exceeds the switcher row limit`() {
        prepare(filler = SyntheticSandbox.DEFAULT_FILLER)
        assertTrue(scan().size > 200)
    }

    @Test fun `documented example queries behave as the guide says`() {
        prepare()
        val sessions = scan()
        val handles = sessions.entries.associate { (handle, session) -> session.id to handle }
        val providers = providers()
        ContentSearchIndex({ providers.getValue(it.kind).conversationText(it) }, { providers.getValue(it.kind).contentStamp(it) },
            entryExtractor = { providers.getValue(it.kind).conversationEntries(it) }).use { index ->
            val all = sessions.values.toList()
            for (check in SyntheticScenarios.queries()) {
                val hits = index.search(check.query, all, check.sources).map { handles.getValue(it.session.id) }
                if (check.first == null) assertEquals(check.query, emptyList<String>(), hits)
                else assertEquals("${check.query}: $hits", check.first, hits.firstOrNull())
                assertTrue(check.query, hits.none { it in check.excludes })
            }
            val late = index.searchSnapshot(SyntheticScenarios.LATE_MARKER, all)
            assertTrue("the long history is only partly searchable", late.coverage.partial)
            val elsewhere = index.searchSnapshot("\"connection pool exhausted\"", all.filter { it.cwd == layout.projects.lantern })
            assertTrue(elsewhere.hits.isEmpty())
            assertFalse("a lantern-only search has complete coverage", elsewhere.coverage.partial)
        }
    }

    @Test fun `seeded settings, stubs and manifest stay inside the sandbox`() {
        prepare()
        val settings = Files.readString(layout.settingsFile)
        layout.settings().forEach { (name, value) ->
            assertTrue(name, settings.contains("<option name=\"$name\" value=\"$value\" />"))
            assertTrue(name, Path.of(value).startsWith(layout.root))
        }
        val trusted = Files.readString(layout.ideConfig.resolve("options/trusted-paths.xml"))
        assertTrue(trusted.contains("<entry key=\"${layout.projects.harbor}\" value=\"true\" />"))
        assertFalse("worktrees keep the trust prompt", trusted.contains(layout.projects.harborRetry.toString()))
        for (kind in AgentKind.entries) {
            val launcher = layout.executable(kind)
            assertTrue(Files.isExecutable(launcher))
            assertTrue(Files.readString(launcher).startsWith("#!"))
        }
        @Suppress("UNCHECKED_CAST")
        val config = Gson().fromJson(Files.readString(layout.bin.resolve("lib/sandbox.json")), Map::class.java) as Map<String, String>
        assertTrue(config.values.all { Path.of(it).startsWith(layout.root) })
        val manifest = SyntheticSandbox.readManifest(layout)!!
        assertEquals(AgentKind.entries.associateWith { layout.storage(it).toString() }, manifest.storage)
        assertTrue(manifest.sessions.all { Path.of(it.cwd).startsWith(layout.root) })
    }

    @Test fun `an existing sandbox is reused and missing settings are restored`() {
        prepare()
        val transcript = scan().getValue("db-locked").transcriptPath!!
        val before = Files.readString(transcript)
        Files.writeString(layout.settingsFile, "<application />\n")
        assertTrue(prepare().startsWith("Using the existing synthetic sandbox"))
        assertEquals(before, Files.readString(transcript))
        assertEquals("<application />\n", Files.readString(layout.settingsFile))
        Files.delete(layout.settingsFile)
        assertTrue(prepare().contains("Restored missing Seshlog settings"))
        assertTrue(Files.readString(layout.settingsFile).contains("claudeExecutable"))
    }

    @Test fun `unsafe roots are refused and unknown directories are never deleted`() {
        val env = mapOf("CODEX_HOME" to tmp.root.toPath().resolve("real-codex").toString())
        fun reason(path: Path) = SyntheticSandbox.unsafeRootReason(path, env, home)
        assertNotNull(reason(home))
        assertNotNull(reason(home.resolve(".claude/sandbox")))
        assertNotNull(reason(tmp.root.toPath().resolve("real-codex")))
        val repository = tmp.newFolder("repository").toPath()
        Files.createDirectory(repository.resolve(".git"))
        assertNotNull(reason(repository.resolve("nested/sandbox")))
        val used = tmp.newFolder("used").toPath()
        Files.writeString(used.resolve("keep.txt"), "user data")
        assertNotNull(reason(used))
        assertNull(reason(tmp.root.toPath().resolve("fresh")))
        Files.createDirectories(tmp.root.toPath().resolve("ide-only/ide/config"))
        assertNull(reason(tmp.root.toPath().resolve("ide-only")))

        val unknown = SandboxLayout(used, used.resolve("ide/config"), used.resolve("ide/log"), used.resolve("ide/plugins"))
        assertThrows(IllegalStateException::class.java) { SyntheticSandbox.reset(unknown, false, 4, now, null, emptyMap(), home) }
        assertEquals("user data", Files.readString(used.resolve("keep.txt")))
        prepare()
        assertThrows(IllegalStateException::class.java) { SyntheticSandbox.reset(layout, true, 4, now, null, emptyMap(), home) }
        assertTrue(Files.exists(layout.manifest))
        SyntheticSandbox.reset(layout, false, 4, now.plusSeconds(60), null, emptyMap(), home)
        assertEquals(now.plusSeconds(60).toString(), SyntheticSandbox.readManifest(layout)!!.created)
    }

    private fun requireStubs(): Path {
        assumeFalse("the stub launchers are POSIX scripts", System.getProperty("os.name").startsWith("Windows"))
        val node = SyntheticSandbox.findNode()
        assumeTrue("Node.js runs the stub agents", node != null)
        return node!!
    }

    @Test fun `native fork stubs record new checkout locations without changing source sessions`() {
        requireStubs()
        prepare()
        val before = scan()
        for ((kind, handle) in listOf(AgentKind.CLAUDE_CODE to "db-locked", AgentKind.CODEX to "codex-lantern-migration")) {
            val source = before.getValue(handle)
            val checkout = GitRepository.checkout(source.cwd)!!
            val destination = checkout.root.resolveSibling("fork-${kind.name.lowercase()}")
            val created = com.hedworth.seshlog.worktree.WorktreeCreator.create(source, checkout,
                com.hedworth.seshlog.worktree.WorktreeCreationOptions("HEAD", "fork-${kind.name.lowercase()}", destination),
                listOf(layout.claude, layout.codex))
            val args = if (kind == AgentKind.CLAUDE_CODE) listOf("--resume", source.id, "--fork-session")
                else listOf("fork", source.id, "--cd", created.directory.toString())
            assertEquals(0, stub(kind, args, created.directory, "Synthetic fork turn"))
            val after = scan()
            val fork = after.values.single { it.kind == kind && it.id !in before.values.map { s -> s.id } }
            assertEquals(created.directory, fork.cwd)
            assertEquals(source.cwd, after.getValue(handle).cwd)
            assertEquals(source.promptCount, after.getValue(handle).promptCount)
        }
    }

    @Test fun `stub agents record turns that the providers read back`() {
        requireStubs()
        prepare()
        val p = layout.projects
        val before = scan()

        assertEquals(0, stub(AgentKind.CLAUDE_CODE, listOf("--resume", before.getValue("db-locked").id), p.harbor, "A stub prompt", "/title Renamed by the stub"))
        stub(AgentKind.CODEX, listOf("resume", before.getValue("codex-lantern-migration").id), p.lantern, "A stub prompt", "/title Codex renamed")
        stub(AgentKind.CODEX, listOf("fork", before.getValue("codex-lantern-migration").id), p.lantern, "A fork prompt")
        stub(AgentKind.PI, listOf("--session", before.getValue("pi-dark-theme").transcriptPath.toString()), p.lantern, "A stub prompt", "/title Pi renamed")
        stub(AgentKind.CLAUDE_CODE, emptyList(), p.notes, "A brand new session")
        assertEquals("a new session outside the sandbox is refused", 1, stub(AgentKind.CLAUDE_CODE, emptyList(), tmp.root.toPath(), "Outside"))

        val after = scan()
        after.getValue("db-locked").let {
            assertEquals("Renamed by the stub", it.title)
            assertEquals(before.getValue("db-locked").promptCount + 1, it.promptCount)
        }
        assertEquals(0, Files.list(layout.claude.resolve("sessions")).use { it.count() })
        after.getValue("codex-lantern-migration").let {
            assertEquals("Codex renamed", it.title)
            assertEquals(Activity.WAITING, it.activity)
            assertEquals(before.getValue("codex-lantern-migration").promptCount + 1, it.promptCount)
        }
        after.getValue("pi-dark-theme").let {
            assertEquals("Pi renamed", it.title)
            assertEquals(Activity.WAITING, it.activity)
        }
        assertEquals(before.size + 2, after.size)
        assertTrue(after.values.any { it.kind == AgentKind.CLAUDE_CODE && it.cwd == p.notes && it.title == "A brand new session" })
        assertTrue(after.values.all { it.cwd.startsWith(layout.root) })
    }

    @Test fun `the opencode stub records turns in the database`() {
        val node = requireStubs()
        assumeTrue("the opencode stub needs node:sqlite", run(listOf(node.toString(), "-e", "require('node:sqlite')")) == 0)
        prepare()
        val opencode = scan().getValue("opencode-notes")
        stub(AgentKind.OPENCODE, listOf("--session", opencode.id), layout.projects.notes, "A stub prompt", "/title opencode renamed")
        scan().getValue("opencode-notes").let {
            assertEquals("opencode renamed", it.title)
            assertEquals(opencode.promptCount + 1, it.promptCount)
            assertEquals(Activity.WAITING, it.activity)
        }
    }

    @Test fun `verifier needs the running sandbox IDE, this build and synthetic scans`() {
        prepare()
        val manifest = SyntheticSandbox.readManifest(layout)!!
        val repository = tmp.newFolder("repository").toPath()
        Files.createDirectories(repository.resolve("src/main"))
        Files.writeString(repository.resolve("src/main/Source.kt"), "")
        Files.setLastModifiedTime(repository.resolve("src/main/Source.kt"), FileTime.from(now.minusSeconds(3600)))
        val built = repository.resolve("seshlog-1.0.jar").also { Files.writeString(it, "jar") }
        val installed = layout.idePlugins.resolve("seshlog/lib/seshlog-1.0.jar")
        Files.createDirectories(installed.parent)
        Files.writeString(installed, "jar")
        val started = Instant.now().plusSeconds(5)
        val ide = SandboxProcess(4242, started, "/jbr/bin/java", listOf("-Didea.config.path=${layout.ideConfig}",
            "-Didea.log.path=${layout.ideLog}", "-Didea.plugins.path=${layout.idePlugins}", "-Didea.system.path=${layout.root.resolve("ide/system")}"))
        fun verifier(processes: List<SandboxProcess>) = SyntheticSandboxVerifier(layout, built, "1.0", repository) { processes }

        assertEquals(false, verifier(emptyList()).verify().single { it.label == "IDE process" }.ok)

        val log = layout.ideLog.resolve("seshlog/sessions.jsonl")
        Files.createDirectories(log.parent)
        val env = mapOf("schema" to 1, "plugin" to "1.0", "ide" to "IU-262.1", "java" to "25", "os" to "Mac OS X", "arch" to "aarch64", "idePid" to 4242)
        fun scanRecord(kind: AgentKind, storage: Path, sessions: Int) = Gson().toJson(mapOf("time" to started.plusSeconds(10).toString(),
            "event" to "provider_scanned", "env" to env,
            "fields" to mapOf("kind" to kind.name, "health" to "READY", "sessions" to sessions, "storage" to DiagnosticRef.path(storage))))
        Files.write(log, listOf(Gson().toJson(mapOf("time" to started.minusSeconds(3600).toString(), "event" to "provider_scanned", "env" to env,
            "fields" to mapOf("kind" to "CODEX", "health" to "READY", "sessions" to 1, "storage" to "from-an-earlier-process")))) +
            AgentKind.entries.map { scanRecord(it, layout.storage(it), manifest.expected.getValue(it)) })
        val checks = verifier(listOf(ide)).verify()
        assertEquals(SyntheticSandboxVerifier.report(checks), emptyList<SandboxCheck>(), checks.filter { it.ok == false })

        Files.write(log, listOf(scanRecord(AgentKind.CODEX, home.resolve(".codex/sessions"), 900)), StandardOpenOption.APPEND)
        assertEquals(false, verifier(listOf(ide)).verify().single { it.label == "Scanned roots" }.ok)

        Files.writeString(built, "rebuilt")
        assertEquals(false, verifier(listOf(ide)).verify().single { it.label == "Plugin jar" }.ok)
    }

    private fun stub(kind: AgentKind, args: List<String>, cwd: Path, vararg input: String): Int {
        val builder = ProcessBuilder(listOf(layout.executable(kind).toString()) + args).directory(cwd.toFile()).redirectErrorStream(true)
        builder.environment()["SESHLOG_STUB_DELAY_MS"] = "0"
        builder.environment()["PWD"] = cwd.toString() // As a shell sets it.
        val process = builder.start()
        process.outputStream.bufferedWriter().use { writer -> input.forEach { writer.write(it + "\n") } }
        val output = process.inputStream.readAllBytes().decodeToString()
        assertTrue("stub timed out: $output", process.waitFor(30, TimeUnit.SECONDS))
        if (process.exitValue() != 0 && cwd.startsWith(layout.root)) fail("stub failed: $output")
        return process.exitValue()
    }

    private fun run(command: List<String>): Int = runCatching {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        process.inputStream.readAllBytes()
        if (process.waitFor(30, TimeUnit.SECONDS)) process.exitValue() else -1
    }.getOrDefault(-1)
}
