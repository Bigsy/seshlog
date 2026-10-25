package com.hedworth.seshlog.claude

import com.google.gson.JsonObject
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.intellij.openapi.diagnostic.logger
import java.io.StringReader

/** Reads the envelope, skipping arbitrarily large messages/tool output without a JSON tree. */
internal object ClaudeEnvelope {
    private val strings = setOf("type", "sessionId", "cwd", "gitBranch", "version", "timestamp")

    fun read(line: String): JsonObject? = try {
        JsonReader(StringReader(line)).use { reader ->
            val out = JsonObject()
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                when {
                    name in strings && reader.peek() == JsonToken.STRING -> out.addProperty(name, reader.nextString())
                    name == "isSidechain" && reader.peek() == JsonToken.BOOLEAN -> out.addProperty(name, reader.nextBoolean())
                    name == "worktreeSession" && reader.peek() == JsonToken.NULL -> { reader.nextNull(); out.add(name, null) }
                    name == "worktreeSession" && reader.peek() == JsonToken.BEGIN_OBJECT -> {
                        val state = JsonObject()
                        reader.beginObject()
                        while (reader.hasNext()) {
                            val field = reader.nextName()
                            if (field == "worktreePath" && reader.peek() == JsonToken.STRING) state.addProperty(field, reader.nextString())
                            else reader.skipValue()
                        }
                        reader.endObject()
                        out.add(name, state)
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            if (reader.peek() == JsonToken.END_DOCUMENT) out else null
        }
    } catch (e: Exception) {
        logger<ClaudeEnvelope>().debug("Skipping malformed Claude envelope", e)
        null
    }
}
