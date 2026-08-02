package opensamguk.gateway.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerRegistryTest {

    private val mapper = ObjectMapper()

    private fun registry(json: String): ServerRegistry =
        ServerRegistry(json, "http://game-api:8081", "http://game-engine:8082", "opensamguk", "통일 서버", mapper)

    @Test
    fun `default compose coordinates derive from the public server ID`() {
        val parsed = registry("""[{"id":"s1"},{"id":"current"}]""")

        assertEquals(listOf("s1", "current"), parsed.all().map { it.id })
        assertEquals("http://ss1-game-api:8081", parsed.find("s1")?.gameApiUrl)
        assertEquals("http://scurrent-game-api:8081", parsed.find("current")?.gameApiUrl)
    }

    @Test
    fun `a mixed invalid collection fails closed instead of keeping its valid prefix`() {
        val parsed = registry("""[{"id":"pep"},{"id":"main"}]""")

        assertTrue(parsed.all().isEmpty())
        assertNull(parsed.find("pep"))
        assertNull(parsed.default())
    }

    @Test
    fun `canonical collisions reject the complete collection`() {
        val parsed = registry("""[{"id":"pep"},{"id":"pep"}]""")

        assertTrue(parsed.all().isEmpty())
    }

    @Test
    fun `bad internal API origin rejects the complete collection`() {
        val parsed = registry(
            """[{"id":"pep","gameApiUrl":"http://spep-game-api:8081"},{"id":"a1","gameApiUrl":"http://a1-game-api:8081"}]""",
        )

        assertTrue(parsed.all().isEmpty())
    }

    @Test
    fun `reserved top-level route IDs are rejected while current remains public`() {
        val reserved = listOf(
            "all", "main", "admin1", "admin2", "admin5", "admin7", "admin8", "auction", "battle-center",
            "betting", "board", "chief-center", "city", "coming-soon", "diplomacy", "generals",
            "global-diplomacy", "history", "inherit", "join", "mailbox", "map", "my", "my-boss",
            "my-cities", "my-generals", "my-nation", "nation", "nation-betting", "nation-finance",
            "npc-control", "rankings", "register", "select-pool", "simulator", "tournament",
            "tournament-admin", "troop", "vote", "world-log",
        )

        assertEquals(listOf("current"), registry("""[{"id":"current"}]""").all().map { it.id })
        reserved.forEach { id ->
            assertTrue(registry("""[{"id":"current"},{"id":"$id"}]""").all().isEmpty(), "reserved id=$id")
        }
    }

    @Test
    fun `object-form registry follows the same atomic coordinate contract`() {
        val valid = registry(
            """{"pep":"http://spep-game-api:8081","current":{"name":"현재"}}""",
        )
        val invalid = registry(
            """{"pep":"http://spep-game-api:8081","a1":"http://wrong-game-api:8081"}""",
        )

        assertEquals(listOf("pep", "current"), valid.all().map { it.id })
        assertEquals("http://spep-game-api:8081", valid.find("pep")?.gameApiUrl)
        assertTrue(invalid.all().isEmpty())
    }
}
