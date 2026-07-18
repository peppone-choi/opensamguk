package opensamguk.gateway.profile

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.imageio.ImageReader

data class DecodedProfileIcon(
    val bytes: ByteArray,
    val extension: String,
    val mediaType: String,
    val width: Int,
    val height: Int,
)

class InvalidProfileIconException(message: String = "올바른 프로필 아이콘 이미지가 아닙니다.") :
    IllegalArgumentException(message)

class ProfileIconDecoder(
    private val maxBytes: Int,
    private val minDimension: Int = 64,
    private val maxDimension: Int = 128,
) {
    fun decode(source: ByteArray): DecodedProfileIcon {
        if (source.isEmpty() || source.size > maxBytes) {
            throw InvalidProfileIconException()
        }

        return try {
            decodeImage(source)
        } catch (e: InvalidProfileIconException) {
            throw e
        } catch (e: Exception) {
            throw InvalidProfileIconException()
        }
    }

    private fun decodeImage(source: ByteArray): DecodedProfileIcon {
        val stream = ImageIO.createImageInputStream(ByteArrayInputStream(source))
            ?: throw InvalidProfileIconException()
        stream.use { input ->
            val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull()
                ?: throw InvalidProfileIconException()
            return reader.useReader {
                reader.input = input
                val format = allowedFormat(reader.formatName)
                validateContainerBounds(source, format.extension)
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                validateDimensions(width, height)
                if (reader.getNumImages(true) != 1) {
                    throw InvalidProfileIconException()
                }
                val raster = reader.read(0) ?: throw InvalidProfileIconException()
                if (raster.width != width || raster.height != height) {
                    throw InvalidProfileIconException()
                }
                DecodedProfileIcon(
                    bytes = source.copyOf(),
                    extension = format.extension,
                    mediaType = format.mediaType,
                    width = width,
                    height = height,
                )
            }
        }
    }

    private fun validateDimensions(width: Int, height: Int) {
        if (width != height || width !in minDimension..maxDimension || height !in minDimension..maxDimension) {
            throw InvalidProfileIconException("프로필 아이콘은 64~128px 정사각형이어야 합니다.")
        }
    }

    private fun allowedFormat(formatName: String): AllowedFormat = when (formatName.lowercase()) {
        "avif" -> AllowedFormat("avif", "image/avif")
        "webp" -> AllowedFormat("webp", "image/webp")
        "jpeg", "jpg" -> AllowedFormat("jpg", "image/jpeg")
        "png" -> AllowedFormat("png", "image/png")
        "gif" -> AllowedFormat("gif", "image/gif")
        else -> throw InvalidProfileIconException()
    }

    private fun validateContainerBounds(source: ByteArray, extension: String) {
        val valid = when (extension) {
            "png" -> hasExactPngBounds(source)
            "webp" -> hasExactWebpBounds(source)
            "avif" -> hasExactIsoBmffBounds(source)
            "jpg" -> hasExactJpegBounds(source)
            "gif" -> hasExactGifBounds(source)
            else -> false
        }
        if (!valid) {
            throw InvalidProfileIconException()
        }
    }

    private fun hasExactPngBounds(source: ByteArray): Boolean {
        if (source.size < 20 || !source.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)) {
            return false
        }
        var offset = 8
        while (offset + 12 <= source.size) {
            val dataLength = unsignedIntBigEndian(source, offset)
            if (dataLength > Int.MAX_VALUE) {
                return false
            }
            val end = offset.toLong() + dataLength + 12
            if (end > source.size) {
                return false
            }
            val type = source.copyOfRange(offset + 4, offset + 8).toString(Charsets.US_ASCII)
            offset = end.toInt()
            if (type == "IEND") {
                return dataLength == 0L && offset == source.size
            }
        }
        return false
    }

    private fun hasExactWebpBounds(source: ByteArray): Boolean {
        if (source.size < 12 || source.copyOfRange(0, 4).toString(Charsets.US_ASCII) != "RIFF") {
            return false
        }
        if (source.copyOfRange(8, 12).toString(Charsets.US_ASCII) != "WEBP") {
            return false
        }
        return unsignedIntLittleEndian(source, 4) + 8 == source.size.toLong()
    }

    private fun hasExactIsoBmffBounds(source: ByteArray): Boolean {
        var offset = 0L
        while (offset < source.size) {
            if (offset + 8 > source.size) {
                return false
            }
            val start = offset.toInt()
            val shortSize = unsignedIntBigEndian(source, start)
            var headerSize = 8L
            val boxSize = when (shortSize) {
                0L -> source.size.toLong() - offset
                1L -> {
                    if (offset + 16 > source.size) {
                        return false
                    }
                    headerSize = 16L
                    unsignedLongBigEndian(source, start + 8) ?: return false
                }
                else -> shortSize
            }
            if (boxSize < headerSize || offset + boxSize > source.size) {
                return false
            }
            offset += boxSize
        }
        return offset == source.size.toLong()
    }

    private fun hasExactJpegBounds(source: ByteArray): Boolean {
        if (source.size < 4 || source[0] != 0xff.toByte() || source[1] != 0xd8.toByte()) {
            return false
        }
        for (index in 2 until source.size - 1) {
            if (source[index] == 0xff.toByte() && source[index + 1] == 0xd9.toByte()) {
                return index + 2 == source.size
            }
        }
        return false
    }

    private fun hasExactGifBounds(source: ByteArray): Boolean {
        if (source.size < 14) {
            return false
        }
        val header = source.copyOfRange(0, 6).toString(Charsets.US_ASCII)
        if (header !in setOf("GIF87a", "GIF89a")) {
            return false
        }

        var offset = 6
        val logicalScreenPacked = source[offset + 4].toInt() and 0xff
        offset += 7
        if (logicalScreenPacked and 0x80 != 0) {
            offset = skipGifColorTable(source, offset, logicalScreenPacked) ?: return false
        }

        while (offset < source.size) {
            when (source[offset].toInt() and 0xff) {
                0x3b -> return offset + 1 == source.size
                0x21 -> {
                    offset += 1
                    if (offset >= source.size) {
                        return false
                    }
                    offset += 1
                    offset = skipGifSubBlocks(source, offset) ?: return false
                }
                0x2c -> {
                    offset += 1
                    if (source.size - offset < 9) {
                        return false
                    }
                    val imagePacked = source[offset + 8].toInt() and 0xff
                    offset += 9
                    if (imagePacked and 0x80 != 0) {
                        offset = skipGifColorTable(source, offset, imagePacked) ?: return false
                    }
                    if (offset >= source.size) {
                        return false
                    }
                    offset += 1
                    offset = skipGifSubBlocks(source, offset) ?: return false
                }
                else -> return false
            }
        }
        return false
    }

    private fun skipGifColorTable(source: ByteArray, offset: Int, packed: Int): Int? {
        val tableSize = 3 * (1 shl ((packed and 0x07) + 1))
        return if (tableSize <= source.size - offset) offset + tableSize else null
    }

    private fun skipGifSubBlocks(source: ByteArray, startOffset: Int): Int? {
        var offset = startOffset
        while (offset < source.size) {
            val blockSize = source[offset].toInt() and 0xff
            offset += 1
            if (blockSize == 0) {
                return offset
            }
            if (blockSize > source.size - offset) {
                return null
            }
            offset += blockSize
        }
        return null
    }

    private fun unsignedIntBigEndian(source: ByteArray, offset: Int): Long =
        ((source[offset].toLong() and 0xff) shl 24) or
            ((source[offset + 1].toLong() and 0xff) shl 16) or
            ((source[offset + 2].toLong() and 0xff) shl 8) or
            (source[offset + 3].toLong() and 0xff)

    private fun unsignedIntLittleEndian(source: ByteArray, offset: Int): Long =
        (source[offset].toLong() and 0xff) or
            ((source[offset + 1].toLong() and 0xff) shl 8) or
            ((source[offset + 2].toLong() and 0xff) shl 16) or
            ((source[offset + 3].toLong() and 0xff) shl 24)

    private fun unsignedLongBigEndian(source: ByteArray, offset: Int): Long? {
        if (source[offset].toInt() and 0x80 != 0) {
            return null
        }
        var value = 0L
        repeat(8) { index -> value = (value shl 8) or (source[offset + index].toLong() and 0xff) }
        return value
    }

    private inline fun <T> ImageReader.useReader(block: () -> T): T = try {
        block()
    } finally {
        dispose()
    }

    private data class AllowedFormat(val extension: String, val mediaType: String)

    companion object {
        private val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4e,
            0x47,
            0x0d,
            0x0a,
            0x1a,
            0x0a,
        )
    }
}
