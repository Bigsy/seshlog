package com.hedworth.seshlog.opencode

import org.sqlite.JDBC
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties

/** Builds `opencode.db` in [dir] from `fixtures/opencode_fixture.sql`, so the fixture stays readable and reviewable. */
object OpenCodeFixture {
    fun create(dir: Path): Path {
        val script = Paths.get(OpenCodeFixture::class.java.getResource("/fixtures/opencode_fixture.sql")!!.toURI())
        val statements = Files.readString(script)
            .lineSequence()
            .filterNot { it.trimStart().startsWith("--") }
            .joinToString("\n")
            .split(Regex(";\\s*\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val db = dir.resolve(OpenCodeSessionProvider.DATABASE_FILE)
        JDBC.createConnection("jdbc:sqlite:$db", Properties()).use { conn ->
            conn.createStatement().use { st -> statements.forEach { st.execute(it) } }
        }
        return db
    }
}
