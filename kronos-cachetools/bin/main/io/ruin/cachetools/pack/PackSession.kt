package io.ruin.cachetools.pack

import com.displee.cache.CacheLibrary
import io.ruin.cachetools.Backups
import io.ruin.cachetools.KronosCache
import io.ruin.cachetools.RegionKeys
import io.ruin.cachetools.Workspace
import java.io.File
import java.nio.file.Files

class PackOptions(
    val only: Set<String>?,
    val dryRun: Boolean,
    val force: Boolean,
    val allowRunning: Boolean,
    val backup: Boolean,
)

/**
 * `pack`: raw-cache -> Cache.
 *
 *  1. copy the cache into `build/cachetools/work` and pack every raw-cache input there (never into Cache/);
 *  2. stop if any input was refused (unless `--force`);
 *  3. commit, then `rebuild` into `build/cachetools/rebuilt` (compact sector layout, drops orphaned sectors);
 *  4. verify the rebuilt cache against the original ([Verification]);
 *  5. install the rebuilt files; with `--backup`, Cache/ and region_keys.json are first backed up into Cache-backups/<stamp>.
 */
class PackSession(private val ws: Workspace, private val options: PackOptions) {

    private fun say(s: String = "") = println(s)

    fun run(): Int {
        if (!KronosCache.isCacheDir(ws.cache)) return fail("${ws.cache} is not a cache directory (main_file_cache.dat2/idx255 missing)")
        if (!ws.raw.isDirectory) {
            ws.raw.mkdirs()
            PACKERS.forEach { File(ws.raw, it.directory).mkdirs() }
            say("Created ${ws.raw} with ${PACKERS.joinToString { it.directory + "/" }}. Put your assets there and run pack again.")
            return 0
        }
        if (!options.dryRun && !options.allowRunning) {
            val running = ws.runningServers()
            if (running.isNotEmpty()) return fail("stop ${running.joinToString(" and ")} first: they hold the cache open and serve it from memory (or pass --allow-running)")
        }

        say("kronos-cachetools: pack ${ws.raw} -> ${ws.cache} (revision ${KronosCache.REVISION})${if (options.dryRun) "  [dry run]" else ""}")
        val workCache = File(ws.work, "work")
        val rebuiltCache = File(ws.work, "rebuilt")
        ws.work.deleteRecursively()
        workCache.mkdirs()
        for (f in KronosCache.cacheFiles(ws.cache)) Files.copy(f.toPath(), File(workCache, f.name).toPath())

        val keys = RegionKeys(ws.regionKeys)
        val report = PackReport()
        val library = CacheLibrary(workCache.path)
        val before: Snapshot
        try {
            before = Snapshot.take(library)
            val ctx = PackContext(library, ws.raw, keys, report)
            for (packer in PACKERS) {
                if (options.only != null && packer.name !in options.only) continue
                val dir = File(ws.raw, packer.directory)
                if (!dir.isDirectory) continue
                val count = report.changes.size
                packer.pack(ctx, dir)
                say("  ${packer.name.padEnd(8)} ${report.changes.size - count} file(s) staged")
            }
            report.changes.groupBy { Triple(it.index, it.archive, it.file) }.filterValues { it.size > 1 }.forEach { (k, v) ->
                report.error("index ${k.first} archive ${k.second} file ${k.third} is written by more than one input: ${v.map { it.source }}")
            }
            printReport(report)
            if (report.errors.isNotEmpty() && !options.force) return fail("${report.errors.size} input(s) refused; nothing was written. Fix them, or pass --force to pack everything else.")
            if (report.changes.isEmpty()) {
                say("Nothing to pack.")
                return 0
            }
            library.update()
        } finally {
            library.close()
        }

        val reopened = CacheLibrary(workCache.path)
        try {
            reopened.rebuild(rebuiltCache)
        } finally {
            reopened.close()
        }

        val problems = Verification.verify(before, rebuiltCache, report)
        if (problems.isNotEmpty()) {
            problems.forEach { say("  VERIFY FAILED: $it") }
            return fail("verification failed; Cache/ was not modified. The rebuilt cache is in $rebuiltCache for inspection.")
        }
        say("Verified: ${report.changes.size} file(s) read back through Kronos' FileStore; every other archive is byte-identical.")

        if (options.dryRun) {
            say("Dry run: Cache/ and region_keys.json were not modified (rebuilt cache left in $rebuiltCache).")
            return 0
        }

        val backup: File?
        try {
            Backups.checkNotLocked(ws.cache)
            backup = if (options.backup) Backups.create(ws, "pack of ${report.changes.size} file(s)") else null
            // Without a backup, a scratch copy in build/cachetools/ is still needed to roll back a failed install.
            val rollback = backup ?: File(ws.work, "previous").also { dir ->
                dir.mkdirs()
                for (f in KronosCache.cacheFiles(ws.cache)) Files.copy(f.toPath(), File(dir, f.name).toPath())
            }
            Backups.install(KronosCache.cacheFiles(rebuiltCache), ws.cache, rollback)
        } catch (e: IllegalStateException) {
            return fail(e.message ?: e.toString())
        }
        if (keys.changed) keys.save()
        val pruned = if (backup != null) Backups.prune(ws) else emptyList()

        say()
        say("Packed ${report.changes.size} file(s) into ${ws.cache} (${report.changes.count { it.replacedExisting }} replaced, ${report.changes.count { !it.replacedExisting }} new).")
        if (ws.regionKeys.isFile && report.changes.any { it.index == KronosCache.MAPS }) say("Updated ${ws.regionKeys}.")
        if (backup != null) say("Backup: ${backup.name}  (restore: gradlew :kronos-cachetools:restoreCache -PcacheArgs=\"${backup.name}\")")
        else say("No backup taken (pass --backup to keep one in ${ws.backups}).")
        if (pruned.isNotEmpty()) say("Removed old backups: ${pruned.joinToString { it.name }}")
        say("Start kronos-update-server and kronos-server again; clients download the changed archives on their next connect.")
        return 0
    }

    private fun printReport(report: PackReport) {
        report.notes.forEach { say("  note: $it") }
        report.warnings.forEach { say("  warning: $it") }
        report.errors.forEach { say("  REFUSED: $it") }
    }

    private fun fail(message: String): Int {
        System.err.println("error: $message")
        return 1
    }
}
