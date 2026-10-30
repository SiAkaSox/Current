package io.ruin.cachetools

import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Properties

/**
 * Where everything lives, relative to the repository root (the working directory of the Gradle tasks).
 * The cache and region-keys locations are read from `kronos-server/server.properties` (`cache_path`, `data_path`)
 * so the tools always operate on the same files the server boots from.
 */
class Workspace(
    val root: File,
    val cache: File,
    val raw: File,
    val dump: File,
    val backups: File,
    val regionKeys: File,
    val work: File,
) {
    companion object {
        fun resolve(options: Map<String, String>): Workspace {
            val root = File(options["root"] ?: ".").absoluteFile.normalize()
            val serverDir = File(root, "kronos-server")
            val serverProps = loadProperties(File(serverDir, "server.properties"))
            fun path(option: String, default: () -> File) = options[option]?.let { File(it).let { f -> if (f.isAbsolute) f else File(root, it) } }?.normalize() ?: default().normalize()
            return Workspace(
                root = root,
                cache = path("cache") { File(serverDir, serverProps.getProperty("cache_path", "../Cache")) },
                raw = path("raw") { File(root, "raw-cache") },
                dump = path("dump") { File(root, "cache-dump") },
                backups = path("backups") { File(root, "Cache-backups") },
                regionKeys = path("keys") { File(File(serverDir, serverProps.getProperty("data_path", "data")), "region_keys.json") },
                work = File(root, "build/cachetools"),
            )
        }

        private fun loadProperties(file: File) = Properties().apply { if (file.isFile) file.inputStream().use { load(it) } }
    }

    /**
     * The game server and the update server keep the cache files open and serve from memory, so packing under
     * them would either fail on Windows or leave them serving stale data. Returns the running ones.
     */
    fun runningServers(): List<String> {
        val result = ArrayList<String>()
        for ((name, dir) in listOf("kronos-server" to "kronos-server", "kronos-update-server" to "kronos-update-server")) {
            val address = loadProperties(File(root, "$dir/server.properties")).getProperty("world_address") ?: continue
            val port = address.substringAfterLast(':').trim().toIntOrNull() ?: continue
            val listening = try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) }; true
            } catch (e: Exception) {
                false
            }
            if (listening) result += "$name (port $port)"
        }
        return result
    }
}
