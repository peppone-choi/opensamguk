package opensamguk.common.wire

import kotlin.test.Test
import kotlin.test.assertEquals

class CouncilInputWireCompatibilityTest {
    @Test
    fun `별도 sealed subtype은 기존 councilInput JSON 원문과 영수증을 보존한다`() {
        for (revision in listOf(null, "authority-1")) {
            val command = CouncilInput("receipt-1", 10, 7, 1, "POST_ARTICLE", "{}", revision)
            val withoutRevision = """{"type":"councilInput","requestId":"receipt-1","generalId":10,"ownerUserId":7,"nationId":1,"action":"POST_ARTICLE","argJson":"{}"}"""
            val original = if (revision == null) withoutRevision else
                withoutRevision.dropLast(1) + ""","authorityRevision":"authority-1"}"""
            assertEquals(original, WireJson.encodeToString(TurnDaemonCommand.serializer(), command))
            assertEquals(command, WireJson.decodeFromString(TurnDaemonCommand.serializer(), original))
            assertEquals("councilInput", command.type)
        }
    }
}
