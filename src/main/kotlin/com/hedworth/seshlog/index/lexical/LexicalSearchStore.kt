package com.hedworth.seshlog.index.lexical

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
)

data class LexicalScore(
    val key: SearchSessionKey,
    val score: Double,
    val entry: String?,
    val start: Int,
    val end: Int,
    val hash: String?,
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
                    "SELECT count(*) FROM associations WHERE kind=? AND session=? AND field='body'"
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
                require(schema(input) == schema(db)) {
                    "Unsupported or unsafe search snapshot schema."
                }
                input.createStatement().use { s ->
                    s.executeQuery("PRAGMA user_version").use {
                        require(it.next() && it.getInt(1) == VERSION)
                    }
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
                            "SELECT count(*) FROM associations a LEFT JOIN sources s ON a.kind=s.kind AND a.session=s.session WHERE a.id<=0 OR s.kind IS NULL OR a.field NOT IN ('title','path','body') OR a.start<0 OR a.finish<a.start OR a.finish>500000"
                        )
                        .use {
                            it.next()
                            require(it.getInt(1) == 0)
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
                                        "SELECT id,kind,session,entry,start,finish,hash FROM associations WHERE field=? AND id>? ORDER BY id LIMIT ?"
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
                                if (field == "body") {
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
                        "SELECT count(*) FROM associations WHERE kind=? AND session=? AND field='body'"
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
            if (passages != null) deleteFields(key, listOf("body"))
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
            insert(key, "title", null, 0, 0, null, title)
            if (localTitle.isNotBlank() && localTitle != title)
                insert(key, "title", null, 0, 0, null, localTitle)
            insert(key, "path", null, 0, 0, null, pathText)
            passages?.forEach { insert(key, "body", it.entry, it.start, it.end, it.hash, it.text) }
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
        deleteFields(key, listOf("title", "path", "body"))
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
        cancelled: () -> Boolean = { false },
    ): List<LexicalScore> {
        if (query.tokens.isEmpty()) return emptyList()
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
        val sql =
            """WITH
            titles AS MATERIALIZED (SELECT rowid,-bm25(title) score FROM title WHERE title MATCH ?),
            paths AS MATERIALIZED (SELECT rowid,-bm25(path) score FROM path WHERE path MATCH ?),
            bodies AS MATERIALIZED (SELECT rowid,-bm25(body) score FROM body WHERE body MATCH ?),
            scores AS (
                SELECT * FROM titles UNION ALL SELECT * FROM paths UNION ALL SELECT * FROM bodies
            ),
            ranked AS (
                SELECT a.*,s.score,ROW_NUMBER() OVER (PARTITION BY a.kind,a.session,a.field ORDER BY s.score DESC,a.id) ordinal
                FROM scores s JOIN associations a ON a.id=s.rowid
                JOIN scope c ON c.kind=a.kind AND c.session=a.session
            ) SELECT kind,session,SUM(score*CASE field WHEN 'title' THEN 8 WHEN 'path' THEN 3 ELSE 1 END) relevance,
                MAX(CASE WHEN field='body' THEN entry END),
                MAX(CASE WHEN field='body' THEN start ELSE 0 END),
                MAX(CASE WHEN field='body' THEN finish ELSE 0 END),
                MAX(CASE WHEN field='body' THEN hash END)
              FROM ranked WHERE ordinal=1 GROUP BY kind,session ORDER BY relevance DESC,kind,session"""
        ProgressHandler.setHandler(
            db,
            1000,
            object : ProgressHandler() {
                override fun progress(): Int = if (cancelled()) 1 else 0
            },
        )
        return try {
            db.prepareStatement(sql).use { statement ->
                (1..3).forEach { statement.setString(it, query.match) }
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
        cancelled: () -> Boolean,
    ): Set<SearchSessionKey> {
        if (query.verifyAll || query.tokens.isEmpty()) return emptySet()
        val predicates =
            query.tokens.joinToString(" OR ") { "(length(v.term)>? AND v.term LIKE ? ESCAPE '\\')" }
        val sql =
            "SELECT DISTINCT a.kind,a.session FROM body_vocabulary v JOIN associations a ON a.id=v.doc JOIN scope s ON s.kind=a.kind AND s.session=a.session WHERE $predicates"
        ProgressHandler.setHandler(
            db,
            1000,
            object : ProgressHandler() {
                override fun progress(): Int = if (cancelled()) 1 else 0
            },
        )
        return try {
            db.prepareStatement(sql).use { statement ->
                query.tokens.forEachIndexed { index, term ->
                    statement.setInt(index * 2 + 1, term.length)
                    statement.setString(
                        index * 2 + 2,
                        "%" +
                            term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") +
                            "%",
                    )
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
                        it.executeQuery("SELECT count(*) FROM associations WHERE field='body'")
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
        normalized: Boolean = false,
    ) {
        db.prepareStatement(
                "INSERT INTO associations(kind,session,field,entry,start,finish,hash) VALUES(?,?,?,?,?,?,?)"
            )
            .use {
                it.setString(1, key.kind.name)
                it.setString(2, key.id)
                it.setString(3, field)
                it.setString(4, entry)
                it.setInt(5, start)
                it.setInt(6, end)
                it.setString(7, hash)
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
        const val VERSION = 2
        const val MAX_PASSAGES = 100_000
        const val MAX_SOURCES = 50_000
        const val MAX_DISK_BYTES = 256L * 1024 * 1024
        private val FIELDS = listOf("title", "path", "body")

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
                    require(version == 0 || version == VERSION) {
                        "Unsupported lexical index version; preserve the file and upgrade Seshlog."
                    }
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
                            "CREATE TABLE associations(id INTEGER PRIMARY KEY,kind TEXT NOT NULL,session TEXT NOT NULL,field TEXT NOT NULL,entry TEXT,start INTEGER NOT NULL,finish INTEGER NOT NULL,hash TEXT)"
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
                    s.execute(
                        "CREATE VIRTUAL TABLE temp.body_vocabulary USING fts5vocab(main,body,instance)"
                    )
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
