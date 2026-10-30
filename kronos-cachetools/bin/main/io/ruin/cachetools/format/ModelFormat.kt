package io.ruin.cachetools.format

/**
 * OSRS model encodings, told apart by the last two bytes. The rev-184 client (`ModelData`) only decodes [OLD] and
 * [TYPE1]; [TYPE2] and [TYPE3] come from newer caches (and from tools that export for them) and would fail to load, so
 * the packer converts them with [ModelConverter].
 */
enum class ModelFormat(val supportedByKronos: Boolean, private val headerSize: Int) {
    OLD(true, 18),
    TYPE1(true, 23),
    TYPE2(false, 23),
    TYPE3(false, 26);

    companion object {
        fun detect(data: ByteArray): ModelFormat {
            if (data.size >= 2 && data[data.size - 2] == (-1).toByte()) {
                when (data[data.size - 1]) {
                    (-1).toByte() -> return TYPE1
                    (-2).toByte() -> return TYPE2
                    (-3).toByte() -> return TYPE3
                }
            }
            return OLD
        }

        /** A cheap structural check: returns a problem description, or null when the header looks sane. */
        fun check(data: ByteArray): String? {
            val format = detect(data)
            if (data.size < format.headerSize) return "too small to be a model (${data.size} bytes)"
            val header = data.size - format.headerSize
            val vertices = ((data[header].toInt() and 0xFF) shl 8) or (data[header + 1].toInt() and 0xFF)
            val faces = ((data[header + 2].toInt() and 0xFF) shl 8) or (data[header + 3].toInt() and 0xFF)
            if (vertices == 0 || faces == 0) return "header declares $vertices vertices / $faces faces; not a model (or gzip data saved as .dat?)"
            return null
        }
    }
}
