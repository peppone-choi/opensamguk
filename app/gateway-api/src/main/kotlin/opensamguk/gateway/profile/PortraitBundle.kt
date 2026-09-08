package opensamguk.gateway.profile

import com.fasterxml.jackson.databind.ObjectMapper
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.math.abs

data class CropRect(val x: Double, val y: Double, val width: Double, val height: Double)
data class PortraitCrops(val hero: CropRect, val card: CropRect, val icon: CropRect)

/** All variants and the retained source share ONE journaled file and one ownership identity. */
object PortraitBundle {
    const val MAX_SOURCE_BYTES = 8 * 1024 * 1024
    const val MAX_BUNDLE_BYTES = 12 * 1024 * 1024
    val NAME = Regex("[0-9a-f]{8}\\.portrait")
    private val mapper = ObjectMapper()
    private val decoder = ProfileIconDecoder(MAX_SOURCE_BYTES, 64, 8192, 24_000_000)

    fun create(source: ByteArray, crops: PortraitCrops): DecodedProfileIcon {
        if (source.size > MAX_SOURCE_BYTES) throw ProfileIconPayloadTooLargeException("초상 원본은 8MiB 이하여야 합니다.")
        val decoded = decoder.decode(source)
        if (decoded.extension !in setOf("jpg", "png", "webp")) {
            throw InvalidProfileIconException("수동 초상은 JPEG, PNG, WebP 정지 이미지를 사용해 주세요.")
        }
        val raster = ImageIO.read(source.inputStream()) ?: throw InvalidProfileIconException()
        val image = PortraitOrientation.orient(raster, PortraitOrientation.read(source, decoded.extension))
        val variants = listOf(Triple("hero", crops.hero, 633 to 900), Triple("card", crops.card, 148 to 210), Triple("icon", crops.icon, 96 to 96))
        // Validate every rectangle before performing output work.
        variants.forEach { (_, crop, size) -> validate(crop, image.width, image.height, size.first, size.second) }
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            // Small public entries first: reading an icon never decompresses the retained original.
            for ((name, crop, size) in variants.reversed()) {
                val rendered = render(image, crop, size.first, size.second)
                val jpeg = ByteArrayOutputStream().also { ImageIO.write(rendered, "jpeg", it) }.toByteArray()
                add(zip, "$name.jpg", jpeg)
            }
            add(zip, "crops.json", mapper.writeValueAsBytes(crops))
            add(zip, "source-type", decoded.mediaType.toByteArray(Charsets.US_ASCII))
            add(zip, "source", source)
        }
        val bytes = output.toByteArray()
        if (bytes.size > MAX_BUNDLE_BYTES) throw ProfileIconPayloadTooLargeException("초상 저장 용량을 초과했습니다.")
        return DecodedProfileIcon(bytes, "portrait", "application/octet-stream", image.width, image.height)
    }

    fun parseCrops(json: String): PortraitCrops = try {
        if (json.length > 4096) throw InvalidProfileIconException()
        val root = mapper.readTree(json)
        if (!root.isObject || root.fieldNames().asSequence().toSet() != setOf("hero", "card", "icon")) throw InvalidProfileIconException()
        fun crop(key: String): CropRect {
            val node = root[key]
            if (!node.isObject || node.fieldNames().asSequence().toSet() != setOf("x", "y", "width", "height")) throw InvalidProfileIconException()
            fun number(field: String): Double {
                if (!node[field].isNumber) throw InvalidProfileIconException()
                return node[field].doubleValue().also { if (!it.isFinite()) throw InvalidProfileIconException() }
            }
            return CropRect(number("x"), number("y"), number("width"), number("height"))
        }
        PortraitCrops(crop("hero"), crop("card"), crop("icon"))
    } catch (_: Exception) { throw InvalidProfileIconException("세 가지 초상의 자르기 영역을 확인해 주세요.") }

    fun readCrops(input: InputStream): PortraitCrops = parseCrops(entry(input, "crops.json").toString(Charsets.UTF_8))

    /** Caller chooses from a fixed entry allowlist; bounded extraction never writes archive paths. */
    fun entry(input: InputStream, name: String): ByteArray {
        val limit = when (name) {
            "hero.jpg", "card.jpg", "icon.jpg" -> 2 * 1024 * 1024
            "crops.json" -> 4096
            "source-type" -> 64
            "source" -> MAX_SOURCE_BYTES
            else -> throw InvalidProfileIconException()
        }
        ZipInputStream(input).use { zip ->
            var total = 0
            var count = 0
            while (true) {
                val entry = zip.nextEntry ?: throw ProfileIconStorageException()
                if (++count > 6 || entry.isDirectory) throw ProfileIconStorageException()
                val bytes = zip.readNBytes((if (entry.name == name) limit else MAX_SOURCE_BYTES) + 1)
                total += bytes.size
                if (total > MAX_BUNDLE_BYTES) throw ProfileIconStorageException()
                if (entry.name == name) {
                    if (bytes.size > limit) throw ProfileIconStorageException()
                    return bytes
                }
            }
        }
    }

    private fun validate(c: CropRect, width: Int, height: Int, targetWidth: Int, targetHeight: Int) {
        val values = listOf(c.x, c.y, c.width, c.height)
        if (values.any { !it.isFinite() } || c.x < 0 || c.y < 0 || c.width * width < 1 || c.height * height < 1 || c.x + c.width > 1.000000001 || c.y + c.height > 1.000000001) {
            throw InvalidProfileIconException("자르기 영역은 원본 이미지 안에 있어야 합니다.")
        }
        val ratio = c.width * width / (c.height * height)
        if (abs(ratio / (targetWidth.toDouble() / targetHeight) - 1) > 0.005) {
            throw InvalidProfileIconException("자르기 영역의 비율이 초상 규격과 다릅니다.")
        }
    }

    private fun render(source: BufferedImage, c: CropRect, width: Int, height: Int): BufferedImage {
        val output = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        output.createGraphics().apply {
            color = Color.WHITE
            fillRect(0, 0, width, height)
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            val sx = width / (c.width * source.width)
            val sy = height / (c.height * source.height)
            drawImage(source, AffineTransform(sx, 0.0, 0.0, sy, -c.x * source.width * sx, -c.y * source.height * sy), null)
            dispose()
        }
        return output
    }

    private fun add(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }
}
