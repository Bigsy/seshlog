package com.hedworth.seshlog.sandbox

import com.google.gson.GsonBuilder
import com.hedworth.seshlog.model.AgentKind
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.sql.DriverManager
import java.sql.Types
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.HexFormat
import java.util.UUID

/** Writes [SessionSpec]s in each agent's own storage format, as recent versions of the CLIs do. */
internal class SyntheticHistories(private val layout: SandboxLayout, private val now: Instant) {
    // Pi entries need an explicit null parentId and cleared Claude worktree state is a JSON null.
    private val gson = GsonBuilder().serializeNulls().disableHtmlEscaping().create()

    fun write(specs: List<SessionSpec>): Map<String, String> {
        val ids = specs.associate { it.handle to id(it) }
        for (spec in specs) when (spec.kind) {
            AgentKind.CLAUDE_CODE -> claude(spec, ids)
            AgentKind.CODEX -> codex(spec, ids.getValue(spec.handle))
            AgentKind.PI -> pi(spec, ids.getValue(spec.handle))
            AgentKind.OPENCODE -> Unit
        }
        opencode(specs.filter { it.kind == AgentKind.OPENCODE }, ids)
        return ids
    }

    /** Stable across regeneration, so documentation and diagnostics can refer to one session. */
    private fun id(spec: SessionSpec): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("seshlog-sandbox/${spec.handle}".toByteArray())
        return if (spec.kind == AgentKind.OPENCODE) "ses_" + HexFormat.of().formatHex(digest, 0, 6)
        else UUID.nameUUIDFromBytes(digest).toString()
    }

    /** [count] record times thirty seconds apart, the last [SessionSpec.age] before now. */
    private class Timeline(end: Instant, count: Int) {
        private val start = end.minusSeconds(30L * (count - 1).coerceAtLeast(0))
        private var next = 0
        fun next(): Instant = start.plusSeconds(30L * next++)
        val last: Instant get() = start.plusSeconds(30L * (next - 1).coerceAtLeast(0))
    }

    private fun claude(spec: SessionSpec, ids: Map<String, String>) {
        val id = ids.getValue(spec.handle)
        val directory = layout.claude.resolve("projects").resolve(spec.cwd.toString().replace(Regex("[^A-Za-z0-9]"), "-"))
        val time = Timeline(now.minus(spec.age), (if (spec.metadataOnly) 1 else 0) + spec.turns.sumOf { 2 + 2 * it.tools.size } +
            (if (spec.uncertainLocation) 1 else 0) + (if (spec.continuedIn != null) 1 else 0))
        val records = ArrayList<Map<String, Any?>>()
        var parent: String? = null
        fun message(type: String, content: Any, extra: Map<String, Any?> = emptyMap()) {
            val uuid = UUID.randomUUID().toString()
            records += linkedMapOf("parentUuid" to parent, "isSidechain" to false, "type" to type,
                "message" to mapOf("role" to type, "content" to content), "uuid" to uuid, "timestamp" to time.next().toString(),
                "cwd" to spec.cwd.toString(), "sessionId" to id, "version" to CLAUDE_VERSION) +
                (spec.branch?.let { mapOf("gitBranch" to it) } ?: emptyMap()) + extra
            parent = uuid
        }
        if (spec.metadataOnly) {
            message("user", "<local-command-caveat>Caveat: a synthetic session with no conversation.</local-command-caveat>", mapOf("isMeta" to true))
        }
        spec.turns.forEachIndexed { index, turn ->
            message("user", turn.user)
            turn.tools.forEachIndexed { n, tool ->
                val call = "toolu_${spec.handle.hashCode().toUInt()}_${index}_$n"
                message("assistant", listOf(mapOf("type" to "tool_use", "id" to call, "name" to tool.name, "input" to mapOf("command" to tool.command))))
                message("user", listOf(mapOf("tool_use_id" to call, "type" to "tool_result", "content" to tool.output)))
            }
            message("assistant", listOf(mapOf("type" to "text", "text" to turn.assistant)))
            if (index == 0 && spec.title != null) records += mapOf("type" to "ai-title", "aiTitle" to spec.title, "sessionId" to id)
        }
        spec.customTitle?.let { records += mapOf("type" to "custom-title", "customTitle" to it, "sessionId" to id) }
        if (spec.uncertainLocation) {
            records += mapOf("type" to "worktree-state", "worktreeSession" to null, "sessionId" to id, "timestamp" to time.next().toString())
        }
        spec.continuedIn?.let {
            records += mapOf("type" to "continued-in", "sessionId" to id, "continuedIn" to ids.getValue(it), "timestamp" to time.next().toString())
        }
        writeJsonl(directory.resolve("$id.jsonl"), records, time.last)
        if (spec.subagent.isNotEmpty()) {
            val at = time.last.toString()
            val agent = spec.subagent.flatMap { turn ->
                listOf(mapOf("type" to "user", "sessionId" to id, "timestamp" to at,
                    "message" to mapOf("role" to "user", "content" to turn.user)),
                    mapOf("type" to "assistant", "sessionId" to id, "timestamp" to at,
                        "message" to mapOf("role" to "assistant", "content" to listOf(mapOf("type" to "text", "text" to turn.assistant)))))
            }
            writeJsonl(directory.resolve(id).resolve("subagents").resolve("agent-${id.take(8)}.jsonl"), agent, time.last)
        }
    }

    private fun codex(spec: SessionSpec, id: String) {
        val time = Timeline(now.minus(spec.age), spec.turns.sumOf { 4 + 2 * it.tools.size } + 2)
        val started = time.next()
        val records = ArrayList<Map<String, Any?>>()
        fun record(type: String, payload: Map<String, Any?>, at: Instant = time.next()) {
            records += linkedMapOf("timestamp" to at.toString(), "type" to type, "payload" to payload)
        }
        fun message(role: String, kind: String, text: String) =
            record("response_item", mapOf("type" to "message", "role" to role, "content" to listOf(mapOf("type" to kind, "text" to text))))
        record("session_meta", linkedMapOf("id" to id, "timestamp" to started.toString(), "cwd" to spec.cwd.toString(),
            "originator" to "codex_cli_rs", "cli_version" to CODEX_VERSION, "source" to "cli",
            "git" to mapOf("branch" to spec.branch, "commit_hash" to "0000000000000000000000000000000000000000")), started)
        message("user", "input_text", "<environment_context>\n  <cwd>${spec.cwd}</cwd>\n</environment_context>")
        spec.turns.forEachIndexed { index, turn ->
            record("event_msg", mapOf("type" to "task_started"))
            message("user", "input_text", turn.user)
            turn.tools.forEachIndexed { n, tool ->
                val call = "call_${index}_$n"
                record("response_item", mapOf("type" to "function_call", "name" to tool.name, "call_id" to call,
                    "arguments" to gson.toJson(mapOf("command" to listOf("bash", "-lc", tool.command)))))
                record("response_item", mapOf("type" to "function_call_output", "call_id" to call, "output" to tool.output))
            }
            message("assistant", "output_text", turn.assistant)
            record("event_msg", mapOf("type" to "task_complete"))
        }
        val day = DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneOffset.UTC).format(started)
        val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss").withZone(ZoneOffset.UTC).format(started)
        writeJsonl(layout.codex.resolve("sessions").resolve(day).resolve("rollout-$stamp-$id.jsonl"), records, time.last)
        spec.title?.let {
            val index = layout.codex.resolve("session_index.jsonl")
            Files.writeString(index, gson.toJson(mapOf("id" to id, "thread_name" to it, "updated_at" to time.last.toString())) + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
    }

    private fun pi(spec: SessionSpec, id: String) {
        val time = Timeline(now.minus(spec.age), 1 + spec.turns.size * 2 + (if (spec.title != null) 1 else 0))
        val started = time.next()
        val records = arrayListOf<Map<String, Any?>>(linkedMapOf("type" to "session", "version" to 3, "id" to id,
            "timestamp" to started.toString(), "cwd" to spec.cwd.toString()))
        var parent: String? = null
        fun entry(fields: Map<String, Any?>) {
            val entryId = UUID.randomUUID().toString().take(8)
            records += linkedMapOf<String, Any?>("type" to "message", "id" to entryId, "parentId" to parent, "timestamp" to time.next().toString()) + fields
            parent = entryId
        }
        for (turn in spec.turns) {
            entry(mapOf("message" to mapOf("role" to "user", "content" to turn.user)))
            entry(mapOf("message" to mapOf("role" to "assistant", "content" to listOf(mapOf("type" to "text", "text" to turn.assistant)), "stopReason" to "stop")))
        }
        spec.title?.let { entry(mapOf("type" to "session_info", "name" to it)) }
        val directory = layout.piSessions.resolve("--" + spec.cwd.toString().removePrefix("/").replace(Regex("[/\\\\:]"), "-") + "--")
        writeJsonl(directory.resolve("${started.toString().replace(Regex("[:.]"), "-")}_$id.jsonl"), records, time.last)
    }

    private fun opencode(specs: List<SessionSpec>, ids: Map<String, String>) {
        val file = layout.opencode.resolve("opencode.db")
        Files.createDirectories(file.parent)
        DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA journal_mode = WAL")
                OPENCODE_SCHEMA.forEach(statement::execute)
            }
            connection.autoCommit = false
            val session = connection.prepareStatement("INSERT INTO session VALUES (?, ?, ?, ?, ?, ?, '1.18.25', ?, ?, ?, 'build', ?)")
            val message = connection.prepareStatement("INSERT INTO message VALUES (?, ?, ?, ?, ?)")
            val part = connection.prepareStatement("INSERT INTO part VALUES (?, ?, ?, ?, ?, ?)")
            for (spec in specs) {
                val id = ids.getValue(spec.handle)
                val time = Timeline(now.minus(spec.age), spec.turns.size * 2 + 1)
                val created = time.next()
                var counter = 0
                fun insertMessage(data: Map<String, Any?>, at: Instant, parts: List<Map<String, Any?>>) {
                    val messageId = "msg_${id.removePrefix("ses_")}_${counter++}"
                    message.apply { setString(1, messageId); setString(2, id); setLong(3, at.toEpochMilli()); setLong(4, at.toEpochMilli())
                        setString(5, gson.toJson(data)); addBatch() }
                    parts.forEachIndexed { n, value ->
                        part.apply { setString(1, "prt_${messageId.removePrefix("msg_")}_$n"); setString(2, messageId); setString(3, id)
                            setLong(4, at.toEpochMilli()); setLong(5, at.toEpochMilli()); setString(6, gson.toJson(value)); addBatch() }
                    }
                }
                spec.turns.forEachIndexed { index, turn ->
                    val asked = time.next()
                    insertMessage(mapOf("role" to "user", "time" to mapOf("created" to asked.toEpochMilli()), "agent" to "build"), asked,
                        listOf(mapOf("type" to "text", "text" to turn.user)))
                    val answered = time.next()
                    val tools = turn.tools.mapIndexed { n, tool -> mapOf("type" to "tool", "tool" to tool.name, "callID" to "call_${index}_$n",
                        "state" to mapOf("status" to "completed", "input" to mapOf("command" to tool.command), "output" to tool.output)) }
                    insertMessage(mapOf("role" to "assistant", "time" to mapOf("created" to answered.toEpochMilli(), "completed" to answered.toEpochMilli()),
                        "finish" to "stop"), answered, tools + mapOf("type" to "text", "text" to turn.assistant))
                }
                session.apply {
                    setString(1, id); setString(2, "prj_${spec.cwd.fileName}"); setString(3, spec.parent?.let(ids::getValue))
                    setString(4, spec.handle); setString(5, spec.cwd.toString()); setString(6, spec.title ?: spec.turns.first().user.take(60))
                    setLong(7, created.toEpochMilli()); setLong(8, time.last.toEpochMilli())
                    if (spec.archived) setLong(9, time.last.toEpochMilli()) else setNull(9, Types.INTEGER)
                    setString(10, """{"providerID":"synthetic","modelID":"stub"}"""); addBatch()
                }
            }
            session.executeBatch(); message.executeBatch(); part.executeBatch()
            connection.commit()
        }
    }

    private fun writeJsonl(file: Path, records: List<Map<String, Any?>>, modified: Instant) {
        Files.createDirectories(file.parent)
        Files.writeString(file, records.joinToString("") { gson.toJson(it) + "\n" })
        Files.setLastModifiedTime(file, FileTime.from(modified))
    }

    companion object {
        const val CLAUDE_VERSION = "2.1.300"
        const val CODEX_VERSION = "0.50.0"

        /** The columns Seshlog reads from opencode 1.18, as in fixtures/opencode_fixture.sql. */
        private val OPENCODE_SCHEMA = listOf(
            """CREATE TABLE session (id text PRIMARY KEY, project_id text NOT NULL, parent_id text, slug text NOT NULL,
                directory text NOT NULL, title text NOT NULL, version text NOT NULL, time_created integer NOT NULL,
                time_updated integer NOT NULL, time_archived integer, agent text, model text)""",
            "CREATE INDEX session_parent_idx ON session (parent_id)",
            "CREATE TABLE message (id text PRIMARY KEY, session_id text NOT NULL, time_created integer NOT NULL, time_updated integer NOT NULL, data text NOT NULL)",
            "CREATE INDEX message_session_time_created_id_idx ON message (session_id, time_created, id)",
            """CREATE TABLE part (id text PRIMARY KEY, message_id text NOT NULL, session_id text NOT NULL, time_created integer NOT NULL,
                time_updated integer NOT NULL, data text NOT NULL)""",
            "CREATE INDEX part_session_idx ON part (session_id)",
            "CREATE INDEX part_message_id_id_idx ON part (message_id, id)",
        )
    }
}
