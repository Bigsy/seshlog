package com.hedworth.seshlog.diagnostics

import com.intellij.ide.plugins.PluginDetailsService
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginId
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

@Service(Service.Level.APP)
internal class SessionDiagnostics : Disposable {
    val files = RotatingDiagnosticFile(Path.of(PathManager.getLogPath(), "seshlog"))
    private val pluginVersion = PluginDetailsService.getInstance().findDetails(PluginId.getId("com.hedworth.seshlog"))?.version
    private val writer = AsyncDiagnosticLog(files::write, environment = mapOf(
        "schema" to 1, "plugin" to pluginVersion,
        "ide" to ApplicationInfo.getInstance().build.asString(), "java" to System.getProperty("java.version"),
        "os" to System.getProperty("os.name"), "arch" to System.getProperty("os.arch"), "idePid" to ProcessHandle.current().pid(),
    ), onFailure = {
        // No exception message/stack: filesystem errors can contain private paths.
        logger<SessionDiagnostics>().warn("Seshlog session diagnostics unavailable (${it.javaClass.simpleName}); will retry")
    })

    init {
        writer.trace.event("diagnostics_started", "schema" to 1,
            "plugin" to pluginVersion,
            "ide" to ApplicationInfo.getInstance().build.asString(), "java" to System.getProperty("java.version"),
            "os" to System.getProperty("os.name"), "arch" to System.getProperty("os.arch"),
            "idePid" to ProcessHandle.current().pid(), "fileBytes" to 5 * 1024 * 1024, "files" to 4)
    }

    fun flush(): Boolean = writer.flush()
    override fun dispose() {
        writer.trace.event("diagnostics_stopping")
        writer.close()
    }

    companion object {
        private val serial = AtomicLong()
        fun nextId(): Long = serial.incrementAndGet()
        fun getInstance(): SessionDiagnostics = ApplicationManager.getApplication().getService(SessionDiagnostics::class.java)

        fun trace(component: String, vararg context: Pair<String, Any?>): DiagnosticTrace = DiagnosticTrace { event, fields ->
            val app = ApplicationManager.getApplication()
            if (app != null && !app.isDisposed && !app.isUnitTestMode) {
                getInstance().writer.trace.record(event, mapOf("component" to component) + context.toMap() + fields)
            }
        }
    }
}
