package io.ruin.cachetools.format

import java.nio.ByteBuffer

/** The map square carried by a `.pack` file: rev-184 terrain and locations, ready for the same checks as `m`/`l` files. */
class PackedRegion(val terrain: ByteArray, val locations: ByteArray, val trailerBytes: Int, val trailerOk: Boolean)

/**
 * `.pack` map bundles (e.g. `Tutorial_Island.pack`): one map square in a single file. Layout, as worked out from a
 * sample file (all integers big-endian):
 *
 * ```
 * u32  version              1
 * u32  (unknown)            1 in the sample; anything else is refused rather than guessed
 * u32  (unknown) x3         0 in the sample; ignored (the structure checks below catch a different layout)
 * u32  locationsLength
 * ...  locations            `l{x}_{y}` content, [locationsLength] bytes
 * u32  terrainLength
 * ...  terrain              `m{x}_{y}` content, [terrainLength] bytes
 * ...  trailer              opaque; ends with "TFWM" <u32 n> <n bytes of JSON> <u32 n> "MWFT". Never read.
 * ```
 *
 * The file does not say which map square it is, so the destination comes from the file name (see MapPacker).
 * Nothing is trusted: both parts must be valid rev-184 map data ([MapFormat]) and fit the declared lengths exactly.
 */
object PackFormat {

    private const val HEADER = 24
    private val END_MAGIC = "MWFT".toByteArray(Charsets.US_ASCII)

    /** Returns the region, or throws [IllegalArgumentException] with the reason this is not a readable pack. */
    fun parse(data: ByteArray): PackedRegion {
        require(data.size >= HEADER + 8) { "file is too small (${data.size} bytes) to be a .pack map" }
        val buf = ByteBuffer.wrap(data) // big-endian
        val version = buf.getInt(0)
        require(version == 1) { "unsupported .pack version $version (only version 1 is known)" }
        val second = buf.getInt(4)
        require(second == 1) { "unsupported .pack header (second field is $second, 1 expected); this may be a multi-map or newer pack" }

        val locationsLength = buf.getInt(20)
        require(locationsLength in 1..(data.size - HEADER - 4)) { "locations length $locationsLength does not fit the file" }
        val terrainLengthAt = HEADER + locationsLength
        val terrainLength = buf.getInt(terrainLengthAt)
        val terrainStart = terrainLengthAt + 4
        require(terrainLength in 1..(data.size - terrainStart)) { "terrain length $terrainLength does not fit the file" }

        val locations = data.copyOfRange(HEADER, terrainLengthAt)
        val terrain = data.copyOfRange(terrainStart, terrainStart + terrainLength)
        MapFormat.checkLocations(locations)?.let { throw IllegalArgumentException("locations block is not rev-184 map data: $it") }
        MapFormat.checkTerrain(terrain)?.let { throw IllegalArgumentException("terrain block is not rev-184 map data: $it") }

        val trailerStart = terrainStart + terrainLength
        val trailerOk = data.size - trailerStart >= END_MAGIC.size &&
            data.copyOfRange(data.size - END_MAGIC.size, data.size).contentEquals(END_MAGIC)
        return PackedRegion(terrain, locations, data.size - trailerStart, trailerOk)
    }
}
