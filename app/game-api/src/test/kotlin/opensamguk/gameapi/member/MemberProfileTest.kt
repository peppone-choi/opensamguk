package opensamguk.gameapi.member

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class MemberProfileTest {
    private val objectMapper = ObjectMapper().findAndRegisterModules()

    @Test
    fun `game profile wire dto contains only the four display fields`() {
        val json = objectMapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(
            MemberProfile(
                name = "display-name",
                grade = 6,
                picture = "member.png",
                imageServer = 1,
            ),
        )

        assertEquals(
            setOf("name", "grade", "picture", "imageServer"),
            json.fieldNames().asSequence().toSet(),
        )
    }

    @Test
    fun `game profile wire dto preserves a null picture field`() {
        val json = objectMapper.writeValueAsString(MemberProfile("display-name", 1, null, 0))

        assertEquals(
            """{"name":"display-name","grade":1,"picture":null,"imageServer":0}""",
            json,
        )
    }
}
