package opensamguk.gateway.profile

import java.awt.image.BufferedImage
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows

class PortraitOrientationTest {
    @Test
    fun `all eight EXIF transforms put source corners in the browser displayed order`() {
        val input = BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB)
        input.setRGB(0, 0, 1); input.setRGB(1, 0, 2); input.setRGB(0, 2, 3); input.setRGB(1, 2, 4)
        val corners = listOf(listOf(1, 2, 3, 4), listOf(2, 1, 4, 3), listOf(4, 3, 2, 1), listOf(3, 4, 1, 2), listOf(1, 3, 2, 4), listOf(3, 1, 4, 2), listOf(4, 2, 3, 1), listOf(2, 4, 1, 3))
        for (orientation in 1..8) {
            val out = PortraitOrientation.orient(input, orientation)
            assertEquals(if (orientation >= 5) 3 else 2, out.width)
            assertEquals(corners[orientation - 1], listOf(out.getRGB(0, 0), out.getRGB(out.width - 1, 0), out.getRGB(0, out.height - 1), out.getRGB(out.width - 1, out.height - 1)).map { it and 0xffffff })
            assertEquals(orientation, PortraitOrientation.read(jpegExif(orientation), "jpg"))
        }
    }

    @Test
    fun `out of chunk TIFF offsets and invalid orientation fail explicitly`() {
        assertThrows<InvalidProfileIconException> { PortraitOrientation.read(jpegExif(9), "jpg") }
        val invalid = jpegExif(6).apply { this[16] = 0x7f }
        assertThrows<InvalidProfileIconException> { PortraitOrientation.read(invalid, "jpg") }
    }

    @Test
    fun `PNG and WebP EXIF chunks use the same bounded orientation reader`() {
        val tiff = jpegExif(8).copyOfRange(12, 38)
        val pngChunk = java.nio.ByteBuffer.allocate(8 + tiff.size + 4).putInt(tiff.size).put("eXIf".toByteArray()).put(tiff).putInt(0).array()
        assertEquals(8, PortraitOrientation.read(ByteArray(8) + pngChunk, "png"))
        val webpChunk = java.nio.ByteBuffer.allocate(8 + tiff.size).order(java.nio.ByteOrder.LITTLE_ENDIAN).put("EXIF".toByteArray()).putInt(tiff.size).put(tiff).array()
        assertEquals(8, PortraitOrientation.read(ByteArray(12) + webpChunk, "webp"))
        assertThrows<InvalidProfileIconException> { PortraitOrientation.read(ByteArray(8) + pngChunk + pngChunk, "png") }
    }

    private fun jpegExif(orientation: Int): ByteArray = byteArrayOf(
        0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe1.toByte(), 0, 34,
        69, 120, 105, 102, 0, 0, 73, 73, 42, 0, 8, 0, 0, 0,
        1, 0, 0x12, 1, 3, 0, 1, 0, 0, 0, orientation.toByte(), 0, 0, 0, 0, 0, 0, 0,
        0xff.toByte(), 0xd9.toByte(),
    )
}
