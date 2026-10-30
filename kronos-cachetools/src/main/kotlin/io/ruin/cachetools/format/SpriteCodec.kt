package io.ruin.cachetools.format

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class SpriteFrame(val offsetX: Int, val offsetY: Int, val image: BufferedImage)

/** One group of index 8: a canvas size shared by every frame, and the frames themselves. */
class SpriteGroup(val width: Int, val height: Int, val frames: List<SpriteFrame>)

class EncodedSprite(val data: ByteArray, val colours: Int, val quantizedFrom: Int?, val flattenedAlphaPixels: Int)

/**
 * The index-8 sprite format as the rev-184 client reads it (`SpriteBuffer_decode`): per frame one flags byte
 * (0 = row-major, 1 = column-major) and one palette index per pixel; then the shared palette; then the trailer.
 *
 * That client has **no alpha channel**: flag bit 2 (per-pixel alpha, added in later revisions) is not understood
 * and would desynchronise every following frame. So the encoder writes binary transparency only, with alpha
 * thresholded at 50%, and quantizes to the 255-colour palette limit when a group has more colours.
 */
object SpriteCodec {

    private const val MAX_COLOURS = 255

    fun encode(group: SpriteGroup): EncodedSprite {
        // Colour of every pixel, 0 for transparent. Opaque black is stored as 1, since palette slot 0 is transparency.
        var flattened = 0
        val pixels = group.frames.map { frame ->
            val img = frame.image
            IntArray(img.width * img.height) { i ->
                val argb = img.getRGB(i % img.width, i / img.width)
                val alpha = argb ushr 24
                if (alpha in 1..254) flattened++
                if (alpha < 128) 0 else (argb and 0xFFFFFF).let { if (it == 0) 1 else it }
            }
        }

        val histogram = HashMap<Int, Int>()
        pixels.forEach { frame -> frame.forEach { if (it != 0) histogram.merge(it, 1, Int::plus) } }
        val remap = if (histogram.size > MAX_COLOURS) MedianCut.reduce(histogram, MAX_COLOURS) else null

        val palette = ArrayList<Int>()
        val paletteIndex = HashMap<Int, Int>()
        fun indexOf(rgb: Int): Int {
            if (rgb == 0) return 0
            val colour = remap?.get(rgb) ?: rgb
            return paletteIndex.getOrPut(colour) { palette.add(colour); palette.size }
        }

        val out = ByteArrayOutputStream()
        val dos = DataOutputStream(out)
        for (frame in pixels) {
            dos.writeByte(0) // row-major, no alpha
            for (rgb in frame) dos.writeByte(indexOf(rgb))
        }
        check(palette.size <= MAX_COLOURS) { "palette overflow: ${palette.size}" }
        for (rgb in palette) {
            dos.writeByte(rgb shr 16); dos.writeByte(rgb shr 8); dos.writeByte(rgb)
        }
        dos.writeShort(group.width)
        dos.writeShort(group.height)
        dos.writeByte(palette.size)
        group.frames.forEach { dos.writeShort(it.offsetX) }
        group.frames.forEach { dos.writeShort(it.offsetY) }
        group.frames.forEach { dos.writeShort(it.image.width) }
        group.frames.forEach { dos.writeShort(it.image.height) }
        dos.writeShort(group.frames.size)
        return EncodedSprite(out.toByteArray(), palette.size, if (remap != null) histogram.size else null, flattened)
    }

