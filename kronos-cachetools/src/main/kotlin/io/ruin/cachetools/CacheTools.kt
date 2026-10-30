package io.ruin.cachetools

import com.displee.cache.CacheLibrary
import io.ruin.cachetools.pack.PACKERS
import io.ruin.cachetools.pack.PackOptions
import io.ruin.cachetools.pack.PackSession
import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """kronos-cachetools - pack custom content into the Kronos (rev 184) cache

  pack    [--only files,definitions,models,sprites,maps] [--dry-run] [--force] [--allow-running] [--backup]
          Pack everything under raw-cache/ into Cache/. Verifies before installing; --backup backs up first.
  dump    model|sprite|map|file|item|npc|object <ids...>
          Export existing assets into cache-dump/ (raw-cache layout; definitions as TOML).
  info                                       Summarise the cache's indices.
  restore [<backup name>]                    List backups, or restore one (Cache/ and region_keys.json).

Global options: --cache <dir> --raw <dir> --dump <dir> --keys <region_keys.json> --backups <dir>
Paths are relative to the repository root; the cache and keys default to kronos-server/server.properties."""

private val VALUE_OPTIONS = setOf("cache", "raw", "dump", "keys", "backups", "root", "only")

fun main(args: Array<String>) {
    exitProcess(run(args.toList()))
}

fun run(args: List<String>): Int {
    val options = HashMap<String, String>()
    val flags = HashSet<String>()
    val positional = ArrayList<String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        if (a.startsWith("--")) {
            val key = a.removePrefix("--")
            if (key in VALUE_OPTIONS) {
                if (i + 1 >= args.size) return usage("--$key needs a value")
                options[key] = args[i + 1]; i += 2; continue
            }
            flags += key
        } else {
            positional += a
        }
        i++
    }
    val ws = Workspace.resolve(options)
    return when (positional.firstOrNull()) {
        "pack" -> {
            val only = options["only"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
            val unknown = only.orEmpty() - PACKERS.map { it.name }.toSet()
            if (unknown.isNotEmpty()) return usage("unknown --only value(s) $unknown; choose from ${PACKERS.map { it.name }}")
            val unknownFlags = flags - setOf("dry-run", "force", "allow-running", "backup")
            if (unknownFlags.isNotEmpty()) return usage("unknown flag(s) ${unknownFlags.map { "--$it" }}")
            PackSession(ws, PackOptions(only, "dry-run" in flags, "force" in flags, "allow-running" in flags, "backup" in flags)).run()
        }
        "dump" -> Dump(ws).run(positional.drop(1))
        "info" -> info(ws)
        "restore" -> restore(ws, positional.getOrNull(1), "allow-running" in flags)
        null, "help" -> usage(null)
        else -> usage("unknown command '${positional.first()}'")
    }
}

private fun info(ws: Workspace): Int {
    if (!KronosCache.isCacheDir(ws.cache)) { System.err.println("error: ${ws.cache} is not a cache directory"); return 1 }
    val library = CacheLibrary(ws.cache.path)
    try {
        println("Cache: ${ws.cache}  (${KronosCache.cacheFiles(ws.cache).sumOf { it.length() } / 1024 / 1024} MB)")
        println("index  archives  highest  protocol  revision  named")
        for (index in library.indices()) {
            val ids = index.archiveIds()
            println("%5d  %8d  %7s  %8d  %8d  %s".format(index.id, ids.size, ids.lastOrNull()?.toString() ?: "-", index.version, index.revision, if (index.isNamed()) "yes" else "no"))
        }
        println("Raw content: ${ws.raw}${if (ws.raw.isDirectory) "" else "  (missing; `pack` creates it)"}")
        println("Region keys: ${ws.regionKeys}")
    } finally {
        library.close()
    }
    return 0
}

private fun restore(ws: Workspace, name: String?, allowRunning: Boolean): Int {
    val backups = Backups.list(ws)
    if (name == null) {
        if (backups.isEmpty()) println("No backups in ${ws.backups}.")
        backups.forEach { println("  ${it.name}") }
        if (backups.isNotEmpty()) println("Restore one with: gradlew :kronos-cachetools:restoreCache -PcacheArgs=\"<name>\"")
        return 0
    }
    val backup = backups.firstOrNull { it.name == name } ?: run { System.err.println("error: no backup named '$name' in ${ws.backups}"); return 1 }
    if (!allowRunning) {
        val running = ws.runningServers()
        if (running.isNotEmpty()) { System.err.println("error: stop ${running.joinToString(" and ")} first (or pass --allow-running)"); return 1 }
    }
    val safety: File
    try {
        Backups.checkNotLocked(ws.cache)
        safety = Backups.create(ws, "restore of ${backup.name}")
        Backups.restore(ws, backup, safety)
    } catch (e: IllegalStateException) {
        System.err.println("error: ${e.message}")
        return 1
    }
    println("Restored ${backup.name} into ${ws.cache}. The state before the restore was saved as ${safety.name}.")
    return 0
}

private fun usage(error: String?): Int {
    if (error != null) System.err.println("error: $error\n")
    println(USAGE)
    return if (error == null) 0 else 1
}
