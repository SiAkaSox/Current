package io.ruin.cachetools.pack

import com.displee.cache.CacheLibrary
import com.google.gson.JsonParser
import io.ruin.cachetools.KronosCache
import io.ruin.cachetools.RegionKeys
import io.ruin.cachetools.format.MapFormat
import io.ruin.cachetools.format.ModelConverter
import io.ruin.cachetools.format.ModelFormat
import io.ruin.cachetools.format.PackFormat
import io.ruin.cachetools.format.SpriteCodec
import io.ruin.cachetools.format.SpriteFrame
import io.ruin.cachetools.format.SpriteGroup
import java.io.File
import java.util.zip.GZIPInputStream
import javax.imageio.ImageIO

class PackContext(
    val library: CacheLibrary,
    val raw: File,
    val keys: RegionKeys,
    val report: PackReport,
)

/** One kind of raw-cache input. Packers only stage changes in [PackContext.library]; nothing touches disk until commit. */
interface Packer {
    val name: String
    val directory: String
    fun pack(ctx: PackContext, dir: File)
}

// DefinitionPacker runs after FilePacker so a TOML definition can inherit from a .dat packed in the same run, and
// before MapPacker so a map can place an object defined in TOML.
val PACKERS: List<Packer> = listOf(FilePacker, DefinitionPacker, ModelPacker, SpritePacker, MapPacker)

internal fun readMaybeGzip(file: File): ByteArray =
    if (file.extension.equals("gz", true)) GZIPInputStream(file.inputStream()).use { it.readBytes() } else file.readBytes()

internal fun rel(root: File, file: File) = file.relativeTo(root).path.replace('\\', '/')

internal fun filesIn(dir: File, vararg extensions: String): List<File> =
    dir.walkTopDown().filter { f -> f.isFile && extensions.any { f.extension.equals(it, true) } }.sortedBy { it.path }.toList()

/**
 * `raw-cache/files/<index>/<archive>/<file>.dat` -> that exact cache file, for anything without a dedicated packer
 * (definitions exported from an editor, enums, interfaces, ...). The rest of the archive is kept.
 */
object FilePacker : Packer {
    override val name = "files"
    override val directory = "files"

    override fun pack(ctx: PackContext, dir: File) {
        for (file in filesIn(dir, "dat", "gz")) {
            val path = rel(dir, file)
            val parts = path.substringBeforeLast('.').split('/')
            val ids = parts.map { it.toIntOrNull() }
            if (parts.size != 3 || ids.any { it == null || it < 0 }) {
                ctx.report.warn("files/$path: expected files/<index>/<archive>/<file>.dat with numeric ids; skipped")
                continue
            }
            val (index, archive, fileId) = ids.map { it!! }
            when {
                index == KronosCache.MAPS -> { ctx.report.error("files/$path: map groups are named and keyed; put them in maps/ as m{x}_{y} / l{x}_{y}"); continue }
                index >= 255 || !ctx.library.exists(index) -> { ctx.report.error("files/$path: index $index does not exist in this cache"); continue }
            }
            val data = readMaybeGzip(file)
            val existed = ctx.library.index(index).archive(archive)?.contains(fileId) == true
            ctx.library.put(index, archive, fileId, data)
            ctx.report.changes += Change(index, archive, fileId, data, "files/$path", replacedExisting = existed)
        }
    }
}

/** `raw-cache/models/<id>.dat|.gz` -> index 7, group `id`, file 0. */
object ModelPacker : Packer {
    override val name = "models"
    override val directory = "models"

    override fun pack(ctx: PackContext, dir: File) {
        val byId = LinkedHashMap<Int, MutableList<File>>()
        for (file in filesIn(dir, "dat", "gz")) {
            val id = file.nameWithoutExtension.toIntOrNull()
            if (id == null || id < 0) { ctx.report.warn("models/${rel(dir, file)}: file name is not a model id; skipped"); continue }
            byId.getOrPut(id) { ArrayList() } += file
        }
        val index = ctx.library.index(KronosCache.MODELS)
        for ((id, files) in byId.toSortedMap()) {
            if (files.size > 1) { ctx.report.error("models: model $id is provided more than once: ${files.map { rel(dir, it) }}"); continue }
            val file = files.single()
            val label = "models/${rel(dir, file)}"
            val raw = readMaybeGzip(file)
            val problem = ModelFormat.check(raw)
            if (problem != null) { ctx.report.error("$label: $problem"); continue }
            val converted = try {
                ModelConverter.toSupported(raw)
            } catch (e: IllegalArgumentException) {
                ctx.report.error("$label: ${ModelFormat.detect(raw)} model could not be converted for the rev-${KronosCache.REVISION} client: ${e.message}")
                continue
            }
            val data = converted.data
            if (converted.from != converted.to) {
                ctx.report.note("$label: converted ${converted.from} model to ${converted.to} (the rev-${KronosCache.REVISION} client only decodes OLD and TYPE1)")
                converted.notes.forEach { ctx.report.warn("$label: $it") }
            }
            val existed = index.contains(id)
            ctx.library.put(KronosCache.MODELS, id, 0, data)
            ctx.report.changes += Change(KronosCache.MODELS, id, 0, data, label, replacedExisting = existed)
        }
    }
}

