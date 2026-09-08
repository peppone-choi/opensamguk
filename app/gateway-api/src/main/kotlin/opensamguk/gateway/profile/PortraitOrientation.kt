package opensamguk.gateway.profile

import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Bounded EXIF orientation extraction; offsets remain inside the selected metadata chunk. */
internal object PortraitOrientation {
    fun read(bytes: ByteArray, extension: String): Int {
        val exif = mutableListOf<ByteArray>()
        fun collect(start: Int, length: Int) {
            if (start < 0 || length < 0 || start.toLong() + length > bytes.size) throw InvalidProfileIconException()
            var data = bytes.copyOfRange(start, start + length)
            if (data.size >= 6 && data.copyOfRange(0, 6).contentEquals(byteArrayOf(69, 120, 105, 102, 0, 0))) data = data.copyOfRange(6, data.size)
            exif.add(data)
            if (exif.size > 1) throw InvalidProfileIconException("이미지 방향 정보가 중복되었습니다.")
        }
        when (extension) {
            "jpg" -> {
                var offset = 2
                while (offset + 4 <= bytes.size) {
                    if (bytes[offset].toInt() and 255 != 255) throw InvalidProfileIconException()
                    while (offset < bytes.size && bytes[offset].toInt() and 255 == 255) offset++
                    if (offset >= bytes.size) break
                    val marker = bytes[offset++].toInt() and 255
                    if (marker == 0xda || marker == 0xd9) break
                    if (marker == 0x01 || marker in 0xd0..0xd7) continue
                    if (offset + 2 > bytes.size) throw InvalidProfileIconException()
                    val length = ((bytes[offset].toInt() and 255) shl 8) or (bytes[offset + 1].toInt() and 255)
                    if (length < 2 || offset.toLong() + length > bytes.size) throw InvalidProfileIconException()
                    if (marker == 0xe1 && length >= 8 && bytes.copyOfRange(offset + 2, offset + 8).contentEquals(byteArrayOf(69, 120, 105, 102, 0, 0))) collect(offset + 2, length - 2)
                    offset += length
                }
            }
            "png", "webp" -> {
                var offset = if (extension == "png") 8 else 12
                val order = if (extension == "png") ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
                while (offset + 8 <= bytes.size) {
                    val typeOffset = if (extension == "png") offset + 4 else offset
                    val sizeOffset = if (extension == "png") offset else offset + 4
                    val length = ByteBuffer.wrap(bytes, sizeOffset, 4).order(order).int
                    if (length < 0 || offset.toLong() + 8 + length > bytes.size) throw InvalidProfileIconException()
                    val type = String(bytes, typeOffset, 4, Charsets.US_ASCII)
                    if (type in setOf("acTL", "ANIM", "ANMF")) throw InvalidProfileIconException("움직이는 이미지는 수동 초상으로 사용할 수 없습니다.")
                    if (type == "eXIf" || type == "EXIF") collect(offset + 8, length)
                    offset += 8 + length + if (extension == "png") 4 else length % 2
                }
            }
        }
        return exif.singleOrNull()?.let(::tiffOrientation) ?: 1
    }

    private fun tiffOrientation(data: ByteArray): Int {
        fun invalid(): Nothing = throw InvalidProfileIconException("이미지 방향 정보를 읽을 수 없습니다.")
        if (data.size < 8) invalid()
        val order = when (String(data, 0, 2, Charsets.US_ASCII)) { "II" -> ByteOrder.LITTLE_ENDIAN; "MM" -> ByteOrder.BIG_ENDIAN; else -> invalid() }
        val buffer = ByteBuffer.wrap(data).order(order)
        fun short(at: Int): Int { if (at < 0 || at.toLong() + 2 > data.size) invalid(); return buffer.getShort(at).toInt() and 65535 }
        fun uint(at: Int): Long { if (at < 0 || at.toLong() + 4 > data.size) invalid(); return buffer.getInt(at).toLong() and 0xffffffffL }
        if (short(2) != 42) invalid()
        val ifd = uint(4)
        if (ifd < 8 || ifd + 2 > data.size) invalid()
        val count = short(ifd.toInt())
        if (ifd + 2 + count.toLong() * 12 + 4 > data.size) invalid()
        var orientation: Int? = null
        repeat(count) { index ->
            val at = ifd.toInt() + 2 + index * 12
            if (short(at) == 0x0112) {
                if (orientation != null || short(at + 2) != 3 || uint(at + 4) != 1L) invalid()
                orientation = short(at + 8).also { if (it !in 1..8) invalid() }
            }
        }
        return orientation ?: 1
    }

    fun orient(source: BufferedImage, orientation: Int): BufferedImage {
        if (orientation == 1) return source
        val w = source.width
        val h = source.height
        val output = BufferedImage(if (orientation >= 5) h else w, if (orientation >= 5) w else h, BufferedImage.TYPE_INT_ARGB)
        val matrix = when (orientation) {
            2 -> doubleArrayOf(-1.0, 0.0, 0.0, 1.0, w.toDouble(), 0.0)
            3 -> doubleArrayOf(-1.0, 0.0, 0.0, -1.0, w.toDouble(), h.toDouble())
            4 -> doubleArrayOf(1.0, 0.0, 0.0, -1.0, 0.0, h.toDouble())
            5 -> doubleArrayOf(0.0, 1.0, 1.0, 0.0, 0.0, 0.0)
            6 -> doubleArrayOf(0.0, 1.0, -1.0, 0.0, h.toDouble(), 0.0)
            7 -> doubleArrayOf(0.0, -1.0, -1.0, 0.0, h.toDouble(), w.toDouble())
            8 -> doubleArrayOf(0.0, -1.0, 1.0, 0.0, 0.0, w.toDouble())
            else -> throw InvalidProfileIconException()
        }
        output.createGraphics().apply {
            drawImage(source, java.awt.geom.AffineTransform(matrix), null)
            dispose()
        }
        return output
    }
}
