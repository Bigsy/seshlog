package com.hedworth.seshlog.terminal

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.terminal.TerminalTitle
import com.intellij.ui.content.Content
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Optional reworked-terminal API. Resolve it through the terminal plugin's loader, so the same
 * plugin still loads on 2024.1. Never wait for a session or shell integration on the UI thread.
 */
internal class ReworkedTerminal(
    private val loadClass: (String) -> Class<*> = ::loadApiClass,
) {
    private val log = logger<ReworkedTerminal>()

    /** The terminal view carried by the action context, independent of editor/component wrappers. */
    fun contextView(context: com.intellij.openapi.actionSystem.DataContext): Any? = read {
        val type = loadClass("${FRONTEND}.view.TerminalView")
        val companion = type.getField("Companion").get(null)
        val key = call(companion, "getDATA_KEY") as? com.intellij.openapi.actionSystem.DataKey<*> ?: return@read null
        context.getData(key)?.takeIf(type::isInstance)
    }

    fun contentForView(project: Project, view: Any, contents: List<Content> = emptyList()): Content? {
        contentForViewIn(contents, view)?.let { return it }
        return read {
            val manager = loadClass("${FRONTEND}.toolwindow.TerminalToolWindowTabsManager")
                .getMethod("getInstance", Project::class.java).invoke(null, project)
            contentForViewIn(manager, view)
        }
    }

    /** The tab stores its view even while detached from the tool window (dragging/editor moves). */
    internal fun viewOf(content: Content): Any? = read {
        val type = loadClass("${FRONTEND}.toolwindow.TerminalToolWindowTab")
        // Early 261/262 releases had no tab key. Cache the exact view when enumerated there.
        val companion = type.fields.firstOrNull { it.name == "Companion" }?.get(null) ?: return@read null
        val key = call(companion, "getKEY") as? com.intellij.openapi.util.Key<*> ?: return@read null
        content.getUserData(key)?.takeIf(type::isInstance)?.let { call(it, "getView") }
    } ?: content.getUserData(VIEW_KEY)

    internal fun contentForViewIn(contents: List<Content>, view: Any): Content? {
        contents.singleOrNull { viewOf(it) === view }?.let { return it }
        // Some API versions/wrappers do not expose the tab key. The view's own component is
        // still exact evidence; using an unrelated selected tab or current focus is not.
        val component = read { call(view, "getComponent") as? java.awt.Component } ?: return null
        return com.hedworth.seshlog.copy.CopyTarget.focusedContent(component, contents) { it.component }
    }

    internal fun contentForViewIn(manager: Any, view: Any): Content? = read {
        val tabs = call(manager, "getTabs") as? List<*> ?: return@read null
        val tab = tabs.filterNotNull().singleOrNull { call(it, "getView") === view } ?: return@read null
        call(tab, "getContent") as? Content
    }

    fun find(project: Project, content: Content): TerminalHandle? {
        viewOf(content)?.let { return handle(content, it) { localProject(project) } }
        return read {
            val manager = loadClass("${FRONTEND}.toolwindow.TerminalToolWindowTabsManager")
                .getMethod("getInstance", Project::class.java).invoke(null, project)
            findIn(manager, content) { localProject(project) }
        }
    }

    internal fun findIn(manager: Any, content: Content, legacyLocal: () -> Boolean = { false }): TerminalHandle? = read {
        val tabs = call(manager, "getTabs") as? List<*> ?: return@read null
        val tab = tabs.filterNotNull().firstOrNull { call(it, "getContent") === content } ?: return@read null
        val view = call(tab, "getView") ?: return@read null
        handle(content, view, legacyLocal)
    }

    /** Null means this IDE lacks the API, or the user selected a different engine. */
    fun launch(project: Project, directory: String, title: String): TerminalHandle? {
        val managerClass = read {
            val options = loadClass("org.jetbrains.plugins.terminal.TerminalOptionsProvider")
                .getMethod("getInstance").invoke(null)
            if ((call(options, "getTerminalEngine") as? Enum<*>)?.name != "REWORKED") return@read null
            loadClass("${FRONTEND}.toolwindow.TerminalToolWindowTabsManager")
        } ?: return null
        // Once creation starts, propagate failures instead of opening a duplicate classic tab.
        val manager = managerClass.getMethod("getInstance", Project::class.java).invoke(null, project)
        val builder = requireNotNull(call(manager, "createTabBuilder"))
        call(builder, "workingDirectory", directory)
        call(builder, "tabName", title)
        call(builder, "requestFocus", true)
        call(builder, "deferSessionStartUntilUiShown", false)
        val tab = requireNotNull(call(builder, "createTab"))
        return handle(call(tab, "getContent") as Content, requireNotNull(call(tab, "getView"))) { localProject(project) }
    }

    internal fun handle(content: Content?, view: Any, legacyLocal: () -> Boolean = { false }): TerminalHandle = object : TerminalHandle {
        override val content = content
        init { content?.putUserData(VIEW_KEY, view) }

        override fun shellPid(): Long? = read {
            if (!hasSessionApi(view)) {
                if (!legacyLocal() || !legacyRunning(view)) return@read null
                // 261 publishes only the local ProcessTtyConnector PID in startup options.
                val options = completed(call(view, "getStartupOptionsDeferred")) ?: return@read null
                return@read (call(options, "getPid") as? Number)?.toLong()?.takeIf { it > 0 }
            }
            val session = session(view) ?: return@read null
            val descriptor = call(session, "getEelDescriptor") ?: return@read null
            // Remote PIDs must never be compared with, or used to terminate, local processes.
            if (!loadClass("com.intellij.platform.eel.provider.LocalEelDescriptor").isInstance(descriptor)) return@read null
            (call(session, "getProcessId") as? Number)?.toLong()?.takeIf { it > 0 }
        }

        override fun state(): TerminalState = read {
            if (hasSessionApi(view)) {
                session(view) ?: return@read TerminalState.UNKNOWN
            } else if (!legacyRunning(view)) return@read TerminalState.UNKNOWN
            val integration = completed(call(view, "getShellIntegrationDeferred")) ?: return@read TerminalState.UNKNOWN
            val status = (call(integration, "getOutputStatus") as? StateFlow<*>)?.value
            when (status?.javaClass?.simpleName) {
                "TypingCommand" -> TerminalState.IDLE
                "ExecutingCommand", "WaitingForPrompt" -> TerminalState.BUSY
                else -> TerminalState.UNKNOWN
            }
        } ?: TerminalState.UNKNOWN

        override fun execute(command: String) {
            val builder = requireNotNull(call(view, "createSendTextBuilder"))
            call(builder, "shouldExecute")
            call(builder, "send", command)
        }

        override fun rename(title: String) {
            // The view owns the persistent title. Changing only Content is overwritten when
            // shell integration next updates the title, and is lost when the IDE restores tabs.
            read { (call(view, "getTitle") as? TerminalTitle)?.change { userDefinedTitle = title } }
            super.rename(title)
        }
    }

    private fun localProject(project: Project): Boolean = read {
        val descriptor = loadClass("com.intellij.platform.eel.provider.EelProviderUtil")
            .getMethod("getEelDescriptor", Project::class.java).invoke(null, project)
        loadClass("com.intellij.platform.eel.provider.LocalEelDescriptor").isInstance(descriptor)
    } ?: false

    private fun hasSessionApi(view: Any) = view.javaClass.methods.any { it.name == "getSessionDeferred" && it.parameterCount == 0 }

    private fun legacyRunning(view: Any): Boolean =
        (call(view, "getSessionState") as? StateFlow<*>)?.value?.javaClass?.simpleName == "Running"

    /** null means the reworked API is absent; false includes initialization/failure. */
    fun tabsRestored(project: Project): Boolean? {
        val type = try { loadClass("${FRONTEND}.toolwindow.TerminalToolWindowTabsManager") }
        catch (_: ClassNotFoundException) { return null }
        return read {
            val options = loadClass("org.jetbrains.plugins.terminal.TerminalOptionsProvider")
                .getMethod("getInstance").invoke(null)
            if ((call(options, "getTerminalEngine") as? Enum<*>)?.name != "REWORKED") return@read true
            val manager = type.getMethod("getInstance", Project::class.java).invoke(null, project)
            restored(manager)
        } ?: false
    }

    internal fun restored(manager: Any): Boolean = read {
        // No public readiness API in 261/262. Keep this optional implementation detail here.
        val field = manager.javaClass.getDeclaredField("tabsRestoredDeferred")
        if (!field.trySetAccessible()) return@read false
        val deferred = field.get(manager) as? Deferred<*> ?: return@read false
        deferred.isCompleted && !deferred.isCancelled
    } ?: false

    private fun session(view: Any): Any? {
        val session = completed(call(view, "getSessionDeferred")) ?: return null
        return session.takeIf { call(it, "isClosed") == false }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun completed(value: Any?): Any? = (value as? Deferred<*>)?.let {
        if (it.isCompleted && !it.isCancelled) it.getCompleted() else null
    }

    private fun <T> read(action: () -> T): T? = try {
        action()
    } catch (_: ClassNotFoundException) {
        null // Expected on older IDEs.
    } catch (e: ReflectiveOperationException) {
        log.debug("Cannot inspect reworked terminal", e)
        null
    } catch (e: LinkageError) {
        log.debug("Reworked terminal API is unavailable", e)
        null
    }

    companion object {
        private const val FRONTEND = "com.intellij.terminal.frontend"
        private val VIEW_KEY = com.intellij.openapi.util.Key.create<Any>("seshlog.terminal.view")

        internal fun loadApiClass(name: String): Class<*> {
            val loader = TerminalToolWindowManager::class.java.classLoader
            try { return Class.forName(name, false, loader) } catch (missing: ClassNotFoundException) {
                // Recent IDEs load the frontend as a separate content module, invisible to the
                // terminal plugin's main loader. Use that module's own loader when necessary.
                if (!name.startsWith(FRONTEND)) throw missing
                val core = Class.forName("com.intellij.ide.plugins.PluginManagerCore", false, loader)
                val getPlugins = core.getMethod("getPluginSet")
                val receiver = if (Modifier.isStatic(getPlugins.modifiers)) null else core.getField("INSTANCE").get(null)
                val plugins = getPlugins.invoke(receiver) ?: throw missing
                val modules = call(plugins, "getEnabledModules") as? Iterable<*> ?: throw missing
                val frontend = modules.filterNotNull().firstOrNull { module ->
                    module.javaClass.methods.firstOrNull { it.name == "getModuleNameString" }
                        ?.invoke(module) == "intellij.terminal.frontend"
                } ?: throw missing
                val frontendLoader = call(frontend, "getPluginClassLoader") as? ClassLoader ?: throw missing
                return Class.forName(name, false, frontendLoader)
            }
        }

        internal fun call(target: Any, name: String, vararg args: Any): Any? {
            val method = target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == args.size }
                ?: throw NoSuchMethodException("${target.javaClass.name}.$name/${args.size}")
            // Builders may be private implementation classes. Invoke their public interface.
            fun accessible(type: Class<*>): Method? {
                for (api in type.interfaces) {
                    if (Modifier.isPublic(api.modifiers)) {
                        try { return api.getMethod(name, *method.parameterTypes) } catch (_: NoSuchMethodException) { }
                    }
                    accessible(api)?.let { return it }
                }
                return type.superclass?.let(::accessible)
            }
            return (accessible(target.javaClass) ?: method).invoke(target, *args)
        }
    }
}
