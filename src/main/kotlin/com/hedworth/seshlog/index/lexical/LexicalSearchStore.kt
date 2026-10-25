package com.hedworth.seshlog.index.lexical

import com.hedworth.seshlog.model.AgentKind
import org.sqlite.JDBC
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.util.Properties

/** Provider-aware identities are used at every search boundary, without migrating organisation settings. */
data class SearchSessionKey(val kind: AgentKind, val id: String)
data class LexicalPassage(val entry: String, val start: Int, val end: Int, val hash: String, val text: String)
data class LexicalScore(val key: SearchSessionKey, val score: Double, val entry: String?, val start: Int, val end: Int, val hash: String?)
data class SavedSource(val stamp: String?, val fingerprint: String?, val metadata: String, val partial: Boolean)

/**
 * Contentless FTS5 owns matching/statistics/BM25. Raw inputs exist only as transaction parameters.
 * A single caller serializes this connection. SQLite DELETE/FULL journaling bounds sidecars;
 * the owner holds a process lease for the entire connection lifetime (including maintenance).
 */
class LexicalSearchStore private constructor(private val db: Connection, val path: Path?, private val lease: SearchStoreLease?) : AutoCloseable {
    fun source(key: SearchSessionKey): SavedSource? = db.prepareStatement("SELECT stamp,fingerprint,metadata,partial FROM sources WHERE kind=? AND session=?").use {
        it.setString(1, key.kind.name); it.setString(2, key.id)
        it.executeQuery().use { r -> if (!r.next()) null else SavedSource(r.getString(1), r.getString(2), r.getString(3), r.getBoolean(4)) }
    }

    fun hasRoom(passages: Int): Boolean = passageCount.toLong()+passages <= MAX_PASSAGES && sourceCount < MAX_SOURCES

    fun clear() { transaction {
        FIELDS.forEach { db.createStatement().use { s -> s.execute("DELETE FROM $it") } }
        db.createStatement().use { it.execute("DELETE FROM associations");it.execute("DELETE FROM sources") }
    }; db.createStatement().use { it.execute("VACUUM") } }

    /** A consistent SQLite snapshot, never a copy of an active main file. */
    fun export(destination: Path) {
        require(path?.toAbsolutePath()?.normalize() != destination.toAbsolutePath().normalize())
        require(!Files.exists(destination)) { "Choose a new export filename." }
        try {
            db.prepareStatement("VACUUM INTO ?").use { it.setString(1,destination.toString());it.execute() }
            privatePermissions(destination)
            // Stamps are host-specific; imported rows must validate portable raw-source fingerprints.
            JDBC.createConnection("jdbc:sqlite:$destination",Properties()).use { exported ->
                exported.createStatement().use { it.execute("UPDATE sources SET stamp=NULL") }
            }
        } catch (t: Throwable) { Files.deleteIfExists(destination);throw t }
    }

