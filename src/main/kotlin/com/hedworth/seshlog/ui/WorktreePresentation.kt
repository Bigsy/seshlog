package com.hedworth.seshlog.ui

import com.hedworth.seshlog.model.LocationSource
import com.hedworth.seshlog.model.Session

/** Pure presentation shared by browse/search rows and accessibility. No path resolution here. */
object WorktreePresentation {
    fun details(session: Session): String? {
        val checkout = session.checkout
        if (checkout?.linked != true) {
            if (session.location?.uncertain == true) return "location uncertain"
            return checkout?.branchLabel ?: session.displayBranch
        }
        return buildList {
            add("worktree ${checkout.root.fileName}")
            add(checkout.branchLabel)
            if (!checkout.available) add("unavailable")
            if (session.location?.uncertain == true) add("location uncertain")
        }.joinToString(" · ")
    }

    fun description(session: Session): List<String> = buildList {
        val checkout = session.checkout
        if (checkout != null) {
            add("${if (checkout.linked) "Worktree" else "Checkout"}: ${checkout.root}")
            add("${if (checkout.available) "Observed" else "Last observed"} Git branch: ${checkout.branchLabel}")
            if (session.cwd != checkout.root) add("Reported session directory: ${session.cwd}")
            if (!checkout.available) add("Unavailable: ${checkout.problem ?: "checkout is no longer available"}")
            session.displayBranch?.takeIf { it != checkout.branch }?.let { add("Session-reported branch: $it") }
        }
        val evidence = session.location
        if (evidence != null) {
            add(when (evidence.source) {
                LocationSource.INITIAL -> "Initial recorded directory; later moves may not have been recorded."
                LocationSource.CODEX_DATABASE -> "Last recorded directory from Codex database; change time unknown."
                else -> "Latest recorded session location; individual tools may use other directories."
            })
            if (evidence.uncertain) add("Location uncertain: ${evidence.explanation ?: "conflicting or insufficient evidence"}")
            add("Location source: ${evidence.source.name.lowercase().replace('_', ' ')}" +
                (evidence.timestamp?.let { "; recorded $it" } ?: ""))
        }
    }
}