/**
 * `raw-cache/sprites/<id>.png` (one frame) or `<id>_<frame>.png` (frames 0..n) -> index 8, group `id`.
 *
 * Canvas size and frame offsets come from an optional `<id>.json` sidecar (`{"width":..,"height":..,"frames":
 * [{"offsetX":..,"offsetY":..}]}`, as written by `dump sprite`). Without one, a sprite that replaces an existing
 * group with the same frame count keeps that group's canvas and offsets; a new sprite gets offsets 0 and a canvas
 * the size of its largest frame.
 */
object SpritePacker : Packer {
    override val name = "sprites"
    override val directory = "sprites"

    private val NAME = Regex("^(\\d+)(?:_(\\d+))?$")

    override fun pack(ctx: PackContext, dir: File) {
        val groups = LinkedHashMap<Int, MutableMap<Int, File>>()
        val singles = HashSet<Int>()
        for (file in filesIn(dir, "png")) {
            val match = NAME.matchEntire(file.nameWithoutExtension)
            if (match == null) { ctx.report.warn("sprites/${rel(dir, file)}: expected <id>.png or <id>_<frame>.png; skipped"); continue }
            val id = match.groupValues[1].toInt()
            val frame = match.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: 0
            if (match.groupValues[2].isEmpty()) singles += id
            val previous = groups.getOrPut(id) { LinkedHashMap() }.put(frame, file)
            if (previous != null) ctx.report.error("sprites: sprite $id frame $frame is provided twice (${rel(dir, previous)}, ${rel(dir, file)})")
        }
        val index = ctx.library.index(KronosCache.SPRITES)
        for ((id, frameFiles) in groups.toSortedMap()) {
            if (id in singles && frameFiles.size > 1) { ctx.report.error("sprites: sprite $id has both $id.png and ${id}_<frame>.png files"); continue }
            val frameIds = frameFiles.keys.sorted()
            if (frameIds != (0 until frameIds.size).toList()) { ctx.report.error("sprites: sprite $id frames must be numbered 0..${frameIds.size - 1}, found $frameIds"); continue }
            val images = frameIds.map { f ->
                val file = frameFiles.getValue(f)
                ImageIO.read(file) ?: run { ctx.report.error("sprites/${rel(dir, file)}: not a readable PNG"); null }
            }
            if (images.any { it == null }) continue
            val label = "sprites/" + if (frameFiles.size == 1) rel(dir, frameFiles.values.single()) else "${id}_[0-${frameIds.size - 1}].png"

            val existing = if (index.contains(id)) ctx.library.data(KronosCache.SPRITES, id, 0)?.let { runCatching { SpriteCodec.decode(it) }.getOrNull() } else null
            val sidecar = File(frameFiles.values.first().parentFile, "$id.json").takeIf { it.isFile }
            val group = layout(ctx, id, images.map { it!! }, sidecar, existing) ?: continue

            val encoded = SpriteCodec.encode(group)
            encoded.quantizedFrom?.let { ctx.report.warn("$label: $it colours reduced to ${encoded.colours} (sprites are limited to a 255-colour palette)") }
            if (encoded.flattenedAlphaPixels > 0) ctx.report.warn("$label: ${encoded.flattenedAlphaPixels} semi-transparent pixels made fully opaque/transparent (the rev-${KronosCache.REVISION} client has no sprite alpha)")
            ctx.library.put(KronosCache.SPRITES, id, 0, encoded.data)
            ctx.report.changes += Change(KronosCache.SPRITES, id, 0, encoded.data, label, replacedExisting = existing != null || index.contains(id))
        }
    }

