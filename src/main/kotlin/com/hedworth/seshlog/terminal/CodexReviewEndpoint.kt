package com.hedworth.seshlog.terminal

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Live endpoint evidence, independent of argv, launch settings and daemon thread metadata.
 * macOS lsof exposes a connected Unix socket's peer PCB and the named server endpoint's PCB.
 * Other OSes, unavailable fields and failed inspections deliberately cannot authorize delivery.
 * Descriptor output stays bounded in memory; no environment, paths or kernel addresses are logged.
 */
internal object CodexReviewEndpoint {
    fun connected(tui: ProcessHandle, home: Path): Boolean = try {
        if (!System.getProperty("os.name").startsWith("Mac")) false else {
            val started = tui.info().startInstant().orElse(null)
            val socket = home.resolve("app-server-control/app-server-control.sock").toRealPath()
            val server = query(listOf("--", socket.toString()))
            val client = query(listOf("-p", tui.pid().toString()))
            started != null && tui.isAlive && tui.info().startInstant().orElse(null) == started &&
                server != null && client != null && matches(tui.pid(), socket, server, client)
        }
    } catch (_: Exception) { false }

    internal fun matches(tui: Long, socket: Path, server: String, client: String): Boolean {
        data class Descriptor(val pid: Long?, val type: String?, val device: String?, val name: String?)
        fun parse(output: String): List<Descriptor> {
            val result = mutableListOf<Descriptor>()
            var pid: Long? = null
            var descriptor = false
            var type: String? = null
            var device: String? = null
            var name: String? = null
            fun finish() { if (descriptor) result += Descriptor(pid, type, device, name) }
            for (raw in output.split('\u0000')) {
                val field = raw.trimStart('\n')
                when (field.firstOrNull()) {
                    'p' -> { finish(); descriptor = false; pid = field.drop(1).toLongOrNull() }
                    'f' -> { finish(); descriptor = true; type = null; device = null; name = null }
                    't' -> type = field.drop(1)
                    'd' -> device = field.drop(1)
                    'n' -> name = field.drop(1)
                }
            }
            finish()
            return result
        }
        val peers = parse(server).filter { it.pid != null && it.pid != tui && it.type == "unix" && it.name == socket.toString() }
            .mapNotNull { it.device?.takeIf { value -> value.matches(Regex("0x[0-9a-fA-F]+")) && value.drop(2).any { c -> c != '0' } } }.toSet()
        return parse(client).any { it.pid == tui && it.type == "unix" && it.name?.let { name ->
            name.startsWith("->") && name.drop(2) in peers
        } == true }
    }

    private fun query(selection: List<String>): String? {
        val executable = listOf("/usr/sbin/lsof", "/usr/bin/lsof").firstOrNull { Files.isExecutable(Path.of(it)) } ?: return null
        val process = ProcessBuilder(listOf(executable, "-nP", "-a", "-U", "-F0pftnd") + selection)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val reader = FutureTask { process.inputStream.use { it.readNBytes(1024 * 1024 + 1) } }
        Thread.ofVirtual().start(reader)
        return try {
            if (!process.waitFor(1, TimeUnit.SECONDS) || process.exitValue() != 0) return null
            val bytes = reader.get(1, TimeUnit.SECONDS)
            if (bytes.size > 1024 * 1024) null else bytes.toString(Charsets.UTF_8)
        } finally { process.destroyForcibly(); reader.cancel(true) }
    }
}