    /** Untrusted snapshots may contain only this exact schema; never execute imported SQL. */
    fun importSnapshot(snapshot: Path, cancelled: () -> Boolean = { false }): Int {
        require(Files.size(snapshot) <= MAX_DISK_BYTES)
        require(!Files.exists(snapshot.resolveSibling(snapshot.fileName.toString()+"-wal")) && !Files.exists(snapshot.resolveSibling(snapshot.fileName.toString()+"-journal"))) { "Choose a completed export, not a live database." }
        return JDBC.createConnection("jdbc:sqlite:${snapshot.toAbsolutePath().toUri()}?mode=ro&immutable=1",Properties()).use { input ->
            input.createStatement().use { it.execute("PRAGMA trusted_schema=OFF");it.execute("PRAGMA query_only=ON") }
            fun schema(c: Connection): Map<String,String> = c.createStatement().use { s ->
                s.executeQuery("SELECT name,sql FROM sqlite_master WHERE sql IS NOT NULL ORDER BY name").use { r -> buildMap { while(r.next())put(r.getString(1),r.getString(2)) } }
            }
            require(schema(input)==schema(db)) { "Unsupported or unsafe search snapshot schema." }
            input.createStatement().use { s ->
                s.executeQuery("PRAGMA user_version").use { require(it.next() && it.getInt(1)==VERSION) }
                s.executeQuery("PRAGMA integrity_check").use { require(it.next() && it.getString(1)=="ok") }
                s.executeQuery("SELECT count(*) FROM sources").use { it.next();require(it.getInt(1)<=MAX_SOURCES) }
                s.executeQuery("SELECT count(*) FROM associations").use { it.next();require(it.getInt(1)<=MAX_PASSAGES+MAX_SOURCES*3) }
                s.executeQuery("SELECT count(*) FROM associations a LEFT JOIN sources s ON a.kind=s.kind AND a.session=s.session WHERE s.kind IS NULL OR a.field NOT IN ('title','path','body') OR a.start<0 OR a.finish<a.start OR a.finish>500000").use { it.next();require(it.getInt(1)==0) }
                // These TEMP virtual tables use our fixed SQL and the validated FTS schema.
                s.execute("PRAGMA query_only=OFF")
                FIELDS.forEach { field -> s.execute("CREATE VIRTUAL TABLE temp.import_$field USING fts5vocab(main,'$field','instance')") }
            }
            var imported=0
            transaction {
                input.createStatement().use { s -> s.executeQuery("SELECT kind,session,fingerprint,metadata,partial FROM sources ORDER BY kind,session").use { r ->
                    while(r.next()) {
                        check(!cancelled()) { "Import cancelled." }
                        val key=SearchSessionKey(AgentKind.valueOf(r.getString(1)),r.getString(2))
                        require(key.id.length<=4096 && Regex("[0-9a-f]{64}").matches(r.getString(4)))
                        require(r.getString(3)==null || Regex("[0-9a-f]{64}").matches(r.getString(3)))
                        if(source(key)!=null)continue
                        require(sourceCount<MAX_SOURCES)
                        db.prepareStatement("INSERT INTO sources VALUES(?,?,NULL,?,?,?)").use {
                            it.setString(1,key.kind.name);it.setString(2,key.id);it.setString(3,r.getString(3));it.setString(4,r.getString(4));it.setBoolean(5,r.getBoolean(5));it.executeUpdate()
                        }
                        input.prepareStatement("SELECT id,field,entry,start,finish,hash FROM associations WHERE kind=? AND session=? ORDER BY id").use { a ->
                            a.setString(1,key.kind.name);a.setString(2,key.id)
                            a.executeQuery().use { rows -> while(rows.next()) {
                                check(!cancelled()) { "Import cancelled." }
                                val field=rows.getString(2);require(field in FIELDS)
                                require(rows.getString(3)==null || rows.getString(3).length<=4096)
                                require(rows.getString(6)==null || Regex("[0-9a-f]{64}").matches(rows.getString(6)))
                                if(field=="body")require(passageCount<MAX_PASSAGES)
                                val text=input.prepareStatement("SELECT term,offset FROM temp.import_$field WHERE doc=? ORDER BY offset").use { v ->
                                    v.setLong(1,rows.getLong(1));v.executeQuery().use { terms ->
                                        val tokens=ArrayList<String>();var chars=0
                                        while(terms.next()) { require(terms.getInt(2)==tokens.size && tokens.size<100000);val term=terms.getString(1);chars+=term.length+1;require(chars<=1_000_000 && term.length<=32000);tokens.add(term) }
                                        tokens.joinToString(" ")
                                    }
                                }
                                require(text.length<=1_000_000)
                                insert(key,field,rows.getString(3),rows.getInt(4),rows.getInt(5),rows.getString(6),text,normalized=true)
                            } }
                        }
                        imported++
                    }
                } }
            }
            imported
        }
    }

