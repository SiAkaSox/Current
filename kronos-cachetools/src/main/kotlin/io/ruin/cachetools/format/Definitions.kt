package io.ruin.cachetools.format

import io.ruin.cachetools.format.Part.*

/**
 * Item / NPC / object definitions (index 2, archives 10 / 9 / 6) as both decoders read them: Kronos'
 * `io.ruin.cache.{ItemDef,NPCDef,ObjectDef}` and the rev-184 client. Keys follow the names used by the OpenRune
 * TOML files elsewhere in this repository where an equivalent exists.
 *
 * Opcodes only one side decodes are never written from TOML; they are kept (with a warning) when inherited.
 */
object Definitions {

    val ITEM = DefinitionSchema("item", 10, "items").apply {
        op(1, Field("inventoryModel", U16))
        op(2, Field("name", STR))
        op(4, Field("zoom2d", U16))
        op(5, Field("xan2d", U16))
        op(6, Field("yan2d", U16))
        op(7, Field("xOffset2d", I16))
        op(8, Field("yOffset2d", I16))
        flag(11, "stackable")
        op(12, Field("cost", I32))
        flag(16, "members")
        op(23, Field("maleModel0", U16), Field("maleOffset", U8, 0))
        op(24, Field("maleModel1", U16))
        op(25, Field("femaleModel0", U16), Field("femaleOffset", U8, 0))
        op(26, Field("femaleModel1", U16))
        for (i in 0 until 5) op(30 + i, Field("option${i + 1}", STR))   // ground options; "Hidden" hides one
        for (i in 0 until 5) op(35 + i, Field("ioption${i + 1}", STR))  // inventory options; ioption5 defaults to "Drop"
        op(40, Field("recolor", PAIR_LIST))
        op(41, Field("retexture", PAIR_LIST))
        keep(42, "shift-click index", I8, note = "the client reads it but Kronos' ItemDef does not, so the server misreads the rest of this definition")
        flag(65, "grandExchange")
        op(78, Field("maleModel2", U16))
        op(79, Field("femaleModel2", U16))
        op(90, Field("maleHeadModel0", U16))
        op(91, Field("femaleHeadModel0", U16))
        op(92, Field("maleHeadModel1", U16))
        op(93, Field("femaleHeadModel1", U16))
        op(95, Field("zan2d", U16))
        op(97, Field("noteLinkId", U16))
        op(98, Field("noteTemplateId", U16))
        for (i in 0 until 10) keep(100 + i, "stack variant ${i + 1}", U16, U16)
        op(110, Field("resizeX", U16))
        op(111, Field("resizeY", U16))
        op(112, Field("resizeZ", U16))
        op(113, Field("ambient", I8))
        op(114, Field("contrast", I8))
        op(115, Field("team", U8))
        keep(139, "unnoted link", U16)
        keep(140, "noted link", U16)
        op(148, Field("placeholderLink", U16))
        op(149, Field("placeholderTemplate", U16))
        keep(249, "params", PARAMS)
        aliases += mapOf("maleModel" to "maleModel0", "femaleModel" to "femaleModel0", "value" to "cost")
        ignored += mapOf(
            "description" to "rev-184 items have no examine text in the cache (Kronos keeps examines in its own data files)",
            "examine" to "rev-184 items have no examine text in the cache (Kronos keeps examines in its own data files)",
            "wearPos1" to "equipment slots are not in the rev-184 cache (Kronos keeps them in its own data files)",
            "wearPos2" to "equipment slots are not in the rev-184 cache (Kronos keeps them in its own data files)",
            "wearPos3" to "equipment slots are not in the rev-184 cache (Kronos keeps them in its own data files)",
        )
    }

    val NPC = DefinitionSchema("npc", 9, "npcs").apply {
        op(1, Field("models", U16_LIST))
        op(2, Field("name", STR))
        op(12, Field("size", U8))
        op(13, Field("standAnim", U16))
        op(14, Field("walkAnim", U16))
        op(15, Field("turnLeftAnim", U16))
        op(16, Field("turnRightAnim", U16))
        op(17, Field("walkAnim", U16), Field("walkBackAnim", U16), Field("walkLeftAnim", U16), Field("walkRightAnim", U16), replaces = listOf(14))
        for (i in 0 until 5) op(30 + i, Field("option${i + 1}", STR))   // "Hidden" hides one
        op(40, Field("recolor", PAIR_LIST))
        op(41, Field("retexture", PAIR_LIST))
        op(60, Field("chatheadModels", U16_LIST))
        flag(93, "minimapVisible", inverted = true)
        op(95, Field("combatLevel", U16))
        op(97, Field("widthScale", U16))
        op(98, Field("heightScale", U16))
        flag(99, "renderPriority")
        op(100, Field("ambient", I8))
        op(101, Field("contrast", I8))
        op(102, Field("headIcon", U16))
        op(103, Field("rotation", U16))
        op(106, Field("varbit", U16N, -1), Field("varp", U16N, -1), Field("transforms", TRANSFORMS), replaces = listOf(118))
        flag(107, "clickable", inverted = true)
        keep(109, "rotation flag")
        flag(111, "follower")
        op(118, Field("varbit", U16N, -1), Field("varp", U16N, -1), Field("transformDefault", U16N), Field("transforms", TRANSFORMS), replaces = listOf(106))
        keep(249, "params", PARAMS)
        ignored += mapOf(
            "description" to "rev-184 NPCs have no examine text in the cache (Kronos keeps examines in its own data files)",
            "examine" to "rev-184 NPCs have no examine text in the cache (Kronos keeps examines in its own data files)",
        )
    }

