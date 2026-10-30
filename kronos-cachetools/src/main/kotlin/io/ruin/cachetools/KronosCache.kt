package io.ruin.cachetools

import java.io.File

/**
 * Facts about the Kronos cache that every tool relies on. Kronos (server, update server and the OpenOSRS-based
 * client) is OSRS revision 184: maps are *named* groups `m{x}_{y}` / `l{x}_{y}` in index 5 with XTEA keys from
 * `kronos-server/data/region_keys.json`, models have no `-2`/`-3` format, and sprites have no alpha channel.
 */
object KronosCache {

    const val REVISION = 184

    const val CONFIGS = 2
    const val MAPS = 5
    const val MODELS = 7
    const val SPRITES = 8

    /** Index 2 archive holding object (loc) definitions; map location files may only place ids defined here. */
    const val OBJECT_DEFS_ARCHIVE = 6

    const val FILE_PREFIX = "main_file_cache."

    fun isCacheDir(dir: File) = File(dir, "${FILE_PREFIX}dat2").isFile && File(dir, "${FILE_PREFIX}idx255").isFile

    fun cacheFiles(dir: File): List<File> =
        dir.listFiles { f -> f.isFile && f.name.startsWith(FILE_PREFIX) }?.sortedBy { it.name }.orEmpty()

    fun regionId(x: Int, y: Int) = (x shl 8) or y

    fun terrainName(x: Int, y: Int) = "m${x}_$y"

    fun locationsName(x: Int, y: Int) = "l${x}_$y"
}
