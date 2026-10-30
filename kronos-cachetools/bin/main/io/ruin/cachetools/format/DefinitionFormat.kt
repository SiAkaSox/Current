package io.ruin.cachetools.format

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

class DefinitionException(message: String) : Exception(message)

private val CP1252: Charset = Charset.forName("windows-1252")

/** How one value inside an opcode's payload is stored. */
enum class Part {
    U8, I8, U16,
    /** u16 where 65535 means -1 (animations, varbits, transform ids). */
    U16N,
    I16, I32, STR,
    /** u8 count, then that many u16. */
    U16_LIST,
    /** u8 count, then that many (u16, u16) pairs; recolours / retextures written as `[[from, to], ...]`. */
    PAIR_LIST,
    /** u8 count, then that many (u16 model, u8 type) pairs (object opcode 1). */
    MODEL_TYPES,
    /** u8 n, then n + 1 u16 ids where 65535 means -1 (NPC / object transforms). */
    TRANSFORMS,
    /** u8 count, then (u8 isString, medium key, string | i32) entries (opcode 249). */
    PARAMS,
}

/** One value of an opcode: its TOML key (null = not editable from TOML) and its default when the opcode is new. */
class Field(val key: String?, val part: Part, val default: Any? = null)

/**
 * One opcode. A flag (no fields) is written as `flagKey = true/false`, where [inverted] flags are present when the
 * key is false (e.g. NPC opcode 93 = `minimapVisible = false`). Writing this opcode removes the ones in [replaces]
 * (alternative encodings of the same value). [note] marks opcodes that only one of server / client decodes.
 */
class OpSpec(
    val opcode: Int,
    val fields: List<Field>,
    val flagKey: String? = null,
    val inverted: Boolean = false,
    val replaces: List<Int> = emptyList(),
    val label: String? = null,
    val note: String? = null,
) {
    val keys: List<String> = fields.mapNotNull { it.key } + listOfNotNull(flagKey)
    val isFlag get() = fields.isEmpty()
    val description get() = label ?: keys.joinToString("/").ifEmpty { "opcode $opcode" }
}

/** The opcodes of one definition type, in the form both the Kronos server and the rev-184 client decode them. */
class DefinitionSchema(val kind: String, val archive: Int, val directory: String) {
    val specs = LinkedHashMap<Int, OpSpec>()
    val aliases = HashMap<String, String>()
    /** Keys that are accepted for compatibility but have no rev-184 cache field. */
    val ignored = HashMap<String, String>()

    fun op(opcode: Int, vararg fields: Field, replaces: List<Int> = emptyList()) { specs[opcode] = OpSpec(opcode, fields.toList(), replaces = replaces) }
    fun flag(opcode: Int, key: String, inverted: Boolean = false) { specs[opcode] = OpSpec(opcode, emptyList(), key, inverted) }
    /** Parsed and kept when inheriting, but not editable from TOML. */
    fun keep(opcode: Int, label: String, vararg parts: Part, note: String? = null) {
        specs[opcode] = OpSpec(opcode, parts.map { Field(null, it) }, label = label, note = note)
    }

    fun specsFor(key: String): List<OpSpec> = specs.values.filter { key in it.keys }

    val allKeys: Set<String> get() = specs.values.flatMap { it.keys }.toSet() + aliases.keys

    // ---- binary ------------------------------------------------------------------------------------------------

    /** Splits an encoded definition into opcode -> payload, in stream order. */
    fun parse(data: ByteArray): LinkedHashMap<Int, ByteArray> {
        val r = Reader(data)
        val ops = LinkedHashMap<Int, ByteArray>()
        while (true) {
            if (r.p >= data.size) throw DefinitionException("ends without the 0 terminator")
            val opcode = r.u8()
            if (opcode == 0) break
            val spec = specs[opcode] ?: throw DefinitionException("unknown $kind opcode $opcode at byte ${r.p - 1}")
            val start = r.p
            try {
                spec.fields.forEach { r.read(it.part) }
            } catch (e: IndexOutOfBoundsException) {
                throw DefinitionException("opcode $opcode is truncated")
            }
            ops[opcode] = data.copyOfRange(start, r.p)
        }
        if (r.p != data.size) throw DefinitionException("${data.size - r.p} byte(s) after the 0 terminator")
        return ops
    }

