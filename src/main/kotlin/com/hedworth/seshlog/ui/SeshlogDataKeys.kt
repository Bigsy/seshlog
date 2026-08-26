package com.hedworth.seshlog.ui

import com.hedworth.seshlog.model.Session
import com.intellij.openapi.actionSystem.DataKey

object SeshlogDataKeys {
    @JvmField
    val SESSION: DataKey<Session> = DataKey.create("seshlog.session")

    @JvmField
    val PANEL: DataKey<SessionTreePanel> = DataKey.create("seshlog.panel")
}
