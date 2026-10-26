package com.hedworth.seshlog.index.lexical

import com.hedworth.seshlog.index.*
import com.hedworth.seshlog.model.AgentKind
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.util.Properties
import org.sqlite.JDBC
import org.sqlite.ProgressHandler

/**
 * Provider-aware identities are used at every search boundary, without migrating organisation
 * settings.
 */
data class SearchSessionKey(val kind: AgentKind, val id: String)

data class LexicalPassage(
    val entry: String,
    val start: Int,
    val end: Int,
    val hash: String,
    val text: String,
    val source: SearchEntrySource = SearchEntrySource.ASSISTANT,
)

data class LexicalScore(
    val key: SearchSessionKey,
    val score: Double,
    val entry: String?,
    val start: Int,
    val end: Int,
    val hash: String?,
    val source: SearchMatchSource,
    val variant: SearchMetadataVariant?,
)

data class SavedSource(
    val stamp: String?,
    val fingerprint: String?,
    val metadata: String,
    val partial: Boolean,
)

/**
 * Contentless FTS5 owns matching/statistics/BM25. Raw inputs exist only as transaction parameters.
 * A single caller serializes this connection. SQLite DELETE/FULL journaling bounds sidecars; the
 * owner holds a process lease for the entire connection lifetime (including maintenance).
 */
