package com.hedworth.seshlog.sandbox

import com.hedworth.seshlog.index.SearchSources
import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.AgentKind.CLAUDE_CODE
import com.hedworth.seshlog.model.AgentKind.CODEX
import com.hedworth.seshlog.model.AgentKind.OPENCODE
import com.hedworth.seshlog.model.AgentKind.PI
import java.nio.file.Path
import java.time.Duration

internal data class ToolUse(val name: String, val command: String, val output: String)

internal data class Turn(val user: String, val assistant: String, val tools: List<ToolUse> = emptyList())

/**
 * One fictional session. [title] is the provider's own title (Claude ai-title, Codex thread name,
 * opencode title, Pi session name); [customTitle] is Claude's `/rename`. [age] dates the last record.
 */
internal data class SessionSpec(
    val handle: String,
    val kind: AgentKind,
    val cwd: Path,
    val branch: String?,
    val age: Duration,
    val turns: List<Turn>,
    val purpose: String,
    val title: String? = null,
    val customTitle: String? = null,
    /** Claude: dialogue in a subagent transcript, searchable only as Tool activity. */
    val subagent: List<Turn> = emptyList(),
    /** Claude: a cleared worktree state leaves the recorded directory uncertain. */
    val uncertainLocation: Boolean = false,
    /** Claude: handle of the session this one continues in. */
    val continuedIn: String? = null,
    /** Claude: a title and directory but no conversation. */
    val metadataOnly: Boolean = false,
    val archived: Boolean = false,
    /** opencode: handle of the parent session; children are subagents and stay hidden. */
    val parent: String? = null,
) {
    /** Sessions a provider scan returns; archived and child opencode sessions are hidden by design. */
    val listed: Boolean get() = !archived && parent == null
}

/** A documented example query, asserted against the generated histories by SyntheticSandboxTest. */
internal data class QueryCheck(
    val query: String,
    val sources: SearchSources,
    /** Handle of the expected first hit; null when no session may match. */
    val first: String?,
    val note: String,
    val excludes: Set<String> = emptySet(),
)

/** Fictional checkouts and directories. Nothing here exists outside the sandbox root. */
internal class SandboxProjects(workspace: Path, codexHome: Path) {
    val harbor: Path = workspace.resolve("harbor")
    val harborApi: Path = harbor.resolve("services/api")
    val harborRetry: Path = workspace.resolve("harbor-retry")
    val harborHotfix: Path = workspace.resolve("harbor-hotfix")
    val harborSpike: Path = workspace.resolve("harbor-spike")
    val lantern: Path = workspace.resolve("lantern")
    val notes: Path = workspace.resolve("notes")
    val notesDrafts: Path = notes.resolve("drafts")
    val archive: Path = workspace.resolve("archive")
    val goneScratch: Path = workspace.resolve("gone-scratch")
    val protectedRoot: Path = codexHome

    fun named(): Map<String, Path> = linkedMapOf(
        "harbor" to harbor, "harbor/services/api" to harborApi, "harbor-retry" to harborRetry,
        "harbor-hotfix" to harborHotfix, "harbor-spike (removed)" to harborSpike, "lantern" to lantern,
        "notes" to notes, "notes/drafts" to notesDrafts, "archive" to archive,
        "gone-scratch (never created)" to goneScratch, "codex data dir (protected)" to protectedRoot,
    )
}

internal object SyntheticScenarios {
    const val RETRY_BRANCH = "feature/retry-backoff"
    const val SPIKE_BRANCH = "spike/old-parser"
    const val LARGE_TURNS = 1_100
    const val EARLY_MARKER = "importer-baseline-alpha"
    const val LATE_MARKER = "importer-final-omega"

    private fun minutes(value: Long) = Duration.ofMinutes(value)
    private fun hours(value: Long) = Duration.ofHours(value)
    private fun days(value: Long) = Duration.ofDays(value)

    fun sessions(p: SandboxProjects, filler: Int): List<SessionSpec> = scenarios(p) + filler(p, filler)

