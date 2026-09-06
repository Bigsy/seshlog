package com.hedworth.seshlog.ui.actions

import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.settings.SessionOrganisation
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

class PinSessionAction : SessionAction("Pin / Unpin") {
    override fun perform(project: Project, session: Session) {
        SessionOrganisation.getInstance().edit(session.id) { it.pinned = !it.pinned }
    }
}

class RenameSessionAction : SessionAction("Rename Locally…") {
    override fun perform(project: Project, session: Session) {
        val organisation = SessionOrganisation.getInstance()
        val title = Messages.showInputDialog(project, "Local title (leave blank to use the provider title):", "Rename Session", null,
            organisation.metadata(session.id).title, null) ?: return
        organisation.edit(session.id) { it.title = title.trim() }
    }
}

class HideSessionAction : SessionAction("Hide / Unhide") {
    override fun perform(project: Project, session: Session) {
        SessionOrganisation.getInstance().edit(session.id) { it.hidden = !it.hidden }
    }
}
