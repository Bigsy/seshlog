package com.hedworth.seshlog.claude

import com.hedworth.seshlog.model.ConversationMessage
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Reads the last N conversation messages of a JSONL transcript without scanning the whole file:
 * the file is read backwards in chunks, split into lines, and each complete line is parsed
 * from the end until enough messages are found. Transcripts can exceed 5 MB, and the preview
 * only needs the tail, so this keeps selection-time cost proportional to what is shown.
 *
 * The line format is the caller's business: [parseLine] turns one line into a message or null.
 * It defaults to Claude Code's format; Codex passes its own parser.
 */
object TranscriptTailReader {
    const val DEFAULT_CHUNK_SIZE = 256 * 1024

    /** Last [count] messages in chronological order. Never throws for bad content; I/O errors propagate. */
    fun lastMessages(
        path: Path,
        count: Int,
        chunkSize: Int = DEFAULT_CHUNK_SIZE,
        parseLine: (String) -> ConversationMessage? = ConversationMessages::parseLine,
    ): List<ConversationMessage> {
        if (count <= 0) return emptyList()
        val found = ArrayList<ConversationMessage>(count)
        FileChannel.open(path, StandardOpenOption.READ).use { channel ->
            var position = channel.size()
            // Bytes after `position` that have been read but belong to a line whose start we have
            // not seen yet (the first, possibly partial, line of the previous chunk).
            var carry = ByteArray(0)
            while (position > 0 && found.size < count) {
                val toRead = minOf(chunkSize.toLong(), position).toInt()
                position -= toRead
                val buf = ByteBuffer.allocate(toRead)
                channel.read(buf, position)
                val bytes = buf.array()
                val joined = if (carry.isEmpty()) bytes else bytes + carry

                // Split on '\n' from the end; the segment before the first '\n' is incomplete
                // unless we are at the start of the file.
                var end = joined.size
                var i = joined.size - 1
                while (i >= 0 && found.size < count) {
                    if (joined[i] == '\n'.code.toByte()) {
                        offer(joined, i + 1, end, found, parseLine)
                        end = i
                    }
                    i--
                }
                if (found.size >= count) break
                if (position == 0L) {
                    offer(joined, 0, end, found, parseLine)
                    carry = ByteArray(0)
                } else {
                    carry = joined.copyOfRange(0, end)
                }
            }
        }
        found.reverse()
        return found
    }

    private fun offer(
        bytes: ByteArray, from: Int, to: Int, into: MutableList<ConversationMessage>,
        parseLine: (String) -> ConversationMessage?,
    ) {
        if (to <= from) return
        val line = String(bytes, from, to - from, StandardCharsets.UTF_8)
        if (line.isBlank()) return
        parseLine(line)?.let { into += it }
    }
}
