package io.ruin.cachetools.format

/**
 * Converts models exported for newer revisions into a format the rev-184 client decodes:
 * [ModelFormat.TYPE2] -> [ModelFormat.OLD] and [ModelFormat.TYPE3] -> [ModelFormat.TYPE1].
 *
 * Each newer format is its older counterpart plus skeletal ("animaya") animation data: the data is appended to the
 * vertex-skin section, and the footer gains an animaya flag and the length of that section. The rev-184 client has no
 * skeletal animation, so that data is dropped; every other section is copied byte for byte. The result is checked with
 * [ModelDecoder.check], which reads it the way the client does.
 */
object ModelConverter {

    class Result(val data: ByteArray, val from: ModelFormat, val to: ModelFormat, val notes: List<String>)

    /** Returns [data] unchanged for OLD and TYPE1. Throws [IllegalArgumentException] when the model cannot be converted. */
    fun toSupported(data: ByteArray): Result {
        val format = ModelFormat.detect(data)
        val notes = ArrayList<String>()
        val converted = when (format) {
            ModelFormat.OLD, ModelFormat.TYPE1 -> return Result(data, format, format, notes)
            ModelFormat.TYPE2 -> type2ToOld(data, notes)
            ModelFormat.TYPE3 -> type3ToType1(data, notes)
        }
        val to = ModelFormat.detect(converted)
        ModelDecoder.check(converted)?.let { throw IllegalArgumentException("converted to $to but the result does not decode: $it") }
        return Result(converted, format, to, notes)
    }

    private fun type2ToOld(d: ByteArray, notes: MutableList<String>): ByteArray {
        val f = Footer(d, d.size - 23)
        val vertices = f.u16(); val faces = f.u16(); val texFaces = f.u8()
        val textured = f.u8(); val priority = f.u8(); val alpha = f.u8(); val faceSkins = f.u8(); val vertexSkins = f.u8()
        val animaya = f.u8()
        val xLen = f.u16(); val yLen = f.u16(); val zLen = f.u16(); val indexLen = f.u16(); val skinLen = f.u16()

        val skinStart = vertices + faces + (if (priority == 255) faces else 0) + (if (faceSkins == 1) faces else 0) +
            (if (textured == 1) faces else 0)
        val body = rebuildBody(
            d, bodyEnd = d.size - 23, vertices, skinStart, skinLen, keptSkins = if (vertexSkins == 1) vertices else 0,
            animaya = animaya == 1,
            betweenSkinsAndX = (if (alpha == 1) faces else 0) + indexLen + faces * 2 + texFaces * 6,
            fromXToEnd = xLen + yLen + zLen,
            notes,
        )
        val footer = Writer().apply {
            u16(vertices); u16(faces); u8(texFaces)
            u8(textured); u8(priority); u8(alpha); u8(faceSkins); u8(vertexSkins)
            u16(xLen); u16(yLen); u16(zLen); u16(indexLen)
        }
        return body + footer.bytes()
    }

    private fun type3ToType1(d: ByteArray, notes: MutableList<String>): ByteArray {
        val f = Footer(d, d.size - 26)
        val vertices = f.u16(); val faces = f.u16(); val texFaces = f.u8()
        val faceTypes = f.u8(); val priority = f.u8(); val alpha = f.u8(); val faceSkins = f.u8(); val textures = f.u8()
        val vertexSkins = f.u8(); val animaya = f.u8()
        val xLen = f.u16(); val yLen = f.u16(); val zLen = f.u16(); val indexLen = f.u16(); val texCoordLen = f.u16()
        val skinLen = f.u16()

        require(texFaces <= d.size - 26) { "footer declares $texFaces texture triangles; file too small" }
        val skinStart = texFaces + vertices + (if (faceTypes == 1) faces else 0) + faces +
            (if (priority == 255) faces else 0) + (if (faceSkins == 1) faces else 0)
        val body = rebuildBody(
            d, bodyEnd = d.size - 26, vertices, skinStart, skinLen, keptSkins = if (vertexSkins == 1) vertices else 0,
            animaya = animaya == 1,
            betweenSkinsAndX = (if (alpha == 1) faces else 0) + indexLen + (if (textures == 1) faces * 2 else 0) +
                texCoordLen + faces * 2,
            fromXToEnd = xLen + yLen + zLen + ModelDecoder.textureMappingLength(d, texFaces),
            notes,
        )
        val footer = Writer().apply {
            u16(vertices); u16(faces); u8(texFaces)
            u8(faceTypes); u8(priority); u8(alpha); u8(faceSkins); u8(textures); u8(vertexSkins)
            u16(xLen); u16(yLen); u16(zLen); u16(indexLen); u16(texCoordLen)
            u8(0xFF); u8(0xFF)
        }
        return body + footer.bytes()
    }

