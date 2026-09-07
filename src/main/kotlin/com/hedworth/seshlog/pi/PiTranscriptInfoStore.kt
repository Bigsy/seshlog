package com.hedworth.seshlog.pi

import com.google.gson.JsonObject
import com.hedworth.seshlog.cache.InfoStore
import com.hedworth.seshlog.cache.InfoStore.Companion.long
import com.hedworth.seshlog.cache.InfoStore.Companion.string
import java.time.Instant

/** Only metadata crosses the persistence boundary; the branch index and messages never do. */
object PiTranscriptInfoStore : InfoStore<PiTranscriptInfo>(1, ::write, ::read)

private fun write(i: PiTranscriptInfo, o: JsonObject) {
    o.addProperty("sessionId", i.sessionId)
    o.addProperty("cwd", i.cwd)
    o.addProperty("explicitTitle", i.explicitTitle)
    o.addProperty("promptTitle", i.promptTitle)
    i.startedAt?.let { o.addProperty("startedAt", it.toEpochMilli()) }
    i.lastActivityAt?.let { o.addProperty("lastActivityAt", it.toEpochMilli()) }
    o.addProperty("promptCount", i.promptCount)
}

private fun read(o: JsonObject) = PiTranscriptInfo(
    o.string("sessionId"), o.string("cwd"), o.string("explicitTitle"), o.string("promptTitle"),
    o.long("startedAt")?.let(Instant::ofEpochMilli), o.long("lastActivityAt")?.let(Instant::ofEpochMilli),
    o.long("promptCount")?.toInt() ?: 0,
)
