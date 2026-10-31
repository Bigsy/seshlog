package com.hedworth.seshlog.review

import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.terminal.CodexReviewEndpoint

/** One preflight contract for UI availability and delivery; no process or filesystem reads. */
internal object ReviewDeliverySupport {
    fun unavailableReason(kind: AgentKind, osName: String = System.getProperty("os.name")): String? = when {
        kind != AgentKind.CODEX -> "provider_unsupported"
        !CodexReviewEndpoint.supportedPlatform(osName) -> "platform_unsupported"
        else -> null
    }
    fun description(kind: AgentKind, osName: String = System.getProperty("os.name")): String = when (unavailableReason(kind, osName)) {
        "provider_unsupported" -> "Direct send unavailable for ${kind.displayName}. Copy Review and Go to Session are available."
        "platform_unsupported" -> "Direct send unavailable on $osName; it is currently supported on macOS only. Copy Review and Go to Session are available."
        else -> "Direct send: local Codex daemon 0.166.0 on macOS; the terminal endpoint, recipient and source files are verified on send."
    }
}