    fun decode(data: ByteArray): SpriteGroup {
        fun u8(pos: Int) = data[pos].toInt() and 0xFF
        fun u16(pos: Int) = (u8(pos) shl 8) or u8(pos + 1)

        val count = u16(data.size - 2)
        var pos = data.size - 7 - count * 8
        val width = u16(pos)
        val height = u16(pos + 2)
        val paletteSize = u8(pos + 4) + 1
        pos += 5
        val offsetsX = IntArray(count) { u16(pos + it * 2) }
        val offsetsY = IntArray(count) { u16(pos + count * 2 + it * 2) }
        val widths = IntArray(count) { u16(pos + count * 4 + it * 2) }
        val heights = IntArray(count) { u16(pos + count * 6 + it * 2) }

        val paletteStart = data.size - 7 - count * 8 - (paletteSize - 1) * 3
        val palette = IntArray(paletteSize) { i ->
            if (i == 0) 0 else ((u8(paletteStart + (i - 1) * 3) shl 16) or (u8(paletteStart + (i - 1) * 3 + 1) shl 8) or u8(paletteStart + (i - 1) * 3 + 2)).let { if (it == 0) 1 else it }
        }

        pos = 0
        val frames = ArrayList<SpriteFrame>(count)
        for (f in 0 until count) {
            val w = widths[f]
            val h = heights[f]
            val flags = u8(pos++)
            val columnMajor = flags and 1 != 0
            val indices = IntArray(w * h)
            for (i in 0 until w * h) {
                val target = if (columnMajor) (i % h) * w + i / h else i
                indices[target] = u8(pos++)
            }
            val alpha = IntArray(w * h) { 255 }
            if (flags and 2 != 0) { // not produced for rev 184, but read it so newer caches can be dumped
                for (i in 0 until w * h) {
                    val target = if (columnMajor) (i % h) * w + i / h else i
                    alpha[target] = u8(pos++)
                }
            }
            val image = BufferedImage(maxOf(w, 1), maxOf(h, 1), BufferedImage.TYPE_INT_ARGB)
            for (i in 0 until w * h) {
                val index = indices[i]
                val a = if (flags and 2 != 0) alpha[i] else if (index == 0) 0 else 255
                if (a != 0) image.setRGB(i % w, i / w, (a shl 24) or palette[index])
            }
            frames += SpriteFrame(offsetsX[f], offsetsY[f], image)
        }
        return SpriteGroup(width, height, frames)
    }
}

/** Median-cut colour reduction: maps every colour of [histogram] to one of at most [max] representative colours. */
internal object MedianCut {

    private class Box(val colours: List<Int>, val histogram: Map<Int, Int>) {
        private fun channel(rgb: Int, c: Int) = (rgb shr (16 - 8 * c)) and 0xFF
        fun range(c: Int) = colours.maxOf { channel(it, c) } - colours.minOf { channel(it, c) }
        val widest = (0..2).maxByOrNull { range(it) }!!
        val score = range(widest).toLong() * colours.sumOf { histogram.getValue(it) }

        fun split(): Pair<Box, Box> {
            val sorted = colours.sortedBy { channel(it, widest) }
            val half = sorted.sumOf { histogram.getValue(it).toLong() } / 2
            var acc = 0L
            var cut = 1
            for ((i, c) in sorted.withIndex()) {
                acc += histogram.getValue(c)
                if (acc >= half) { cut = (i + 1).coerceIn(1, sorted.size - 1); break }
            }
            return Box(sorted.subList(0, cut), histogram) to Box(sorted.subList(cut, sorted.size), histogram)
        }

        fun average(): Int {
            var r = 0L; var g = 0L; var b = 0L; var n = 0L
            for (c in colours) {
                val w = histogram.getValue(c).toLong()
                r += channel(c, 0) * w; g += channel(c, 1) * w; b += channel(c, 2) * w; n += w
            }
            val rgb = ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
            return if (rgb == 0) 1 else rgb
        }
    }

    fun reduce(histogram: Map<Int, Int>, max: Int): Map<Int, Int> {
        val boxes = mutableListOf(Box(histogram.keys.sorted(), histogram))
        while (boxes.size < max) {
            val target = boxes.filter { it.colours.size > 1 }.maxByOrNull { it.score } ?: break
            boxes.remove(target)
            target.split().let { boxes += it.first; boxes += it.second }
        }
        val result = HashMap<Int, Int>()
        for (box in boxes) {
            val avg = box.average()
            box.colours.forEach { result[it] = avg }
        }
        return result
    }
}
