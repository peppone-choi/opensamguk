package opensamguk.gameapi.battle.realtime

import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import opensamguk.infra.battle.realtime.BattleCommandReceipt
import opensamguk.infra.battle.realtime.BattleCommandVerdict

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
    @Test
    fun `numeric and canonical string source identities produce the same domain move and intent`() {
        for (id in listOf(1, 701, Int.MAX_VALUE)) {
            val numeric = frame.replace("701", id.toString())
            val textual = numeric.replace("\"sourceId\":$id", "\"sourceId\":\"$id\"")
            assertEquals(codec.deploymentMove(numeric), codec.deploymentMove(textual))
            assertEquals(codec.intent(codec.deploymentMove(numeric)), codec.intent(codec.deploymentMove(textual)))
        }
    }

    @Test
    fun `reject noncanonical nonpositive fractional and overflowing source identities`() {
        for (id in listOf("\"07\"", "\"0\"", "\"-1\"", "\"7.0\"", "\" 7\"", "\"+7\"",
                "\"7e0\"", "\"2147483648\"", "\"\"", "0", "-1", "7.5", "2147483648", "null", "true")) {
            assertFails(id) { codec.deploymentMove(frame.replace("\"sourceId\":701", "\"sourceId\":$id")) }
        }
    }

    @Test
    fun `ACK uses actual web envelope and nested receipt with decimal revision`() {
        val receipt = BattleCommandReceipt("move-1", BattleCommandVerdict.ACCEPTED,
            null, 0, null, null, 3, deploymentRevisionAfter = Long.MAX_VALUE)
        val encoded = codec.ack("battle", 7, receipt)
        println("C3A_ACK_FRAME=$encoded")
        val frame = ObjectMapper().readTree(encoded)
        assertEquals(2, frame["schemaVersion"].intValue())
        assertEquals("ACK", frame["t"].textValue())
        assertEquals("7", frame["sessionEpoch"].textValue())
        assertEquals("ACCEPTED", frame["receipt"]["verdict"].textValue())
        assertEquals(Long.MAX_VALUE.toString(), frame["receipt"]["deploymentRevisionAfter"].textValue())
        assertEquals(false, frame["replayed"].booleanValue())
        assertEquals(true, frame["inputId"].isNull)
    }

}
