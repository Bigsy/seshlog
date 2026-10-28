package com.hedworth.seshlog.sandbox

import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * `prepare`, `reset` or `verify` the synthetic sandbox. Run through Gradle (`make sandbox`,
 * `make sandbox-reset`, `make sandbox-verify`), which supplies the sandbox IDE's directories.
 */
object SyntheticSandboxCli {
    @JvmStatic
    fun main(args: Array<String>) {
        val command = args.firstOrNull()
        val options = args.drop(1).chunked(2).associate { (name, value) -> name.removePrefix("--") to value }
        fun path(name: String) = Path.of(options[name] ?: usage("missing --$name"))
        val layout = SandboxLayout(path("root"), path("ide-config"), path("ide-log"), path("ide-plugins"))
        val filler = options["filler"]?.toInt() ?: SyntheticSandbox.DEFAULT_FILLER
        try {
            when (command) {
                "prepare" -> print(SyntheticSandbox.prepare(layout, filler))
                "reset" -> print(SyntheticSandbox.reset(layout, SandboxProcess.find(layout).isNotEmpty(), filler))
                "verify" -> {
                    val checks = SyntheticSandboxVerifier(layout, path("plugin-jar"), options["plugin-version"] ?: usage("missing --plugin-version"),
                        path("repository")).verify()
                    val report = SyntheticSandboxVerifier.report(checks)
                    print(report)
                    if (Files.isDirectory(layout.root)) {
                        Files.writeString(layout.root.resolve("verification.txt"), report)
                        println("Saved to ${layout.root.resolve("verification.txt")}")
                    }
                    if (checks.any { it.ok == false }) exitProcess(1)
                }
                else -> usage("unknown command ${command ?: "(none)"}")
            }
        } catch (e: IllegalStateException) {
            System.err.println("Synthetic sandbox: ${e.message}")
            exitProcess(1)
        }
    }

    private fun usage(problem: String): Nothing {
        System.err.println("Synthetic sandbox: $problem\nUsage: prepare|reset|verify --root DIR --ide-config DIR --ide-log DIR --ide-plugins DIR " +
            "[--filler N] [--plugin-jar JAR --plugin-version V --repository DIR]")
        exitProcess(2)
    }
}
