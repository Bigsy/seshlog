package com.hedworth.seshlog.ui

import com.hedworth.seshlog.index.ResolvedPaths
import com.hedworth.seshlog.model.*
import com.hedworth.seshlog.settings.AgentFilterMode
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Path
import java.time.Instant

class WorktreeScopeTest {
    private val main = Path.of("/synthetic/main")
    private fun checkout(root: Path, linked: Boolean) = Checkout(root,
        if (linked) main.resolve(".git/worktrees/${root.fileName}") else root.resolve(".git"), main.resolve(".git"),
        if (linked) Checkout.Kind.LINKED else Checkout.Kind.MAIN, "topic", null)
    private fun session(path: Path) = Session(AgentKind.CODEX, path.toString(), "Title", path, null,
        null, Instant.EPOCH, null, false, null, null, 2, true)

    @Test fun `nested and external siblings use the same browse search and attention scope`() {
        val nested = main.resolve(".claude/worktrees/topic")
        val external = Path.of("/synthetic/external")
        val subdir = main.resolve("src")
        val layouts = mapOf(main to checkout(main, false), subdir to checkout(main, false),
            nested to checkout(nested, true), external to checkout(external, true))
        val snapshot = ResolvedPaths.resolve(layouts.keys, resolver = { it }, repositoryResolver = { null },
            checkoutResolver = { path, _ -> layouts[path] })
        val all = listOf(subdir, nested, external).map(::session)
        val filters = SessionListFilters(false, false, false, 1, AgentFilterMode.All)
        val browse = SessionListViewModel.build(all, listOf(main), snapshot, filters)
        assertEquals(listOf(all[0]), browse.visible)
        // Search uses the same visible function to construct candidates.
        assertEquals(browse.visible, SessionListViewModel.visible(all, listOf(main), snapshot, filters))
        assertEquals(all, SessionListViewModel.visible(all, listOf(main), snapshot, filters.copy(includeWorktrees = true)))
        assertEquals(listOf(all[0]), AttentionProjectFilter.apply(all, listOf(main), false, false,
            AgentFilterMode.All, emptySet(), minPrompts = 1, canonical = snapshot::canonical,
            repository = snapshot::repository, inScope = { snapshot.inScope(it, listOf(main), false) }))
        assertEquals(all, SessionListViewModel.visible(all, listOf(main, external, nested), snapshot, filters))
    }

    @Test fun `large history resolves paths once and refreshed HEAD changes session equality`() {
        val paths = (0 until 50_000).map { main.resolve("topic-${it % 100}") }
        var calls = 0
        var branch = "old"
        var available = true
        val resolve: (Path, Checkout?) -> Checkout? = { path, _ ->
            calls++; checkout(path, true).copy(branch = branch, available = available)
        }
        val first = ResolvedPaths.resolve(paths, resolver = { it }, repositoryResolver = { null }, checkoutResolver = resolve)
        assertEquals(100, calls)
        val before = session(paths.first()).copy(checkout = first.checkout(paths.first()))
        branch = "new"
        val next = ResolvedPaths.resolve(paths, first, resolver = { it }, repositoryResolver = { null }, refresh = true, checkoutResolver = resolve)
        assertEquals(200, calls)
        assertNotEquals(before, before.copy(checkout = next.checkout(before.cwd)))
        available = false
        val deleted = ResolvedPaths.resolve(paths, next, resolver = { it }, repositoryResolver = { null }, refresh = true, checkoutResolver = resolve)
        repeat(3) { paths.forEach { deleted.checkout(it); deleted.repository(it); deleted.canonical(it) } }
        assertEquals(300, calls)
        assertFalse(deleted.checkout(before.cwd)!!.available)
        assertEquals(first.repository(before.cwd), deleted.repository(before.cwd))
    }
}
