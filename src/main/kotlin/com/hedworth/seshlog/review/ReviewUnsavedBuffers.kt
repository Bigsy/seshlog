package com.hedworth.seshlog.review

import com.intellij.openapi.fileEditor.FileDocumentManager
import java.nio.file.Path

/** EDT capture only; never saves documents or reads their text/files. */
internal object ReviewUnsavedBuffers {
    fun capture(recipient: ReviewRecipient): Set<String> {
        val manager = FileDocumentManager.getInstance()
        val root = recipient.checkout.root
        return manager.unsavedDocuments.mapNotNull { document ->
            val file = manager.getFile(document) ?: return@mapNotNull null
            val path = runCatching { Path.of(file.path).toAbsolutePath().normalize() }.getOrNull() ?: return@mapNotNull null
            path.takeIf { it.startsWith(root) && it != root }?.let { root.relativize(it).joinToString("/") }
        }.toSet()
    }
}
