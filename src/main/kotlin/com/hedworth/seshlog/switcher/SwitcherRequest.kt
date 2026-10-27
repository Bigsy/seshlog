package com.hedworth.seshlog.switcher

import com.hedworth.seshlog.model.Session

/** One explicit navigation request. All expensive evidence is collected between two identity guards. */
internal class SwitcherRequest(
    private val current: (Session) -> Session?,
    private val hidden: (Session) -> Boolean,
    private val worker: (() -> Unit) -> Unit,
    private val ui: (() -> Unit) -> Unit,
    private val active: () -> Boolean,
    private val report: (String) -> Unit,
) {
    fun run(selected: Session, validate: () -> String?, ready: () -> Boolean, effect: (Session) -> Unit) {
        fun unchanged() = sameSwitchTarget(selected, current(selected))
        if (!active()) return
        if (hidden(selected) || !unchanged() || !ready()) {
            report("Session or terminal changed; select it again")
            return
        }
        worker {
            val problem = try {
                if (!unchanged()) "Session location changed; select it again" else validate()
            } catch (_: Exception) { "Could not validate session or checkout; refresh and try again" }
            ui {
                if (!active()) return@ui
                val latest = current(selected)
                when {
                    hidden(selected) || !sameSwitchTarget(selected, latest) || !ready() -> report("Session or terminal changed; select it again")
                    problem != null -> report(problem)
                    else -> try { effect(latest!!) }
                        catch (_: Exception) { report("Could not navigate to the session; refresh and try again") }
                }
            }
        }
    }
}