    fun encode(ops: Map<Int, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((opcode, payload) in ops) { out.write(opcode); out.write(payload) }
        out.write(0)
        return out.toByteArray()
    }

    fun values(opcode: Int, payload: ByteArray): List<Any> {
        val r = Reader(payload)
        return specs.getValue(opcode).fields.map { r.read(it.part) }
    }

    fun name(ops: Map<Int, ByteArray>): String? = ops[2]?.let { values(2, it)[0] as String }

    // ---- TOML -> definition ------------------------------------------------------------------------------------

    class Result(val ops: LinkedHashMap<Int, ByteArray>, val errors: List<String>, val warnings: List<String>)

    /**
     * Applies TOML [values] on top of [base] (the inherited definition, or empty). `id`, `inherit` and `remove` are
     * handled by the caller except `remove`, which lists keys whose opcodes are dropped from the inherited definition.
     */
    fun apply(base: LinkedHashMap<Int, ByteArray>, values: Map<String, Any>): Result {
        val ops = LinkedHashMap(base)
        val errors = ArrayList<String>()
        val warnings = ArrayList<String>()
        val input = LinkedHashMap<String, Any>()
        for ((k, v) in values) {
            if (k == "id" || k == "inherit" || k == "remove") continue
            val reason = ignored[k]
            if (reason != null) { warnings += "'$k' ignored: $reason"; continue }
            val key = aliases[k] ?: k
            if (key !in allKeys && key !in customKeys) { errors += "unknown key '$k'" + suggestion(k); continue }
            if (input.put(key, v) != null) errors += "'$key' is set twice ('$k' is another name for it)"
        }

        (values["remove"])?.let { remove ->
            val list = (remove as? List<*>)?.map { it as? String } ?: listOf(null)
            if (list.any { it == null }) errors += "remove must be a list of key names, like remove = [\"option2\"]"
            for (k in list.filterNotNull()) {
                val key = aliases[k] ?: k
                val opcodes = specsFor(key).map { it.opcode } + (customRemove[key] ?: emptyList())
                if (opcodes.isEmpty()) errors += "remove: unknown key '$k'" + suggestion(k)
                opcodes.forEach { ops.remove(it) }
            }
        }

        custom?.invoke(this, ops, input, errors)

        val fieldKeys = input.keys.filter { it !in customKeys }
        val uniqueTouched = fieldKeys.filter { specsFor(it).size == 1 }.map { specsFor(it).single().opcode }.toSet()
        val groups = LinkedHashMap<OpSpec, MutableList<String>>()
        for (key in fieldKeys) {
            val candidates = specsFor(key)
            val target = candidates.firstOrNull { it.opcode in uniqueTouched }
                ?: candidates.firstOrNull { it.opcode in ops }
                ?: candidates.minBy { it.fields.size }
            groups.getOrPut(target) { ArrayList() } += key
        }

        for ((spec, keys) in groups) {
            if (spec.isFlag) {
                val v = input.getValue(keys.single())
                if (v !is Boolean) { errors += "'${spec.flagKey}' must be true or false"; continue }
                if (v != spec.inverted) ops[spec.opcode] = ByteArray(0) else ops.remove(spec.opcode)
                continue
            }
            // A new opcode starts from the values of the alternative encoding it replaces (e.g. NPC 14 -> 17).
            val current: MutableList<Any?> = ops[spec.opcode]?.let { values(spec.opcode, it).toMutableList() }
                ?: spec.fields.map { f -> f.key?.let { replacedValue(ops, spec, it) } ?: f.default }.toMutableList()
            var ok = true
            for (key in keys) {
                val i = spec.fields.indexOfFirst { it.key == key }
                try {
                    current[i] = convert(key, input.getValue(key), spec.fields[i].part)
                } catch (e: DefinitionException) {
                    errors += e.message!!; ok = false
                }
            }
            if (!ok) continue
            val missing = spec.fields.filterIndexed { i, _ -> current[i] == null }.mapNotNull { it.key }
            if (missing.isNotEmpty()) {
                errors += "${keys.joinToString()} must be set together with ${missing.joinToString()} (they are stored in one opcode, ${spec.opcode})"
                continue
            }
            ops[spec.opcode] = Writer().apply { spec.fields.forEachIndexed { i, f -> write(f.part, current[i]!!) } }.bytes()
            spec.replaces.forEach { ops.remove(it) }
        }

        for (opcode in ops.keys) specs[opcode]?.note?.let { warnings += "keeps opcode $opcode (${specs.getValue(opcode).description}): $it" }
        return Result(ops, errors, warnings)
    }

