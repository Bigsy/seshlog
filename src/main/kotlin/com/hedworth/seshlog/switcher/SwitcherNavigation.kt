package com.hedworth.seshlog.switcher

import com.hedworth.seshlog.index.GitRepository
import com.hedworth.seshlog.index.SessionFilter
import com.hedworth.seshlog.index.SessionIndex
import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.model.Checkout
import com.hedworth.seshlog.restore.SessionRestoreManager
import com.hedworth.seshlog.settings.SessionOrganisation
import com.hedworth.seshlog.terminal.OwnedTerminalTabs
import com.hedworth.seshlog.terminal.TerminalTabs
import com.hedworth.seshlog.worktree.OpenProjectSnapshot
import com.hedworth.seshlog.worktree.WorktreeProjects
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import java.nio.file.Files

internal class SwitcherNavigation(
    private val source: Project,
    private val active: () -> Boolean,
    private val report: (String) -> Unit,
    private val done: () -> Unit,
) {
    private val index get() = SessionIndex.getInstance()
    private fun current(s: Session) = index.sessions.firstOrNull { it.kind == s.kind && it.id == s.id }
    private fun owners(s: Session) = WorktreeProjects.projectSet().filter { OwnedTerminalTabs.getInstance(it).owns(s.id) }
    private fun unique(s: Session) = index.sessions.count { it.id == s.id } == 1
    private fun stopped(s: Session) = current(s)?.isLive == false && owners(s).isEmpty() && unique(s) &&
        !SessionOrganisation.getInstance().metadata(s.id).hidden &&
        switcherSessions(index.sessions, emptySet()).any { it.kind == s.kind && it.id == s.id }

    private fun request() = SwitcherRequest(::current,
        { SessionOrganisation.getInstance().metadata(it.id).hidden },
        { ApplicationManager.getApplication().executeOnPooledThread(it) },
        { ApplicationManager.getApplication().invokeLater(it) },
        { active() && !source.isDisposed }, report)

    fun focus(s: Session, observed: Checkout? = s.checkout) {
        val project = owners(s).singleOrNull()
        val target = project?.let {
            OwnedTerminalTabs.getInstance(it).navigationTarget(s) {
                unique(s) && sameSwitchTarget(s, current(s)) && !SessionOrganisation.getInstance().metadata(s.id).hidden
            }
        }
        if (target == null || !unique(s)) { report("No unambiguous associated terminal; refresh and try again"); return }
        request().run(s,
            validate = {
                if (!target.inspect()) "Terminal identity could not be verified; refresh and try again" else checkoutProblem(s, observed, focusOnly = true)
            },
            ready = { unique(s) && target.current() },
            effect = {
                ProjectUtil.focusProjectWindow(project, true)
                if (target.focus()) done() else report("Terminal closed or moved; refresh and try again")
            })
    }

    fun openWorktree(s: Session) {
        request().run(s, { checkoutProblem(s) }, { unique(s) }, {
            val accepted = WorktreeProjects.openFromSwitcher(source, it) { candidate ->
                unique(candidate) && !SessionOrganisation.getInstance().metadata(candidate.id).hidden
            }
            if (accepted) done() else report("Worktree is busy or unavailable; please try again")
        })
    }

    fun resume(s: Session, observed: Checkout? = s.checkout) {
        var projects: OpenProjectSnapshot<Project>? = null
        var destination: Project? = null
        var command: String? = null
        request().run(s,
            validate = {
                index.requireFreshScan(s.kind)
                val problem = checkoutProblem(s, observed)
                if (problem == null) {
                    projects = WorktreeProjects.capture()
                    val directory = SessionFilter.canonical(s.cwd)
                    val checkout = observed ?: GitRepository.checkout(directory)
                    destination = switcherDestination(directory, checkout, projects) ?: source
                    command = index.resumeCommand(s)
                }
                problem
            },
            ready = { stopped(s) && (projects == null || projects.projects == WorktreeProjects.projectSet()) },
            effect = { latest ->
                if (latest.checkout?.linked == true) {
                    val accepted = WorktreeProjects.openAndResume(source, latest,
                        // WorktreeOpening calls this on workers too: use only index identity here.
                        allowed = { candidate -> !candidate.isLive && sameSwitchTarget(latest, candidate) },
                        uiAllowed = ::stopped,
                        resume = { destination, selected ->
                            // The trust/import flow can take arbitrarily long. Never launch from its old running state.
                            check(stopped(selected) && sameSwitchTarget(selected, current(selected))) {
                                "Session started, changed or was hidden while the worktree opened; select it again"
                            }
                            launch(destination, selected, command!!)
                        })
                    if (accepted) done() else report("Worktree is busy or unavailable; please try again")
                } else {
                    val target = destination!!
                    check(!target.isDisposed)
                    ProjectUtil.focusProjectWindow(target, true)
                    launch(target, latest, command!!)
                    done()
                }
            })
    }

    private fun launch(project: Project, session: Session, command: String) {
        TerminalTabs.resume(project, session, command)
        SessionRestoreManager.getInstance(project).recordLaunch(session)
    }

    /** Worker only. Missing locations may still focus their existing terminal; never launch there. */
    private fun checkoutProblem(s: Session, checkout: Checkout? = s.checkout, focusOnly: Boolean = false): String? {
        if (!focusOnly && s.location?.uncertain == true) return "Location uncertain; refresh after the agent records its directory"
        if (checkout != null) {
            val verified = GitRepository.checkout(checkout.root, checkout)
            if (verified?.available == true) {
                if (!checkout.sameIdentity(verified)) return "Checkout was replaced; refresh and select the session again"
            } else if (!focusOnly) return "Checkout is missing or unreadable"
            if (!focusOnly && GitRepository.checkout(s.cwd)?.let(checkout::sameIdentity) != true)
                return "Recorded directory no longer belongs to this checkout"
        }
        if (!focusOnly && !Files.isDirectory(s.cwd)) return "Recorded directory is missing or unreadable"
        return null
    }
}