    private fun scenarios(p: SandboxProjects): List<SessionSpec> = listOf(
        SessionSpec("retry-backoff", CLAUDE_CODE, p.harborRetry, RETRY_BRANCH, minutes(25), purpose =
            "Unopened linked worktree; natural-language search", title = "Retry backoff with jitter for the uploader", turns = listOf(
            Turn("The uploader keeps hammering the storage API after a 503. Can we add exponential backoff with jitter and a retry budget?",
                "I'll add a RetryPolicy with exponential backoff, full jitter and a budget of five attempts per request.",
                listOf(ToolUse("Edit", "edit src/retry/Backoff.kt", "Updated src/retry/Backoff.kt (42 lines)"))),
            Turn("Also log when the budget is exhausted, but don't spam the log.",
                "Done: one WARN per request when the retry budget runs out, rate-limited to a line per minute."),
        )),
        SessionSpec("db-locked", CLAUDE_CODE, p.harbor, "main", hours(3), purpose =
            "Quoted phrase with an unquoted boost term", turns = listOf(
            Turn("Tests fail intermittently with \"database is locked\" when two workers write the cache at once.",
                "SQLite reports SQLITE_BUSY while another connection holds the write lock. Set busy_timeout to 5000 ms and keep transactions short.",
                listOf(ToolUse("Bash", "./gradlew test --tests '*CacheWriterTest*'",
                    "CacheWriterTest > concurrentWrites FAILED\n    org.sqlite.SQLiteException: [SQLITE_BUSY] The database file is locked"))),
            Turn("What timeout do the other services use?", "The api service waits two seconds; the worker waits five."),
        )),
        SessionSpec("subagent-review", CLAUDE_CODE, p.harbor, "main", days(1), purpose =
            "Claude subagent result, searchable only with Tool activity", title = "Review the cache eviction change", turns = listOf(
            Turn("Review the cache eviction change before I merge it.", "I'll ask a reviewer agent to check the eviction edge cases."),
            Turn("What did the reviewer find?", "One stale-entry problem; the details are in the reviewer's report."),
        ), subagent = listOf(
            Turn("Check eviction when the ledger and the index disagree.",
                "Found a quokka ledger mismatch: entries evicted from the index stay in the ledger until restart."),
        )),
        SessionSpec("tool-only-error", CLAUDE_CODE, p.lantern, "main", days(2), purpose =
            "Error code that appears only in a tool result", title = "Fix the lantern build on CI", turns = listOf(
            Turn("CI is red on lantern, can you look?", "Running the build to reproduce.", listOf(ToolUse("Bash", "npm run build",
                "error TS2589: Type instantiation is excessively deep and possibly infinite.\n  at src/theme/tokens.ts:88:14\nERR_LANTERN_TOKENS_0xBEEF"))),
            Turn("Can you fix it without loosening the types?", "The recursive token type now has an explicit depth limit of four levels."),
        )),
        SessionSpec("unicode", CLAUDE_CODE, p.notes, null, days(6), purpose =
            "Unicode, accents, CJK and emoji highlighting", title = "Café menu translations", turns = listOf(
            Turn("Translate the menu headings: café, naïve, Größe and 東京. Keep the emoji in the 🚀 Launch day banner.",
                "café → coffee shop; naïve → unsophisticated; Größe → size; 東京 → Tokyo. The banner stays \"🚀 Launch day\"."),
        )),
        SessionSpec("custom-title", CLAUDE_CODE, p.harbor, "main", hours(2), purpose =
            "Claude /rename title wins over the AI title", title = "Release notes draft", customTitle = "release-checklist-0.9", turns = listOf(
            Turn("Draft the release checklist for 0.9: changelog, version bump, plugin verifier and a manual upload.",
                "Checklist: update CHANGELOG.md, bump pluginVersion, run verifyPlugin, then upload by hand."),
        )),
        SessionSpec("uncertain-location", CLAUDE_CODE, p.harbor, "main", hours(4), uncertainLocation = true, purpose =
            "Uncertain recorded directory (worktree state cleared)", title = "Worktree cleanup after the spike", turns = listOf(
            Turn("Clean up the spike worktree now that the parser work is abandoned.", "Removed the worktree; the branch is kept for reference."),
        )),
        SessionSpec("missing-worktree", CLAUDE_CODE, p.harborSpike, SPIKE_BRANCH, days(9), purpose =
            "Linked worktree removed after the session", title = "Spike: hand-written parser for config files", turns = listOf(
            Turn("Try a hand-written recursive descent parser for the config format.", "The spike parses every sample file; error messages need work."),
        )),
        SessionSpec("nested-api", CLAUDE_CODE, p.harborApi, "main", minutes(50), purpose =
            "Session in a subdirectory of a main checkout", title = "Paginate the orders endpoint", turns = listOf(
            Turn("Paginate GET /orders in OrdersHandler.kt with a cursor rather than offsets.",
                "Added an opaque cursor built from the last order id; page size defaults to 50."),
        )),
        SessionSpec("symbols", CLAUDE_CODE, p.lantern, "main", hours(5), purpose =
            "Exact symbols, error codes, paths and C++/C#", title = "Port the colour parser from C++ and C#", turns = listOf(
            Turn("Port parse_hex_colour() from the C++ prototype and the C# tool into ColourParser.kt. Error E0277 showed up in the Rust spike too.",
                "ColourParser.parseHex(\"#7FFF00\") now matches both ports, and it accepts the 0x7FFF00 form too. " +
                    "The file is src/main/kotlin/com/lantern/colour/ColourParser.kt."),
        )),
        SessionSpec("phrase-split", CLAUDE_CODE, p.lantern, "main", days(1).plusHours(3), purpose =
            "A phrase split across two messages must not match", title = "Connection pool sizing", turns = listOf(
            Turn("Why does the API slow down under load? The metrics show the connection pool",
                "exhausted warnings appear every few minutes because the pool is sized at four connections."),
        )),
        SessionSpec("large-history", CLAUDE_CODE, p.archive, null, days(12), purpose =
            "History beyond the retention limits: partial coverage", title = "Bulk import performance log", turns = largeHistory()),
        SessionSpec("metadata-only", CLAUDE_CODE, p.notes, null, days(20), metadataOnly = true, purpose =
            "Title and directory with no conversation", customTitle = "Metadata-only: onboarding checklist", turns = emptyList()),
        SessionSpec("continued-original", CLAUDE_CODE, p.harbor, "main", days(8), continuedIn = "continued-successor", purpose =
            "Continued session, collapsed into its successor", title = "Search index migration (part 1)", turns = listOf(
            Turn("Plan the search index migration to the new schema.", "Step one copies postings; step two swaps the tables."),
        )),
        SessionSpec("continued-successor", CLAUDE_CODE, p.harbor, "main", days(7), purpose =
            "Successor of a continued session", title = "Search index migration (part 2)", turns = listOf(
            Turn("Continue the index migration: swap the tables.", "Tables swapped inside one transaction; the old ones are kept for a day."),
        )),

        SessionSpec("codex-lantern-migration", CODEX, p.lantern, "main", minutes(30), purpose =
            "Codex thread name; filename query", title = "Lantern schema migration", turns = listOf(
            Turn("Write the migration that adds a theme_preference column defaulting to 'system'.",
                "Added V7__theme_preference.sql with DEFAULT 'system' and a backfill. The old database locked once; a longer timeout fixed it.",
                listOf(ToolUse("shell", "cat db/migrations/V6__users.sql", "CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL);"))),
        )),
        SessionSpec("codex-gradle-resolve", CODEX, p.harbor, "main", hours(6), purpose =
            "Dependency error only in tool output", turns = listOf(
            Turn("The build fails after bumping sqlite-jdbc in build.gradle.kts.", "Checking the dependency resolution.",
                listOf(ToolUse("shell", "./gradlew dependencies --configuration runtimeClasspath",
                    "Could not resolve org.xerial:sqlite-jdbc:3.99.0.\n  > No matching variant was found."))),
            Turn("Pin it back to the last good version.", "Pinned sqlite-jdbc to 3.53.4.0; the build resolves again."),
        )),
        SessionSpec("codex-retry-metrics", CODEX, p.harborRetry, RETRY_BRANCH, days(1).plusHours(1), purpose =
            "Second agent in the unopened worktree", turns = listOf(
            Turn("Add retry metrics: a retry_attempts_total counter and a retry_delay_seconds histogram.",
                "Both metrics are registered and labelled by endpoint."),
        )),
        SessionSpec("codex-detached", CODEX, p.harborHotfix, null, days(3), purpose =
            "Detached HEAD checkout", turns = listOf(
            Turn("Bisect the flaky checksum test on the hotfix checkout.", "The flake started when the checksum moved off the IO thread."),
        )),
        SessionSpec("codex-gone", CODEX, p.goneScratch, null, days(15), purpose =
            "Recorded directory that never existed", turns = listOf(
            Turn("Scratch experiment: benchmark three JSON parsers.", "The streaming parser was fastest on large inputs."),
        )),
        SessionSpec("codex-protected", CODEX, p.protectedRoot, null, days(10), purpose =
            "Session inside an agent data root", turns = listOf(
            Turn("Explain what is in this config directory.", "It holds rollouts and a thread index; nothing here should be edited by hand."),
        )),

        SessionSpec("opencode-notes", OPENCODE, p.notes, null, minutes(40), purpose =
            "opencode title in a plain directory", title = "Draft onboarding notes for new contributors", turns = listOf(
            Turn("Draft onboarding notes for new contributors: setup, tests, and where to ask questions.",
                "Drafted three sections with a short setup checklist."),
        )),
        SessionSpec("opencode-harbor-tool", OPENCODE, p.harbor, null, days(2).plusHours(2), purpose =
            "opencode tool output", title = "Trace the slow startup", turns = listOf(
            Turn("Startup got slow this week. Find out where the time goes.", "Profiling startup now.",
                listOf(ToolUse("bash", "./scripts/profile-startup.sh", "startup took 8.4 s; 6.1 s spent in ClasspathScanner.scanAll"))),
            Turn("Cache the scan between runs.", "The classpath index is now cached and startup is under two seconds."),
        )),
        SessionSpec("opencode-archived", OPENCODE, p.harbor, null, days(30), archived = true, purpose =
            "Archived in opencode: hidden unless shown in settings", title = "Archived: old deploy notes", turns = listOf(
            Turn("Summarise the old deploy notes.", "They describe a manual deploy that no longer exists."),
        )),
        SessionSpec("opencode-child", OPENCODE, p.harbor, null, days(2).plusHours(1), parent = "opencode-harbor-tool", purpose =
            "opencode subagent child: never listed", title = "Child: inspect the classpath scanner", turns = listOf(
            Turn("Inspect the classpath scanner.", "It walks every jar on each start."),
        )),

        SessionSpec("pi-dark-theme", PI, p.lantern, null, minutes(15), purpose =
            "Pi session name; dialogue-only provider", title = "Lantern dark theme contrast", turns = listOf(
            Turn("Check the contrast ratio of the muted text in dark mode.",
                "Muted text is 3.9:1 on #1E1F22; raising it to #9DA0A8 gives 4.6:1, which passes WCAG AA."),
        )),
        SessionSpec("pi-drafts", PI, p.notesDrafts, null, days(4), purpose =
            "Nested plain directory", turns = listOf(
            Turn("Outline a blog post about local-first search.", "Three parts: why local, how BM25 works, and what stays on disk."),
        )),
        SessionSpec("pi-standup", PI, p.harbor, null, hours(1), purpose =
            "Pi session in the main checkout", turns = listOf(
            Turn("Summarise yesterday's retry changes for the standup.", "Backoff with jitter landed; metrics are in review."),
        )),
    )

