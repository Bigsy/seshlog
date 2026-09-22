package com.hedworth.seshlog.index

import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.StandardOpenOption.APPEND
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Agent data is read with java.nio, so the VFS has never listed it unless the watcher does. */
class SessionWatcherVfsTest : BasePlatformTestCase() {
    /** A native watcher notification amounts to this refresh; it only reports listed directories. */
    private fun rescanned(changes: AtomicInteger, root: VirtualFile): Boolean {
        VfsUtil.markDirtyAndRefresh(false, true, false, root)
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            if (changes.getAndSet(0) > 0) return true
            Thread.sleep(50)
        }
        return false
    }

    fun `test writes in never-listed and newly created directories trigger a rescan`() {
        val root = Files.createTempDirectory("seshlog-watch").toRealPath()
        try {
            val transcript = Files.writeString(Files.createDirectories(root.resolve("project")).resolve("old.jsonl"), "{}\n")
            val changes = AtomicInteger()
            val watcher = SessionWatcher(testRootDisposable) { changes.incrementAndGet() }
            Disposer.register(testRootDisposable, watcher)
            watcher.start(listOf(root)).get(10, TimeUnit.SECONDS)
            val vf = requireNotNull(LocalFileSystem.getInstance().findFileByNioFile(root))
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            changes.set(0)

            Files.writeString(transcript, "{\"more\":1}\n", APPEND)
            assertTrue("append to an existing transcript", rescanned(changes, vf))
            Files.writeString(root.resolve("project/new.jsonl"), "{}\n")
            assertTrue("new transcript in an existing directory", rescanned(changes, vf))
            // Codex creates a directory per day; the first write lands in a directory the VFS never saw.
            val day = Files.createDirectories(root.resolve("2026/09/22"))
            val rollout = Files.writeString(day.resolve("rollout.jsonl"), "{}\n")
            assertTrue("new directory", rescanned(changes, vf))
            Files.writeString(rollout, "{\"more\":1}\n", APPEND)
            assertTrue("write inside the new directory", rescanned(changes, vf))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
