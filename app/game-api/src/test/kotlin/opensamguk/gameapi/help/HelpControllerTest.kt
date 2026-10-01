package opensamguk.gameapi.help

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.gameapi.read.WorldStateReadEntity
import opensamguk.gameapi.read.WorldStateReadRawRepository
import opensamguk.gameapi.read.WorldStateReadRepository
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        val input = (context.body as Map<*, *>)["input"] as Map<*, *>
        assertEquals(mapOf("state" to "UNMAPPED", "stepId" to null, "naReason" to null),
            input["firstStepsExplanation"])
        assertFalse(input.containsKey("tutorialObjectiveId"))
        val unmappedInput = (controller.context("action.farm").body as Map<*, *>)["input"] as Map<*, *>
        val unmapped = unmappedInput["firstStepsExplanation"] as Map<*, *>
        assertEquals("UNMAPPED", unmapped["state"])
        val topic = (controller.topic("commands.action.enlist").body as Map<*, *>)["topic"] as HelpTopic
        assertEquals(HelpReviewState.DRAFT, topic.reviewState)
        val reason = controller.failure("ALREADY_SERVING", "action.enlist").body as Map<*, *>
        assertEquals(HelpReviewState.DRAFT, reason["reviewState"])
        assertEquals(HttpStatus.OK, controller.search("출사", "1").statusCode)
    }

    @Test
    fun `reward over cap has queryable failure help in the active world`() {
        MockMvcBuilders.standaloneSetup(controller).build()
            .perform(get("/api/help/failures/REWARD_OVER_CAP").param("inputId", "court.reward"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.schemaVersion").value(1))
            .andExpect(jsonPath("$.reason").value("REWARD_OVER_CAP"))
            .andExpect(jsonPath("$.reviewState").value("DRAFT"))
            .andExpect(jsonPath("$.explanation").isNotEmpty)
            .andExpect(jsonPath("$.recoveryAdvice").isNotEmpty)
            .andExpect(jsonPath("$.relatedTopicIds[0]").value("commands.court.reward"))
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
    }

    private fun controller(world: WorldStateReadEntity?): HelpController {
        val raw = object : WorldStateReadRawRepository {
            override fun findById(id: Int): Optional<WorldStateReadEntity> = Optional.ofNullable(world)
        }
        return HelpController(HelpStoreProvider(), WorldStateReadRepository(raw, GameApiProcessWorld(1)), ObjectMapper())
    }
}
