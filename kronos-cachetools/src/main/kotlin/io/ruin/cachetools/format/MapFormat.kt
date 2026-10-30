package io.ruin.cachetools.format

class Location(val id: Int, val localX: Int, val localY: Int, val plane: Int, val type: Int, val rotation: Int)

/**
 * The rev-184 map square formats, decoded exactly the way `io.ruin.model.map.Region.init` (and the client) read
 * them. Files from newer revisions (u16 tile opcodes, extended-smart object ids) do not parse and are refused.
 */
object MapFormat {

    /** Terrain (`m{x}_{y}`): 4 planes x 64 x 64 tiles of byte opcodes. Returns a problem, or null when valid. */
    fun checkTerrain(data: ByteArray): String? {
        var pos = 0
        for (z in 0 until 4) for (x in 0 until 64) for (y in 0 until 64) {
            while (true) {
                if (pos >= data.size) return "truncated at plane $z tile $x,$y"
                val opcode = data[pos++].toInt() and 0xFF
                if (opcode == 0) break
                if (opcode == 1) { pos++; break }     // height
                if (opcode <= 49) pos++               // overlay id (one byte in this revision)
                // 50..81 = tile settings, 82+ = underlay id: opcode only
            }
        }
        if (pos > data.size) return "truncated in the last tile"
        if (pos != data.size) return "${data.size - pos} unread bytes after the last tile"
        return null
    }

    /** Locations (`l{x}_{y}`): object id deltas, then per object position deltas + one type/rotation byte. */
    fun decodeLocations(data: ByteArray): List<Location> {
        var pos = 0
        fun byte(): Int {
            require(pos < data.size) { "truncated" }
            return data[pos++].toInt() and 0xFF
        }
        fun smart(): Int {
            require(pos < data.size) { "truncated" }
            val peek = data[pos].toInt() and 0xFF
            return if (peek < 128) byte() else (((byte() shl 8) or byte()) - 32768)
        }

        val result = ArrayList<Location>()
        var id = -1
        while (true) {
            val idDelta = smart()
            if (idDelta == 0) break
            id += idDelta
            var position = 0
            while (true) {
                val posDelta = smart()
                if (posDelta == 0) break
                position += posDelta - 1
                val attributes = byte()
                val plane = position shr 12
                val type = attributes shr 2
                require(plane in 0..3) { "object $id on plane $plane" }
                require(type <= 22) { "object $id has type $type (max 22)" }
                result += Location(id, (position shr 6) and 0x3F, position and 0x3F, plane, type, attributes and 3)
            }
        }
        require(pos == data.size) { "${data.size - pos} unread bytes after the end marker" }
        return result
    }

    fun checkLocations(data: ByteArray): String? = try {
        decodeLocations(data); null
    } catch (e: IllegalArgumentException) {
        e.message
    }
}
