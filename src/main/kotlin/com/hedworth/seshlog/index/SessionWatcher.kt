package com.hedworth.seshlog.index

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import java.nio.file.Path

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
                    if (events.any { e -> isUnderRoots(e.path, currentRoots) }) schedule()
                }
            })
    }

    fun start(newRoots: List<Path>) {
        roots = newRoots
        // Watch registration and the initial VFS refresh touch the filesystem — keep off the EDT.
        AppExecutorUtil.getAppExecutorService().execute {
            val lfs = LocalFileSystem.getInstance()
            val old = watchRequests
            val paths = newRoots.map { it.toAbsolutePath().toString() }.toSet()
            watchRequests = lfs.replaceWatchedRoots(old, paths, emptySet())
            // The watcher only reports files the VFS knows about, so make it aware of the tree.
            for (root in newRoots) {
                val vf = lfs.refreshAndFindFileByNioFile(root) ?: continue
                VfsUtil.markDirtyAndRefresh(true, true, false, vf)
            }
            LOG.debug("Watching ${paths.joinToString()}")
        }
    }

    private fun isUnderRoots(path: String, roots: List<Path>): Boolean =
        roots.any { root -> path.startsWith(root.toAbsolutePath().toString()) }

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
    }
}
