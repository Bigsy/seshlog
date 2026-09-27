package com.hedworth.seshlog.ui

import com.hedworth.seshlog.model.Session
import com.intellij.openapi.actionSystem.DataKey
import java.nio.file.Path

object SeshlogDataKeys {
    @JvmField
    val SESSION: DataKey<Session> = DataKey.create("seshlog.session")

    @JvmField
    val PANEL: DataKey<SessionTreePanel> = DataKey.create("seshlog.panel")

    /** Project row cwd supplied by the tree; absent when a session row is selected. */
    @JvmField
    val PROJECT_CWD: DataKey<Path> = DataKey.create("seshlog.project.cwd")
}