    private fun layout(ctx: PackContext, id: Int, images: List<java.awt.image.BufferedImage>, sidecar: File?, existing: SpriteGroup?): SpriteGroup? {
        if (sidecar != null) {
            val json = runCatching { JsonParser().parse(sidecar.readText()).asJsonObject }.getOrElse {
                ctx.report.error("sprites/${sidecar.name}: invalid JSON (${it.message})"); return null
            }
            val frames = json.getAsJsonArray("frames")
            if (frames != null && frames.size() != images.size) {
                ctx.report.error("sprites/${sidecar.name}: lists ${frames.size()} frames but sprite $id has ${images.size} PNGs"); return null
            }
            val result = images.mapIndexed { i, img ->
                val f = frames?.get(i)?.asJsonObject
                SpriteFrame(f?.get("offsetX")?.asInt ?: 0, f?.get("offsetY")?.asInt ?: 0, img)
            }
            return SpriteGroup(json.get("width")?.asInt ?: result.maxOf { it.offsetX + it.image.width }, json.get("height")?.asInt ?: result.maxOf { it.offsetY + it.image.height }, result)
        }
        if (existing != null && existing.frames.size == images.size) {
            val result = images.mapIndexed { i, img -> SpriteFrame(existing.frames[i].offsetX, existing.frames[i].offsetY, img) }
            val width = maxOf(existing.width, result.maxOf { it.offsetX + it.image.width })
            val height = maxOf(existing.height, result.maxOf { it.offsetY + it.image.height })
            ctx.report.note("sprite $id: kept the canvas and frame offsets of the sprite it replaces")
            return SpriteGroup(width, height, result)
        }
        return SpriteGroup(images.maxOf { it.width }, images.maxOf { it.height }, images.map { SpriteFrame(0, 0, it) })
    }
}

/**
 * `raw-cache/maps/m{x}_{y}` (terrain) + `l{x}_{y}` (locations), `.dat` or `.gz` -> the named groups of index 5.
 *
 * The landscape is stored unencrypted and the region's entry in region_keys.json is set to `[0,0,0,0]`, which the
 * server and client both treat as "no XTEA" (a region with no entry at all would load without objects).
 *
 * Kronos names follow Jagex (`m` = terrain, `l` = locations); OpenRune and RSPSi exports use the reverse. Both files
 * are parsed, so a reversed pair is detected and packed correctly rather than producing a broken region.
 *
 * `raw-cache/maps/<name>@<x>_<y>.pack` is the single-file form of the same pair (see [PackFormat]). A pack does not
 * say which map square it is, so the destination is the `@<x>_<y>` in its file name; it then goes through exactly the
 * same checks and writes as an `m`/`l` pair.
 */
object MapPacker : Packer {
    override val name = "maps"
    override val directory = "maps"

    private val NAME = Regex("^([ml])(\\d+)_(\\d+)$")
    private val PACK_NAME = Regex("^.+@(\\d+)_(\\d+)$")

    /** One validated map square, whichever kind of input it came from. */
    private class Square(val label: String, val terrain: ByteArray, val locations: ByteArray)

    override fun pack(ctx: PackContext, dir: File) {
        val regions = java.util.TreeMap<Int, MutableMap<Char, File>>()
        for (file in filesIn(dir, "dat", "gz")) {
            val match = NAME.matchEntire(file.nameWithoutExtension)
            if (match == null) { ctx.report.warn("maps/${rel(dir, file)}: expected m{x}_{y} / l{x}_{y}; skipped"); continue }
            val region = KronosCache.regionId(match.groupValues[2].toInt(), match.groupValues[3].toInt())
            val previous = regions.getOrPut(region) { HashMap() }.put(match.groupValues[1][0], file)
            if (previous != null) ctx.report.error("maps: ${rel(dir, previous)} and ${rel(dir, file)} are the same map file")
        }

        val squares = java.util.TreeMap<Int, Square>()
        for ((region, pair) in regions) {
            val x = region shr 8
            val y = region and 0xFF
            val m = pair['m']
            val l = pair['l']
            if (m == null || l == null) { ctx.report.error("maps: region ${x}_$y needs both m${x}_$y and l${x}_$y (found ${pair.values.map { it.name }})"); continue }
            val label = "maps/${x}_$y"
            val mData = readMaybeGzip(m)
            val lData = readMaybeGzip(l)

            val jagexOrder = MapFormat.checkTerrain(mData) == null && MapFormat.checkLocations(lData) == null
            val swappedOrder = MapFormat.checkTerrain(lData) == null && MapFormat.checkLocations(mData) == null
            val (terrain, locations) = when {
                jagexOrder -> mData to lData
                swappedOrder -> {
                    ctx.report.note("$label: m/l names are swapped (OpenRune/RSPSi convention: l = terrain); packed l${x}_$y as terrain")
                    lData to mData
                }
                else -> {
                    ctx.report.error(
                        "$label: not a rev-${KronosCache.REVISION} map square (maps exported from newer revisions use a different tile format). " +
                            "As m=terrain: ${MapFormat.checkTerrain(mData) ?: "ok"} / l=locations: ${MapFormat.checkLocations(lData) ?: "ok"}. " +
                            "As l=terrain: ${MapFormat.checkTerrain(lData) ?: "ok"} / m=locations: ${MapFormat.checkLocations(mData) ?: "ok"}"
                    )
                    continue
                }
            }

            squares[region] = Square(label, terrain, locations)
        }

        collectPacks(ctx, dir, squares)

        val objectDefs = ctx.library.index(KronosCache.CONFIGS).archive(KronosCache.OBJECT_DEFS_ARCHIVE)?.fileIds()?.toHashSet() ?: hashSetOf()
        val index = ctx.library.index(KronosCache.MAPS)

        for ((region, square) in squares) {
            val x = region shr 8
            val y = region and 0xFF
            val label = square.label
            val undefined = MapFormat.decodeLocations(square.locations).map { it.id }.filter { it !in objectDefs }.distinct().sorted()
            if (undefined.isNotEmpty()) {
                ctx.report.error("$label: places objects with no definition in this cache: ${undefined.take(20)}${if (undefined.size > 20) " (+${undefined.size - 20} more)" else ""}")
                continue
            }

            val replaced = put(index, KronosCache.terrainName(x, y), square.terrain).let { (id, existed) ->
                ctx.report.changes += Change(KronosCache.MAPS, id, 0, square.terrain, label, KronosCache.terrainName(x, y), existed); existed
            }
            put(index, KronosCache.locationsName(x, y), square.locations).let { (id, existed) ->
                ctx.report.changes += Change(KronosCache.MAPS, id, 0, square.locations, label, KronosCache.locationsName(x, y), existed)
            }
            if (ctx.keys.markUnencrypted(region)) {
                ctx.report.note("$label: region_keys.json entry for region $region set to [0,0,0,0] (landscape stored unencrypted)")
            }
            if (!replaced) ctx.report.note("$label: new region (did not exist in the cache)")
        }
    }