    /**
     * Copies the body with the skin section cut down to the plain vertex skins ([keptSkins] bytes). Clients locate
     * every section from the start of the file, so bytes after the last section (Jagex's encoder leaves one) are never
     * read; they are kept as they are.
     */
    private fun rebuildBody(
        d: ByteArray, bodyEnd: Int, vertices: Int, skinStart: Int, skinLen: Int, keptSkins: Int, animaya: Boolean,
        betweenSkinsAndX: Int, fromXToEnd: Int, notes: MutableList<String>,
    ): ByteArray {
        val skinEnd = skinStart + skinLen
        require(skinLen >= keptSkins) { "skin section is $skinLen bytes, shorter than the $keptSkins vertex skins" }
        require(skinEnd <= bodyEnd) { "skin section runs past the end of the data" }
        if (animaya) {
            var pos = skinStart + keptSkins
            for (v in 0 until vertices) {
                require(pos < skinEnd) { "skeletal animation data is truncated at vertex $v" }
                pos += 1 + 2 * (d[pos].toInt() and 0xFF)
            }
            require(pos <= skinEnd) { "skeletal animation data runs past its section" }
            notes += "dropped ${skinLen - keptSkins} bytes of skeletal animation data (the rev-184 client cannot play skeletal animations)"
        } else if (skinLen > keptSkins) {
            notes += "dropped ${skinLen - keptSkins} unused bytes from the vertex skin section"
        }
        val sectionsEnd = skinEnd + betweenSkinsAndX + fromXToEnd
        require(sectionsEnd <= bodyEnd) { "${sectionsEnd - bodyEnd} bytes shorter than the footer declares" }
        return d.copyOfRange(0, skinStart + keptSkins) + d.copyOfRange(skinEnd, bodyEnd)
    }

    private class Footer(private val d: ByteArray, private var pos: Int) {
        init { require(pos >= 0) { "too small to be a model" } }
        fun u8() = d[pos++].toInt() and 0xFF
        fun u16() = (u8() shl 8) or u8()
    }

    private class Writer {
        private val out = java.io.ByteArrayOutputStream()
        fun u8(v: Int) = out.write(v)
        fun u16(v: Int) { out.write(v ushr 8); out.write(v) }
        fun bytes(): ByteArray = out.toByteArray()
    }
}

/**
 * Reads [ModelFormat.OLD] and [ModelFormat.TYPE1] models section by section, the way the rev-184 client (`ModelData`)
 * does, without building the model: every section must lie inside the file (bytes after the last one are never read),
 * the vertex and face streams must use up exactly the bytes the footer declares, and every face must point at an
 * existing vertex. Texture triangle vertices are not checked: Jagex ships models whose texture triangles point past the
 * last vertex, and the client accepts them.
 */
object ModelDecoder {

    /** Returns a problem description, or null when the model decodes. */
    fun check(data: ByteArray): String? = try {
        when (ModelFormat.detect(data)) {
            ModelFormat.OLD -> checkOld(data)
            ModelFormat.TYPE1 -> checkType1(data)
            else -> throw IllegalArgumentException("only OLD and TYPE1 models can be checked")
        }
        null
    } catch (e: IllegalArgumentException) {
        e.message
    } catch (e: IndexOutOfBoundsException) {
        "reads past the end of the file"
    }

    /** Length of the texture mapping data after the vertex Z deltas in TYPE1/TYPE3 models, from the render types at the start. */
    fun textureMappingLength(d: ByteArray, texFaces: Int): Int {
        var simple = 0; var complex = 0; var cylindrical = 0
        for (i in 0 until texFaces) {
            val type = d[i].toInt()
            if (type == 0) simple++
            if (type in 1..3) complex++
            if (type == 2) cylindrical++
        }
        return simple * 6 + complex * 6 + complex * 6 + complex * 2 + complex + complex * 2 + cylindrical * 2
    }

    private fun checkOld(d: ByteArray) {
        val f = Stream(d, d.size - 18, d.size)
        val vertices = f.u16(); val faces = f.u16(); val texFaces = f.u8()
        val textured = f.u8(); val priority = f.u8(); val alpha = f.u8(); val faceSkins = f.u8(); val vertexSkins = f.u8()
        val xLen = f.u16(); val yLen = f.u16(); val zLen = f.u16(); val indexLen = f.u16()

        val flags = 0
        val indexTypes = flags + vertices
        var pos = indexTypes + faces
        if (priority == 255) pos += faces
        if (faceSkins == 1) pos += faces
        val textureFlags = pos
        if (textured == 1) pos += faces
        if (vertexSkins == 1) pos += vertices
        if (alpha == 1) pos += faces
        val indices = pos; pos += indexLen
        pos += faces * 2
        pos += texFaces * 6
        val x = pos; pos += xLen
        val y = pos; pos += yLen
        val z = pos; pos += zLen
        require(pos <= d.size - 18) { "sections need $pos bytes, but the data is ${d.size - 18}" }

        checkVertices(d, vertices, flags, x, y, z, xLen, yLen, zLen)
        checkFaces(d, vertices, faces, indexTypes, indices, indexLen)
        if (textured == 1) for (face in 0 until faces) {
            val flag = d[textureFlags + face].toInt() and 0xFF
            if (flag and 2 != 0) require(flag shr 2 < texFaces) { "face $face uses texture triangle ${flag shr 2} of $texFaces" }
        }
    }

