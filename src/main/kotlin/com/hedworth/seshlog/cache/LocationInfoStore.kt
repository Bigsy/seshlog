package com.hedworth.seshlog.cache

import com.google.gson.JsonObject
import com.hedworth.seshlog.cache.InfoStore.Companion.long
import com.hedworth.seshlog.cache.InfoStore.Companion.string
import com.hedworth.seshlog.model.LocationSource
import com.hedworth.seshlog.model.SessionLocation
import java.time.Instant

/** Metadata only: never serialize envelopes, tool arguments, or outputs. */
object LocationInfoStore {
    fun write(location: SessionLocation?, out: JsonObject) {
        if (location == null) return
        val value = JsonObject()
        value.addProperty("source", location.source.name)
        value.addProperty("record", location.record)
        location.timestamp?.let { value.addProperty("timestamp", it.toString()) }
        value.addProperty("uncertain", location.uncertain)
        value.addProperty("explanation", location.explanation)
        out.add("location", value)
    }

    fun read(obj: JsonObject): SessionLocation? {
        val value = obj.get("location")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val source = LocationSource.entries.firstOrNull { it.name == value.string("source") } ?: return null
        return SessionLocation(source, value.long("record") ?: 0,
            value.string("timestamp")?.let { runCatching { Instant.parse(it) }.getOrNull() },
            value.string("uncertain") == "true", value.string("explanation"))
    }
}
