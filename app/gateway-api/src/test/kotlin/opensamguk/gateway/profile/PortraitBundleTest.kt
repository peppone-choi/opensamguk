package opensamguk.gateway.profile

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows

class PortraitBundleTest {
    @Test
    fun `independent source rectangles select true pixels at all three target sizes and preserve original`() {
        val source = stripedSource()
        val bundle = PortraitBundle.create(source, crops())
        for ((variant, expected) in mapOf("hero" to Triple(633, 900, Color.RED), "card" to Triple(148, 210, Color.GREEN), "icon" to Triple(96, 96, Color.BLUE))) {
            val image = ImageIO.read(ByteArrayInputStream(PortraitBundle.entry(bundle.bytes.inputStream(), "$variant.jpg")))
            assertEquals(expected.first, image.width)
            assertEquals(expected.second, image.height)
            val pixel = Color(image.getRGB(image.width / 2, image.height / 2))
            assertTrue(kotlin.math.abs(pixel.red - expected.third.red) < 5)
            assertTrue(kotlin.math.abs(pixel.green - expected.third.green) < 5)
            assertTrue(kotlin.math.abs(pixel.blue - expected.third.blue) < 5)
        }
        assertArrayEquals(source, PortraitBundle.entry(bundle.bytes.inputStream(), "source"))
        assertEquals(crops(), PortraitBundle.readCrops(bundle.bytes.inputStream()))
    }

    @Test
    fun `out of bounds nonfinite empty and wrong aspect rectangles fail`() {
        val source = stripedSource()
        for (bad in listOf(CropRect(-0.1, 0.0, 0.5, 0.5), CropRect(0.8, 0.0, 0.5, 0.5), CropRect(0.0, 0.0, 0.0, 0.5), CropRect(Double.NaN, 0.0, 0.5, 0.5), CropRect(0.0, 0.0, 0.5, 0.5))) {
            assertThrows<InvalidProfileIconException> { PortraitBundle.create(source, crops().copy(hero = bad)) }
        }
    }

    @Test
    fun `crop json requires exactly three complete numeric rectangles`() {
        for (json in listOf("{}", "[]", "null", "{\"hero\":{}}", "{\"hero\":{\"x\":\"0\"}}")) {
            assertThrows<InvalidProfileIconException> { PortraitBundle.parseCrops(json) }
        }
    }

    @Test
    fun `EXIF thumbnail JPEG crops use displayed source coordinates while retaining exact original bytes`() {
        val raster = ImageIO.read(stripedSource().inputStream())
        val jpeg = ByteArrayOutputStream().also { ImageIO.write(raster, "jpeg", it) }.toByteArray()
        val thumbnail = TestImageFixtures.image("jpg", 64)
        // TIFF IFD0 orientation=6 and IFD1 JPEGInterchangeFormat/Length reference a real thumbnail.
        val tiff = java.nio.ByteBuffer.allocate(56 + thumbnail.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .put(73).put(73).putShort(42).putInt(8)
            .putShort(1).putShort(0x112).putShort(3).putInt(1).putShort(6).putShort(0).putInt(26)
            .putShort(2).putShort(0x201).putShort(4).putInt(1).putInt(56)
            .putShort(0x202).putShort(4).putInt(1).putInt(thumbnail.size).putInt(0).put(thumbnail).array()
        val payload = byteArrayOf(69, 120, 105, 102, 0, 0) + tiff
        val exif = java.nio.ByteBuffer.allocate(4 + payload.size).put(0xff.toByte()).put(0xe1.toByte())
            .putShort((payload.size + 2).toShort()).put(payload).array()
        val source = jpeg.copyOfRange(0, 2) + exif + jpeg.copyOfRange(2, jpeg.size)
        val crops = PortraitCrops(CropRect(0.0, 0.0, 633.0 / 2700, 1.0 / 3), CropRect(0.0, 1.0 / 3, 148.0 / 630, 1.0 / 3), CropRect(0.0, 2.0 / 3, 1.0 / 3, 1.0 / 3))
        assertEquals(900, ImageIO.read(source.inputStream()).width)
        val bundle = PortraitBundle.create(source, crops)
        assertThrows<InvalidProfileIconException> { PortraitBundle.create(source + "junk".toByteArray(), crops) }
        for ((name, color) in mapOf("hero" to Color.RED, "card" to Color.GREEN, "icon" to Color.BLUE)) {
            val image = ImageIO.read(PortraitBundle.entry(bundle.bytes.inputStream(), "$name.jpg").inputStream())
            val pixel = Color(image.getRGB(image.width / 2, image.height / 2))
            assertTrue(kotlin.math.abs(pixel.red - color.red) < 5)
            assertTrue(kotlin.math.abs(pixel.green - color.green) < 5)
            assertTrue(kotlin.math.abs(pixel.blue - color.blue) < 5)
        }
        assertArrayEquals(source, PortraitBundle.entry(bundle.bytes.inputStream(), "source"))
    }

    @Test
    fun `source byte and pixel limits are enforced before large raster allocation`() {
        assertThrows<ProfileIconPayloadTooLargeException> { PortraitBundle.create(ByteArray(8 * 1024 * 1024 + 1), crops()) }
        val source = stripedSource()
        java.nio.ByteBuffer.wrap(source).apply { putInt(16, 8192); putInt(20, 4000) }
        val crc = java.util.zip.CRC32().apply { update(source, 12, 17) }.value
        java.nio.ByteBuffer.wrap(source).putInt(29, crc.toInt())
        val failure = assertThrows<InvalidProfileIconException> { PortraitBundle.create(source, crops()) }
        assertTrue(failure.message!!.contains("24MP"))
    }

    companion object {
        fun crops() = PortraitCrops(CropRect(0.0, 0.0, 1.0 / 3, 300.0 * 900 / 633 / 900), CropRect(1.0 / 3, 0.0, 1.0 / 3, 300.0 * 210 / 148 / 900), CropRect(2.0 / 3, 0.0, 1.0 / 3, 1.0 / 3))
        fun stripedSource(): ByteArray {
            val image = BufferedImage(900, 900, BufferedImage.TYPE_INT_RGB)
            image.createGraphics().apply { for ((i, color) in listOf(Color.RED, Color.GREEN, Color.BLUE).withIndex()) { this.color = color; fillRect(i * 300, 0, 300, 900) }; dispose() }
            return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        }
    }
}
