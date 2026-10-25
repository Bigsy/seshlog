package com.hedworth.seshlog.index

import com.hedworth.seshlog.index.lexical.*
import com.hedworth.seshlog.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.sqlite.JDBC
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Properties

class DurableLexicalIndexTest {
    @get:Rule val tmp=TemporaryFolder()
    private var reads=0
    private fun session(id:String="a",kind:AgentKind=AgentKind.CLAUDE_CODE):Session {
        val p=tmp.root.toPath().resolve("$id-${kind.name}.txt");Files.writeString(p,"database locked\nnext message")
        return Session(kind,id,"UniqueTitle",tmp.root.toPath(),null,null,Instant.EPOCH,p,false,null,null,1,true)
    }
    private fun index(db:Path, title:()->String={""}, stamp:((Session)->Any?)?=null, extractor:((Session)->List<String>)?=null)=ContentSearchIndex(
        extractor=extractor ?: { reads++;Files.readAllLines(it.transcriptPath!!) },
        contentStamp=stamp ?: { Files.size(it.transcriptPath!!).toString()+Files.getLastModifiedTime(it.transcriptPath!!) },
        localTitle={title()}, storeFactory={LexicalSearchStore.open(db)},
    )
    @Test fun `restart reuses postings and metadata rename never extracts sources`() {
        val s=session();val db=tmp.root.toPath().resolve("index.sqlite")
        index(db).use { assertEquals(1,it.search("UniqueTitle",listOf(s)).size) }
        assertEquals(1,reads)
        index(db,{"LocalRenamed"}).use {
            assertEquals(1,it.search("LocalRenamed",listOf(s)).size)
            assertEquals(1,reads)
            Files.writeString(s.transcriptPath!!,"rewritten content")
            assertTrue(it.search("\"database locked\"",listOf(s)).isEmpty())
            assertEquals(1,it.search("rewritten",listOf(s)).size)
            assertTrue(it.search("rewritten",emptyList()).isEmpty())
        }
    }
    @Test fun `portable snapshot reuses moved sources without transcript extraction`() {
        val s=session();val db=tmp.root.toPath().resolve("index.sqlite");val exported=tmp.root.toPath().resolve("export.sqlite")
        index(db).use { it.search("UniqueTitle",listOf(s));it.export(exported) }
        val moved=tmp.newFolder("relocated").toPath().resolve("moved.txt");Files.copy(s.transcriptPath!!,moved)
        reads=0
        index(tmp.root.toPath().resolve("fresh.sqlite")).use {
            assertEquals(1,it.importSnapshot(exported))
            assertEquals(1,it.search("UniqueTitle",listOf(s.copy(transcriptPath=moved,cwd=moved.parent))).size)
            assertEquals(0,reads)
            assertEquals(1,it.search("\"database locked\"",listOf(s.copy(transcriptPath=moved))).size)
            assertEquals(1,reads) // Literal verification reads the original, never reconstructed postings.
        }
    }
    @Test fun `replacement rollback and process lease preserve known good rows`() {
        val db=tmp.root.toPath().resolve("index.sqlite");val key=SearchSessionKey(AgentKind.PI,"a")
        LexicalSearchStore.open(db).use { s ->
            s.replace(key,"1",null,searchHash("m"),false,"before","","",emptyList())
            assertThrows(Exception::class.java) { LexicalSearchStore.open(db) }
            assertThrows(IllegalStateException::class.java) { s.replace(key,"2",null,searchHash("m"),false,"after","","",emptyList()) { error("interrupted") } }
            assertEquals(1,s.scores(RetrievalQuery.compile("before"),listOf(key)).size)
            assertTrue(s.scores(RetrievalQuery.compile("after"),listOf(key)).isEmpty())
        }
        LexicalSearchStore.open(db).use { assertEquals(1,it.sourceCount) }
    }
    @Test fun `provider collisions null stamps failed reads and zero hit partial coverage`() {
        val a=session();val b=session(kind=AgentKind.CODEX)
        index(tmp.root.toPath().resolve("index.sqlite"),stamp={null},extractor={reads++;if(it.kind==AgentKind.CODEX)error("synthetic failure") else listOf("needle")}).use {
            val result=it.searchSnapshot("needle",listOf(a,b));assertEquals(1,result.hits.size);assertEquals(1,result.coverage.skipped)
            assertEquals(1,it.searchSnapshot("absent",listOf(a,b)).coverage.skipped)
            assertEquals(5,reads)
            assertEquals(2,it.search("UniqueTitle",listOf(a,b)).size)
        }
    }
    @Test fun `corrupt newer and unsafe imports preserve stores with temporary fallback`() {
        val s=session();val bad=tmp.root.toPath().resolve("bad.sqlite");Files.writeString(bad,"not sqlite")
        index(bad).use { val result=it.searchSnapshot("UniqueTitle",listOf(s));assertEquals(1,result.hits.size);assertNotNull(result.availability) }
        assertEquals("not sqlite",Files.readString(bad))
        val newer=tmp.root.toPath().resolve("new.sqlite")
        JDBC.createConnection("jdbc:sqlite:$newer",Properties()).use { it.createStatement().use { s -> s.execute("PRAGMA user_version=999") } }
        index(newer).use { assertNotNull(it.searchSnapshot("UniqueTitle",listOf(s)).availability) }
        val good=tmp.root.toPath().resolve("good.sqlite");val snapshot=tmp.root.toPath().resolve("snapshot.sqlite")
        index(good).use { it.search("UniqueTitle",listOf(s));it.export(snapshot) }
        JDBC.createConnection("jdbc:sqlite:$snapshot",Properties()).use { it.createStatement().use { s -> s.execute("CREATE VIEW unsafe AS SELECT 1") } }
        index(good).use { assertThrows(IllegalArgumentException::class.java) { it.importSnapshot(snapshot) };assertEquals(1,it.search("UniqueTitle",listOf(s)).size) }
    }
    @Test fun `literal exact searches verify beyond top hundred and preserve UTF16 ranges`() {
        val sessions=(0..110).map { session(it.toString()) }
        index(tmp.root.toPath().resolve("many.sqlite"),extractor={ if(it.id=="110")listOf("😺 İSTANBUL database locked") else listOf("database-locked") }).use {
            val result=it.search("\"database locked\"",sessions).single();assertEquals("110",result.session.id)
            assertEquals(12,result.range!!.first)
            assertTrue(it.search("\"database next\"",sessions).isEmpty())
        }
    }
    @Test fun `contentless tables contain no text and snapshots contain no queries`() {
        val s=session();val db=tmp.root.toPath().resolve("index.sqlite")
        index(db).use { it.search("synthetic_query_marker",listOf(s)) }
        JDBC.createConnection("jdbc:sqlite:$db",Properties()).use { c -> c.createStatement().use { st ->
            listOf("body","title","path").forEach { table -> st.executeQuery("SELECT count(*) FROM $table WHERE text IS NOT NULL").use { r ->r.next();assertEquals(0,r.getInt(1)) } }
        } }
        assertFalse(String(Files.readAllBytes(db),Charsets.ISO_8859_1).contains("synthetic_query_marker"))
    }
}