    private fun checkType1(d: ByteArray) {
        val f = Stream(d, d.size - 23, d.size)
        val vertices = f.u16(); val faces = f.u16(); val texFaces = f.u8()
        val faceTypes = f.u8(); val priority = f.u8(); val alpha = f.u8(); val faceSkins = f.u8(); val textures = f.u8()
        val vertexSkins = f.u8()
        val xLen = f.u16(); val yLen = f.u16(); val zLen = f.u16(); val indexLen = f.u16(); val texCoordLen = f.u16()

        val flags = texFaces
        var pos = flags + vertices
        if (faceTypes == 1) pos += faces
        val indexTypes = pos; pos += faces
        if (priority == 255) pos += faces
        if (faceSkins == 1) pos += faces
        if (vertexSkins == 1) pos += vertices
        if (alpha == 1) pos += faces
        val indices = pos; pos += indexLen
        val faceTextures = pos
        if (textures == 1) pos += faces * 2
        val texCoords = pos; pos += texCoordLen
        pos += faces * 2
        val x = pos; pos += xLen
        val y = pos; pos += yLen
        val z = pos; pos += zLen
        pos += textureMappingLength(d, texFaces)
        require(pos <= d.size - 23) { "sections need $pos bytes, but the data is ${d.size - 23}" }

        checkVertices(d, vertices, flags, x, y, z, xLen, yLen, zLen)
        checkFaces(d, vertices, faces, indexTypes, indices, indexLen)
        if (textures == 1) {
            val tex = Stream(d, faceTextures, faceTextures + faces * 2)
            val coords = Stream(d, texCoords, texCoords + texCoordLen)
            for (face in 0 until faces) {
                val texture = tex.u16() - 1
                if (texFaces > 0 && texture != -1) {
                    val coord = coords.u8() - 1
                    require(coord < texFaces) { "face $face uses texture triangle $coord of $texFaces" }
                }
            }
        }
    }

    private fun checkVertices(d: ByteArray, vertices: Int, flags: Int, x: Int, y: Int, z: Int, xLen: Int, yLen: Int, zLen: Int) {
        val xs = Stream(d, x, x + xLen); val ys = Stream(d, y, y + yLen); val zs = Stream(d, z, z + zLen)
        for (v in 0 until vertices) {
            val flag = d[flags + v].toInt()
            if (flag and 1 != 0) xs.smart()
            if (flag and 2 != 0) ys.smart()
            if (flag and 4 != 0) zs.smart()
        }
        require(xs.done() && ys.done() && zs.done()) { "vertex data does not match the X/Y/Z lengths in the footer" }
    }

    private fun checkFaces(d: ByteArray, vertices: Int, faces: Int, indexTypes: Int, indices: Int, indexLen: Int) {
        val s = Stream(d, indices, indices + indexLen)
        var a = 0; var b = 0; var c = 0; var last = 0
        for (face in 0 until faces) {
            when (val type = d[indexTypes + face].toInt()) {
                1 -> { a = s.smart() + last; b = s.smart() + a; c = s.smart() + b; last = c }
                2 -> { b = c; c = s.smart() + last; last = c }
                3 -> { a = c; c = s.smart() + last; last = c }
                4 -> { val t = a; a = b; b = t; c = s.smart() + last; last = c }
                else -> throw IllegalArgumentException("face $face has index type $type (expected 1-4)")
            }
            require(a in 0 until vertices && b in 0 until vertices && c in 0 until vertices) {
                "face $face uses vertices $a, $b, $c; the model has $vertices"
            }
        }
        require(s.done()) { "face index data does not match its length in the footer" }
    }

    private class Stream(private val d: ByteArray, private var pos: Int, private val end: Int) {
        init { require(pos in 0..end && end <= d.size) { "section lies outside the file" } }
        fun u8(): Int {
            require(pos < end) { "a section is shorter than its contents" }
            return d[pos++].toInt() and 0xFF
        }
        fun u16() = (u8() shl 8) or u8()
        fun smart(): Int = if ((d[pos].toInt() and 0xFF) < 128) u8() - 64 else u16() - 0xC000
        fun done() = pos == end
    }
}
