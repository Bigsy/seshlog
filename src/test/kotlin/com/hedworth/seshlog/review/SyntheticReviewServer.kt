package com.hedworth.seshlog.review

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Test-only RFC 6455 receiver; fictional protocol data, no model or histories. */
internal class SyntheticReviewServer(val home: Path, private val checkout: Path, val threadId: String) : AutoCloseable {
    enum class Mode { QUEUE, BUSY, REFUSE, LOST_RECEIPT, INTERNAL_ERROR, WRONG_RECEIPT, NOT_LOADED, WRONG_THREAD, WRONG_CHECKOUT, STALL }
    var mode = Mode.QUEUE
    var version = "0.166.0"
    var fragmented = false
    var reportedHome = home
    val requests = CopyOnWriteArrayList<JsonObject>()
    val enqueued = AtomicInteger()
    val received = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private var connection: SocketChannel? = null
    val socket = home.resolve("app-server-control/app-server-control.sock")
    private val server: ServerSocketChannel
    private val thread: Thread
    init {
        Files.createDirectories(socket.parent)
        Files.setPosixFilePermissions(socket.parent, PosixFilePermissions.fromString("rwx------"))
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(UnixDomainSocketAddress.of(socket))
        Files.setPosixFilePermissions(socket, PosixFilePermissions.fromString("rw-------"))
        thread = Thread.ofVirtual().name("synthetic-review-server").start {
            try { server.accept().use { channel -> connection = channel; serve(channel) } } catch (_: Exception) {}
        }
    }
    private fun serve(channel: SocketChannel) {
        val input = Channels.newInputStream(channel)
        val output = Channels.newOutputStream(channel)
        val header = StringBuilder()
        while (!header.endsWith("\r\n\r\n")) {
            check(header.length < 16 * 1024)
            val byte = input.read(); if (byte < 0) throw EOFException()
            header.append(byte.toChar())
        }
        val nonce = header.toString().lineSequence().single { it.startsWith("Sec-WebSocket-Key:") }.substringAfter(':').trim()
        val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
            .digest((nonce + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
        output.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
        output.flush()
        fun frame(bytes: ByteArray, opcode: Int = 1, final: Boolean = true) {
            val out = ByteArrayOutputStream()
            out.write((if (final) 0x80 else 0) or opcode)
            when {
                bytes.size < 126 -> out.write(bytes.size)
                bytes.size <= 65535 -> { out.write(126); out.write(bytes.size ushr 8); out.write(bytes.size) }
                else -> { out.write(127); out.write(ByteBuffer.allocate(8).putLong(bytes.size.toLong()).array()) }
            }
            out.write(bytes); output.write(out.toByteArray()); output.flush()
        }
        fun respond(value: JsonObject) {
            val bytes = value.toString().toByteArray(Charsets.UTF_8)
            if (fragmented) {
                frame(byteArrayOf(1, 2), 9)
                frame(bytes.copyOfRange(0, bytes.size / 2), final = false)
                frame(bytes.copyOfRange(bytes.size / 2, bytes.size), opcode = 0)
            } else frame(bytes)
        }
        while (true) {
            val first = input.read(); if (first < 0) return
            val second = input.read(); if (second < 0) return
            check(second and 0x80 != 0)
            val length = when (val small = second and 127) {
                126 -> ByteBuffer.wrap(input.readNBytes(2)).short.toInt() and 65535
                127 -> ByteBuffer.wrap(input.readNBytes(8)).long.also { check(it in 0..LocalReviewSocket.MAX_MESSAGE.toLong()) }.toInt()
                else -> small
            }
            val mask = input.readNBytes(4)
            val body = input.readNBytes(length)
            check(body.size == length)
            body.indices.forEach { body[it] = (body[it].toInt() xor mask[it % 4].toInt()).toByte() }
            if (first and 15 == 10) continue // Pong is framing, never an agent input.
            check(first and 15 == 1)
            val request = JsonParser.parseString(body.toString(Charsets.UTF_8)).asJsonObject
            requests += request
            if (!request.has("id")) continue
            val response = JsonObject().apply { add("id", request["id"]) }
            val params = request["params"].asJsonObject
            when (request["method"].asString) {
                "initialize" -> response.add("result", JsonObject().apply {
                    addProperty("userAgent", "seshlog-review/$version (synthetic)")
                    addProperty("codexHome", reportedHome.toString())
                })
                "thread/read" -> response.add("result", JsonObject().apply {
                    add("thread", JsonObject().apply {
                        addProperty("id", if (mode == Mode.WRONG_THREAD) "another-thread" else threadId)
                        addProperty("cwd", (if (mode == Mode.WRONG_CHECKOUT) checkout.parent else checkout).toString())
                        add("status", JsonObject().apply { addProperty("type", when (mode) { Mode.BUSY -> "active"; Mode.NOT_LOADED -> "notLoaded"; else -> "idle" }) })
                    })
                })
                "thread/queue/add" -> {
                    check(params["threadId"].asString == threadId)
                    if (mode == Mode.INTERNAL_ERROR) enqueued.incrementAndGet() // A commit can precede an internal error.
                    if (mode == Mode.REFUSE || mode == Mode.INTERNAL_ERROR) response.add("error", JsonObject().apply {
                        addProperty("code", if (mode == Mode.REFUSE) -32600 else -32603)
                        addProperty("message", "Synthetic receiver error")
                    }) else {
                        enqueued.incrementAndGet(); received.countDown()
                        if (mode == Mode.LOST_RECEIPT) return
                        if (mode == Mode.STALL) { release.await(5, TimeUnit.SECONDS); return }
                        response.add("result", JsonObject().apply {
                            add("queuedSubmission", JsonObject().apply {
                                addProperty("id", "synthetic-receipt")
                                addProperty("clientUserMessageId", if (mode == Mode.WRONG_RECEIPT) "different-batch" else params["clientUserMessageId"].asString)
                                add("input", params["input"])
                            })
                        })
                    }
                }
                else -> error("Unexpected synthetic method")
            }
            respond(response)
        }
    }
    override fun close() {
        release.countDown(); connection?.close(); server.close(); thread.join(3000); Files.deleteIfExists(socket)
    }
}
