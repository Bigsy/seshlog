package com.hedworth.seshlog.terminal

import com.google.gson.JsonParser
import com.hedworth.seshlog.codex.CodexSessionProvider
import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.sandbox.SandboxLayout
import com.hedworth.seshlog.sandbox.SyntheticSandbox
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Real synthetic stubs, production provider/inspection/reconciliation, explicit stdout barriers. */
class CodexSyntheticDaemonTest {
    @get:Rule val temporary = TemporaryFolder(File(if (System.getProperty("os.name").lowercase().contains("mac")) "/tmp" else System.getProperty("java.io.tmpdir")))
    private val clients = mutableListOf<Client>()

    private class Client(val process: Process) : AutoCloseable {
        private val lines = LinkedBlockingQueue<String>()
        private val writer = process.outputStream.bufferedWriter()
        var title: String? = null
        var id: String = ""
        init {
            Thread({ process.inputStream.bufferedReader().useLines { it.forEach(lines::add) } }, "synthetic-codex-output")
                .apply { isDaemon = true; start() }
        }
        fun await(marker: String): String {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (System.nanoTime() < deadline) {
                val line = lines.poll(1, TimeUnit.SECONDS) ?: continue
                if (line.contains(marker)) return line.substringAfter(marker)
            }
            error("Synthetic process did not reach $marker (alive=${process.isAlive})")
        }
        fun ready() {
            val state = JsonParser.parseString(await("SESHLOG_READY ")).asJsonObject
            id = state.get("id").asString; title = state.get("title").takeUnless { it.isJsonNull }?.asString
        }
        fun send(command: String) { writer.write(command); writer.newLine(); writer.flush() }
        fun change(command: String) { send(command); ready() }
        fun barrier() {
            send("/barrier ready")
            val state = JsonParser.parseString(await("SESHLOG_BARRIER ")).asJsonObject
            id = state.get("id").asString; title = state.get("title").takeUnless { it.isJsonNull }?.asString
        }
        override fun close() {
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) }
        }
    }

    @Test fun `manual wrapped launch enables titles without changing the synthetic agent config`() {
        assumeFalse(System.getProperty("os.name").lowercase().contains("win"))
        assumeTrue("Node is required for synthetic agents", SyntheticSandbox.findNode() != null)
        val root = temporary.root.toPath().resolve("wrapped-profile")
        val layout = SandboxLayout(root, root.resolve("ide/config"), root.resolve("ide/log"), root.resolve("ide/plugins"))
        SyntheticSandbox.prepare(layout, filler = 0, env = emptyMap(), home = temporary.root.toPath().resolve("home"))
        val executable = layout.executable(AgentKind.CODEX)
        val provider = CodexSessionProvider({ layout.codex }, { executable.toString() })
        val id = provider.scan(emptyMap()).first { it.cwd == layout.projects.harbor }.id
        val wrapper = CodexTerminalWrapper.install(root.resolve("ide/config/seshlog/terminal"))
        val config = layout.codex.resolve("config.toml")
        val before = if (java.nio.file.Files.exists(config)) java.nio.file.Files.readString(config) else null
        try {
            val daemon = Client(ProcessBuilder(executable.toString(), "app-server", "--synthetic-daemon").start()).also { clients += it }
            daemon.await("SESHLOG_DAEMON_READY ")
            val builder = ProcessBuilder("/bin/sh", "-c", "codex resume \"\$1\"", "shell", id)
                .directory(layout.projects.harbor.toFile()).redirectErrorStream(true)
            builder.environment().putAll(mapOf(
                "PATH" to "$wrapper:${executable.parent}:${System.getenv("PATH")}",
                "SESHLOG_STUB_CODEX_DAEMON" to "1", "SESHLOG_STUB_TITLES" to "0",
            ))
            val tui = Client(builder.start()).also { clients += it; it.ready() }
            assertEquals(id, tui.id)
            assertTrue(tui.title.orEmpty().contains(id))
            tui.change("/new")
            assertNotEquals(id, tui.id)
            assertTrue(tui.title.orEmpty().contains(tui.id))
            assertEquals(before, if (java.nio.file.Files.exists(config)) java.nio.file.Files.readString(config) else null)
        } finally { clients.asReversed().forEach(Client::close) }
    }

    @Test fun `shared synthetic daemon exercises switches exchanges index lag and mixed titles`() {
        assumeFalse(System.getProperty("os.name").lowercase().contains("win"))
        assumeTrue("Node is required for synthetic agents", SyntheticSandbox.findNode() != null)
        val root = temporary.root.toPath().resolve("profile")
        val layout = SandboxLayout(root, root.resolve("ide/config"), root.resolve("ide/log"), root.resolve("ide/plugins"))
        SyntheticSandbox.prepare(layout, filler = 0, env = emptyMap(), home = temporary.root.toPath().resolve("home"))
        val executable = layout.executable(AgentKind.CODEX)
        val provider = CodexSessionProvider({ layout.codex }, { executable.toString() })
        fun scan() = provider.scan(emptyMap())
        fun launch(vararg args: String, ready: Boolean = true): Client {
            val builder = ProcessBuilder(listOf(executable.toString()) + args).directory(layout.projects.harbor.toFile()).redirectErrorStream(true)
            builder.environment()["SESHLOG_STUB_CODEX_DAEMON"] = "1"
            return Client(builder.start()).also { clients += it; if (ready) it.ready() }
        }
        try {
            val daemon = launch("app-server", "--synthetic-daemon", ready = false)
            daemon.await("SESHLOG_DAEMON_READY ")
            val seeded = scan().filter { it.cwd == layout.projects.harbor }
            val a = seeded.first().id
            val first = launch("resume", a)
            val second = launch("--synthetic-start-paused", ready = false)
            second.await("SESHLOG_START_WAIT")
            // Older TUI changes thread while the newer TUI is alive but has not created its thread.
            first.change("/new")
            val b = first.id
            second.send("/start"); second.ready()
            val c = second.id
            fun inspect(sessions: List<Session> = scan()): ProcessBatchResult {
                val table = ProcessTableSnapshot.of(listOf(
                    ProcessRecord(10, 1, "zsh", emptyList(), first.process.toHandle()),
                    ProcessRecord(first.process.pid(), 10, "node", listOf(executable.toString(), "resume", a), first.process.toHandle(), first.process.toHandle().info().startInstant().orElse(null)),
                    ProcessRecord(20, 1, "zsh", emptyList(), second.process.toHandle()),
                    ProcessRecord(second.process.pid(), 20, "node", listOf(executable.toString()), second.process.toHandle(), second.process.toHandle().info().startInstant().orElse(null)),
                    ProcessRecord(daemon.process.pid(), 1, "node", listOf(executable.toString(), "app-server"), daemon.process.toHandle()),
                ))
                return ProcessBatchInspector(snapshot = { table }).inspect(setOf(10, 20), sessions,
                    mapOf(AgentKind.CODEX to executable.toString()), titles = mapOf(10L to first.title, 20L to second.title))
            }
            second.change("/picker $a"); second.change("/titles off")
            var batch = inspect()
            assertEquals(setOf(b), batch.discoveries.getValue(10).discovery.sessionIds)
            assertNull(batch.discoveries.getValue(20).discovery.copySession(b))
            first.change("/titles off")
            assertTrue(inspect().discoveries.values.all { it.discovery.sessionIds.isEmpty() })
            // The daemon retains old rollouts while neither TUI has a persistent writer.
            val held = ProcessTranscripts.writableFiles(setOf(daemon.process.pid())).files[daemon.process.pid()].orEmpty()
            assertTrue(held.count { it.fileName.toString().endsWith(".jsonl") } >= 3)
            val tuiFiles = ProcessTranscripts.writableFiles(setOf(first.process.pid(), second.process.pid())).files.values.flatten()
            assertTrue(tuiFiles.none { it.fileName.toString().endsWith(".jsonl") })

            first.change("/titles on"); second.change("/titles on")
            second.change("/resume $c")
            val registry = TabRegistry<Long>()
            registry.register(b, 10); registry.register(c, 20)
            fun reconcile(batch: ProcessBatchResult, sessions: List<Session>) {
                val owners = registry.snapshot()
                val discoveries = batch.discoveries.mapNotNull { (shell, found) ->
                    val id = found.discovery.sessionIds.singleOrNull() ?: return@mapNotNull null
                    TabProcessDiscovery(shell, id, found.discovery.processes[id]?.let(batch.handles::get))
                }
                val invalidated = owners.filter { (id, shell) -> id in batch.discoveries.getValue(shell).discovery.excludedSessionIds }.values.toSet()
                val result = TerminalReconciler.reconcile(TerminalReconcileInput(sessions, owners,
                    setOf(10L, 20L).associateWith(registry::sessionFor), discoveries, emptySet(), emptyMap(), emptyMap(), emptySet(),
                    emptyMap(), { it.title }, invalidatedTabs = invalidated))
                val next = result.adopt.entries.associate { it.value to it.key }
                assertTrue(registry.transition((next.keys + result.displacedTabs.values).map { tab ->
                    TabRegistry.Transition(tab, registry.sessionFor(tab), registry.generation(tab), next[tab])
                }))
                assertTrue(result.ended.isEmpty())
            }
            first.change("/resume $c"); second.change("/resume $b")
            reconcile(inspect(), scan())
            assertEquals(c, registry.sessionFor(10)); assertEquals(b, registry.sessionFor(20))

            first.change("/new delayed")
            val hidden = first.id
            var sessions = scan()
            assertTrue(sessions.none { it.id == hidden })
            batch = inspect(sessions)
            assertEquals(SessionProcess.IdentityState.UNINDEXED, batch.discoveries.getValue(10).discovery.state)
            assertNull(batch.discoveries.getValue(10).discovery.copySession(c))
            reconcile(batch, sessions)
            assertNull(registry.sessionFor(10)); assertTrue(registry.isReserved(10))
            first.change("/publish")
            first.send("/delay 0"); first.send("A fictional prompt for Copy"); first.barrier()
            sessions = scan(); batch = inspect(sessions)
            reconcile(batch, sessions)
            assertEquals(hidden, registry.sessionFor(10))
            assertEquals(hidden, batch.discoveries.getValue(10).discovery.copySession(c))
            assertTrue((provider.lastAssistantMessage(sessions.single { it.id == hidden }) as com.hedworth.seshlog.copy.CopyContent.Found).text.contains("Synthetic reply"))
            first.change("/clear")
            assertNotEquals(hidden, first.id)
            assertEquals(setOf(first.id), inspect().discoveries.getValue(10).discovery.sessionIds)
        } finally { clients.asReversed().forEach(Client::close) }
    }
}
