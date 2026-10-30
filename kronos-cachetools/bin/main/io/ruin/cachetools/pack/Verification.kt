package io.ruin.cachetools.pack

import com.displee.cache.CacheLibrary
import io.ruin.api.filestore.FileStore
import java.io.File
import java.util.zip.CRC32

/** CRC32 + length of every raw archive sector, per index, plus each index's reference-table sector. */
class Snapshot private constructor(val tables: Map<Int, Long>, val archives: Map<Int, Map<Int, Long>>) {

    companion object {
        private fun signature(data: ByteArray?): Long {
            if (data == null) return -1
            val crc = CRC32().apply { update(data) }.value
            return (data.size.toLong() shl 32) xor crc
        }

        fun take(library: CacheLibrary): Snapshot {
            val tables = HashMap<Int, Long>()
            val archives = HashMap<Int, Map<Int, Long>>()
            for (index in library.indices()) {
                tables[index.id] = signature(library.index255?.readArchiveSector(index.id)?.data)
                archives[index.id] = index.archiveIds().associateWith { signature(index.readArchiveSector(it)?.data) }
            }
            return Snapshot(tables, archives)
        }
    }
}

object Verification {

    /**
     * Compares the rebuilt cache with the snapshot of the original:
     *  - every index this pack did not touch is byte-identical (reference table and every archive sector);
     *  - in touched indices, every archive this pack did not write is byte-identical and none disappeared;
     *  - every written archive has a sector whose CRC matches its reference-table entry (what the client checks);
     *  - Kronos' own FileStore (the server's reader) returns exactly the packed bytes, looking maps up by name.
     */
    fun verify(before: Snapshot, rebuiltDir: File, report: PackReport): List<String> {
        val problems = ArrayList<String>()
        val rebuilt = CacheLibrary(rebuiltDir.path)
        try {
            val after = Snapshot.take(rebuilt)
            if (after.tables.keys != before.tables.keys) problems += "index set changed: ${before.tables.keys.sorted()} -> ${after.tables.keys.sorted()}"
            val touched = report.touchedIndices()
            for (id in before.tables.keys) {
                val old = before.archives.getValue(id)
                val new = after.archives[id] ?: continue
                if (id !in touched) {
                    if (before.tables[id] != after.tables[id]) problems += "index $id: reference table changed although nothing was packed into it"
                    if (old != new) problems += "index $id: archives changed although nothing was packed into it"
                    continue
                }
                val written = report.touchedArchives(id)
                (old.keys - new.keys).takeIf { it.isNotEmpty() }?.let { problems += "index $id: archives disappeared: ${it.take(20)}" }
                (new.keys - old.keys - written).takeIf { it.isNotEmpty() }?.let { problems += "index $id: unexpected new archives: ${it.take(20)}" }
                val changedUnexpectedly = old.keys.filter { it !in written && new[it] != old[it] }
                if (changedUnexpectedly.isNotEmpty()) problems += "index $id: archives changed that were not packed: ${changedUnexpectedly.take(20)}"
                val index = rebuilt.index(id)
                for (archiveId in written) {
                    val sector = index.readArchiveSector(archiveId)?.data
                    if (sector == null) { problems += "index $id archive $archiveId: no sector"; continue }
                    val crc = CRC32().apply { update(sector, 0, sector.size - 2) }.value.toInt()
                    val expected = index.archive(archiveId, direct = true)?.crc
                    if (crc != expected) problems += "index $id archive $archiveId: sector CRC $crc != reference table CRC $expected"
                }
            }
        } finally {
            rebuilt.close()
        }

        val store = FileStore(rebuiltDir.absolutePath)
        for (change in report.changes) {
            val index = store.get(change.index)
            if (index == null) { problems += "Kronos FileStore: index ${change.index} missing"; continue }
            val archiveId = change.archiveName?.let { index.getArchiveId(it) } ?: change.archive
            if (archiveId != change.archive) { problems += "Kronos FileStore: '${change.archiveName}' resolves to archive $archiveId, expected ${change.archive}"; continue }
            val data = try {
                index.getFile(archiveId, change.file, null)
            } catch (t: Throwable) {
                problems += "Kronos FileStore: reading ${change.source} (index ${change.index} archive $archiveId file ${change.file}) threw $t"; continue
            }
            if (data == null || !data.contentEquals(change.expected)) {
                problems += "Kronos FileStore: ${change.source} reads back ${data?.size ?: "null"} bytes, expected ${change.expected.size} (index ${change.index} archive $archiveId file ${change.file})"
            }
        }
        return problems
    }
}
