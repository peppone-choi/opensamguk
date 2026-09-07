package opensamguk.gateway.profile

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * 업로드 전콘은 카드 규격(148×210)으로 변환돼 저장된다. 이 규격이 깨지면 카드 슬롯에
 * 빈 띠가 생겨 유저 초상만 깨져 보인다 — 정사각 강제를 되돌리면 아래 단언들이 빨개진다.
 */
class ProfileIconTransformerTest {
    private val decoder = ProfileIconDecoder(maxBytes = 4_194_304, minDimension = 32, maxDimension = 4096)
    private val transformer = ProfileIconTransformer(maxStoredBytes = 51_200)

    private fun card(source: ByteArray): DecodedProfileIcon = transformer.toCard(decoder.decode(source))

    @Test
    fun `정사각 업로드도 카드 규격으로 나온다`() {
        val out = card(TestImageFixtures.image("png", 200, 200))
        assertEquals(ProfileIconTransformer.CARD_WIDTH, out.width)
        assertEquals(ProfileIconTransformer.CARD_HEIGHT, out.height)
        assertEquals("jpg", out.extension)
        assertEquals("image/jpeg", out.mediaType)
    }

    @Test
    fun `세로로 긴 업로드도 카드 규격으로 나온다`() {
        val out = card(TestImageFixtures.image("png", 300, 900))
        assertEquals(ProfileIconTransformer.CARD_WIDTH, out.width)
        assertEquals(ProfileIconTransformer.CARD_HEIGHT, out.height)
    }

    @Test
    fun `가로로 긴 업로드도 카드 규격으로 나온다`() {
        val out = card(TestImageFixtures.image("jpg", 1200, 400))
        assertEquals(ProfileIconTransformer.CARD_WIDTH, out.width)
        assertEquals(ProfileIconTransformer.CARD_HEIGHT, out.height)
    }

    @Test
    fun `실제 픽셀 크기도 카드 규격이다`() {
        val out = card(TestImageFixtures.image("png", 640, 480))
        val image = ImageIO.read(ByteArrayInputStream(out.bytes))
        assertEquals(ProfileIconTransformer.CARD_WIDTH, image.width)
        assertEquals(ProfileIconTransformer.CARD_HEIGHT, image.height)
    }

    @Test
    fun `저장 상한 안에 들어온다`() {
        val out = card(TestImageFixtures.image("png", 1600, 1600))
        assertTrue(out.bytes.size <= 51_200, "저장 바이트 ${out.bytes.size} 가 상한을 넘었다")
    }

    /** 정사각 강제를 되살리면 이 업로드가 디코더에서 죽는다 — 그게 고치기 전 프로덕션 동작이었다. */
    @Test
    fun `비정사각 업로드가 디코더를 통과한다`() {
        val decoded = decoder.decode(TestImageFixtures.image("png", 300, 900))
        assertEquals(300, decoded.width)
        assertEquals(900, decoded.height)
    }

    @Test
    fun `디코더 범위 밖은 여전히 거절한다`() {
        assertThrows<InvalidProfileIconException> {
            decoder.decode(TestImageFixtures.image("png", 16, 16))
        }
    }
}
