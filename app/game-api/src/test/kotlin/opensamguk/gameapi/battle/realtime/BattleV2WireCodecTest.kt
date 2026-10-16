package opensamguk.gameapi.battle.realtime

import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class BattleV2WireCodecTest {
    private val codec = BattleV2WireCodec(ObjectMapper())
    private val frame = """{"schemaVersion":2,"t":"DEPLOYMENT_MOVE","clientCommandId":"move-1","sourceKey":{"kind":"RETINUE","sourceId":701},"targetCell":{"row":1,"col":2},"expectedEpoch":"9223372036854775807","expectedAuthorityRevision":"3","expectedDeploymentRevision":"0"}"""

    @Test
    fun `exact web frame preserves long strings and numeric source identity`() {
        val move = codec.deploymentMove(frame)
        assertEquals("701", move.sourceKey.sourceId)
        assertEquals(Long.MAX_VALUE, move.expectedEpoch)
        assertEquals(1, move.targetCell.row)
        assertEquals(2, move.targetCell.col)
    }

    @Test
    fun `reject malformed versions unknown fields duplicate keys and noncanonical longs`() {
        listOf("{}", "[]", frame.replace("\"schemaVersion\":2", "\"schemaVersion\":1"),
            frame.replace("DEPLOYMENT_MOVE", "COMMAND"),
            frame.replace("\"sourceId\":701", "\"sourceId\":\"701\""),
            frame.replace("\"sourceId\":701", "\"sourceId\":701.0"),
            frame.replace("\"row\":1", "\"row\":64"),
            frame.replace("\"col\":2", "\"col\":2,\"slot\":0"),
            frame.replace("\"expectedDeploymentRevision\":\"0\"", "\"expectedDeploymentRevision\":0"),
            frame.replace("\"expectedAuthorityRevision\":\"3\"", "\"expectedAuthorityRevision\":\"03\""),
            frame.replace("9223372036854775807", "9223372036854775808"),
            frame.replace("\"schemaVersion\":2", "\"schemaVersion\":2,\"schemaVersion\":2"),
            frame + " {}",
        ).forEach { invalid -> assertFails(invalid) { codec.deploymentMove(invalid) } }
    }
}
