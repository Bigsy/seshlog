package com.hedworth.seshlog.model

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes

/** Missing installation, successfully empty storage, and unreadable storage are distinct outcomes. */
enum class ProviderHealth { MISSING, READY, ERROR }
data class ProviderScan(val sessions: List<Session>, val health: ProviderHealth, val problem: String? = null) {
    companion object {
        fun read(storage: Path, directory: Boolean, scan: () -> List<Session>, problem: () -> String? = { null }): ProviderScan {
            try {
                val attrs = Files.readAttributes(storage, BasicFileAttributes::class.java)
                if ((directory && !attrs.isDirectory) || (!directory && !attrs.isRegularFile) || !Files.isReadable(storage)) {
                    return ProviderScan(emptyList(), ProviderHealth.ERROR, "Storage is not a readable ${if (directory) "directory" else "file"}: $storage")
                }
            } catch (_: NoSuchFileException) {
                return ProviderScan(emptyList(), ProviderHealth.MISSING)
            } catch (e: Exception) {
                return ProviderScan(emptyList(), ProviderHealth.ERROR, "Cannot inspect $storage: ${e.message}")
            }
            return try {
                val sessions = scan()
                val error = problem()
                ProviderScan(sessions, if (error == null) ProviderHealth.READY else ProviderHealth.ERROR, error)
            } catch (e: Exception) {
                ProviderScan(emptyList(), ProviderHealth.ERROR, "Cannot read $storage: ${e.message}")
            }
        }
    }
}
