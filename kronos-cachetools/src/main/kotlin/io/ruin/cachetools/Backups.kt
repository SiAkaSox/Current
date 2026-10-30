package io.ruin.cachetools

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Timestamped copies of the cache (and region_keys.json) taken before every write, in `Cache-backups/`. */
object Backups {

    private const val MARKER = "KRONOS_CACHE_BACKUP.txt"
    private const val KEYS_COPY = "region_keys.json"
    const val KEEP = 5

    fun list(ws: Workspace): List<File> =
        ws.backups.listFiles { f -> File(f, MARKER).isFile }?.sortedBy { it.name }.orEmpty()

    fun create(ws: Workspace, reason: String): File {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        var dir = File(ws.backups, stamp)
        var n = 1
        while (dir.exists()) dir = File(ws.backups, "$stamp-${n++}")
        dir.mkdirs()
        for (f in KronosCache.cacheFiles(ws.cache)) Files.copy(f.toPath(), File(dir, f.name).toPath())
        if (ws.regionKeys.isFile) Files.copy(ws.regionKeys.toPath(), File(dir, KEYS_COPY).toPath())
        File(dir, MARKER).writeText("Backup of ${ws.cache} taken by kronos-cachetools before: $reason\nRestore with: gradlew :kronos-cachetools:restoreCache -PcacheArgs=\"${dir.name}\"\n")
        return dir
    }

    /** Deletes the oldest tool-made backups beyond [KEEP]. Directories without the marker are never touched. */
    fun prune(ws: Workspace): List<File> {
        val all = list(ws)
        val doomed = all.dropLast(KEEP)
        doomed.forEach { it.deleteRecursively() }
        return doomed
    }

    /** Restores [backup] into the cache; [safety] is the backup of the current state, used to roll back a failed copy. */
    fun restore(ws: Workspace, backup: File, safety: File) {
        require(File(backup, MARKER).isFile) { "$backup is not a kronos-cachetools backup" }
        install(KronosCache.cacheFiles(backup), ws.cache, safety)
        File(backup, KEYS_COPY).takeIf { it.isFile }?.let { Files.copy(it.toPath(), ws.regionKeys.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }

    /**
     * Replaces the cache files in [cacheDir] with [files], removing cache files that are not part of the new set.
     *
     * Never leaves a half-replaced cache: it first checks that no cache file is held open by another process
     * (Windows refuses to replace those; the check renames each file and back), and if a copy still fails part
     * way, every file is put back from [rollback] before the error is rethrown.
     */
    fun install(files: List<File>, cacheDir: File, rollback: File) {
        checkNotLocked(cacheDir)
        try {
            copyOver(files, cacheDir)
        } catch (e: Exception) {
            try {
                copyOver(KronosCache.cacheFiles(rollback), cacheDir)
            } catch (r: Exception) {
                e.addSuppressed(r)
                throw IllegalStateException("installing into $cacheDir failed AND the rollback failed; restore ${rollback.name} manually", e)
            }
            throw IllegalStateException("installing into $cacheDir failed part way; it was rolled back from ${rollback.name}", e)
        }
    }

    /** Throws when a cache file is held open by another process (Windows refuses to replace those). */
    fun checkNotLocked(cacheDir: File) {
        val locked = KronosCache.cacheFiles(cacheDir).filterNot { replaceable(it) }
        check(locked.isEmpty()) {
            "${locked.joinToString { it.name }} in $cacheDir is in use by another process (a server, the cache editor or a client?); nothing was changed"
        }
    }

    private fun copyOver(files: List<File>, cacheDir: File) {
        val names = files.map { it.name }.toSet()
        for (f in files) Files.copy(f.toPath(), File(cacheDir, f.name).toPath(), StandardCopyOption.REPLACE_EXISTING)
        for (stale in KronosCache.cacheFiles(cacheDir)) if (stale.name !in names) Files.delete(stale.toPath())
    }

    private fun replaceable(file: File): Boolean {
        val probe = File(file.parentFile, file.name + ".lockcheck")
        if (!file.renameTo(probe)) return false
        check(probe.renameTo(file)) { "could not rename $probe back to ${file.name}; rename it manually" }
        return true
    }
}
