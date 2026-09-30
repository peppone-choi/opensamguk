package opensamguk.gameapi.help

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.gameapi.read.WorldStateReadEntity
import opensamguk.gameapi.read.WorldStateReadRawRepository
import opensamguk.gameapi.read.WorldStateReadRepository
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class HelpControllerTest {
    private val controller = controller(WorldStateReadEntity(
        id = 1, config = mapOf("worldFormat" to "GENERAL_RETAINER_CAMPAIGN"),
    ))

    @Test
    fun `topic context and search read catalog backed help`() {
        assertEquals(HttpStatus.OK, controller.topic("commands.action.enlist").statusCode)
        val context = controller.context("action.enlist")
        assertEquals(HttpStatus.OK, context.statusCode)
        assertNotNull(context.body)
        val topic = (controller.topic("commands.action.enlist").body as Map<*, *>)["topic"] as HelpTopic
        assertEquals(HelpReviewState.DRAFT, topic.reviewState)
        val reason = controller.failure("ALREADY_SERVING", "action.enlist").body as Map<*, *>
        assertEquals(HelpReviewState.DRAFT, reason["reviewState"])
        assertEquals(HttpStatus.OK, controller.search("출사", "1").statusCode)
        val listed = controller.topics(null)
        assertEquals(HttpStatus.OK, listed.statusCode)
        val summaries = (listed.body as Map<*, *>)["topics"] as List<*>
        assertEquals(74, summaries.size)
        assertEquals(HttpStatus.NOT_MODIFIED, controller.topics(listed.headers.eTag).statusCode)
    }

    @Test
    fun `unknown inputs and mismatched reason return typed errors`() {
        assertEquals(HttpStatus.NOT_FOUND, controller.context("action.notRegistered").statusCode)
        assertEquals(HttpStatus.BAD_REQUEST, controller.failure("ALREADY_SERVING", "action.move").statusCode)
        assertEquals(HttpStatus.BAD_REQUEST, controller.search(" ", "abc").statusCode)
    }

    @Test
    fun `missing world fails closed`() {
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, controller(null).topic("commands.action.enlist").statusCode)
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, controller(null).topics(null).statusCode)
    }

    private fun controller(world: WorldStateReadEntity?): HelpController {
        val raw = object : WorldStateReadRawRepository {
            override fun findById(id: Int): Optional<WorldStateReadEntity> = Optional.ofNullable(world)
        }
        return HelpController(HelpStoreProvider(), WorldStateReadRepository(raw, GameApiProcessWorld(1)), ObjectMapper())
    }
}
