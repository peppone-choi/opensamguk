package opensamguk.gameapi.admin

import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.gameapi.reserve.CommandReserveService
import opensamguk.gameapi.reserve.CommandReserveService.ReserveResult
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.Test
import kotlin.test.assertEquals

class AdminGeneralModerationServiceTest {
    private val commands = mock(CommandReserveService::class.java)

    private fun anyCommand(): TurnDaemonCommand =
        any(TurnDaemonCommand::class.java) ?: TurnDaemonCommand.Pause()

    @Test
    fun `forceDeath returns every child request id in command order`() {
        `when`(commands.reserve(10, "휴식", 0, "{}")).thenReturn(ReserveResult("req-rest-10", 0))
        `when`(commands.reserve(20, "휴식", 0, "{}")).thenReturn(ReserveResult("req-rest-20", 0))
        `when`(commands.publishImmediate(anyCommand())).thenReturn(ReserveResult("req-force", 0))

        val result = AdminGeneralModerationService(commands)
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

        val result = AdminGeneralModerationService(commands)
            .apply("dex1", listOf(10), null, actorGeneralId = 77)

        assertEquals(listOf("req-dex", "req-message-10"), result.requestIds)
    }
}