    private fun replacedValue(ops: Map<Int, ByteArray>, spec: OpSpec, key: String): Any? = spec.replaces.firstNotNullOfOrNull { opcode ->
        val payload = ops[opcode] ?: return@firstNotNullOfOrNull null
        val i = specs.getValue(opcode).fields.indexOfFirst { it.key == key }
        if (i < 0) null else values(opcode, payload)[i]
    }

    /** Validates a TOML value for [part] and returns the value to write; throws [DefinitionException] otherwise. */
    private fun convert(key: String, v: Any, part: Part): Any {
        fun bad(expected: String): Nothing = throw DefinitionException("'$key' must be $expected")
        fun int(x: Any?, range: IntRange, expected: String): Int = (x as? Int)?.takeIf { it in range } ?: bad(expected)
        fun list(x: Any?, sizes: IntRange, expected: String): List<*> = (x as? List<*>)?.takeIf { it.size in sizes } ?: bad(expected)
        return when (part) {
            Part.U8 -> int(v, 0..255, "an integer 0..255")
            Part.I8 -> int(v, -128..127, "an integer -128..127")
            Part.U16 -> int(v, 0..65535, "an integer 0..65535")
            Part.U16N -> int(v, -1..65535, "an integer -1..65535 (-1 = none)")
            Part.I16 -> int(v, -32768..32767, "an integer -32768..32767")
            Part.I32 -> v as? Int ?: bad("an integer")
            Part.STR -> {
                if (v !is String) bad("a string")
                if ('\u0000' in v || !CP1252.newEncoder().canEncode(v)) bad("text the client can show (Windows-1252 characters only)")
                v
            }
            Part.U16_LIST -> "a list of up to 255 integers 0..65535".let { e -> list(v, 0..255, e).map { int(it, 0..65535, e) } }
            Part.PAIR_LIST -> "a list of up to 255 [from, to] pairs, like [[6798, 920]]".let { e ->
                list(v, 0..255, e).map { pair -> list(pair, 2..2, e).map { int(it, 0..65535, e) } }
            }
            Part.TRANSFORMS -> "a list of 1..256 ids (-1 = shows nothing)".let { e -> list(v, 1..256, e).map { int(it, -1..65535, e) } }
            Part.MODEL_TYPES, Part.PARAMS -> bad("set through its own keys")
        }
    }

    private fun suggestion(k: String): String {
        val close = allKeys.filter { it.equals(k, true) || levenshtein(it.lowercase(), k.lowercase()) <= 2 }
        return if (close.isEmpty()) "" else " (did you mean ${close.joinToString(" or ") { "'$it'" }}?)"
    }

    // Object models are one opcode for two keys (objectModels/objectTypes), so objects plug in custom handling.
    var customKeys: Set<String> = emptySet()
    var customRemove: Map<String, List<Int>> = emptyMap()
    var custom: (DefinitionSchema.(LinkedHashMap<Int, ByteArray>, Map<String, Any>, MutableList<String>) -> Unit)? = null
    var customDump: (DefinitionSchema.(Int, ByteArray) -> List<Pair<String, Any>>?)? = null

    // ---- definition -> TOML ------------------------------------------------------------------------------------

