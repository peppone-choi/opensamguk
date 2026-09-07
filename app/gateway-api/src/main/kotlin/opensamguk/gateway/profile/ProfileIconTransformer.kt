package opensamguk.gateway.profile

import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * 업로드된 전콘을 **카드 규격(148×210)** 으로 변환한다.
 *
 * 왜 필요한가: 초상은 ADR-LITE-049 로 3종(원본 히어로 / 148×210 카드 / 96 아이콘)만 쓴다. 그런데
 * 업로드 전콘은 정사각형만 받아서, 카드 슬롯(`os-portrait--portrait`, `object-fit: contain`)에
 * 들어가면 아래에 29% 빈 띠가 남았다 — RTK14 초상은 꽉 차는데 유저 전콘만 깨져 보였다.
 * 화면 안내문은 「중앙을 정사각형으로 잘라 자동 변환한다」고 적혀 있었지만 **변환 코드는 없었다**.
 *
 * 그래서 여기서 실제로 변환한다. 잘라내는 기준은 **위쪽 중앙**이다 — 카드 슬롯의
 * `object-position: top center` 와 같은 기준이라야 얼굴이 잘리지 않는다.
 *
 * 아이콘(96×96) 슬롯은 이 카드 한 장을 `object-fit: cover` 로 얼굴만 잘라 쓴다. 파일을 두 장
 * 저장하면 저장·회수(reconciler)·동기화가 전부 두 배가 되는데, 얻는 건 축소 품질뿐이라
 * 한 장으로 둔다.
 */
class ProfileIconTransformer(
    private val maxStoredBytes: Int,
    private val width: Int = CARD_WIDTH,
    private val height: Int = CARD_HEIGHT,
) {
    fun toCard(decoded: DecodedProfileIcon): DecodedProfileIcon {
        val source = readImage(decoded.bytes)
        val cropped = coverCropTopCenter(source)
        val scaled = scaleTo(cropped, width, height)
        val bytes = encodeJpeg(scaled)
        return DecodedProfileIcon(
            bytes = bytes,
            extension = "jpg",
            mediaType = "image/jpeg",
            width = width,
            height = height,
        )
    }

    private fun readImage(bytes: ByteArray): BufferedImage =
        ImageIO.read(ByteArrayInputStream(bytes)) ?: throw InvalidProfileIconException()

    /**
     * 카드 비율로 덮어 자른다(cover). 가로가 남으면 좌우를 均等히 버리고, 세로가 남으면
     * **아래쪽만** 버린다 — 인물 사진은 얼굴이 위에 있다.
     */
    private fun coverCropTopCenter(image: BufferedImage): BufferedImage {
        val targetRatio = width.toDouble() / height
        val sourceRatio = image.width.toDouble() / image.height
        return when {
            sourceRatio > targetRatio -> {
                val cropWidth = Math.max(1, Math.round(image.height * targetRatio).toInt())
                image.getSubimage((image.width - cropWidth) / 2, 0, cropWidth, image.height)
            }
            sourceRatio < targetRatio -> {
                val cropHeight = Math.max(1, Math.round(image.width / targetRatio).toInt())
                image.getSubimage(0, 0, image.width, cropHeight)
            }
            else -> image
        }
    }

    private fun scaleTo(image: BufferedImage, w: Int, h: Int): BufferedImage {
        // JPEG 은 알파가 없다. 투명 PNG 를 그대로 그리면 알파 자리가 검게 남으므로 흰 바탕을 깐다.
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = Color.WHITE
            g.fillRect(0, 0, w, h)
            g.drawImage(image, 0, 0, w, h, null)
        } finally {
            g.dispose()
        }
        return out
    }

    /** 저장 상한 안에 들어올 때까지 품질을 낮춘다. 148×210 이면 최고 품질도 대개 상한의 절반이다. */
    private fun encodeJpeg(image: BufferedImage): ByteArray {
        for (quality in QUALITY_LADDER) {
            val bytes = encodeJpegAt(image, quality)
            if (bytes.size <= maxStoredBytes) return bytes
        }
        // 사다리 끝까지 못 줄였으면 저장하지 않는다 — 상한을 넘긴 바이트를 흘려보내지 않는다.
        throw ProfileIconPayloadTooLargeException()
    }

    private fun encodeJpegAt(image: BufferedImage, quality: Float): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val out = ByteArrayOutputStream()
        try {
            ImageIO.createImageOutputStream(out).use { stream ->
                writer.output = stream
                val params = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = quality
                }
                writer.write(null, IIOImage(image, null, null), params)
            }
        } finally {
            writer.dispose()
        }
        return out.toByteArray()
    }

    companion object {
        /** ADR-LITE-049 초상 3종의 카드 규격. */
        const val CARD_WIDTH = 148
        const val CARD_HEIGHT = 210
        private val QUALITY_LADDER = floatArrayOf(0.92f, 0.85f, 0.75f, 0.6f, 0.45f)
    }
}
