package opensamguk.infra.seed

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

class Han780V1CompatibilityResourceTest {

    @Test
    fun `persisted world v2 resolves the frozen 774 city resource`() {
        val v2 = MapJson.loadFromClasspath("han-world-v2")
        assertEquals((1..774).toList(), v2.cities.map { it.id })
        assertEquals(MapJson.loadFromClasspath("han").cities, v2.cities)
    }

    @Test
    fun `new world v3 resolves its reviewed 1224 city resource`() {
        val v3 = MapJson.loadFromClasspath("han-world-v3")
        // 결손 縣 56 곳은 1342–1397 을 받았다. 은퇴 번호 26 개(≤1194)와 동결 1341 판에서 철회한 1195–1341 은
        // 계속 비워 둔다(korea-retired-settlements-v1 numericIdsReserved).
        val retired = setOf(1143, 1148, 1157, 1159, 1160, 1161, 1162, 1163, 1164, 1178, 1179, 1180, 1181, 1182, 1183, 1184, 1185, 1186, 1187, 1188, 1189, 1190, 1191, 1192, 1193, 1194)
        assertEquals((1..1194).filterNot { it in retired } + (1342..1397), v3.cities.map { it.id })
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