    val OBJECT = DefinitionSchema("object", 6, "objects").apply {
        keep(1, "models with types", MODEL_TYPES)   // edited through objectModels + objectTypes, see below
        op(2, Field("name", STR))
        op(5, Field("objectModels", U16_LIST), replaces = listOf(1))
        op(14, Field("sizeX", U8))
        op(15, Field("sizeY", U8))
        flag(17, "solid", inverted = true)
        flag(18, "impenetrable", inverted = true)
        op(19, Field("interactive", U8))
        flag(21, "contouredGround")
        flag(22, "nonFlatShading")
        flag(23, "modelClipped")
        op(24, Field("animationId", U16N))
        keep(27, "clip type 1")
        op(28, Field("decorDisplacement", U8))
        op(29, Field("ambient", I8))
        for (i in 0 until 5) op(30 + i, Field("option${i + 1}", STR))   // "Hidden" hides one
        op(39, Field("contrast", I8))
        op(40, Field("recolor", PAIR_LIST))
        op(41, Field("retexture", PAIR_LIST))
        keep(60, "map marker", U16, note = "Kronos' ObjectDef reads it but the rev-184 client does not, so the client misreads the rest of this definition")
        flag(62, "isRotated")
        flag(64, "castsShadow", inverted = true)
        op(65, Field("modelSizeX", U16))
        op(66, Field("modelSizeZ", U16))
        op(67, Field("modelSizeY", U16))
        op(68, Field("mapSceneID", U16))
        op(69, Field("clipMask", U8))
        op(70, Field("offsetX", I16))
        op(71, Field("offsetZ", I16))
        op(72, Field("offsetY", I16))
        flag(73, "obstructive")
        flag(74, "isHollow")
        op(75, Field("supportsItems", U8))
        op(77, Field("varbit", U16N, -1), Field("varp", U16N, -1), Field("transforms", TRANSFORMS), replaces = listOf(92))
        op(78, Field("ambientSoundId", U16), Field("ambientSoundDistance", U8))
        keep(79, "random ambient sounds", U16, U16, U8, U16_LIST)
        keep(81, "contour height", U8)
        keep(82, "map icon", U16, note = "the client reads it but Kronos' ObjectDef does not, so the server misreads the rest of this definition")
        op(92, Field("varbit", U16N, -1), Field("varp", U16N, -1), Field("transformDefault", U16N), Field("transforms", TRANSFORMS), replaces = listOf(77))
        keep(249, "params", PARAMS, note = "the client reads it but Kronos' ObjectDef does not, so the server misreads the rest of this definition")
        ignored += mapOf("description" to "objects have no examine text in the rev-184 cache")

        // Opcode 1 stores (model, type) pairs and opcode 5 bare models; the decoders keep whichever comes first, so
        // exactly one of them is written. objectTypes selects opcode 1; objectModels alone keeps the inherited types
        // when the model count is unchanged, otherwise it writes opcode 5.
        customKeys = setOf("objectModels", "objectTypes")
        customRemove = mapOf("objectModels" to listOf(1, 5), "objectTypes" to listOf(1))
        custom = custom@{ ops, input, errors ->
            val models = input["objectModels"]
            val types = input["objectTypes"]
            if (models == null && types == null) return@custom
            fun ints(v: Any?, key: String, range: IntRange): List<Int>? =
                (v as? List<*>)?.takeIf { l -> l.size <= 255 && l.all { it is Int && it in range } }?.map { it as Int }
                    ?: null.also { errors += "'$key' must be a list of up to 255 integers ${range.first}..${range.last}" }
            val inherited = ops[1]?.let { p -> @Suppress("UNCHECKED_CAST") (values(1, p)[0] as List<List<Int>>) }
            val modelList = if (models != null) ints(models, "objectModels", 0..65535) ?: return@custom else inherited?.map { it[0] }
                ?: ops[5]?.let { p -> @Suppress("UNCHECKED_CAST") (values(5, p)[0] as List<Int>) }
            val typeList = if (types != null) ints(types, "objectTypes", 0..22) ?: return@custom
                           else inherited?.map { it[1] }?.takeIf { it.size == modelList?.size }
            when {
                modelList == null -> errors += "objectTypes needs objectModels"
                typeList == null -> { ops.remove(1); ops[5] = DefinitionSchema.encodeModels(modelList) }
                typeList.size != modelList.size -> errors += "objectModels and objectTypes must have the same length (${modelList.size} vs ${typeList.size})"
                else -> { ops.remove(5); ops[1] = DefinitionSchema.encodeModelTypes(modelList.zip(typeList) { m, t -> listOf(m, t) }) }
            }
        }
        customDump = { opcode, payload ->
            @Suppress("UNCHECKED_CAST")
            if (opcode == 1) (values(1, payload)[0] as List<List<Int>>).let { pairs -> listOf("objectModels" to pairs.map { it[0] }, "objectTypes" to pairs.map { it[1] }) }
            else null
        }
    }

    val ALL = listOf(ITEM, NPC, OBJECT)
}
