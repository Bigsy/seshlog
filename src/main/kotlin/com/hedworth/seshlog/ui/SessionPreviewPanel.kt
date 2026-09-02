package com.hedworth.seshlog.ui

import com.hedworth.seshlog.index.SessionIndex
import com.hedworth.seshlog.model.ConversationMessage
import com.hedworth.seshlog.model.Role
import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.settings.SeshlogSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/**
 * Bottom half of the tool window: the last N messages of the selected session, read lazily
 * through its provider on a background thread. Lets you decide *which* session to resume.
 */
class SessionPreviewPanel(parent: Disposable) : JBPanel<SessionPreviewPanel>(BorderLayout()), Disposable {
    private val LOG = logger<SessionPreviewPanel>()
    private val settings get() = SeshlogSettings.getInstance()

    private val editor = JEditorPane().apply {
        editorKit = HTMLEditorKitBuilder.simple()
        isEditable = false
        background = UIUtil.getPanelBackground()
        border = JBUI.Borders.empty(6)
    }
    private val header = JBLabel().apply { border = JBUI.Borders.empty(4, 6) }
    private val countSpinner = JSpinner(SpinnerNumberModel(settings.previewMessageCount, 1, MAX_MESSAGES, 1))

    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val executor = AppExecutorUtil.createBoundedApplicationPoolExecutor("Seshlog preview", 1)
    private val generation = AtomicLong()

    /** Session shown (or being loaded); null when nothing is selected. */
    var session: Session? = null
        private set

    /** Messages last rendered, for tests. */
    var messages: List<ConversationMessage> = emptyList()
        private set

    init {
        com.intellij.openapi.util.Disposer.register(parent, this)
        countSpinner.toolTipText = "How many of the most recent messages to show"
        countSpinner.addChangeListener {
            settings.previewMessageCount = countSpinner.value as Int
            scheduleLoad()
        }
        val top = JPanel(BorderLayout()).apply {
            add(header, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                isOpaque = false
                add(JBLabel("Last"))
                add(countSpinner)
                add(JBLabel("messages"))
            }, BorderLayout.EAST)
            border = JBUI.Borders.customLineBottom(JBColor.border())
        }
        add(top, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(editor, true), BorderLayout.CENTER)
        showSession(null)
    }

    /** Called on the EDT whenever the tree selection changes. */
    fun showSession(session: Session?) {
        this.session = session
        if (session == null) {
            generation.incrementAndGet()
            alarm.cancelAllRequests()
            header.text = ""
            render(emptyList(), "Select a session to preview its last messages.")
            return
        }
        header.text = "<html><b>${esc(session.title)}</b></html>"
        scheduleLoad()
    }

    private fun scheduleLoad() {
        alarm.cancelAllRequests()
        alarm.addRequest({ load() }, DEBOUNCE_MS)
    }

    private fun load() {
        val target = session ?: return
        val count = settings.previewMessageCount
        val myGen = generation.incrementAndGet()
        executor.execute {
            val result = try {
                SessionIndex.getInstance().providerFor(target).lastMessages(target, count)
            } catch (e: Exception) {
                LOG.debug("Cannot read last messages of ${target.kind} session ${target.id}", e)
                null
            }
            if (generation.get() != myGen) return@execute
            val app = ApplicationManager.getApplication()
            if (app == null || app.isDisposed) return@execute
            app.invokeLater({
                if (generation.get() != myGen) return@invokeLater
                when {
                    result == null -> render(emptyList(), "Transcript could not be read.")
                    result.isEmpty() -> render(emptyList(), "No conversation yet.")
                    else -> render(result, null)
                }
            })
        }
    }

    private fun render(msgs: List<ConversationMessage>, placeholder: String?) {
        messages = msgs
        editor.text = toHtml(msgs, placeholder)
        editor.caretPosition = editor.document.length // scroll to the newest message
    }

    private fun toHtml(msgs: List<ConversationMessage>, placeholder: String?): String {
        val fg = ColorUtil.toHtmlColor(UIUtil.getLabelForeground())
        val gray = ColorUtil.toHtmlColor(UIUtil.getContextHelpForeground())
        val userBg = ColorUtil.toHtmlColor(USER_BG)
        return buildString {
            append("<html><body style='color:$fg;font-family:${UIUtil.getLabelFont().family};font-size:${UIUtil.getLabelFont().size}pt'>")
            if (placeholder != null) append("<p style='color:$gray'><i>").append(esc(placeholder)).append("</i></p>")
            for (m in msgs) {
                val who = if (m.role == Role.USER) "You" else session?.kind?.displayName ?: "Assistant"
                val ts = m.timestamp?.let { TS.format(it.atZone(ZoneId.systemDefault())) } ?: ""
                val bg = if (m.role == Role.USER) " background-color:$userBg;" else ""
                append("<div style='margin:0 0 8px 0; padding:4px 6px;$bg'>")
                append("<span style='color:$gray'><b>").append(who).append("</b> ").append(ts).append("</span>")
                append("<pre style='margin:2px 0 0 0; white-space:pre-wrap; font-family:inherit'>").append(esc(truncate(m.text))).append("</pre>")
                append("</div>")
            }
            append("</body></html>")
        }
    }

    private fun truncate(text: String) =
        if (text.length > MAX_CHARS_PER_MESSAGE) text.substring(0, MAX_CHARS_PER_MESSAGE) + "\n… (${text.length - MAX_CHARS_PER_MESSAGE} more characters)" else text

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    override fun dispose() {
        generation.incrementAndGet()
        executor.shutdownNow()
    }

    companion object {
        const val MAX_MESSAGES = 50
        private const val MAX_CHARS_PER_MESSAGE = 20_000
        private const val DEBOUNCE_MS = 150
        private val TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        private val USER_BG = JBColor(0xEDF3FF, 0x2B3540)
    }
}
