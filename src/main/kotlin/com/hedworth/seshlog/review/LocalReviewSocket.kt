package com.hedworth.seshlog.review

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.nio.charset.CodingErrorAction
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Bounded RFC 6455 JSON-RPC over an existing local Unix socket. Never starts a process/server. */
internal class LocalReviewSocket(path: Path, timeoutMillis: Long = 10_000) : AutoCloseable {
    private val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
    private val selector = Selector.open()
    private val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    private val random = SecureRandom()
    private var incoming = 0L
    private val key: SelectionKey
    init {
        try {
            channel.configureBlocking(false)
            key = channel.register(selector, SelectionKey.OP_CONNECT)
            if (!channel.connect(UnixDomainSocketAddress.of(path))) while (!channel.finishConnect()) await(SelectionKey.OP_CONNECT)
            upgrade()
        } catch (e: Exception) { channel.close(); selector.close(); throw e }
    }
    private fun await(operation: Int) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        val remaining = deadline - System.nanoTime()
        check(remaining > 0) { "Local review protocol deadline" }
        key.interestOps(operation)
        selector.select(minOf(100, TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1)))
        selector.selectedKeys().clear()
    }
    private fun read(size: Int): ByteArray {
        require(size in 0..MAX_MESSAGE)
        incoming += size
        check(incoming <= 8L * MAX_MESSAGE) { "Local review input limit" }
        val bytes = ByteArray(size)
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            check(System.nanoTime() < deadline) { "Local review protocol deadline" }
            when (channel.read(buffer)) { -1 -> throw EOFException(); 0 -> await(SelectionKey.OP_READ) }
        }
        return bytes
    }
    private fun write(bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            check(System.nanoTime() < deadline) { "Local review protocol deadline" }
            if (channel.write(buffer) == 0) await(SelectionKey.OP_WRITE)
        }
    }
    private fun upgrade() {
        val nonce = Base64.getEncoder().encodeToString(ByteArray(16).also(random::nextBytes))
        write(("GET / HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Key: $nonce\r\nSec-WebSocket-Version: 13\r\n\r\n").toByteArray(Charsets.US_ASCII))
        val header = StringBuilder()
        while (!header.endsWith("\r\n\r\n")) {
            check(header.length < 16 * 1024) { "Local review handshake limit" }
            header.append((read(1)[0].toInt() and 255).toChar())
        }
        val lines = header.toString().split("\r\n")
        check(lines.first().startsWith("HTTP/1.1 101 ")) { "Local review handshake refused" }
        val fields = lines.drop(1).filter { ':' in it }.associate { it.substringBefore(':').lowercase(Locale.ROOT) to it.substringAfter(':').trim() }
        val expected = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
            .digest((nonce + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII)))
        check(fields["sec-websocket-accept"] == expected && fields["upgrade"]?.lowercase(Locale.ROOT) == "websocket" &&
            fields["connection"]?.lowercase(Locale.ROOT)?.split(',')?.any { it.trim() == "upgrade" } == true &&
            fields["sec-websocket-extensions"] == null) { "Invalid local review handshake" }
    }
    fun send(value: JsonObject) = frame(1, value.toString().toByteArray(Charsets.UTF_8))
    private fun frame(opcode: Int, bytes: ByteArray) {
        require(bytes.size <= MAX_MESSAGE)
        val out = ByteArrayOutputStream(bytes.size + 14)
        out.write(0x80 or opcode)
        when {
            bytes.size < 126 -> out.write(0x80 or bytes.size)
            bytes.size <= 65535 -> { out.write(0x80 or 126); out.write(bytes.size ushr 8); out.write(bytes.size) }
            else -> { out.write(0x80 or 127); out.write(ByteBuffer.allocate(8).putLong(bytes.size.toLong()).array()) }
        }
        val mask = ByteArray(4).also(random::nextBytes)
        out.write(mask)
        bytes.indices.forEach { out.write(bytes[it].toInt() xor mask[it % 4].toInt()) }
        write(out.toByteArray())
    }
    private fun receive(): JsonObject {
        val out = ByteArrayOutputStream()
        var started = false
        repeat(1024) {
            val header = read(2)
            val first = header[0].toInt() and 255
            val second = header[1].toInt() and 255
            check(first and 0x70 == 0 && second and 0x80 == 0) { "Unsupported local review frame" }
            val opcode = first and 15
            val final = first and 0x80 != 0
            val length = when (val small = second and 127) {
                126 -> ByteBuffer.wrap(read(2)).short.toInt() and 65535
                127 -> ByteBuffer.wrap(read(8)).long.also { check(it in 0..MAX_MESSAGE.toLong()) }.toInt()
                else -> small
            }
            if (opcode >= 8) check(final && length <= 125)
            val bytes = read(length)
            when (opcode) {
                8 -> throw EOFException()
                9 -> frame(10, bytes)
                10 -> Unit
                1, 0 -> {
                    check((opcode == 1 && !started) || (opcode == 0 && started))
                    started = true
                    check(out.size() + bytes.size <= MAX_MESSAGE)
                    out.write(bytes)
                    if (final) {
                        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(out.toByteArray())).toString()
                        return JsonParser.parseString(text).asJsonObject
                    }
                }
                else -> error("Unsupported local review message")
            }
        }
        error("Local review frame count limit")
    }
    fun response(id: String): JsonObject {
        repeat(256) {
            val value = receive()
            if (!value.has("method") && value["id"]?.asString == id) return value
            // Never answer approvals/elicitation, interrupt a turn or alter the receiver's policy.
        }
        error("Local review notification limit")
    }
    fun request(id: String, method: String, params: JsonObject): JsonObject {
        send(JsonObject().apply { addProperty("id", id); addProperty("method", method); add("params", params) })
        return response(id)
    }
    override fun close() { try { channel.close() } finally { selector.close() } }
    companion object { const val MAX_MESSAGE = 1024 * 1024 }
}
