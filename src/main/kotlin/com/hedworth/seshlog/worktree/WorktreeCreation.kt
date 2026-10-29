package com.hedworth.seshlog.worktree

import com.hedworth.seshlog.index.GitRepository
import com.hedworth.seshlog.index.SessionFilter
import com.hedworth.seshlog.model.Checkout
import com.hedworth.seshlog.model.Session
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

internal data class WorktreeCreationOptions(val base: String, val branch: String, val destination: Path)
internal data class CreatedWorktree(val checkout: Checkout, val directory: Path)

/** Git creation only. Never copies dirty files, runs setup scripts, or rolls back a created checkout. */
internal object WorktreeCreator {
    fun create(session: Session, source: Checkout, options: WorktreeCreationOptions,
               protectedRoots: Collection<Path>): CreatedWorktree {
        WorktreeReview.verify(source)
        val cwd = SessionFilter.canonical(session.cwd)
        check(Files.isDirectory(cwd) && cwd.startsWith(source.root) &&
            GitRepository.checkout(cwd)?.let(source::sameIdentity) == true) { "Source directory no longer belongs to this checkout" }
        val destination = options.destination.toAbsolutePath().normalize()
        check(destination.parent != null && Files.isDirectory(destination.parent)) { "Choose an existing parent folder" }
        check(destination.parent.toRealPath() == destination.parent) { "Choose the destination using its real parent path, not a symlink" }
        check(!Files.exists(destination, NOFOLLOW_LINKS)) { "Destination already exists; choose a new folder" }
        check(GitRepository.checkout(destination.parent) == null) { "Choose a destination outside existing checkouts" }
        check(protectedRoots.none { protected ->
            listOf(destination, source.gitDir, source.commonDir).any { it.startsWith(protected) || protected.startsWith(it) }
        }) { "Destination or Git metadata overlaps protected agent storage" }
        require(options.branch.isNotBlank() && !options.branch.startsWith("-") && options.branch.length <= 200) { "Enter a new branch name" }
        GitRead.run(source.root, listOf("check-ref-format", "refs/heads/${options.branch}"), 4096)
        val base = GitRead.commit(source.root, options.base)
        val relative = source.root.relativize(cwd)
        if (relative.toString().isNotEmpty()) {
            val type = GitRead.run(source.root, listOf("cat-file", "-t", "$base:${relative.toString().replace('\\', '/')}"), 4096)
                .toString(Charsets.UTF_8).trim()
            check(type == "tree") { "The recorded subdirectory does not exist in the selected base; choose another base" }
        }
        WorktreeReview.verify(source)
        val hooks = Files.createTempDirectory("seshlog-empty-hooks")
        try {
            val result = GitCommand.run(GitCommand.executable(), source.root,
                listOf("--git-dir=${source.commonDir}", "-c", "core.hooksPath=$hooks", "worktree", "add",
                    "-b", options.branch, "--", destination.toString(), base))
            check(result.exitCode == 0) {
                "Git could not create the worktree: ${result.output.take(1500)}. Check $destination and branch ${options.branch}; partial results are kept."
            }
        } finally { Files.deleteIfExists(hooks) }
        val checkout = GitRepository.checkout(destination)
        check(checkout?.linked == true && checkout.available && checkout.commonDir == source.commonDir && checkout.branch == options.branch) {
            "Worktree may have been created at $destination, but verification failed; inspect it before retrying"
        }
        val directory = destination.resolve(relative)
        check(Files.isDirectory(directory) && SessionFilter.canonical(directory).startsWith(destination)) { "Created worktree has an unavailable subdirectory: $directory" }
        check(protectedRoots.none { SessionFilter.canonical(destination.resolve(".idea")).startsWith(it) }) {
            "Worktree kept at $destination; its IDE configuration path overlaps protected storage"
        }
        return CreatedWorktree(checkout, directory)
    }
}

/** Keeps partial success explicit across worker/UI/trust boundaries. */
internal class WorktreeCreation<P : Any>(
    private val create: () -> CreatedWorktree,
    private val validate: (CreatedWorktree) -> Unit,
    private val open: (CreatedWorktree) -> P?,
    private val destinationValid: (P, CreatedWorktree) -> Boolean,
    private val launch: (P, CreatedWorktree) -> Unit,
    private val worker: (() -> Unit) -> Unit,
    private val ui: (() -> Unit) -> Unit,
    private val active: () -> Boolean,
    private val report: (String) -> Unit,
    private val finished: () -> Unit,
) {
    fun start() = background(null) {
        val created = create()
        ui {
            if (!active()) { finished(); return@ui }
            try {
                val project = open(created)
                if (project == null) end(created, "Opening was cancelled; no agent was launched")
                else background(created) {
                    validate(created)
                    check(destinationValid(project, created)) { "The destination window closed or changed" }
                    ui {
                        try {
                            if (active()) launch(project, created)
                        } catch (e: Exception) { report("Worktree kept at ${created.checkout.root}. Fork launch failed: ${e.message}") }
                        finally { finished() }
                    }
                }
            } catch (e: Exception) { end(created, e.message ?: "Opening failed") }
        }
    }

    private fun background(created: CreatedWorktree?, task: () -> Unit) {
        try {
            worker {
                try { if (active()) task() else finished() }
                catch (e: Exception) { ui { end(created, e.message ?: "Worktree operation failed") } }
            }
        } catch (e: Exception) { end(created, e.message ?: "Could not schedule worktree operation") }
    }
    private fun end(created: CreatedWorktree?, message: String) {
        try { if (active()) report(if (created == null) message else "Worktree kept at ${created.checkout.root}. $message") }
        finally { finished() }
    }
}
