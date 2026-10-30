package io.ruin.cachetools.pack

/**
 * One written cache file. [archiveName] is set for named groups (maps), which is how Kronos looks them up;
 * [expected] is what the server must read back from the rebuilt cache.
 */
class Change(
    val index: Int,
    val archive: Int,
    val file: Int,
    val expected: ByteArray,
    val source: String,
    val archiveName: String? = null,
    val replacedExisting: Boolean,
)

class PackReport {
    val changes = ArrayList<Change>()
    val warnings = ArrayList<String>()
    val errors = ArrayList<String>()
    val notes = ArrayList<String>()

    fun warn(message: String) { warnings += message }
    fun error(message: String) { errors += message }
    fun note(message: String) { notes += message }

    fun touchedIndices(): Set<Int> = changes.map { it.index }.toSet()

    fun touchedArchives(index: Int): Set<Int> = changes.filter { it.index == index }.map { it.archive }.toSet()
}