    /**
     * `<name>@<x>_<y>.pack` -> a [Square] for map square x_y. A pack and an `m`/`l` pair (or two packs) for the same
     * square are ambiguous, so both are refused rather than one silently winning.
     */
    private fun collectPacks(ctx: PackContext, dir: File, squares: java.util.TreeMap<Int, Square>) {
        val fromPack = HashMap<Int, File>()
        val conflicted = HashSet<Int>()
        val notes = java.util.TreeMap<Int, String>()
        for (file in filesIn(dir, "pack")) {
            val path = "maps/${rel(dir, file)}"
            val match = PACK_NAME.matchEntire(file.nameWithoutExtension)
            val x = match?.groupValues?.get(1)?.toIntOrNull()
            val y = match?.groupValues?.get(2)?.toIntOrNull()
            if (x == null || y == null || x !in 0..255 || y !in 0..255) {
                ctx.report.error("$path: a .pack does not say which map square it is. Rename it <name>@<x>_<y>.pack (x and y are the region coordinates, 0-255), e.g. ${file.nameWithoutExtension}@48_48.pack")
                continue
            }
            val region = KronosCache.regionId(x, y)
            val other = fromPack.put(region, file)
            if (other != null) {
                ctx.report.error("maps: ${rel(dir, other)} and ${rel(dir, file)} are both for region ${x}_$y")
                conflicted += region
                continue
            }
            val packed = try {
                PackFormat.parse(file.readBytes())
            } catch (e: IllegalArgumentException) {
                ctx.report.error("$path: ${e.message}")
                continue
            }
            if (squares.containsKey(region)) {
                ctx.report.error("$path: region ${x}_$y is also given as m${x}_$y / l${x}_$y files; provide one or the other")
                conflicted += region
                continue
            }
            if (!packed.trailerOk) ctx.report.warn("$path: trailer does not end with the usual MWFT marker; the map data itself is valid and was packed")
            notes[region] = "$path: packed as region ${x}_$y (terrain ${packed.terrain.size} bytes, locations ${packed.locations.size} bytes; ${packed.trailerBytes}-byte trailer ignored)"
            squares[region] = Square("maps/${x}_$y", packed.terrain, packed.locations)
        }
        conflicted.forEach { squares.remove(it); notes.remove(it) }
        notes.values.forEach { ctx.report.note(it) }
    }

    /**
     * Writes a fresh, unencrypted single-file group under [name], keeping the group id when it already exists.
     * The old group is dropped rather than read, because reading an encrypted landscape needs its key.
     */
    private fun put(index: com.displee.cache.index.Index, name: String, data: ByteArray): Pair<Int, Boolean> {
        val existingId = index.archiveId(name)
        val archive = if (existingId != -1) {
            index.remove(existingId)
            index.add(existingId, index.toHash(name))
        } else {
            index.add(name)
        }
        archive.add(0, data)
        return archive.id to (existingId != -1)
    }
}