    fun replace(key: SearchSessionKey, stamp: String?, fingerprint: String?, metadata: String, partial: Boolean,
                title: String, localTitle: String, pathText: String, passages: List<LexicalPassage>?, beforeCommit: () -> Unit = {}) = transaction {
        if (passages != null) deleteFields(key, listOf("body"))
        deleteFields(key, listOf("title", "path"))
        db.prepareStatement("INSERT INTO sources(kind,session,stamp,fingerprint,metadata,partial) VALUES(?,?,?,?,?,?) ON CONFLICT(kind,session) DO UPDATE SET stamp=excluded.stamp,fingerprint=excluded.fingerprint,metadata=excluded.metadata,partial=excluded.partial").use {
            it.setString(1,key.kind.name); it.setString(2,key.id); it.setString(3,stamp); it.setString(4,fingerprint); it.setString(5,metadata); it.setBoolean(6,partial); it.executeUpdate()
        }
        insert(key, "title", null, 0, 0, null, title)
        if (localTitle.isNotBlank() && localTitle != title) insert(key, "title", null, 0, 0, null, localTitle)
        insert(key, "path", null, 0, 0, null, pathText)
        passages?.forEach { insert(key, "body", it.entry, it.start, it.end, it.hash, it.text) }
        beforeCommit()
    }

    fun validateStamp(key: SearchSessionKey, stamp: String?) = db.prepareStatement("UPDATE sources SET stamp=? WHERE kind=? AND session=?").use {
        it.setString(1,stamp); it.setString(2,key.kind.name); it.setString(3,key.id); it.executeUpdate(); Unit
    }

    fun delete(key: SearchSessionKey) = transaction {
        deleteFields(key, listOf("title", "path", "body"))
        db.prepareStatement("DELETE FROM sources WHERE kind=? AND session=?").use { it.setString(1,key.kind.name); it.setString(2,key.id); it.executeUpdate() }
    }

    /** Scope is materialized BEFORE matching/aggregation; there is no candidate cap before verification. */
    fun scores(query: RetrievalQuery, keys: Collection<SearchSessionKey>, cancelled: () -> Boolean = { false }): List<LexicalScore> {
        if (query.tokens.isEmpty() || keys.isEmpty()) return emptyList()
        db.createStatement().use { it.execute("DELETE FROM scope") }
        db.prepareStatement("INSERT OR IGNORE INTO scope VALUES(?,?)").use { s -> keys.forEach { s.setString(1,it.kind.name);s.setString(2,it.id);s.addBatch() };s.executeBatch() }
        val byField = HashMap<SearchSessionKey, MutableMap<String, LexicalScore>>()
        for (field in FIELDS) {
            if (cancelled()) break
            val sql = """WITH scored AS MATERIALIZED (
                SELECT rowid,-bm25($field) score FROM $field WHERE $field MATCH ?
            ) SELECT a.kind,a.session,s.score,a.entry,a.start,a.finish,a.hash
              FROM scored s JOIN associations a ON a.id=s.rowid
              JOIN scope c ON c.kind=a.kind AND c.session=a.session
              ORDER BY s.score DESC,a.id ASC"""
            db.prepareStatement(sql).use { s ->
                s.setString(1,query.match)
                s.executeQuery().use { r -> while (r.next()) {
                    if (cancelled()) break
                    val key = SearchSessionKey(AgentKind.valueOf(r.getString(1)),r.getString(2))
                    val score = r.getDouble(3)
                    if (score.isFinite() && score > 0) byField.getOrPut(key) { HashMap() }.putIfAbsent(field,
                        LexicalScore(key,score,r.getString(4),r.getInt(5),r.getInt(6),r.getString(7)))
                } }
            }
        }
        return byField.map { (key, fields) ->
            val best = fields["body"] ?: fields["title"] ?: fields.getValue("path")
            best.copy(key=key, score=(fields["title"]?.score ?: 0.0)*8 + (fields["path"]?.score ?: 0.0)*3 + (fields["body"]?.score ?: 0.0))
        }
    }

    val passageCount: Int get() = db.createStatement().use { it.executeQuery("SELECT count(*) FROM associations WHERE field='body'").use { r -> r.next();r.getInt(1) } }
    val sourceCount: Int get() = db.createStatement().use { it.executeQuery("SELECT count(*) FROM sources").use { r -> r.next();r.getInt(1) } }

