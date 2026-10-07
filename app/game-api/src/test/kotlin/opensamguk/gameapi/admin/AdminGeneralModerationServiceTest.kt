package opensamguk.gameapi.admin

import opensamguk.logic.input.RuleProfile
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.gameapi.reserve.CommandReserveService
import opensamguk.gameapi.reserve.CommandReserveService.ReserveResult
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.mockito.Mockito.verifyNoInteractions
import opensamguk.gameapi.read.WorldStateReadEntity
import opensamguk.gameapi.read.WorldStateReadRepository
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

class AdminGeneralModerationServiceTest {
    private val commands = mock(CommandReserveService::class.java)

    private fun policyWorld(profile: String?): WorldStateReadRepository =
        mock(WorldStateReadRepository::class.java).also { worlds ->
            `when`(worlds.findProcessWorld()).thenReturn(profile?.let {
                WorldStateReadEntity(config = mapOf("ruleProfile" to it))
            })
        }

    @Test
    fun `unsupported current world actions reject before any command write`() {
        val service = AdminGeneralModerationService(commands, policyWorld("HWIHA"))
        for (action in listOf("block2", "block3", "forceDeath")) {
            val failure = assertFailsWith<IllegalArgumentException> { service.apply(action, listOf(10), null, 77) }
            assertEquals(AdminGeneralModerationService.UNSUPPORTED_TURN_REASON, failure.message)
        }
        verifyNoInteractions(commands)
    }

    @Test
    fun `missing process policy rejects before publication or reservation`() {
        val service = AdminGeneralModerationService(commands, policyWorld(null))
        for (action in listOf("block2", "block3", "forceDeath")) {
            val failure = assertFailsWith<ResponseStatusException> { service.apply(action, listOf(10), null, 77) }
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.statusCode)
        }
        verifyNoInteractions(commands)
    }

    @Test
    fun `supported current world block still publishes normally`() {
        `when`(commands.publishImmediate(anyCommand())).thenReturn(ReserveResult("req-block", 0))
        val result = AdminGeneralModerationService(commands, policyWorld("HWIHA"))
            .apply("block1", listOf(10), null, 77)
        assertEquals(listOf("req-block"), result.requestIds)
    }

    private fun anyCommand(): TurnDaemonCommand =
        any(TurnDaemonCommand::class.java) ?: TurnDaemonCommand.Pause()

    @Test
    fun `forceDeath returns every child request id in command order`() {
        `when`(commands.reserve(10, "휴식", 0, "{}")).thenReturn(ReserveResult("req-rest-10", 0))
        `when`(commands.reserve(20, "휴식", 0, "{}")).thenReturn(ReserveResult("req-rest-20", 0))
        `when`(commands.publishImmediate(anyCommand())).thenReturn(ReserveResult("req-force", 0))

        val result = AdminGeneralModerationService(commands, policyWorld(RuleProfile.fromWorldConfig(null).name))
            .apply("forceDeath", listOf(10, 20), null, actorGeneralId = 77)

        assertEquals("forceDeath", result.action)
        assertEquals(2, result.affected)
        assertEquals(listOf("req-rest-10", "req-rest-20", "req-force"), result.requestIds)
    }

    @Test
    fun `dex action returns moderation request id followed by message request ids`() {
        `when`(commands.publishImmediate(anyCommand())).thenReturn(ReserveResult("req-dex", 0))
        `when`(
            commands.reserve(
                generalId = 77,
                actionCode = "sendMessage",
                argJson = """{"mailbox":10,"text":"보병숙련도+10000 지급!"}""",
            ),
        ).thenReturn(ReserveResult("req-message-10", 0))

        val result = AdminGeneralModerationService(commands, policyWorld(RuleProfile.fromWorldConfig(null).name))
            .apply("dex1", listOf(10), null, actorGeneralId = 77)

        assertEquals(listOf("req-dex", "req-message-10"), result.requestIds)
    }
}
