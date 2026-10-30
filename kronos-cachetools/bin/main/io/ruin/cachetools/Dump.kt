package io.ruin.cachetools

import com.displee.cache.CacheLibrary
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.ruin.cachetools.format.DefinitionException
import io.ruin.cachetools.format.DefinitionSchema
import io.ruin.cachetools.format.Definitions
import io.ruin.cachetools.format.MapFormat
import io.ruin.cachetools.format.SpriteCodec
import java.io.File
import javax.imageio.ImageIO

/**
 * `dump`: Cache -> cache-dump/, in exactly the layout `pack` reads from raw-cache/, so editing an existing asset is
 * "dump it, edit it, copy it into raw-cache/, pack".
 */
class Dump(private val ws: Workspace) {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun run(args: List<String>): Int {
        val kind = args.firstOrNull()
        val targets = args.drop(1)
        if (kind == null || (targets.isEmpty() && kind != "file")) return usage()
        if (!KronosCache.isCacheDir(ws.cache)) { System.err.println("error: ${ws.cache} is not a cache directory"); return 1 }
        val library = CacheLibrary(ws.cache.path)
        try {
            when (kind) {
                "model", "models" -> ids(targets).forEach { dumpModel(library, it) }
                "sprite", "sprites" -> ids(targets).forEach { dumpSprite(library, it) }
                "map", "maps" -> targets.forEach { dumpMap(library, it) }
                "item", "items" -> ids(targets).forEach { dumpDefinition(library, Definitions.ITEM, it) }
                "npc", "npcs" -> ids(targets).forEach { dumpDefinition(library, Definitions.NPC, it) }
                "object", "objects" -> ids(targets).forEach { dumpDefinition(library, Definitions.OBJECT, it) }
                "file" -> {
                    if (targets.size != 3) return usage()
                    val (index, archive, file) = ids(targets)
                    dumpFile(library, index, archive, file)
                }
                else -> return usage()
            }
        } finally {
            library.close()
        }
        return 0
    }

    /** Accepts `1234`, `1000-1010` and comma separated lists. */
    private fun ids(args: List<String>): List<Int> = args.flatMap { it.split(',') }.filter { it.isNotBlank() }.flatMap { token ->
        val range = token.split('-')
        if (range.size == 2) (range[0].trim().toInt()..range[1].trim().toInt()).toList() else listOf(token.trim().toInt())
    }

    private fun out(vararg path: String) = File(ws.dump, path.joinToString("/")).also { it.parentFile.mkdirs() }

    private fun dumpModel(library: CacheLibrary, id: Int) {
        val data = library.data(KronosCache.MODELS, id, 0) ?: return println("model $id: not in the cache")
        out("models", "$id.dat").writeBytes(data)
        println("model $id -> ${out("models", "$id.dat")}")
    }

    private fun dumpSprite(library: CacheLibrary, id: Int) {
        val data = library.data(KronosCache.SPRITES, id, 0) ?: return println("sprite $id: not in the cache")
        val group = SpriteCodec.decode(data)
        group.frames.forEachIndexed { i, frame ->
            val name = if (group.frames.size == 1) "$id.png" else "${id}_$i.png"
            ImageIO.write(frame.image, "png", out("sprites", name))
        }
        val meta = JsonObject().apply {
            addProperty("width", group.width)
            addProperty("height", group.height)
            add("frames", JsonArray().apply {
                group.frames.forEach { f -> add(JsonObject().apply { addProperty("offsetX", f.offsetX); addProperty("offsetY", f.offsetY) }) }
            })
        }
        out("sprites", "$id.json").writeText(gson.toJson(meta))
        println("sprite $id -> ${group.frames.size} frame(s) in ${File(ws.dump, "sprites")}")
    }

    /** Accepts `50_50` (region coordinates) or a region id like `12850`. */
    private fun dumpMap(library: CacheLibrary, target: String) {
        val (x, y) = if ('_' in target) target.split('_').map { it.toInt() } else target.toInt().let { listOf(it shr 8, it and 0xFF) }
        val region = KronosCache.regionId(x, y)
        val terrain = library.data(KronosCache.MAPS, KronosCache.terrainName(x, y))
        if (terrain == null) return println("map ${x}_$y (region $region): not in the cache")
        out("maps", "${KronosCache.terrainName(x, y)}.dat").writeBytes(terrain)
        val keys = RegionKeys(ws.regionKeys).decryptionKeys(region)
        var failure: String? = null
        val locations = try {
            library.index(KronosCache.MAPS).let { index ->
                val archive = index.archiveId(KronosCache.locationsName(x, y))
                if (archive == -1) null else library.data(KronosCache.MAPS, archive, 0, keys)
            }
        } catch (t: Throwable) {
            failure = t.toString()
            null
        }
        val problem = failure ?: if (locations == null) "no l${x}_$y group" else MapFormat.checkLocations(locations)
        if (locations == null || problem != null) {
            println("map ${x}_$y: terrain dumped; locations could not be read ($problem; keys ${keys?.contentToString() ?: "none"} from ${ws.regionKeys.name})")
            return
        }
        out("maps", "${KronosCache.locationsName(x, y)}.dat").writeBytes(locations)
        println("map ${x}_$y (region $region) -> ${File(ws.dump, "maps")} (${MapFormat.decodeLocations(locations).size} objects)")
    }

    private fun dumpDefinition(library: CacheLibrary, schema: DefinitionSchema, id: Int) {
        val data = library.data(KronosCache.CONFIGS, schema.archive, id) ?: return println("${schema.kind} $id: not in the cache")
        val toml = try {
            schema.toToml(id, data)
        } catch (e: DefinitionException) {
            return println("${schema.kind} $id: cannot be read as TOML (${e.message}); use `dump file 2 ${schema.archive} $id` for the raw bytes")
        }
        val file = out("definitions", schema.directory, "$id.toml")
        file.writeText(toml)
        println("${schema.kind} $id -> $file")
    }

    private fun dumpFile(library: CacheLibrary, index: Int, archive: Int, file: Int) {
        val data = library.data(index, archive, file) ?: return println("file $index/$archive/$file: not in the cache")
        out("files", "$index", "$archive", "$file.dat").writeBytes(data)
        println("file $index/$archive/$file -> ${out("files", "$index", "$archive", "$file.dat")}")
    }

    private fun usage(): Int {
        System.err.println(
            """
            usage: dump model <id|from-to|a,b,c>...
                   dump sprite <id|from-to|a,b,c>...
                   dump map <x>_<y>|<regionId>...
                   dump item|npc|object <id|from-to|a,b,c>...   (as editable TOML)
                   dump file <index> <archive> <file>
            Output goes to ${ws.dump} in the raw-cache layout.
            """.trimIndent()
        )
        return 1
    }
}
