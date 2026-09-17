package com.hedworth.seshlog.copy

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path

/** Shared bounds and exact-text handling for explicit provider plan records. */
object PlanReader {
    const val MAX_PLAN_BYTES = 2 * 1024 * 1024
    const val MAX_RECORD_CHARS = 8 * 1024 * 1024
    const val MAX_SOURCE_CHARS = 128L * 1024 * 1024

    fun content(text: String?): CopyContent = when {
        text == null -> CopyContent.Failed("The latest plan record is invalid.")
        text.isBlank() -> CopyContent.Absent("The latest plan is empty.")
        text.toByteArray(Charsets.UTF_8).size > MAX_PLAN_BYTES -> CopyContent.Failed("The latest plan exceeds the 2 MiB copy limit.")
        else -> CopyContent.Found(text)
    }

    fun file(reference: String?): CopyContent {
        if (reference == null) return CopyContent.Failed("The latest plan has no readable content or file reference.")
        return try {
            val path = Path.of(reference)
            if (!path.isAbsolute || !Files.isRegularFile(path)) return CopyContent.Failed("The referenced plan file is missing or unreadable.")
            val bytes = Files.newInputStream(path).use { it.readNBytes(MAX_PLAN_BYTES + 1) }
            if (bytes.size > MAX_PLAN_BYTES) return CopyContent.Failed("The latest plan exceeds the 2 MiB copy limit.")
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            val result = content(decoder.decode(ByteBuffer.wrap(bytes)).toString())
            if (result is CopyContent.Found) result.copy(detail = " Current contents of the referenced plan file.") else result
        } catch (_: Exception) { CopyContent.Failed("The referenced plan file could not be read.") }
    }

    /** Stream records with bounded allocation. A limit never returns an older candidate. */
    fun scan(path: Path?, accept: (String) -> Unit): CopyContent.Failed? {
        if (path == null) return CopyContent.Failed()
        return try {
            Files.newBufferedReader(path).use { reader ->
                val line = StringBuilder()
                val buffer = CharArray(8192)
                var source = 0L
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    source += count
                    if (source > MAX_SOURCE_CHARS) return CopyContent.Failed("Transcript exceeds the plan scan limit.")
                    for (i in 0 until count) {
                        if (buffer[i] == '\n') { accept(line.toString()); line.setLength(0) }
                        else {
                            if (line.length >= MAX_RECORD_CHARS) return CopyContent.Failed("Transcript record exceeds the plan scan limit.")
                            line.append(buffer[i])
                        }
                    }
                }
                if (line.isNotEmpty()) accept(line.toString())
            }
            null
        } catch (_: Exception) { CopyContent.Failed() }
    }
}
