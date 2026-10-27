package com.hedworth.seshlog.index

import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.ProviderHealth
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** Only a scan that actually published may produce this receipt. No cached fallback for actions. */
internal data class ActionScanSnapshot(val health: Map<AgentKind, ProviderHealth>) {
    fun ready(kind: AgentKind) = health[kind] == ProviderHealth.READY

    companion object {
        fun await(timeoutMillis: Long, submit: () -> Future<ActionScanSnapshot?>): ActionScanSnapshot? {
            var future: Future<ActionScanSnapshot?>? = null
            return try {
                future = submit()
                future.get(timeoutMillis, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                future?.cancel(false)
                Thread.currentThread().interrupt()
                null
            } catch (_: Exception) {
                future?.cancel(false)
                null
            }
        }
    }
}