    private fun insert(key: SearchSessionKey, field: String, entry: String?, start: Int, end: Int, hash: String?, text: String, normalized: Boolean = false) {
        db.prepareStatement("INSERT INTO associations(kind,session,field,entry,start,finish,hash) VALUES(?,?,?,?,?,?,?)").use {
            it.setString(1,key.kind.name);it.setString(2,key.id);it.setString(3,field);it.setString(4,entry);it.setInt(5,start);it.setInt(6,end);it.setString(7,hash);it.executeUpdate()
        }
        val id = db.createStatement().use { it.executeQuery("SELECT last_insert_rowid()").use { r -> r.next();r.getLong(1) } }
        db.prepareStatement("INSERT INTO $field(rowid,text) VALUES(?,?)").use { it.setLong(1,id);it.setString(2,if(normalized) text else LexicalTokens.tokens(text).joinToString(" "));it.executeUpdate() }
    }
    private fun deleteFields(key: SearchSessionKey, fields: List<String>) {
        fields.forEach { field ->
            db.prepareStatement("DELETE FROM $field WHERE rowid IN (SELECT id FROM associations WHERE kind=? AND session=? AND field=?)").use {
                it.setString(1,key.kind.name);it.setString(2,key.id);it.setString(3,field);it.executeUpdate()
            }
            db.prepareStatement("DELETE FROM associations WHERE kind=? AND session=? AND field=?").use { it.setString(1,key.kind.name);it.setString(2,key.id);it.setString(3,field);it.executeUpdate() }
        }
    }
    private fun transaction(action: () -> Unit) {
        db.autoCommit=false
        try { action();db.commit() } catch (t: Throwable) { db.rollback();throw t } finally { db.autoCommit=true }
    }
    override fun close() { try { db.close() } finally { lease?.close() } }

    companion object {
        const val VERSION = 1
        const val MAX_PASSAGES = 100_000
        const val MAX_SOURCES = 50_000
        const val MAX_DISK_BYTES = 256L * 1024 * 1024
        private val FIELDS = listOf("title", "path", "body")
        fun open(path: Path? = null): LexicalSearchStore {
            path?.parent?.let { Files.createDirectories(it) }
            path?.parent?.let { privatePermissions(it,true) }
            val lease = path?.let { SearchStoreLease.acquire(it) }
            val db = try { JDBC.createConnection("jdbc:sqlite:${path ?: ":memory:"}", Properties()) } catch(t: Throwable) { lease?.close();throw t }
            try {
                db.createStatement().use { s ->
                    s.execute("PRAGMA busy_timeout=250");s.execute("PRAGMA cache_size=-8192");s.execute("PRAGMA temp_store=MEMORY");s.execute("PRAGMA synchronous=FULL");s.execute("PRAGMA journal_mode=DELETE");s.execute("PRAGMA max_page_count=28672")
                    val version = s.executeQuery("PRAGMA user_version").use { it.next();it.getInt(1) }
                    require(version == 0 || version == VERSION) { "Unsupported lexical index version; preserve the file and upgrade Seshlog." }
                    if (version == 0) {
                        val existing=s.executeQuery("SELECT count(*) FROM sqlite_master").use { it.next();it.getInt(1) }
                        require(existing==0) { "Unrecognized search database; preserve it and choose explicit rebuild." }
                        db.autoCommit=false
                        s.execute("CREATE TABLE sources(kind TEXT NOT NULL,session TEXT NOT NULL,stamp TEXT,fingerprint TEXT,metadata TEXT NOT NULL,partial INTEGER NOT NULL,PRIMARY KEY(kind,session))")
                        s.execute("CREATE TABLE associations(id INTEGER PRIMARY KEY,kind TEXT NOT NULL,session TEXT NOT NULL,field TEXT NOT NULL,entry TEXT,start INTEGER NOT NULL,finish INTEGER NOT NULL,hash TEXT)")
                        s.execute("CREATE INDEX source_fields ON associations(kind,session,field)")
                        FIELDS.forEach { s.execute("CREATE VIRTUAL TABLE $it USING fts5(text,content='',contentless_delete=1,tokenize=\"unicode61 remove_diacritics 0 tokenchars '_'\")") }
                        s.execute("PRAGMA user_version=$VERSION")
                        db.commit();db.autoCommit=true
                    }
                    s.execute("CREATE TEMP TABLE scope(kind TEXT,session TEXT,PRIMARY KEY(kind,session))")
                }
                path?.let { privatePermissions(it) }
                return LexicalSearchStore(db,path,lease)
            } catch (t: Throwable) { db.close(); lease?.close();throw t }
        }
    }
}
