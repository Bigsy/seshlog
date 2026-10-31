package com.hedworth.seshlog.terminal

import com.hedworth.seshlog.sandbox.SyntheticSandbox
import org.junit.Assert.*
import org.junit.Assume.*
import org.junit.Test
import java.net.StandardProtocolFamily.UNIX
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

class CodexReviewEndpointTest {
    @Test fun `peer descriptors must join the exact selected process to the named endpoint`() {
        val path = Path.of("/synthetic/home A/control.sock")
        val server = "p200\u0000\nf5\u0000tunix\u0000d0xabc\u0000n$path\u0000\n"
        val client = "p100\u0000\nf8\u0000tunix\u0000d0xdef\u0000n->0xabc\u0000\n"
        assertTrue(CodexReviewEndpoint.matches(100, path, server, client))
        assertFalse(CodexReviewEndpoint.matches(101, path, server, client))
        assertFalse(CodexReviewEndpoint.matches(100, Path.of("/synthetic/other/control.sock"), server, client))
        assertFalse(CodexReviewEndpoint.matches(100, path, server, client.replace("->0xabc", "->0x123")))
        assertFalse(CodexReviewEndpoint.matches(100, path, server.replace("d0xabc\u0000", ""), client))
        assertFalse(CodexReviewEndpoint.matches(100, path, server, ""))
        assertFalse(CodexReviewEndpoint.matches(100, path, server.replace("p200", "p100"), client))
        assertFalse(CodexReviewEndpoint.matches(100, path, server.replace("0xabc", "0x0"), client.replace("0xabc", "0x0")))
    }

    @Test fun `a live synthetic client in one home cannot certify the other homes socket`() {
        assumeTrue(System.getProperty("os.name").startsWith("Mac"))
        val node = SyntheticSandbox.findNode(); assumeNotNull(node)
        val root = Files.createTempDirectory(Path.of("/tmp"), "sl-peer-").toRealPath()
        try {
            val first = root.resolve("one"); val second = root.resolve("two")
            fun socket(home: Path) = home.resolve("app-server-control/app-server-control.sock").also { Files.createDirectories(it.parent) }
            val one = socket(first); val two = socket(second)
            ServerSocketChannel.open(UNIX).bind(UnixDomainSocketAddress.of(one)).use { server ->
                ServerSocketChannel.open(UNIX).bind(UnixDomainSocketAddress.of(two)).use {
                    val client = ProcessBuilder(node.toString(), "-e",
                        "const s=require('net').createConnection(process.argv[1]);s.on('connect',()=>console.log('ready'));process.stdin.resume()", one.toString())
                        .redirectError(ProcessBuilder.Redirect.DISCARD).start()
                    try {
                        val ready = FutureTask { client.inputStream.bufferedReader().readLine() }
                        Thread.ofVirtual().start(ready)
                        assertEquals("ready", ready.get(3, TimeUnit.SECONDS))
                        server.accept().use {
                            assertTrue(CodexReviewEndpoint.connected(client.toHandle(), first))
                            assertFalse(CodexReviewEndpoint.connected(client.toHandle(), second))
                            assertFalse(CodexReviewEndpoint.connected(client.toHandle(), root.resolve("missing")))
                        }
                    } finally { client.destroyForcibly(); client.waitFor(3, TimeUnit.SECONDS) }
                }
            }
        } finally { root.toFile().deleteRecursively() }
    }
}
