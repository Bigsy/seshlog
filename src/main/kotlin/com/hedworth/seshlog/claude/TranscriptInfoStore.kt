package com.hedworth.seshlog.claude

import com.google.gson.JsonObject
import com.hedworth.seshlog.cache.InfoStore
import com.hedworth.seshlog.cache.InfoStore.Companion.long
import com.hedworth.seshlog.cache.InfoStore.Companion.string
import java.time.Instant

/**
 * Persistent metadata-only cache for parsed Claude Code transcripts (see [InfoStore]). Version 1
 * stored the raw first prompt; those caches are dropped rather than migrated.
 */
private const val CACHE_VERSION = 2

object TranscriptInfoStore : InfoStore<TranscriptInfo>(CACHE_VERSION, ::write, ::read) {
    const val VERSION = CACHE_VERSION
}

private fun write(i: TranscriptInfo, o: JsonObject) {
    o.addProperty("sessionId", i.sessionId)
    o.addProperty("cwd", i.cwd)
    o.addProperty("gitBranch", i.gitBranch)
    o.addProperty("claudeVersion", i.version)
    o.addProperty("promptTitle", i.promptTitle)
    o.addProperty("aiTitle", i.aiTitle)
    o.addProperty("customTitle", i.customTitle)
    i.startedAt?.let { o.addProperty("startedAt", it.toEpochMilli()) }
    o.addProperty("promptCount", i.promptCount)
}

private fun read(o: JsonObject) = TranscriptInfo(
    sessionId = o.string("sessionId"),
    cwd = o.string("cwd"),
    gitBranch = o.string("gitBranch"),
    version = o.string("claudeVersion"),
    promptTitle = o.string("promptTitle"),
    aiTitle = o.string("aiTitle"),
    customTitle = o.string("customTitle"),
    startedAt = o.long("startedAt")?.let { Instant.ofEpochMilli(it) },
    promptCount = o.long("promptCount")?.toInt() ?: 0,
)
