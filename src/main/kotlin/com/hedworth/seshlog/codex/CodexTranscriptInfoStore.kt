package com.hedworth.seshlog.codex

import com.google.gson.JsonObject
import com.hedworth.seshlog.cache.InfoStore
import com.hedworth.seshlog.cache.InfoStore.Companion.long
import com.hedworth.seshlog.cache.InfoStore.Companion.string
import com.hedworth.seshlog.model.Activity
import java.time.Instant

/** Persistent metadata-only cache for parsed Codex rollouts (see [InfoStore]). Version 5 separates subagent rollouts. */
private const val CACHE_VERSION = 5

object CodexTranscriptInfoStore : InfoStore<CodexTranscriptInfo>(CACHE_VERSION, ::write, ::read) {
    const val VERSION = CACHE_VERSION
}

private fun write(i: CodexTranscriptInfo, o: JsonObject) {
    o.addProperty("sessionId", i.sessionId)
    o.addProperty("forkedFromId", i.forkedFromId)
    o.addProperty("cwd", i.cwd)
    o.addProperty("gitBranch", i.gitBranch)
    o.addProperty("promptTitle", i.promptTitle)
    i.startedAt?.let { o.addProperty("startedAt", it.toEpochMilli()) }
    o.addProperty("promptCount", i.promptCount)
    o.addProperty("isSubagentRollout", i.isSubagentRollout)
    o.addProperty("activity", i.activity.name)
    i.activityAt?.let { o.addProperty("activityAt", it.toEpochMilli()) }
}

private fun read(o: JsonObject) = CodexTranscriptInfo(
    sessionId = o.string("sessionId"),
    forkedFromId = o.string("forkedFromId"),
    cwd = o.string("cwd"),
    gitBranch = o.string("gitBranch"),
    promptTitle = o.string("promptTitle"),
    startedAt = o.long("startedAt")?.let(Instant::ofEpochMilli),
    promptCount = o.long("promptCount")?.toInt() ?: 0,
    isSubagentRollout = o.string("isSubagentRollout") == "true",
    activity = o.string("activity")?.let { name -> Activity.entries.firstOrNull { it.name == name } } ?: Activity.UNKNOWN,
    activityAt = o.long("activityAt")?.let(Instant::ofEpochMilli),
)
