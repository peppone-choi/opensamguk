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
        assertEquals(mapOf("state" to "LINKED", "stepId" to "tutorial.enlist", "naReason" to null),
            input["firstStepsExplanation"])
        assertFalse(input.containsKey("tutorialObjectiveId"))
        val excludedInput = (controller.context("action.farm").body as Map<*, *>)["input"] as Map<*, *>
        assertEquals(mapOf("state" to "NOT_APPLICABLE", "stepId" to null,
            "naReason" to "NOT_IN_FIRST_STEPS_EXPLANATION"), excludedInput["firstStepsExplanation"])
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
    fun `reward over cap has queryable failure help in an active hwiha world`() {
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
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, controller(null).topics(null).statusCode)
    }

    private fun controller(world: WorldStateReadEntity?): HelpController {
        val raw = object : WorldStateReadRawRepository {
            override fun findById(id: Int): Optional<WorldStateReadEntity> = Optional.ofNullable(world)
        }
        return HelpController(HelpStoreProvider(), WorldStateReadRepository(raw, GameApiProcessWorld(1)), ObjectMapper())
    }
}