    fun toToml(id: Int, data: ByteArray): String {
        val ops = parse(data)
        val lines = ArrayList<String>()
        val kept = ArrayList<String>()
        for ((opcode, payload) in ops) {
            val spec = specs.getValue(opcode)
            val pairs: List<Pair<String, Any>> = customDump?.invoke(this, opcode, payload) ?: when {
                spec.isFlag && spec.flagKey != null -> listOf(spec.flagKey to !spec.inverted)
                spec.fields.isNotEmpty() && spec.fields.all { it.key != null } -> spec.fields.map { it.key!! }.zip(values(opcode, payload))
                else -> emptyList()
            }
            if (pairs.isEmpty()) kept += "$opcode (${spec.description})" else pairs.forEach { (k, v) -> lines += "$k = ${Toml.value(v)}" }
        }
        val header = buildList {
            add("# $kind $id, dumped from the cache by kronos-cachetools.")
            add("# Copy it to raw-cache/definitions/$directory/ and edit it; change `id` to pack it as a new $kind.")
            add("# Inherited values stay unless overridden here or listed in remove = [\"key\", ...].")
            add("id = $id")
            add("inherit = $id" + if (kept.isNotEmpty()) "  # also keeps what TOML cannot express: opcode ${kept.joinToString(", ")}" else "")
        }
        return (header + lines).joinToString("\n", postfix = "\n")
    }

    // ---- byte I/O ----------------------------------------------------------------------------------------------

    private class Reader(val b: ByteArray, var p: Int = 0) {
        fun u8() = b[p++].toInt() and 0xFF
        fun u16() = (u8() shl 8) or u8()
        fun i32() = (u16() shl 16) or u16()
        fun str(): String {
            val start = p
            while (b[p] != 0.toByte()) p++
            return String(b, start, p++ - start, CP1252)
        }

        fun read(part: Part): Any = when (part) {
            Part.U8 -> u8()
            Part.I8 -> u8().toByte().toInt()
            Part.U16 -> u16()
            Part.U16N -> u16().let { if (it == 65535) -1 else it }
            Part.I16 -> u16().toShort().toInt()
            Part.I32 -> i32()
            Part.STR -> str()
            Part.U16_LIST -> List(u8()) { u16() }
            Part.PAIR_LIST -> List(u8()) { listOf(u16(), u16()) }
            Part.MODEL_TYPES -> List(u8()) { listOf(u16(), u8()) }
            Part.TRANSFORMS -> List(u8() + 1) { u16().let { if (it == 65535) -1 else it } }
            Part.PARAMS -> List(u8()) {
                val string = u8() == 1
                val key = (u8() shl 16) or u16()
                listOf(key, if (string) str() else i32())
            }
        }
    }

    private class Writer {
        private val out = ByteArrayOutputStream()
        fun bytes(): ByteArray = out.toByteArray()
        private fun u8(v: Int) = out.write(v and 0xFF)
        private fun u16(v: Int) { u8(v shr 8); u8(v) }

        @Suppress("UNCHECKED_CAST")
        fun write(part: Part, v: Any) {
            when (part) {
                Part.U8, Part.I8 -> u8(v as Int)
                Part.U16, Part.U16N, Part.I16 -> u16(v as Int)
                Part.I32 -> { u16((v as Int) shr 16); u16(v) }
                Part.STR -> { out.write((v as String).toByteArray(CP1252)); u8(0) }
                Part.U16_LIST -> (v as List<Int>).let { l -> u8(l.size); l.forEach { u16(it) } }
                Part.PAIR_LIST -> (v as List<List<Int>>).let { l -> u8(l.size); l.forEach { u16(it[0]); u16(it[1]) } }
                Part.MODEL_TYPES -> (v as List<List<Int>>).let { l -> u8(l.size); l.forEach { u16(it[0]); u8(it[1]) } }
                Part.TRANSFORMS -> (v as List<Int>).let { l -> u8(l.size - 1); l.forEach { u16(it) } }
                Part.PARAMS -> error("params are only copied, never written from TOML")
            }
        }
    }

    companion object {
        internal fun encodeModelTypes(pairs: List<List<Int>>): ByteArray = Writer().apply { write(Part.MODEL_TYPES, pairs) }.bytes()
        internal fun encodeModels(models: List<Int>): ByteArray = Writer().apply { write(Part.U16_LIST, models) }.bytes()

        private fun levenshtein(a: String, b: String): Int {
            val d = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                var prev = d[0]; d[0] = i
                for (j in 1..b.length) {
                    val tmp = d[j]
                    d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                    prev = tmp
                }
            }
            return d[b.length]
        }
    }
}
