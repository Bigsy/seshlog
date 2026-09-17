package com.hedworth.seshlog.codex

import com.hedworth.seshlog.copy.CopyContent
import com.hedworth.seshlog.copy.PlanReader
import com.hedworth.seshlog.model.Role
import com.hedworth.seshlog.model.Session

/** Codex's explicit final Plan Mode envelope; update_plan is a task checklist, not a plan. */
object CodexPlanReader {
    fun read(session: Session): CopyContent {
        var latest: CopyContent = CopyContent.Absent("No identifiable plan found for this session.")
        val failure = PlanReader.scan(session.transcriptPath) { line ->
            val message = CodexConversationMessages.parseClipboardLine(line) ?: return@scan
            if (message.role != Role.ASSISTANT) return@scan
            val text = message.text.trim()
            if (!text.startsWith("<proposed_plan>\n") && !text.startsWith("<proposed_plan>\r\n")) return@scan
            val start = text.indexOf('\n') + 1
            val end = text.lastIndexOf("</proposed_plan>")
            latest = if (end < start || !text.endsWith("</proposed_plan>") || text[end - 1] != '\n')
                CopyContent.Failed("The latest proposed plan is incomplete.")
            else PlanReader.content(text.substring(start, end))
        }
        return failure ?: latest
    }
}
