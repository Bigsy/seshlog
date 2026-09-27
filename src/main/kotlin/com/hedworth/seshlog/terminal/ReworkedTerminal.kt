package com.hedworth.seshlog.terminal

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.platform.eel.provider.LocalEelDescriptor
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import com.intellij.terminal.frontend.view.TerminalView
import com.intellij.ui.content.Content
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.jetbrains.plugins.terminal.TerminalEngine
import org.jetbrains.plugins.terminal.TerminalOptionsProvider
import org.jetbrains.plugins.terminal.view.shellIntegration.TerminalOutputStatus
import com.hedworth.seshlog.copy.CopyTarget
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.util.Key

/**
 * Reworked-terminal adapter for the 2026.2 baseline. Never wait for a session or shell
 * integration on the UI thread.
 */
internal class ReworkedTerminal(
    private val terminalEngine: () -> TerminalEngine = { TerminalOptionsProvider.instance.terminalEngine },
    private val tabsManager: (Project) -> TerminalToolWindowTabsManager = { TerminalToolWindowTabsManager.getInstance(it) },
) {
    private val log = logger<ReworkedTerminal>()

    /** The terminal view carried by the action context, independent of editor/component wrappers. */
    fun contextView(context: DataContext): Any? =
        context.getData(TerminalView.DATA_KEY)

    fun contentForView(project: Project, view: Any, contents: List<Content> = emptyList()): Content? {
        contentForViewIn(contents, view)?.let { return it }
        val manager = try { tabsManager(project) } catch (_: IllegalStateException) { return null }
        return contentForViewIn(manager, view)
    }

    /** The tab stores its view even while detached from the tool window (dragging/editor moves). */
    internal fun viewOf(content: Content): Any? = tabView(content) ?: content.getUserData(VIEW_KEY)

    internal fun contentForViewIn(contents: List<Content>, view: Any): Content? {
        contents.singleOrNull { viewOf(it) === view }?.let { return it }
        // Some API versions do not expose the tab key. The view's own component is still exact
        // evidence; using an unrelated selected tab or current focus is not.
        val component = (view as? TerminalView)?.component ?: return null
        return CopyTarget.focusedContent(component, contents) { it.component }
    }

    internal fun contentForViewIn(manager: Any, view: Any): Content? = try {
        (manager as? TerminalToolWindowTabsManager)?.tabs
            ?.singleOrNull { it.view === view }?.content
    } catch (_: IllegalStateException) {
        // The terminal service exists before its tool window is registered. Treat that as
        // temporary absence; querying it must not initialize a terminal window during actions.
        null
    }

    fun find(project: Project, content: Content): TerminalHandle? {
        viewOf(content)?.let { return handle(content, it) }
        val manager = try { tabsManager(project) } catch (_: IllegalStateException) { return null }
        return findIn(manager, content)
    }

    internal fun findIn(manager: Any, content: Content): TerminalHandle? = try {
        (manager as? TerminalToolWindowTabsManager)?.tabs
            ?.firstOrNull { it.content === content }
            ?.let { handle(content, it.view) }
    } catch (_: IllegalStateException) {
        null
    }

    /** Null means the user selected a different engine. */
    fun launch(project: Project, directory: String, title: String): TerminalHandle? {
        if (terminalEngine() != TerminalEngine.REWORKED) return null
        // Once creation starts, propagate failures instead of opening a duplicate classic tab.
        val builder = tabsManager(project).createTabBuilder()
        builder.workingDirectory(directory)
        builder.tabName(title)
        builder.requestFocus(true)
        builder.deferSessionStartUntilUiShown(false)
        val tab = builder.createTab()
        return handle(tab.content, tab.view)
    }

    internal fun handle(content: Content?, view: Any): TerminalHandle = object : TerminalHandle {
        override val content = content
        init { content?.putUserData(VIEW_KEY, view) }

        override fun shellPid(): Long? = try {
            val session = session(view) ?: return null
            // Remote PIDs must never be compared with, or used to terminate, local processes.
            if (sessionProperty(session, "getEelDescriptor") !== LocalEelDescriptor) return null
            (sessionProperty(session, "getProcessId") as? Number)?.toLong()?.takeIf { it > 0 }
        } catch (e: ReflectiveOperationException) {
            log.debug("Cannot inspect reworked terminal session", e)
            null
        } catch (e: LinkageError) {
            log.debug("Reworked terminal session API is unavailable", e)
            null
        }

        override fun state(): TerminalState = try {
            session(view) ?: return TerminalState.UNKNOWN
            val terminalView = view as? TerminalView ?: return TerminalState.UNKNOWN
            val integration = completed(terminalView.shellIntegrationDeferred) ?: return TerminalState.UNKNOWN
            when (integration.outputStatus.value) {
                TerminalOutputStatus.TypingCommand -> TerminalState.IDLE
                TerminalOutputStatus.ExecutingCommand, TerminalOutputStatus.WaitingForPrompt -> TerminalState.BUSY
                else -> TerminalState.UNKNOWN
            }
        } catch (e: ReflectiveOperationException) {
            log.debug("Cannot inspect reworked terminal session", e)
            TerminalState.UNKNOWN
        } catch (e: LinkageError) {
            log.debug("Reworked terminal session API is unavailable", e)
            TerminalState.UNKNOWN
        }

        override fun execute(command: String) {
            (view as TerminalView).createSendTextBuilder().shouldExecute().send(command)
        }

        override fun rename(title: String) {
            // The view owns the persistent title. Changing only Content is overwritten when
            // shell integration next updates the title, and is lost when the IDE restores tabs.
            (view as TerminalView).title.change { userDefinedTitle = title }
            super.rename(title)
        }
    }

    /** Null is retained for the classic engine; false includes initialization/failure. */
    fun tabsRestored(project: Project): Boolean? {
        if (terminalEngine() != TerminalEngine.REWORKED) return true
        return restored(tabsManager(project))
    }

    internal fun restored(manager: Any): Boolean = try {
        // No public readiness API in 262. Keep this optional implementation detail here.
        val field = manager.javaClass.getDeclaredField("tabsRestoredDeferred")
        if (!field.trySetAccessible()) return false
        val deferred = field.get(manager) as? Deferred<*> ?: return false
        deferred.isCompleted && !deferred.isCancelled
    } catch (_: ReflectiveOperationException) { false }

    /** Internal session APIs are intentionally reached by narrow reflection for verifier safety. */
    private fun session(view: Any): Any? = try {
        val terminalView = view as? TerminalView ?: return null
        val method = terminalView.javaClass.getMethod("getSessionDeferred")
        if (!method.trySetAccessible()) return null
        val deferred = method.invoke(terminalView) as? Deferred<*> ?: return null
        val session = completed(deferred) ?: return null
        val closedMethod = session.javaClass.getMethod("isClosed")
        if (!closedMethod.trySetAccessible()) return null
        val closed = closedMethod.invoke(session) as? Boolean ?: return null
        if (closed) null else session
    } catch (e: ReflectiveOperationException) {
        log.debug("Cannot inspect reworked terminal session", e)
        null
    }

    private fun sessionProperty(session: Any, name: String): Any? {
        val method = session.javaClass.getMethod(name)
        if (!method.trySetAccessible()) return null
        return method.invoke(session)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T> completed(value: Deferred<T>): T? = value.let {
        if (it.isCompleted && !it.isCancelled) it.getCompleted() else null
    }

    private fun tabView(content: Content): TerminalView? = try {
        // KEY is a Kotlin companion property, so keep this lookup narrow and compatible with
        // the platform's generated JVM shape while the rest of the adapter stays typed.
        val companion = TerminalToolWindowTab::class.java.getField("Companion").get(null)
        val key = companion.javaClass.getMethod("getKEY").invoke(companion) as Key<*>
        (content.getUserData(key) as? TerminalToolWindowTab)?.view
    } catch (e: ReflectiveOperationException) {
        log.debug("Terminal tab key is unavailable", e)
        null
    }

    companion object {
        private val VIEW_KEY = Key.create<Any>("seshlog.terminal.view")
    }
}
