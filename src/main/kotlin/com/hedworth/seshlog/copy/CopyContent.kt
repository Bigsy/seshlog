package com.hedworth.seshlog.copy

import com.hedworth.seshlog.model.ConversationMessage
import com.hedworth.seshlog.model.Role
import java.io.RandomAccessFile
import java.nio.file.Path

/** Complete content only: a limit or read error must never become a successful partial copy. */
sealed class CopyContent {
    data class Found(val text: String, val detail: String = "") : CopyContent()
    data class Absent(val reason: String = "No assistant reply found.") : CopyContent()
    data class Failed(val reason: String = "Session content could not be read.") : CopyContent()
    data class Unsupported(val reason: String = "This provider does not support copying plans.") : CopyContent()
}

object LastAssistantReader {
    const val MAX_RECORD_BYTES = 8 * 1024 * 1024
    const val MAX_SCAN_BYTES = 128L * 1024 * 1024

    /** Scan backwards in blocks, retaining only one record, without the preview's tail window. */
    fun read(path: Path?, parse: (String) -> ConversationMessage?): CopyContent {
        if (path == null) return CopyContent.Failed()
        return try {
            RandomAccessFile(path.toFile(), "r").use { file ->
                var position = file.length()
                var scanned = 0L
                val block = ByteArray(8192)
                val line = java.io.ByteArrayOutputStream()
                fun message(): String? {
                    val bytes = line.toByteArray()
                    bytes.reverse()
                    line.reset()
                    return parse(String(bytes, Charsets.UTF_8))?.takeIf {
                        it.role == Role.ASSISTANT && it.text.isNotBlank()
                    }?.text
                }
                while (position > 0) {
                    val size = minOf(position, block.size.toLong()).toInt()
                    position -= size
                    file.seek(position)
                    file.readFully(block, 0, size)
                    for (i in size - 1 downTo 0) {
                        if (++scanned > MAX_SCAN_BYTES) return CopyContent.Failed("Transcript exceeds the copy scan limit.")
                        if (block[i] == 10.toByte()) {
                            message()?.let { return CopyContent.Found(it) }
                        } else {
                            if (line.size() >= MAX_RECORD_BYTES) return CopyContent.Failed("Transcript record is too large to copy safely.")
                            line.write(block[i].toInt())
                        }
                    }
                }
                message()?.let { CopyContent.Found(it) } ?: CopyContent.Absent()
            }
        } catch (_: Exception) { CopyContent.Failed() }
    }
}
