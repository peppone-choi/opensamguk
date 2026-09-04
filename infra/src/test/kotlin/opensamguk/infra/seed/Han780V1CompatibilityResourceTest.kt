package opensamguk.infra.seed

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

class Han780V1CompatibilityResourceTest {

    @Test
    fun `world v2 resolves its reviewed 781 city resource`() {
        val v2 = MapJson.loadFromClasspath("han-world-v2")
        assertEquals((1..781).toList(), v2.cities.map { it.id })
    }

    @Test
    fun `compatibility map resource is the immutable 780-city artifact`() {
        val data = MapJson.loadFromClasspath("han-780-v1")
        assertEquals((1..780).toList(), data.cities.map { it.id })
        val bytes = requireNotNull(javaClass.classLoader.getResourceAsStream("map/han-780-v1.json")).readBytes()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("a61cbd8aa6fd0dd2f7f794df6d0ebdc026c0b6c351568c60efb8d115f54b3670", hash)
    }
}
