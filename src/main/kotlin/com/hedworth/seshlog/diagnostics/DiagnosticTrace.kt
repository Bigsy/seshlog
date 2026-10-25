package com.hedworth.seshlog.diagnostics

import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/** Only explicit metadata belongs here. Never pass commands, titles, messages or exception text. */
internal fun interface DiagnosticTrace {
    fun record(event: String, fields: Map<String, Any?>)

    fun event(event: String, vararg fields: Pair<String, Any?>) {
        // Diagnostics must never change a launch, matching decision, or UI operation.
        runCatching { record(event, fields.toMap()) }
    }

    fun with(vararg context: Pair<String, Any?>): DiagnosticTrace =
        DiagnosticTrace { event, fields -> record(event, context.toMap() + fields) }

    fun failure(event: String, error: Throwable) = event(event,
        "error" to error.javaClass.name,
        "cause" to error.cause?.javaClass?.name,
        "stack" to error.stackTrace.take(8).map { "${it.className}.${it.methodName}:${it.lineNumber}" })

    companion object { val NONE = DiagnosticTrace { _, _ -> } }
}

/** Stable opaque references let separate polls correlate paths/ids without storing their text. */
internal object DiagnosticRef {
    fun of(value: String?): String? = value?.let {
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8)), 0, 12)
    }
    fun path(value: Path?): String? = of(value?.normalize()?.toString())
    fun tab(value: Any?): String? = value?.let { Integer.toHexString(System.identityHashCode(it)) }
    fun ids(values: Collection<String>): List<String?> = values.map(::of).sortedBy { it }
}
