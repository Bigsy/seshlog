package com.hedworth.seshlog.review

import com.hedworth.seshlog.diagnostics.DiagnosticRef
import com.hedworth.seshlog.index.SessionIndex
import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.terminal.OwnedTerminalTabs
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager

internal data class ReviewOpenTarget(
    val project: Project, val session: Session, val proof: OwnedTerminalTabs.NavigationTarget, val tabRef: String?,
)
internal object ReviewTargets {
    /** EDT capture; process and filesystem proof is performed separately on the send worker. */
    fun capture(recipient: ReviewRecipient, delivery: Boolean = true): ReviewOpenTarget? {
        if (delivery && recipient.kind != AgentKind.CODEX) return null
        val sessions = SessionIndex.getInstance().sessions.filter { it.id == recipient.sessionId }
        if (sessions.size != 1 || sessions.single().kind != recipient.kind) return null
        val session = sessions.single()
        val matches = ProjectManager.getInstance().openProjects.filterNot { it.isDisposed }.mapNotNull { project ->
            val owned = OwnedTerminalTabs.getInstance(project)
            val terminal = owned.terminalFor(recipient.sessionId) ?: return@mapNotNull null
            val current = {
                val current = SessionIndex.getInstance().sessions.filter { it.id == recipient.sessionId }
                current.size == 1 && current.single().kind == recipient.kind &&
                    current.single().checkout?.let(recipient.checkout::sameIdentity) == true
            }
            val proof = (if (delivery) owned.reviewTarget(session, current) else owned.navigationTarget(session, current)) ?: return@mapNotNull null
            ReviewOpenTarget(project, session, proof, DiagnosticRef.tab(terminal.content))
        }
        return matches.singleOrNull() // Conflicting ownership is unavailable, never "nearest".
    }
}