class LexicalSearchStore
private constructor(
    private val db: Connection,
    val path: Path?,
    private val lease: SearchStoreLease?,
) : AutoCloseable {
    private var sourceSnapshot: MutableMap<SearchSessionKey, SavedSource>? = null
    private var bodyCountCache: Int? = null
    private var sourceCountCache: Int? = null

    fun source(key: SearchSessionKey): SavedSource? {
        sourceSnapshot?.let {
            return it[key]
        }
        return db.prepareStatement(
                "SELECT stamp,fingerprint,metadata,partial FROM sources WHERE kind=? AND session=?"
            )
            .use {
                it.setString(1, key.kind.name)
                it.setString(2, key.id)
                it.executeQuery().use { r ->
                    if (!r.next()) null
                    else
                        SavedSource(r.getString(1), r.getString(2), r.getString(3), r.getBoolean(4))
                }
            }
    }

    fun hasRoom(passages: Int, key: SearchSessionKey): Boolean {
        val oldCount =
            db.prepareStatement(
                    "SELECT count(*) FROM associations WHERE kind=? AND session=? AND field IN ($BODY_FIELDS_SQL)"
                )
                .use { s ->
                    s.setString(1, key.kind.name)
                    s.setString(2, key.id)
                    s.executeQuery().use {
                        it.next()
                        it.getInt(1)
                    }
                }
        return passageCount.toLong() - oldCount + passages <= MAX_PASSAGES &&
            (source(key) != null || sourceCount < MAX_SOURCES)
    }

    /** Only a successful complete provider scan may retire previously validated local rows. */
    fun pruneMissing(live: Set<SearchSessionKey>, completeKinds: Set<AgentKind>) {
        val retired =
            db.createStatement().use { s ->
                s.executeQuery("SELECT kind,session FROM sources WHERE stamp IS NOT NULL").use { r
                    ->
                    buildList {
                        while (r.next()) {
                            val key =
                                SearchSessionKey(AgentKind.valueOf(r.getString(1)), r.getString(2))
                            if (key.kind in completeKinds && key !in live) add(key)
                        }
                    }
                }
            }
        retired.forEach(::delete)
    }

    fun clear() {
        transaction {
            FIELDS.forEach { db.createStatement().use { s -> s.execute("DELETE FROM $it") } }
            db.createStatement().use {
                it.execute("DELETE FROM associations")
                it.execute("DELETE FROM sources")
            }
        }
        sourceSnapshot = null
        bodyCountCache = 0
        sourceCountCache = 0
        db.createStatement().use { it.execute("VACUUM") }
    }

    /** A consistent SQLite snapshot, never a copy of an active main file. */
    fun export(destination: Path) {
        require(path?.toAbsolutePath()?.normalize() != destination.toAbsolutePath().normalize())
        // Create exclusively before entering cleanup: a racing file is never ours to delete.
        try {
            Files.createFile(
                destination,
                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")
                ),
            )
        } catch (_: UnsupportedOperationException) {
            Files.createFile(destination)
        }
        try {
            db.prepareStatement("VACUUM INTO ?").use {
                it.setString(1, destination.toString())
                it.execute()
            }
            privatePermissions(destination)
            // Stamps are host-specific; imported rows must validate portable raw-source
            // fingerprints.
            JDBC.createConnection("jdbc:sqlite:$destination", Properties()).use { exported ->
                exported.createStatement().use { it.execute("UPDATE sources SET stamp=NULL") }
            }
        } catch (t: Throwable) {
            Files.deleteIfExists(destination)
            throw t
        }
    }

    /** Untrusted snapshots may contain only this exact schema; never execute imported SQL. */
    fun importSnapshot(snapshot: Path, cancelled: () -> Boolean = { false }): Int {
        require(Files.size(snapshot) <= MAX_DISK_BYTES)
        require(
            !Files.exists(snapshot.resolveSibling(snapshot.fileName.toString() + "-wal")) &&
                !Files.exists(snapshot.resolveSibling(snapshot.fileName.toString() + "-journal"))
        ) {
            "Choose a completed export, not a live database."
        }
        return JDBC.createConnection(
                "jdbc:sqlite:${snapshot.toAbsolutePath().toUri()}?mode=ro&immutable=1",
                Properties(),
            )
            .use { input ->
                input.createStatement().use {
                    it.execute("PRAGMA trusted_schema=OFF")
                    it.execute("PRAGMA query_only=ON")
                    it.execute("PRAGMA cache_size=-8192")
                    it.execute("PRAGMA temp_store=MEMORY")
                }
                ProgressHandler.setHandler(
                    input,
                    1000,
                    object : ProgressHandler() {
                        override fun progress(): Int = if (cancelled()) 1 else 0
                    },
                )
                fun schema(c: Connection): Map<String, String> =
                    c.createStatement().use { s ->
                        s.executeQuery(
                                "SELECT name,sql FROM sqlite_master WHERE sql IS NOT NULL ORDER BY name"
                            )
                            .use { r ->
                                buildMap { while (r.next()) put(r.getString(1), r.getString(2)) }
                            }
                    }
                input.createStatement().use { s ->
                    s.executeQuery("PRAGMA user_version").use {
                        require(it.next() && it.getInt(1) == VERSION) {
                            "Search snapshot format is incompatible. Rebuild and export again in the original IDE."
                        }
                    }
                    require(schema(input) == schema(db)) { "Unsupported or unsafe search snapshot schema." }
                    s.executeQuery("PRAGMA integrity_check").use {
                        require(it.next() && it.getString(1) == "ok")
                    }
                    s.executeQuery("SELECT count(*) FROM sources").use {
                        it.next()
                        require(it.getInt(1) <= MAX_SOURCES)
                    }
                    s.executeQuery("SELECT count(*) FROM associations").use {
                        it.next()
                        require(it.getInt(1) <= MAX_PASSAGES + MAX_SOURCES * 3)
                    }
                    s.executeQuery(
                            """SELECT count(*) FROM associations a LEFT JOIN sources s ON a.kind=s.kind AND a.session=s.session
                            WHERE a.id<=0 OR s.kind IS NULL OR a.start<0 OR a.finish<a.start OR a.finish>500000
                            OR (a.field IN ('title','path') AND a.variant IS NULL)
                            OR NOT (
                                (a.field='title' AND a.category='TITLE' AND a.variant IN ('PROVIDER_TITLE','LOCAL_TITLE') AND a.entry IS NULL AND a.hash IS NULL AND a.start=0 AND a.finish=0)
                                OR (a.field='path' AND a.category='PROJECT_PATH' AND a.variant='PROJECT_PATH' AND a.entry IS NULL AND a.hash IS NULL AND a.start=0 AND a.finish=0)
                                OR (a.field='user_body' AND a.category='USER' AND a.variant IS NULL AND a.entry IS NOT NULL AND a.hash IS NOT NULL AND a.finish>a.start)
                                OR (a.field='assistant_body' AND a.category='ASSISTANT' AND a.variant IS NULL AND a.entry IS NOT NULL AND a.hash IS NOT NULL AND a.finish>a.start)
                                OR (a.field='tool_body' AND a.category IN ('TOOL_CALL','TOOL_RESULT') AND a.variant IS NULL AND a.entry IS NOT NULL AND a.hash IS NOT NULL AND a.finish>a.start)
                            )"""
                        )
                        .use {
                            it.next()
                            require(it.getInt(1) == 0)
                        }
                    s.executeQuery("SELECT count(*) FROM associations WHERE field IN ($BODY_FIELDS_SQL)").use {
                        it.next()
                        require(it.getInt(1) <= MAX_PASSAGES)
                    }
                    // Each posting belongs to exactly one association in its own fixed field.
                    FIELDS.forEach { field ->
                        s.executeQuery("SELECT count(*) FROM $field f LEFT JOIN associations a ON a.id=f.rowid WHERE a.id IS NULL OR a.field!='$field'").use {
                            it.next()
                            require(it.getInt(1) == 0)
                        }
                        s.executeQuery("SELECT count(*) FROM associations a LEFT JOIN $field f ON f.rowid=a.id WHERE a.field='$field' AND f.rowid IS NULL").use {
                            it.next()
                            require(it.getInt(1) == 0)
                        }
                    }
                    // These TEMP virtual tables use our fixed SQL and the validated FTS schema.
                    s.execute("PRAGMA query_only=OFF")
                    FIELDS.forEach { field ->
                        s.execute(
                            "CREATE VIRTUAL TABLE temp.import_$field USING fts5vocab(main,'$field','instance')"
                        )
                    }
                }
                var imported = 0
                val newKeys = HashSet<SearchSessionKey>()
                var bodies = passageCount
                data class ImportedRow(
                    val id: Long,
                    val key: SearchSessionKey,
                    val entry: String?,
                    val start: Int,
                    val end: Int,
                    val hash: String?,
                    val category: SearchMatchSource,
                    val variant: SearchMetadataVariant?,
                )
                transaction {
                    input.createStatement().use { statement ->
                        statement
                            .executeQuery(
                                "SELECT kind,session,fingerprint,metadata,partial FROM sources ORDER BY kind,session"
                            )
                            .use { rows ->
                                while (rows.next()) {
                                    check(!cancelled()) { "Import cancelled." }
                                    val key =
                                        SearchSessionKey(
                                            AgentKind.valueOf(rows.getString(1)),
                                            rows.getString(2),
                                        )
                                    require(
                                        key.id.length <= 4096 &&
                                            Regex("[0-9a-f]{64}").matches(rows.getString(4))
                                    )
                                    require(
                                        rows.getString(3) == null ||
                                            Regex("[0-9a-f]{64}").matches(rows.getString(3))
                                    )
                                    if (source(key) != null) continue
                                    require(sourceCount < MAX_SOURCES)
                                    db.prepareStatement(
                                            "INSERT INTO sources VALUES(?,?,NULL,?,?,?)"
                                        )
                                        .use {
                                            it.setString(1, key.kind.name)
                                            it.setString(2, key.id)
                                            it.setString(3, rows.getString(3))
                                            it.setString(4, rows.getString(4))
                                            it.setBoolean(5, rows.getBoolean(5))
                                            it.executeUpdate()
                                        }
                                    sourceCountCache = sourceCount + 1
                                    newKeys += key
                                    imported++
                                }
                            }
                    }
                    // fts5vocab is ordered by term, not document. Scanning once per row is
                    // quadratic.
                    // Reconstruct at most 256 documents at a time, with independent byte/token
                    // bounds.
                    // No complete RAM postings copy, imported SQL or shadow-table writes are
                    // needed.
                    for (field in FIELDS) {
                        var after = 0L
                        var batchSize = 256
                        while (true) {
                            check(!cancelled()) { "Import cancelled." }
                            val batch =
                                input
                                    .prepareStatement(
                                        "SELECT id,kind,session,entry,start,finish,hash,category,variant FROM associations WHERE field=? AND id>? ORDER BY id LIMIT ?"
                                    )
                                    .use { q ->
                                        q.setString(1, field)
                                        q.setLong(2, after)
                                        q.setInt(3, batchSize)
                                        q.executeQuery().use { rows ->
                                            buildList {
                                                while (rows.next()) {
                                                    val row =
                                                        ImportedRow(
                                                            rows.getLong(1),
                                                            SearchSessionKey(
                                                                AgentKind.valueOf(
                                                                    rows.getString(2)
                                                                ),
                                                                rows.getString(3),
                                                            ),
                                                            rows.getString(4),
                                                            rows.getInt(5),
                                                            rows.getInt(6),
                                                            rows.getString(7),
                                                            SearchMatchSource.valueOf(rows.getString(8)),
                                                            rows.getString(9)?.let(SearchMetadataVariant::valueOf),
                                                        )
                                                    require(
                                                        row.id > after &&
                                                            (row.entry == null ||
                                                                row.entry.length <= 4096)
                                                    )
                                                    require(
                                                        row.hash == null ||
                                                            Regex("[0-9a-f]{64}").matches(row.hash)
                                                    )
                                                    add(row)
                                                }
                                            }
                                        }
                                    }
                            if (batch.isEmpty()) break
                            val wanted = batch.filter { it.key in newKeys }.associateBy { it.id }
                            if (wanted.isEmpty()) {
                                after = batch.last().id
                                continue
                            }
                            val tokens =
                                importTokens(
                                    input,
                                    field,
                                    batch.first().id,
                                    batch.last().id,
                                    wanted.keys,
                                    cancelled,
                                )
                            if (tokens == null) {
                                require(batch.size > 1) {
                                    "One imported passage exceeds the supported resource limits."
                                }
                                batchSize = maxOf(1, batch.size / 2)
                                continue // Retry the same range; no rows from this batch were
                                // written.
                            }
                            for (row in wanted.values) {
                                if (field in BODY_FIELDS) {
                                    bodies++
                                    require(bodies <= MAX_PASSAGES)
                                }
                                val words = tokens[row.id].orEmpty()
                                require(
                                    words.keys.withIndex().all { (index, offset) ->
                                        index == offset
                                    }
                                )
                                insert(
                                    row.key,
                                    field,
                                    row.entry,
                                    row.start,
                                    row.end,
                                    row.hash,
                                    words.values.joinToString(" "),
                                    row.category,
                                    row.variant,
                                    normalized = true,
                                )
                            }
                            after = batch.last().id
                        }
                    }
                }
                sourceSnapshot = null
                bodyCountCache = bodies
                imported
            }
    }

    /** Null asks the caller to retry fewer documents, without lifting either memory bound. */
    private fun importTokens(
        input: Connection,
        field: String,
        first: Long,
        last: Long,
        wanted: Set<Long>,
        cancelled: () -> Boolean,
    ): Map<Long, java.util.TreeMap<Int, String>>? {
        val tokens = HashMap<Long, java.util.TreeMap<Int, String>>()
        var chars = 0
        var tokenCount = 0
        input
            .prepareStatement(
                "SELECT doc,term,offset FROM temp.import_$field WHERE doc>=? AND doc<=?"
            )
            .use { query ->
                query.setLong(1, first)
                query.setLong(2, last)
                query.executeQuery().use { rows ->
                    while (rows.next()) {
                        check(!cancelled()) { "Import cancelled." }
                        val doc = rows.getLong(1)
                        if (doc !in wanted) continue
                        val term = rows.getString(2)
                        val position = rows.getInt(3)
                        require(term.length <= 32000 && position in 0..100000)
                        chars += term.length
                        tokenCount++
                        if (chars > 4_000_000 || tokenCount > 64_000) return null
                        require(
                            tokens.getOrPut(doc) { java.util.TreeMap() }.put(position, term) == null
                        )
                    }
                }
            }
        return tokens
    }

    fun replace(
        key: SearchSessionKey,
        stamp: String?,
        fingerprint: String?,
        metadata: String,
        partial: Boolean,
        title: String,
        localTitle: String,
        pathText: String,
        passages: List<LexicalPassage>?,
        beforeCommit: () -> Unit = {},
    ) {
        val existed = source(key) != null
        val oldBodies =
            if (passages == null) 0
            else
                db.prepareStatement(
                        "SELECT count(*) FROM associations WHERE kind=? AND session=? AND field IN ($BODY_FIELDS_SQL)"
                    )
                    .use { statement ->
                        statement.setString(1, key.kind.name)
                        statement.setString(2, key.id)
                        statement.executeQuery().use {
                            it.next()
                            it.getInt(1)
                        }
                    }
        val previousBodyCount = passageCount
        val previousSourceCount = sourceCount
        transaction {
            if (passages != null) deleteFields(key, BODY_FIELDS)
            deleteFields(key, listOf("title", "path"))
            db.prepareStatement(
                    "INSERT INTO sources(kind,session,stamp,fingerprint,metadata,partial) VALUES(?,?,?,?,?,?) ON CONFLICT(kind,session) DO UPDATE SET stamp=excluded.stamp,fingerprint=excluded.fingerprint,metadata=excluded.metadata,partial=excluded.partial"
                )
                .use {
                    it.setString(1, key.kind.name)
                    it.setString(2, key.id)
                    it.setString(3, stamp)
                    it.setString(4, fingerprint)
                    it.setString(5, metadata)
                    it.setBoolean(6, partial)
                    it.executeUpdate()
                }
            insert(key, "title", null, 0, 0, null, title, SearchMatchSource.TITLE, SearchMetadataVariant.PROVIDER_TITLE)
            if (localTitle.isNotBlank() && localTitle != title)
                insert(key, "title", null, 0, 0, null, localTitle, SearchMatchSource.TITLE, SearchMetadataVariant.LOCAL_TITLE)
            insert(key, "path", null, 0, 0, null, pathText, SearchMatchSource.PROJECT_PATH, SearchMetadataVariant.PROJECT_PATH)
            passages?.forEach { insert(key, field(it.source), it.entry, it.start, it.end, it.hash, it.text, it.source.matchSource) }
            beforeCommit()
        }
        sourceSnapshot?.put(key, SavedSource(stamp, fingerprint, metadata, partial))
        bodyCountCache = previousBodyCount + if (passages == null) 0 else passages.size - oldBodies
        sourceCountCache = previousSourceCount + if (existed) 0 else 1
    }

    fun validateStamp(key: SearchSessionKey, stamp: String?) =
        db.prepareStatement("UPDATE sources SET stamp=? WHERE kind=? AND session=?").use {
            it.setString(1, stamp)
            it.setString(2, key.kind.name)
            it.setString(3, key.id)
            it.executeUpdate()
            sourceSnapshot?.get(key)?.let { source ->
                sourceSnapshot?.put(key, source.copy(stamp = stamp))
            }
            Unit
        }

    fun delete(key: SearchSessionKey) = transaction {
        deleteFields(key, FIELDS)
        db.prepareStatement("DELETE FROM sources WHERE kind=? AND session=?").use {
            it.setString(1, key.kind.name)
            it.setString(2, key.id)
            it.executeUpdate()
        }
        sourceSnapshot = null
        bodyCountCache = null
        sourceCountCache = null
    }

    /** Scope and best-passage/field aggregation run in SQL before any presentation limit. */
    fun scores(
        query: RetrievalQuery,
        keys: Collection<SearchSessionKey>,
        sources: SearchSources = SearchSources.DEFAULT,
        cancelled: () -> Boolean = { false },
    ): List<LexicalScore> {
        if (keys.isEmpty()) {
            db.createStatement().use { it.execute("DELETE FROM scope") }
            return emptyList()
        }
        transaction {
            db.createStatement().use { it.execute("DELETE FROM scope") }
            db.prepareStatement("INSERT OR IGNORE INTO scope VALUES(?,?)").use { statement ->
                keys.forEach {
                    statement.setString(1, it.kind.name)
                    statement.setString(2, it.id)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }
        if (query.tokens.isEmpty()) return emptyList()
        val fields = selectedFields(sources)
        // Each MATCH/BM25 cursor must materialize before window aggregation (FTS5 contract).
        val cursors = fields.joinToString(",\n") { field ->
            "${field}_matches AS MATERIALIZED (SELECT rowid,-bm25($field) score FROM $field WHERE $field MATCH ?)"
        }
        val union = fields.joinToString(" UNION ALL ") { "SELECT * FROM ${it}_matches" }
        val sourceOrder = "CASE category WHEN 'TITLE' THEN 0 WHEN 'PROJECT_PATH' THEN 1 WHEN 'USER' THEN 2 WHEN 'ASSISTANT' THEN 3 WHEN 'TOOL_CALL' THEN 4 ELSE 5 END"
        val sql = """WITH $cursors,
            scores AS ($union),
            ranked AS (
                SELECT a.*,s.score,ROW_NUMBER() OVER (
                    PARTITION BY a.kind,a.session,CASE a.field WHEN 'title' THEN 'title' WHEN 'path' THEN 'path' ELSE 'body' END
                    ORDER BY s.score DESC,$sourceOrder,a.entry,a.start,a.finish,a.variant,a.id
                ) ordinal
                FROM scores s JOIN associations a ON a.id=s.rowid
                JOIN scope c ON c.kind=a.kind AND c.session=a.session
            ), weighted AS (
                SELECT *,score*CASE field WHEN 'title' THEN 8 WHEN 'path' THEN 3 ELSE 1 END contribution
                FROM ranked WHERE ordinal=1
            ), primary_matches AS (
                SELECT *,SUM(contribution) OVER (PARTITION BY kind,session) relevance,
                    ROW_NUMBER() OVER (PARTITION BY kind,session ORDER BY contribution DESC,$sourceOrder,entry,start,finish,variant,id) primary_ordinal
                FROM weighted
            ) SELECT kind,session,relevance,entry,start,finish,hash,category,variant
              FROM primary_matches WHERE primary_ordinal=1 ORDER BY relevance DESC,kind,session"""
        ProgressHandler.setHandler(
            db,
            1000,
            object : ProgressHandler() {
                override fun progress(): Int = if (cancelled()) 1 else 0
            },
        )
        return try {
            db.prepareStatement(sql).use { statement ->
                fields.indices.forEach { statement.setString(it + 1, query.match) }
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            if (cancelled()) break
                            val score = rows.getDouble(3)
                            if (score.isFinite() && score > 0)
                                add(
                                    LexicalScore(
                                        SearchSessionKey(
                                            AgentKind.valueOf(rows.getString(1)),
                                            rows.getString(2),
                                        ),
                                        score,
                                        rows.getString(4),
                                        rows.getInt(5),
                                        rows.getInt(6),
                                        rows.getString(7),
                                        SearchMatchSource.valueOf(rows.getString(8)),
                                        rows.getString(9)?.let(SearchMetadataVariant::valueOf),
                                    )
                                )
                        }
                    }
                }
            }
        } finally {
            ProgressHandler.clearHandler(db)
        }
    }

    /** A bounded literal lane for plain substrings inside stored identifier tokens. */
    fun substringCandidates(
        query: RetrievalQuery,
        sources: SearchSources = SearchSources.DEFAULT,
        cancelled: () -> Boolean,
    ): Set<SearchSessionKey> {
        if (query.verifyAll || query.tokens.isEmpty()) return emptySet()
        val predicates =
            query.tokens.joinToString(" OR ") { "(length(v.term)>? AND v.term LIKE ? ESCAPE '\\')" }
        val fields = selectedFields(sources)
        val sql = fields.joinToString(" UNION ") { field ->
            "SELECT DISTINCT a.kind,a.session FROM ${field}_vocabulary v JOIN associations a ON a.id=v.doc JOIN scope s ON s.kind=a.kind AND s.session=a.session WHERE $predicates"
        }
        ProgressHandler.setHandler(
            db,
            1000,
            object : ProgressHandler() {
                override fun progress(): Int = if (cancelled()) 1 else 0
            },
        )
        return try {
            db.prepareStatement(sql).use { statement ->
                fields.forEachIndexed { fieldIndex, _ ->
                query.tokens.forEachIndexed { index, term ->
                    val offset = fieldIndex * query.tokens.size * 2 + index * 2
                    statement.setInt(offset + 1, term.length)
                    statement.setString(
                        offset + 2,
                        "%" +
                            term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") +
                            "%",
                    )
                }
                }
                statement.executeQuery().use { rows ->
                    buildSet {
                        while (rows.next()) {
                            if (cancelled()) break
                            add(
                                SearchSessionKey(
                                    AgentKind.valueOf(rows.getString(1)),
                                    rows.getString(2),
                                )
                            )
                        }
                    }
                }
            }
        } finally {
            ProgressHandler.clearHandler(db)
        }
    }

    fun sources(): Map<SearchSessionKey, SavedSource> {
        sourceSnapshot?.let {
            return it
        }
        val snapshot =
            db.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "SELECT kind,session,stamp,fingerprint,metadata,partial FROM sources"
                    )
                    .use { rows ->
                        mutableMapOf<SearchSessionKey, SavedSource>().apply {
                            while (rows.next()) put(
                                SearchSessionKey(
                                    AgentKind.valueOf(rows.getString(1)),
                                    rows.getString(2),
                                ),
                                SavedSource(
                                    rows.getString(3),
                                    rows.getString(4),
                                    rows.getString(5),
                                    rows.getBoolean(6),
                                ),
                            )
                        }
                    }
            }

        sourceSnapshot = snapshot
        return snapshot
    }

    val passageCount: Int
        get() =
            bodyCountCache
                ?: db.createStatement()
                    .use {
                        it.executeQuery("SELECT count(*) FROM associations WHERE field IN ($BODY_FIELDS_SQL)")
                            .use { r ->
                                r.next()
                                r.getInt(1)
                            }
                    }
                    .also { bodyCountCache = it }

    val sourceCount: Int
        get() =
            sourceCountCache
                ?: db.createStatement()
                    .use {
                        it.executeQuery("SELECT count(*) FROM sources").use { r ->
                            r.next()
                            r.getInt(1)
                        }
                    }
                    .also { sourceCountCache = it }

    private fun insert(
        key: SearchSessionKey,
        field: String,
        entry: String?,
        start: Int,
        end: Int,
        hash: String?,
        text: String,
        category: SearchMatchSource,
        variant: SearchMetadataVariant? = null,
        normalized: Boolean = false,
    ) {
        db.prepareStatement(
                "INSERT INTO associations(kind,session,field,entry,start,finish,hash,category,variant) VALUES(?,?,?,?,?,?,?,?,?)"
            )
            .use {
                it.setString(1, key.kind.name)
                it.setString(2, key.id)
                it.setString(3, field)
                it.setString(4, entry)
                it.setInt(5, start)
                it.setInt(6, end)
                it.setString(7, hash)
                it.setString(8, category.name)
                it.setString(9, variant?.name)
                it.executeUpdate()
            }
        val id =
            db.createStatement().use {
                it.executeQuery("SELECT last_insert_rowid()").use { r ->
                    r.next()
                    r.getLong(1)
                }
            }
        db.prepareStatement("INSERT INTO $field(rowid,text) VALUES(?,?)").use {
            it.setLong(1, id)
            it.setString(2, if (normalized) text else LexicalTokens.tokens(text).joinToString(" "))
            it.executeUpdate()
        }
    }

    private fun deleteFields(key: SearchSessionKey, fields: List<String>) {
        fields.forEach { field ->
            db.prepareStatement(
                    "DELETE FROM $field WHERE rowid IN (SELECT id FROM associations WHERE kind=? AND session=? AND field=?)"
                )
                .use {
                    it.setString(1, key.kind.name)
                    it.setString(2, key.id)
                    it.setString(3, field)
                    it.executeUpdate()
                }
            db.prepareStatement("DELETE FROM associations WHERE kind=? AND session=? AND field=?")
                .use {
                    it.setString(1, key.kind.name)
                    it.setString(2, key.id)
                    it.setString(3, field)
                    it.executeUpdate()
                }
        }
    }

    private fun transaction(action: () -> Unit) {
        db.autoCommit = false
        try {
            action()
            db.commit()
        } catch (t: Throwable) {
            sourceSnapshot = null
            bodyCountCache = null
            sourceCountCache = null
            db.rollback()
            throw t
        } finally {
            db.autoCommit = true
        }
    }

    override fun close() {
        try {
            db.close()
        } finally {
            lease?.close()
        }
    }

    companion object {
        const val VERSION = 3
        const val CLASSIFICATION_VERSION = 1
        const val MAX_PASSAGES = 100_000
        const val MAX_SOURCES = 50_000
        const val MAX_DISK_BYTES = 256L * 1024 * 1024
        private val BODY_FIELDS = listOf("user_body", "assistant_body", "tool_body")
        private val FIELDS = listOf("title", "path") + BODY_FIELDS
        private val BODY_FIELDS_SQL = BODY_FIELDS.joinToString(",") { "'$it'" }

        private fun field(source: SearchEntrySource): String = when (source) {
            SearchEntrySource.USER -> "user_body"
            SearchEntrySource.ASSISTANT -> "assistant_body"
            SearchEntrySource.TOOL_CALL, SearchEntrySource.TOOL_RESULT -> "tool_body"
        }

        private fun selectedFields(sources: SearchSources): List<String> = listOf("title", "path") +
            listOfNotNull("user_body".takeIf { sources.userMessages }, "assistant_body".takeIf { sources.assistantReplies }, "tool_body".takeIf { sources.toolActivity })

        fun purge(path: Path) {
            Files.createDirectories(path.parent)
            SearchStoreLease.acquire(path).use {
                val owned = Regex("lexical(?:-recovery-[0-9]+)?\\.sqlite(?:-journal|-wal|-shm)?")
                Files.newDirectoryStream(path.parent).use { files ->
                    files
                        .filter { owned.matches(it.fileName.toString()) }
                        .forEach { Files.delete(it) }
                }
            }
        }

        fun reset(path: Path) {
            Files.createDirectories(path.parent)
            SearchStoreLease.acquire(path).use {
                Files.newDirectoryStream(path.parent, "lexical-recovery-*.sqlite").use {
                    require(!it.iterator().hasNext()) {
                        "A recovery index already exists; export or clear saved indexes before another rebuild."
                    }
                }
                if (Files.exists(path)) {
                    val recovery =
                        path.resolveSibling("lexical-recovery-${System.currentTimeMillis()}.sqlite")
                    Files.move(path, recovery)
                    listOf("-journal", "-wal", "-shm").forEach { suffix ->
                        val sidecar = path.resolveSibling(path.fileName.toString() + suffix)
                        if (Files.exists(sidecar))
                            Files.move(
                                sidecar,
                                recovery.resolveSibling(recovery.fileName.toString() + suffix),
                            )
                    }
                }
            }
        }

        fun open(
            path: Path? = null,
            diskBudgetBytes: Long = 112L * 1024 * 1024,
        ): LexicalSearchStore {
            require(diskBudgetBytes in 65536..MAX_DISK_BYTES)
            path?.parent?.let { Files.createDirectories(it) }
            path?.parent?.let { privatePermissions(it, true) }
            require(path == null || !Files.isSymbolicLink(path)) {
                "Managed search databases must not be symbolic links."
            }
            val lease = path?.let { SearchStoreLease.acquire(it) }
            val db =
                try {
                    JDBC.createConnection("jdbc:sqlite:${path ?: ":memory:"}", Properties())
                } catch (t: Throwable) {
                    lease?.close()
                    throw t
                }
            try {
                db.createStatement().use { s ->
                    s.execute("PRAGMA busy_timeout=250")
                    s.execute("PRAGMA cache_size=-8192")
                    s.execute("PRAGMA temp_store=MEMORY")
                    s.execute("PRAGMA synchronous=FULL")
                    val pageSize =
                        s.executeQuery("PRAGMA page_size").use {
                            it.next()
                            it.getLong(1)
                        }
                    val recoveryBytes =
                        if (path == null) 0L
                        else
                            Files.newDirectoryStream(path.parent, "lexical-recovery-*.sqlite*")
                                .use { files -> files.sumOf { Files.size(it) } }
                    // Reserve one full rollback journal plus 32 MiB maintenance headroom, also
                    // charging the explicit recovery copy retained by Rebuild.
                    val mainBudget =
                        minOf(
                            diskBudgetBytes,
                            (MAX_DISK_BYTES - 32L * 1024 * 1024 - recoveryBytes) / 2,
                        )
                    require(mainBudget >= 65536) {
                        "Recovery files leave no room for a durable search index; export or clear them."
                    }
                    s.execute("PRAGMA max_page_count=${mainBudget/pageSize}")
                    val version =
                        s.executeQuery("PRAGMA user_version").use {
                            it.next()
                            it.getInt(1)
                        }
                    if (version != 0 && version != VERSION) throw IncompatibleLexicalSchema(version)
                    s.execute("PRAGMA journal_mode=DELETE")
                    if (version == 0) {
                        val existing =
                            s.executeQuery("SELECT count(*) FROM sqlite_master").use {
                                it.next()
                                it.getInt(1)
                            }
                        require(existing == 0) {
                            "Unrecognized search database; preserve it and choose explicit rebuild."
                        }
                        db.autoCommit = false
                        s.execute(
                            "CREATE TABLE sources(kind TEXT NOT NULL,session TEXT NOT NULL,stamp TEXT,fingerprint TEXT,metadata TEXT NOT NULL,partial INTEGER NOT NULL,PRIMARY KEY(kind,session))"
                        )
                        s.execute(
                            "CREATE TABLE associations(id INTEGER PRIMARY KEY,kind TEXT NOT NULL,session TEXT NOT NULL,field TEXT NOT NULL,entry TEXT,start INTEGER NOT NULL,finish INTEGER NOT NULL,hash TEXT,category TEXT NOT NULL,variant TEXT)"
                        )
                        s.execute("CREATE INDEX source_fields ON associations(kind,session,field)")
                        FIELDS.forEach {
                            s.execute(
                                "CREATE VIRTUAL TABLE $it USING fts5(text,content='',contentless_delete=1,tokenize=\"ascii tokenchars '_'\")"
                            )
                        }
                        s.execute("PRAGMA user_version=$VERSION")
                        db.commit()
                        db.autoCommit = true
                    }
                    s.execute(
                        "CREATE TEMP TABLE scope(kind TEXT,session TEXT,PRIMARY KEY(kind,session))"
                    )
                    FIELDS.forEach { field -> s.execute("CREATE VIRTUAL TABLE temp.${field}_vocabulary USING fts5vocab(main,$field,instance)") }
                }
                path?.let { privatePermissions(it) }
                return LexicalSearchStore(db, path, lease)
            } catch (t: Throwable) {
                db.close()
                lease?.close()
                throw t
            }
        }
    }
}

/** A recognized older schema has a rebuild remedy; future formats are preserved too. */
class IncompatibleLexicalSchema(val version: Int) : IllegalArgumentException() {
    val explanation: String get() = if (version == 2)
        "Search source filters require a local index rebuild. The old index is preserved; using a temporary index until Rebuild in Search settings."
    else "Saved search index format is unsupported. The file is preserved; using a temporary index. Upgrade Seshlog or explicitly rebuild in Search settings."
}
