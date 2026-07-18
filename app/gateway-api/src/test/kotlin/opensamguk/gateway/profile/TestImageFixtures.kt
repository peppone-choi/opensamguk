package opensamguk.gateway.profile

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Base64
import java.util.zip.CRC32
import javax.imageio.ImageIO

internal object TestImageFixtures {
    // Generated from a synthetic 80x80 PNG with the pinned linuxserver/ffmpeg 7.1.1
    // image sha256:aea59a11c54291ac456bb2d67000445e5a8994f70bc3d96cdc29f022fbbf89fb.
    // Fixture sha256:e43a7ad83e39caeeb12218ee11b284ac50e2a11088c7f0b61e5ff0f06e04df39.
    private const val AVIF_80 =
        "AAAAIGZ0eXBhdmlmAAAAAGF2aWZtaWYxbWlhZk1BMUEAAAD5bWV0YQAAAAAAAAAvaGRscgAAAAAAAAAAcGljdAAAAAAAAAAAAAAAAFBpY3R1cmVIYW5kbGVyAAAAAA5waXRtAAAAAAABAAAAHmlsb2MAAAAARAAAAQABAAAAAQAAASEAAABtAAAAKGlpbmYAAAAAAAEAAAAaaW5mZQIAAAAAAQAAYXYwMUNvbG9yAAAAAGppcHJwAAAAS2lwY28AAAAUaXNwZQAAAAAAAABQAAAAUAAAABBwaXhpAAAAAAMICAgAAAAMYXYxQ4EgAAAAAAATY29scm5jbHgAAgACAAIAAAAAF2lwbWEAAAAAAAAAAQABBAECgwQAAAB1bWRhdAoGOBmnz2wQMmMR4AEEEEEj1AAANzXWaVOD26R9zuvu/79egCWNJjRo414YXF+I5o4OG331GwcoLGY78H+1yx4Ah1nZGJ9mMYT02DTsQlmGXqGQyWgCqBB9ut8wGEpoN/RO5+NYTk+OpWw+uCA="

    // Same generator as AVIF. Fixture
    // sha256:b709d23cf44509282ccd675aa4f2cdb78462e93e98945fcafebfe42420e02e38.
    private const val WEBP_80 =
        "UklGRj4AAABXRUJQVlA4TDEAAAAvT8ATALmM6H8shASECf7/1QwoxID3fwKMPgOZgMXQv7kbhQACoKCJmADgtvn+2ysAAA=="

    fun avif80(): ByteArray = Base64.getDecoder().decode(AVIF_80)

    fun webp80(): ByteArray = Base64.getDecoder().decode(WEBP_80)

    fun image(format: String, width: Int = 80, height: Int = width): ByteArray {
        val type = if (
            format.equals("jpg", ignoreCase = true) ||
            format.equals("jpeg", ignoreCase = true) ||
            format.equals("bmp", ignoreCase = true)
        ) {
            BufferedImage.TYPE_INT_RGB
        } else {
            BufferedImage.TYPE_INT_ARGB
        }
        val image = BufferedImage(width, height, type)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val rgb = (0xff shl 24) or (((x / 8) * 17 and 0xff) shl 16) or
                    (((y / 8) * 23 and 0xff) shl 8) or (((x / 8 + y / 8) * 11) and 0xff)
                image.setRGB(x, y, rgb)
            }
        }
        return ByteArrayOutputStream().use { output ->
            check(ImageIO.write(image, format, output)) { "Missing test writer for $format" }
            output.toByteArray()
        }
    }

    fun exactSizePng(size: Int): ByteArray {
        val base = image("png")
        val chunkDataSize = size - base.size - 12
        require(chunkDataSize >= 0)
        val iendOffset = base.size - 12
        val type = "ruSt".toByteArray(Charsets.US_ASCII)
        val data = ByteArray(chunkDataSize) { 'x'.code.toByte() }
        val crc = CRC32().apply {
            update(type)
            update(data)
        }.value.toInt()
        val chunk = ByteBuffer.allocate(chunkDataSize + 12)
            .putInt(chunkDataSize)
            .put(type)
            .put(data)
            .putInt(crc)
            .array()
        return base.copyOfRange(0, iendOffset) + chunk + base.copyOfRange(iendOffset, base.size)
    }
}