    private fun largeHistory(): List<Turn> = (1..LARGE_TURNS).map { n ->
        val marker = when (n) { 1 -> " $EARLY_MARKER"; LARGE_TURNS -> " $LATE_MARKER"; else -> "" }
        Turn("Import batch $n of $LARGE_TURNS from the legacy archive and report throughput.$marker " + "Rows are validated before insert. ".repeat(4),
            "Batch $n imported: 5,000 rows in ${200 + n % 37} ms with no rejected rows. " + "Indexes were rebuilt after the batch. ".repeat(4))
    }

    private val verbs = listOf("Tidy", "Document", "Profile", "Refactor", "Investigate", "Speed up", "Harden", "Rename", "Split", "Simplify")
    private val objects = listOf("the scheduler", "log rotation", "the settings page", "CSV export", "the release script",
        "error messages", "the login flow", "snapshot tests", "the config loader", "the metrics endpoint", "the CLI help text", "feature flags")

    /** Plain sessions so lists scroll and the switcher's 200-row limit is reached. */
    private fun filler(p: SandboxProjects, count: Int): List<SessionSpec> = (1..count).map { n ->
        val kind = when (n % 20) { in 0..11 -> CLAUDE_CODE; in 12..16 -> CODEX; in 17..18 -> PI; else -> OPENCODE }
        val (cwd, branch) = listOf(p.harbor to "main", p.lantern to "main", p.harborRetry to RETRY_BRANCH, p.notes to null)[n % 4]
        val title = "${verbs[n % verbs.size]} ${objects[(n / verbs.size) % objects.size]} (filler ${"%03d".format(n)})"
        val age = minutes(70L + (n * 523L) % (120L * 24 * 60))
        SessionSpec("filler-%03d".format(n), kind, cwd, branch.takeIf { kind == CLAUDE_CODE || kind == CODEX }, age,
            title = title.takeIf { n % 3 != 0 }, purpose = "Filler", turns = listOf(
                Turn("$title. Make the change small and add one focused test.",
                    "Done: ${title.replaceFirstChar(Char::lowercase)} with one focused test; nothing else changed."),
            ))
    }

