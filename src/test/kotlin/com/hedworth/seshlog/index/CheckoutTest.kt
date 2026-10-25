package com.hedworth.seshlog.index

import com.google.gson.JsonParser
import com.hedworth.seshlog.model.Checkout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class CheckoutTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun layout(): Path {
        val root = tmp.root.toPath().toRealPath()
        val json = javaClass.getResource("/fixtures/locations/git-layout.json")!!.readText()
        JsonParser.parseString(json).asJsonObject.entrySet().forEach { (name, text) ->
            val path = root.resolve(name)
            Files.createDirectories(path.parent)
            Files.writeString(path, text.asString)
        }
        return root
    }

    @Test fun `linked identity uses metadata and backlink not branch or gitfile`() {
        val root = layout()
        val main = GitRepository.checkout(root.resolve("main"))!!
        val topic = GitRepository.checkout(Files.createDirectories(root.resolve("topic space ü/src")))!!
        assertEquals(root.resolve("topic space ü"), topic.root)
        assertTrue(topic.linked)
        assertEquals(main.commonDir, topic.commonDir)
        assertEquals("detached HEAD (12345678)", topic.branchLabel)
        assertFalse(GitRepository.checkout(root.resolve("main/sub"))!!.linked)
        assertNull(GitRepository.checkout(main.gitDir)) // bare metadata is not a checkout
        val alias = root.resolve("alias")
        Files.createSymbolicLink(alias, topic.root)
        assertTrue(topic.sameIdentity(GitRepository.checkout(alias)!!))
        Files.writeString(topic.root.resolve(".git"), "gitdir: ${topic.gitDir}")
        Files.writeString(topic.gitDir.resolve("commondir"), main.gitDir.toString())
        assertTrue(topic.sameIdentity(GitRepository.checkout(alias)!!))
    }

    @Test fun `refresh retains unavailable identity and refuses enclosing or replaced checkout`() {
        val root = layout()
        val main = root.resolve("main")
        val nested = Files.createDirectories(main.resolve("nested"))
        val gitDir = Files.createDirectories(main.resolve(".git/worktrees/nested"))
        Files.writeString(gitDir.resolve("HEAD"), "ref: refs/heads/topic")
        Files.writeString(gitDir.resolve("commondir"), "../..")
        Files.writeString(gitDir.resolve("gitdir"), nested.resolve(".git").toString())
        Files.writeString(nested.resolve(".git"), "gitdir: $gitDir")
        val old = GitRepository.checkout(nested)!!
        Files.delete(nested.resolve(".git"))
        assertFalse(GitRepository.checkout(nested, old)!!.available)
        Files.delete(nested)
        assertFalse(GitRepository.checkout(nested, old)!!.available)
        Files.createDirectory(nested)
        Files.writeString(nested.resolve(".git"), "gitdir: $gitDir")
        assertTrue(GitRepository.checkout(nested, old)!!.available)
        Files.writeString(gitDir.resolve("HEAD"), "ref: refs/heads/new-topic")
        assertEquals("new-topic", GitRepository.checkout(nested, old)!!.branch)
        Files.writeString(gitDir.resolve("gitdir"), "/unrelated/.git")
        assertFalse(GitRepository.checkout(nested, old)!!.available)
        assertNull(GitRepository.checkout(nested))
    }

    @Test fun `snapshot refresh revalidates negative results and filters nested worktrees by identity`() {
        val root = layout()
        val main = root.resolve("main")
        val topic = root.resolve("topic space ü")
        val before = ResolvedPaths.resolve(listOf(main, topic))
        assertTrue(before.inScope(topic, listOf(main), true))
        assertFalse(before.inScope(topic, listOf(main), false))
        Files.writeString(topic.resolve(".git"), "malformed")
        val after = ResolvedPaths.resolve(listOf(main, topic), before, refresh = true)
        assertFalse(after.checkout(topic)!!.available)
        assertTrue(after.belongsToRepository(topic, listOf(main)))
    }
}
