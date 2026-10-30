package io.ruin.cachetools

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/**
 * `kronos-server/data/region_keys.json`: `{"<regionId>": [k0, k1, k2, k3], ...}`.
 *
 * The server only loads a region's objects when the region has an entry here (`region_keys.fromJson` leaves
 * `Region.keys` null otherwise, and `getLandscapeData` returns null for null keys). An all-zero entry means
 * "landscape stored unencrypted" to both the server and the client, which is how this tool packs every map.
 */
class RegionKeys(val file: File) {

    private val keys = LinkedHashMap<Int, IntArray>()

    var changed = false
        private set

    init {
        if (file.isFile) {
            val root = JsonParser().parse(file.readText()).asJsonObject
            for ((region, value) in root.entrySet()) {
                keys[region.toInt()] = value.asJsonArray.map { it.asInt }.toIntArray()
            }
        }
    }

    /** The keys to decrypt [regionId]'s landscape with, or null when it is stored unencrypted (or has no entry). */
    fun decryptionKeys(regionId: Int): IntArray? = keys[regionId]?.takeIf { k -> k.any { it != 0 } }

    fun hasEntry(regionId: Int) = keys.containsKey(regionId)

    /** Records that [regionId]'s landscape is now unencrypted. Returns false when that was already the case. */
    fun markUnencrypted(regionId: Int): Boolean {
        val current = keys[regionId]
        if (current != null && current.all { it == 0 }) return false
        keys[regionId] = IntArray(4)
        changed = true
        return true
    }

    fun save() {
        val root = JsonObject()
        for ((region, k) in keys) {
            root.add(region.toString(), JsonArray().apply { k.forEach { add(it) } })
        }
        file.parentFile?.mkdirs()
        file.writeText(GsonBuilder().setPrettyPrinting().create().toJson(root))
        changed = false
    }
}
