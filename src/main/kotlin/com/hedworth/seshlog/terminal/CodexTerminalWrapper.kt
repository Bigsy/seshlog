package com.hedworth.seshlog.terminal

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

/** Installs only in Seshlog's IDE storage. Content-addressed paths keep open shells usable on update. */
internal object CodexTerminalWrapper {
    fun install(storage: Path): Path {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/terminal/codex")).use { it.readBytes() }
        val version = MessageDigest.getInstance("SHA-256").digest(bytes).take(12).joinToString("") { "%02x".format(it) }
        val directory = storage.resolve("codex-$version").toAbsolutePath().normalize()
        require(':' !in directory.toString()) { "Wrapper directory cannot contain the PATH separator" }
        val permissions = PosixFilePermissions.fromString("rwx------")
        Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(permissions))
        val executable = directory.resolve("codex")
        if (Files.isRegularFile(executable, NOFOLLOW_LINKS) && Files.size(executable) == bytes.size.toLong() &&
            Files.readAllBytes(executable).contentEquals(bytes) && Files.isExecutable(executable)) return directory
        val temporary = Files.createTempFile(directory, ".codex-", ".tmp", PosixFilePermissions.asFileAttribute(permissions))
        try {
            Files.write(temporary, bytes)
            try {
                Files.move(temporary, executable, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, executable, REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
        return directory
    }
}
