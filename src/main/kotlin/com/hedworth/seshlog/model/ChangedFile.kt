package com.hedworth.seshlog.model

import java.nio.file.Path

enum class ChangedFileOperation { WRITE, EDIT, PATCH, DELETE, MOVE }

data class ChangedFileScan(val files: List<ChangedFile>, val partial: Boolean)

/**
 * A file operation recorded by an agent. [historicalContent] is present only for complete Write
 * calls. Edit and patch records retain their recorded payload in [recordedText]; they do not
 * pretend that payload is a historical full-file snapshot.
 */
data class ChangedFile(
    val path: Path,
    val operation: ChangedFileOperation,
    val historicalContent: String?,
    val recordedText: String,
    val sourceId: String,
    val toolName: String,
    val previousPath: Path? = null,
)
