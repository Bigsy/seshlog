package com.hedworth.seshlog.index

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import java.nio.file.Path
import java.util.concurrent.Future

/**
 * Watches the agent data directories through IntelliJ's native file watcher and triggers a
 * debounced rescan. Claude Code appends to transcripts constantly while busy, hence the debounce.
 */
class SessionWatcher(parent: Disposable, private val onChange: () -> Unit) : Disposable {
    private val LOG = logger<SessionWatcher>()
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    @Volatile
    private var roots: List<Path> = emptyList()

    @Volatile
    private var watchRequests: Set<LocalFileSystem.WatchRequest> = emptySet()

    init {
        ApplicationManager.getApplication().messageBus.connect(parent)
            .subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    val currentRoots = roots
                    if (currentRoots.isEmpty()) return
                    if (events.none { e -> isUnderRoots(e.path, currentRoots) }) return
                    // A new directory (Codex's per-day folder, a new Claude project) starts unlisted,
                    // so writes inside it would go unreported. List it off the EDT, then rescan.
                    val created = events.filter { it is VFileCreateEvent && it.isDirectory && isUnderRoots(it.path, currentRoots) }
                        .mapNotNull { it.file }
                    if (created.isEmpty()) schedule()
                    else AppExecutorUtil.getAppExecutorService().execute { created.forEach(::list); schedule() }
                }
            })
    }

    fun start(newRoots: List<Path>): Future<*> {
        roots = newRoots
        // Watch registration and the initial VFS refresh touch the filesystem — keep off the EDT.
        return AppExecutorUtil.getAppExecutorService().submit {
            val lfs = LocalFileSystem.getInstance()
            val old = watchRequests
            val paths = newRoots.map { it.toAbsolutePath().toString() }.toSet()
            watchRequests = lfs.replaceWatchedRoots(old, paths, emptySet())
            // The VFS reports changes only inside directories it has listed; a refresh alone lists
            // nothing. Seshlog reads with java.nio, so list the trees here or no event ever arrives.
            for (root in newRoots) {
                val vf = lfs.refreshAndFindFileByNioFile(root) ?: continue
                list(vf)
                VfsUtil.markDirtyAndRefresh(true, true, false, vf)
            }
            LOG.debug("Watching ${paths.joinToString()}")
        }
    }

    /** Load [file]'s subtree into the VFS (names and stamps only, never content). Off the EDT. */
    private fun list(file: VirtualFile) {
        if (!file.isValid) return
        VfsUtilCore.visitChildrenRecursively(file, object : VirtualFileVisitor<Any>() {})
    }

    // VFS paths always use '/', including on Windows, so the roots are normalised to match.
    private fun isUnderRoots(path: String, roots: List<Path>): Boolean =
        isUnderRoots(path, roots.map { it.toAbsolutePath().toString().replace('\\', '/') })

    private fun schedule() {
        if (alarm.isDisposed) return
        alarm.cancelAllRequests()
        alarm.addRequest({ onChange() }, DEBOUNCE_MS)
    }

    override fun dispose() {
        val lfs = LocalFileSystem.getInstance()
        lfs.removeWatchedRoots(watchRequests)
        watchRequests = emptySet()
    }

    companion object {
        const val DEBOUNCE_MS = 1500

        /**
         * True when [path] is one of [roots] or sits inside one of them. Matching whole path
         * segments matters: a sibling that merely shares a root's prefix is not a match — notably
         * `opencode.db-shm`, the wal-index every SQLite reader (Seshlog's own scan included)
         * touches, which would otherwise make each scan schedule the next one.
         */
        internal fun isUnderRoots(path: String, roots: List<String>): Boolean =
            roots.any { root -> path == root || path.startsWith("$root/") }
    }
}