    fun queries(): List<QueryCheck> = listOf(
        QueryCheck("uploader keeps retrying storage forever", SearchSources.DEFAULT, "retry-backoff",
            "Natural language: two of the five words never appear"),
        QueryCheck("\"database is locked\" timeout", SearchSources.DEFAULT, "db-locked",
            "The phrase is required; Codex's \"database locked\" is not the phrase", excludes = setOf("codex-lantern-migration")),
        QueryCheck("\"connection pool\"", SearchSources.DEFAULT, "phrase-split", "Phrase inside one message"),
        QueryCheck("\"connection pool exhausted\"", SearchSources.DEFAULT, null, "The phrase spans two messages: no match"),
        QueryCheck("ColourParser.parseHex", SearchSources.DEFAULT, "symbols", "Exact symbol"),
        QueryCheck("C++", SearchSources.DEFAULT, "symbols", "Punctuated language name"),
        QueryCheck("C#", SearchSources.DEFAULT, "symbols", "Punctuated language name"),
        QueryCheck("E0277", SearchSources.DEFAULT, "symbols", "Error code"),
        QueryCheck("src/main/kotlin/com/lantern/colour/ColourParser.kt", SearchSources.DEFAULT, "symbols", "Full path"),
        QueryCheck("V7__theme_preference.sql", SearchSources.DEFAULT, "codex-lantern-migration", "Filename in a Codex reply"),
        QueryCheck("build.gradle.kts", SearchSources.DEFAULT, "codex-gradle-resolve", "Filename in a Codex prompt"),
        QueryCheck("Größe", SearchSources.DEFAULT, "unicode", "Accented word"),
        QueryCheck("東京", SearchSources.DEFAULT, "unicode", "CJK"),
        QueryCheck("\"🚀 Launch day\"", SearchSources.DEFAULT, "unicode", "Emoji inside a phrase"),
        QueryCheck("ERR_LANTERN_TOKENS_0xBEEF", SearchSources.DEFAULT, null, "Tool-only error: hidden with tools off"),
        QueryCheck("ERR_LANTERN_TOKENS_0xBEEF", SearchSources.ALL, "tool-only-error", "Tool-only error: found as a tool result"),
        QueryCheck("Could not resolve org.xerial", SearchSources.ALL, "codex-gradle-resolve", "Codex tool output"),
        QueryCheck("quokka", SearchSources.DEFAULT, null, "Subagent dialogue: hidden with tools off"),
        QueryCheck("quokka", SearchSources.ALL, "subagent-review", "Subagent dialogue: found as Tool activity"),
        QueryCheck("ClasspathScanner.scanAll", SearchSources.DEFAULT, null, "opencode tool output: hidden with tools off"),
        QueryCheck("ClasspathScanner.scanAll", SearchSources.ALL, "opencode-harbor-tool", "opencode tool output"),
        QueryCheck("WCAG contrast", SearchSources.DEFAULT, "pi-dark-theme", "Pi reply"),
        QueryCheck(EARLY_MARKER, SearchSources.DEFAULT, "large-history", "Inside the retained part of a long history"),
        QueryCheck(LATE_MARKER, SearchSources.DEFAULT, null, "Beyond the retention limit: no match, partial coverage"),
        QueryCheck("onboarding checklist", SearchSources.METADATA_ONLY, "metadata-only", "Titles & paths only"),
    )
}
