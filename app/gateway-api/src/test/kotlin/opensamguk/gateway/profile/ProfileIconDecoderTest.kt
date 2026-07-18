package opensamguk.gateway.profile

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class ProfileIconDecoderTest {
    private val decoder = ProfileIconDecoder(maxBytes = 51_200)

    @ParameterizedTest
    @MethodSource("supportedImages")
    fun `decodes the complete raster and derives canonical extension from bytes`(
        bytes: ByteArray,
        extension: String,
        mediaType: String,
    ) {
        val decoded = decoder.decode(bytes)

        assertEquals(extension, decoded.extension)
        assertEquals(mediaType, decoded.mediaType)
        assertEquals(80, decoded.width)
        assertEquals(80, decoded.height)
        assertEquals(bytes.toList(), decoded.bytes.toList())
    }

    @Test
    fun `accepts exact byte and dimension boundaries`() {
        assertEquals(51_200, decoder.decode(TestImageFixtures.exactSizePng(51_200)).bytes.size)
        assertEquals(64, decoder.decode(TestImageFixtures.image("png", 64)).width)
        assertEquals(128, decoder.decode(TestImageFixtures.image("png", 128)).width)
    }

    @Test
    fun `rejects byte dimension and shape boundary violations`() {
        assertInvalid(TestImageFixtures.exactSizePng(51_200) + byteArrayOf(0))
        assertInvalid(TestImageFixtures.image("png", 63))
        assertInvalid(TestImageFixtures.image("png", 129))
        assertInvalid(TestImageFixtures.image("png", 80, 81))
    }

    @Test
    fun `rejects empty corrupt and unsupported image containers`() {
        assertInvalid(byteArrayOf())
        assertInvalid(TestImageFixtures.image("png").copyOf(40))
        assertInvalid(TestImageFixtures.image("bmp"))
        assertInvalid(TestImageFixtures.image("png") + "PK-polyglot".toByteArray())
    }

    @ParameterizedTest(name = "{1} rejects bytes after the image container")
    @MethodSource("supportedImages")
    fun `rejects trailing payload for every supported container`(
        bytes: ByteArray,
        extension: String,
        mediaType: String,
    ) {
        assertThrows(InvalidProfileIconException::class.java) {
            decoder.decode(bytes + "trailing-$extension-$mediaType".toByteArray())
        }
    }

    @Test
    fun `gif trailer must terminate the parsed block stream rather than merely be the last byte`() {
        val gif = TestImageFixtures.image("gif")

        assertEquals("gif", decoder.decode(gif).extension)
        assertInvalid(gif + "payload-after-trailer".toByteArray() + byteArrayOf(0x3b))
    }

    private fun assertInvalid(bytes: ByteArray) {
        assertThrows(InvalidProfileIconException::class.java) { decoder.decode(bytes) }
    }

    companion object {
        @JvmStatic
        fun supportedImages(): Stream<Arguments> = Stream.of(
            Arguments.of(TestImageFixtures.avif80(), "avif", "image/avif"),
            Arguments.of(TestImageFixtures.webp80(), "webp", "image/webp"),
            Arguments.of(TestImageFixtures.image("jpg"), "jpg", "image/jpeg"),
            Arguments.of(TestImageFixtures.image("png"), "png", "image/png"),
            Arguments.of(TestImageFixtures.image("gif"), "gif", "image/gif"),
        )
    }
}
