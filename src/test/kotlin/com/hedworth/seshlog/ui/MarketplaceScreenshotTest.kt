package com.hedworth.seshlog.ui

import com.hedworth.seshlog.index.SearchHit
import com.hedworth.seshlog.index.SearchMatchSource
import com.hedworth.seshlog.index.SearchPrimaryMatch
import com.hedworth.seshlog.index.SearchSources
import com.hedworth.seshlog.model.*
import com.hedworth.seshlog.settings.SeshlogSettings
import com.hedworth.seshlog.settings.AgentFilterMode
import com.hedworth.seshlog.settings.AgentFilterState
import com.hedworth.seshlog.switcher.SessionSwitcherPanel
import com.hedworth.seshlog.switcher.SwitcherRow
import com.hedworth.seshlog.switcher.SwitcherSearch
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.util.ui.UIUtil
import java.awt.Container
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import javax.imageio.ImageIO
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.UIManager

/** Opt-in asset renderer, not a UI regression test. Uses only synthetic, in-memory sessions. */
class MarketplaceScreenshotTest : BasePlatformTestCase() {
    override fun isIconRequired() = System.getenv("SESHLOG_SCREENSHOTS") == "1"

    fun testRenderMarketplaceComponents() {
        if (System.getenv("SESHLOG_SCREENSHOTS") != "1") return
        val output = Path.of(System.getenv("SESHLOG_SCREENSHOT_DIR") ?: error("Set SESHLOG_SCREENSHOT_DIR"))
        Files.createDirectories(output)
        val previousLaf = UIManager.getLookAndFeel()
        val previousDark = !JBColor.isBright()
        val settings = SeshlogSettings.getInstance()
        val oldAll = settings.showAllProjects
        val oldCollapsed = settings.previewCollapsed
        val oldSources = settings.searchSources
        val owner = Disposer.newDisposable()
        try {
            UIManager.setLookAndFeel("com.intellij.ide.ui.laf.darcula.DarculaLaf")
            JBColor.setDark(true)
            IconLoader.activate()
            IconLoader.setUseDarkIcons(true)
            settings.showAllProjects = true
            settings.previewCollapsed = true
            settings.searchSources = SearchSources.DEFAULT
            AgentFilterState.getInstance(project).mode = AgentFilterMode.All
            val panel = SessionTreePanel(project, owner)
            SwingUtilities.updateComponentTreeUI(panel)
            val sessions = demoSessions()
            panel.render(sessions)
            panel.refreshBadges(setOf("demo-parent", "demo-fork", "demo-claude"))
            val renderer = panel.tree.cellRenderer as SessionCellRenderer
            renderer.activeSessionId = "demo-fork"
            val treeView = ScrollPaneFactory.createScrollPane(panel.tree)
            capture(treeView, output.resolve("worktree-sessions.png"), 700, 510)

            val hits = listOf(
                SearchHit(sessions[0], 5.0, false, "Keep checkout validation next to the payment request.",
                    primary = SearchPrimaryMatch(SearchMatchSource.USER)),
                SearchHit(sessions[1], 3.0, false, "The checkout validation now covers missing billing details."),
            )
            panel.searchField.text = "checkout validation"
            panel.renderSearchResults("checkout validation", hits)
            capture(treeView, output.resolve("session-search.png"), 700, 185)
            capture(panel.searchField.parent as JComponent, output.resolve("search-field.png"), 700, 34)
            captureSwitcher(output, sessions)
        } finally {
            Disposer.dispose(owner)
            settings.showAllProjects = oldAll
            settings.previewCollapsed = oldCollapsed
            settings.searchSources = oldSources
            UIManager.setLookAndFeel(previousLaf)
            JBColor.setDark(previousDark)
            IconLoader.setUseDarkIcons(previousDark)
            IconLoader.deactivate()
        }
    }

    private fun captureSwitcher(output: Path, sessions: List<Session>) {
        val panel = SessionSwitcherPanel(project, live = false)
        try {
            SwingUtilities.updateComponentTreeUI(panel)
            panel.query.text = "checkout"
            val titles = listOf("Refine checkout validation", "Refine checkout validation (fork)",
                "Add checkout refund history", "Review checkout edge cases", "Improve checkout receipt emails",
                "Document checkout payment events")
            val rows = sessions.mapIndexed { index, session ->
                SwitcherRow(session.copy(title = titles[index], lastActivityAt = session.lastActivityAt.minusSeconds(index * 180L)),
                    projectNames = listOf("atlas"), associated = index == 2, unread = index == 1)
            }
            panel.results.apply(panel.results.begin(panel.query.text), SwitcherSearch.find(rows, panel.query.text))
            panel.render()
            capture(panel, output.resolve("session-switcher.png"), 760, 380)
        } finally { Disposer.dispose(panel) }
    }

    private fun capture(component: JComponent, file: Path, width: Int, height: Int) {
        component.setSize(width, height)
        fun layout(container: Container) {
            container.doLayout()
            container.components.filterIsInstance<Container>().forEach(::layout)
        }
        repeat(3) { layout(component) }
        val image = BufferedImage(width * 2, height * 2, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.scale(2.0, 2.0)
            graphics.color = UIUtil.getPanelBackground()
            graphics.fillRect(0, 0, width, height)
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            component.printAll(graphics)
        } finally { graphics.dispose() }
        ImageIO.write(image, "png", file.toFile())
    }

    private fun demoSessions(): List<Session> {
        val repository = Path.of("/workspace/atlas")
        val now = Instant.now()
        fun session(id: String, title: String, kind: AgentKind, folder: String, branch: String?, activity: Activity? = null): Session {
            val root = repository.resolve("worktrees/$folder")
            return Session(kind, "demo-$id", title, root, branch, now.minusSeconds(1200), now.minusSeconds(300),
                null, false, null, null, 6, true,
                activity = activity ?: Activity.UNKNOWN, activitySince = if (activity != null) now.minusSeconds(120) else null,
                location = SessionLocation(if (kind == AgentKind.CLAUDE_CODE) LocationSource.CLAUDE_CONTEXT else LocationSource.CODEX_CONTEXT, 1),
                checkout = Checkout(root, repository.resolve(".git/worktrees/$folder"), repository.resolve(".git"),
                    Checkout.Kind.LINKED, branch, if (branch == null) "2a7c49e5f03b" else null),
                recordedDirectoryAvailable = true)
        }
        return listOf(
            session("parent", "Refine checkout validation", AgentKind.CODEX, "checkout-flow", "feature/checkout-flow", Activity.WAITING),
            session("fork", "Refine checkout validation (fork)", AgentKind.CODEX, "checkout-flow", "feature/checkout-flow", Activity.WORKING),
            session("claude", "Add refund history", AgentKind.CLAUDE_CODE, "refund-history", "feature/refund-history", Activity.WAITING),
            session("review", "Review payment edge cases", AgentKind.CODEX, "payment-review", null),
            session("opencode", "Improve receipt emails", AgentKind.OPENCODE, "email-receipts", "feature/email-receipts").copy(location = null, checkout = null),
            session("pi", "Document payment events", AgentKind.PI, "payment-docs", "docs/payment-events").copy(location = null, checkout = null),
        )
    }
}
