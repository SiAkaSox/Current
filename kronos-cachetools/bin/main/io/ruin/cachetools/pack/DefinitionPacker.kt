package io.ruin.cachetools.pack

import io.ruin.cachetools.KronosCache
import io.ruin.cachetools.format.DefinitionException
import io.ruin.cachetools.format.DefinitionSchema
import io.ruin.cachetools.format.Definitions
import io.ruin.cachetools.format.Toml
import io.ruin.cachetools.format.TomlException
import java.io.File

/**
 * `raw-cache/definitions/{items,npcs,objects}/<any name>.toml` -> index 2, archive 10 / 9 / 6, file `id`.
 *
 * Each file holds one definition: `id`, optionally `inherit = <id>` to start from an existing definition (from the
 * cache, or another TOML file in the same folder), optionally `remove = ["key", ...]` to drop inherited values, and
 * the keys of [Definitions]. Without `inherit` the definition starts empty, i.e. from the client's defaults.
 */
object DefinitionPacker : Packer {
    override val name = "definitions"
    override val directory = "definitions"

    private class Source(val label: String, val values: Map<String, Any>, val id: Int)

    override fun pack(ctx: PackContext, dir: File) {
        for (schema in Definitions.ALL) {
            val kindDir = File(dir, schema.directory)
            if (kindDir.isDirectory) pack(ctx, schema, kindDir)
        }
        dir.listFiles { f -> f.isDirectory && Definitions.ALL.none { it.directory == f.name } }?.forEach {
            ctx.report.warn("definitions/${it.name}/: not a definition folder (expected ${Definitions.ALL.joinToString { s -> s.directory }}); skipped")
        }
    }

    private fun pack(ctx: PackContext, schema: DefinitionSchema, kindDir: File) {
        val sources = LinkedHashMap<Int, Source>()
        for (file in filesIn(kindDir, "toml")) {
            val label = "definitions/${schema.directory}/${rel(kindDir, file)}"
            val values = try {
                Toml.parse(file.readText(Charsets.UTF_8).removePrefix("﻿"))
            } catch (e: TomlException) {
                ctx.report.error("$label: ${e.message}"); continue
            }
            val id = values["id"] as? Int
            if (id == null || id !in 0..65535) { ctx.report.error("$label: needs `id = <0..65535>`"); continue }
            val inherit = values["inherit"]
            if (inherit != null && (inherit !is Int || inherit < 0)) { ctx.report.error("$label: `inherit` must be a ${schema.kind} id"); continue }
            val duplicate = sources[id]
            if (duplicate != null) { ctx.report.error("$label: ${schema.kind} $id is also defined by ${duplicate.label}"); continue }
            sources[id] = Source(label, values, id)
        }

        val built = HashMap<Int, LinkedHashMap<Int, ByteArray>?>()

        // Inherit cycles between TOML files are refused up front, once per cycle (`inherit = <own id>` means the cache).
        for (start in sources.values) {
            val chain = LinkedHashSet<Int>()
            var id: Int? = start.id
            while (id != null && id in sources && id !in built && chain.add(id)) {
                id = (sources.getValue(id).values["inherit"] as Int?)?.takeIf { it != id }
            }
            if (id != null && id in chain) {
                val cycle = chain.dropWhile { it != id }
                ctx.report.error("${cycle.joinToString { sources.getValue(it).label }}: inherit cycle ${(cycle + id).joinToString(" -> ")}")
                cycle.forEach { built[it] = null }
            }
        }

        // A definition that inherits from another TOML definition is built after it, from that staged result.
        fun build(source: Source): LinkedHashMap<Int, ByteArray>? {
            if (source.id in built) return built[source.id]
            return buildOne(ctx, schema, source, sources, ::build).also { built[source.id] = it }
        }

        for (source in sources.values.sortedBy { it.id }) {
            val ops = build(source) ?: continue
            val data = schema.encode(ops)
            try {
                schema.parse(data)  // never stage bytes that this tool itself cannot read back
            } catch (e: DefinitionException) {
                ctx.report.error("${source.label}: encoded definition does not decode (${e.message}); this is a kronos-cachetools bug"); continue
            }
            val existing = ctx.library.data(KronosCache.CONFIGS, schema.archive, source.id)
            if (existing != null && source.values["inherit"] != source.id) {
                val oldName = runCatching { schema.name(schema.parse(existing)) }.getOrNull()
                ctx.report.warn("${source.label}: replaces existing ${schema.kind} ${source.id}${oldName?.let { " \"$it\"" } ?: ""} (use `inherit = ${source.id}` to edit it instead)")
            }
            ctx.library.put(KronosCache.CONFIGS, schema.archive, source.id, data)
            ctx.report.changes += Change(KronosCache.CONFIGS, schema.archive, source.id, data, source.label, replacedExisting = existing != null)
            val inherit = source.values["inherit"]
            ctx.report.note("${source.label} -> ${schema.kind} ${source.id} \"${schema.name(ops) ?: "null"}\"${if (inherit != null) " (inherits $inherit)" else ""}")
        }
    }

    private fun buildOne(
        ctx: PackContext,
        schema: DefinitionSchema,
        source: Source,
        sources: Map<Int, Source>,
        build: (Source) -> LinkedHashMap<Int, ByteArray>?,
    ): LinkedHashMap<Int, ByteArray>? {
        val inherit = source.values["inherit"] as Int?
        val base: LinkedHashMap<Int, ByteArray> = when {
            inherit == null -> LinkedHashMap()
            inherit in sources && inherit != source.id -> build(sources.getValue(inherit))?.let { LinkedHashMap(it) }
                ?: run { ctx.report.error("${source.label}: inherits ${schema.kind} $inherit, which was refused"); return null }
            else -> {
                val parent = ctx.library.data(KronosCache.CONFIGS, schema.archive, inherit)
                    ?: run { ctx.report.error("${source.label}: inherit $inherit: no such ${schema.kind} in the cache"); return null }
                try {
                    schema.parse(parent)
                } catch (e: DefinitionException) {
                    ctx.report.error("${source.label}: inherit $inherit: cannot read that ${schema.kind} (${e.message})"); return null
                }
            }
        }
        val result = schema.apply(base, source.values)
        result.warnings.forEach { ctx.report.warn("${source.label}: $it") }
        result.errors.forEach { ctx.report.error("${source.label}: $it") }
        if (result.errors.isNotEmpty()) return null
        if (inherit == null && 2 !in result.ops) ctx.report.warn("${source.label}: has no name (the client shows \"null\")")
        return result.ops
    }
}
