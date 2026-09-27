package opensamguk.gameapi.battle.realtime

import java.security.MessageDigest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import opensamguk.common.world.WorldId
import opensamguk.infra.battle.realtime.FrozenBattleParticipant
import opensamguk.infra.battle.realtime.FrozenBattleTicket
import opensamguk.logic.battle.realtime.BattleSide
import opensamguk.logic.battle.realtime.FormationSlot
import opensamguk.logic.battle.realtime.TacticalBoardCatalog

class BattleFrozenInputCodecTest {
    private val world = WorldId(1)
    private val ruleBytes = checkNotNull(javaClass.classLoader.getResourceAsStream("battle/waryong-tactical-rules-v1.json"))
        .use { it.readBytes() }
    private val catalog by lazy { TacticalBoardCatalog.parse(catalogPayload()) }
    private val participant = FrozenBattleParticipant(1, 42, 1, "ATTACKER", 3)
    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
    private fun sha(text: String) = sha(text.toByteArray(Charsets.UTF_8))

    @Test
    fun `pinned field handoff yields the real six-slot default state`() {
        val state = BattleFrozenInputCodec(catalog).initialState(ticket())
        assertEquals(192, state.battlefield.id)
        assertEquals(17L, state.seed)
        assertEquals(2, state.units.size)
        assertEquals(FormationSlot.CENTER, state.units.single { it.side == BattleSide.ATTACKER }.slot)
        assertEquals(emptySet(), state.humanSides)
    }

    @Test
    fun `wrong board rows catalog and participant mapping fail before actor start`() {
        val codec = BattleFrozenInputCodec(catalog)
        assertFailsWith<IllegalArgumentException> {
            codec.initialState(ticket(payload().replace(sha("P".repeat(4096)), "0".repeat(64))))
        }
        assertFailsWith<IllegalArgumentException> {
            codec.initialState(ticket().copy(catalogSha256 = "0".repeat(64)))
        }
        assertFailsWith<IllegalArgumentException> {
            codec.initialState(ticket().copy(participants = listOf(participant.copy(generalId = 99))))
        }
        assertFailsWith<Exception> {
            codec.initialState(ticket(payload().replace("\"tacticalInput\":", "\"missingInput\":")))
        }
    }

    private fun ticket(body: String = payload()): FrozenBattleTicket = FrozenBattleTicket(
        world, "battle-1", body, sha(body), sha(ruleBytes), catalog.catalogSha256,
        "c".repeat(64), 17, 4, 2, Instant.parse("2026-09-27T00:01:00Z"),
        Instant.parse("2026-09-27T00:06:00Z"), listOf(participant))

    private fun payload(): String {
        val unit1 = retinue(1)
        val unit2 = retinue(2)
        val boardSha = sha("P".repeat(4096))
        return """{"schemaVersion":1,"worldId":1,"battleId":"battle-1","kind":"ENCOUNTER","battlefieldId":192,"ruleSha256":"${sha(ruleBytes)}","catalogSha256":"${catalog.catalogSha256}","terrainSha256":"${"c".repeat(64)}","seed":17,"lockGeneration":4,"lockSetRevision":2,"joinDeadlineAt":"2026-09-27T00:01:00Z","deadlineAt":"2026-09-27T00:06:00Z","entityRevisions":{"general:1":1,"general:2":1},"participants":[{"participantId":1,"accountId":42,"generalId":1,"side":"ATTACKER","authorityRevision":3}],"tacticalInput":{"schemaVersion":1,"board":{"id":192,"tileset":0,"terrainRowsSha256":"$boardSha"},"attacker":{"commanderGeneralId":1,"retinues":[$unit1]},"defender":{"commanderGeneralId":2,"retinues":[$unit2]},"gate":null}}"""
    }

    private fun retinue(id: Int): String =
        """{"id":$id,"generalId":$id,"leadership":80,"strength":70,"intelligence":60,"politics":50,"charisma":40,"troops":123,"kind":"INFANTRY","training":50,"morale":90,"fatigue":10,"supply":80,"accompaniesCorps":true}"""

    private fun catalogPayload(): String {
        val plain = "P".repeat(64)
        val wall = "P".repeat(10) + "W" + "P".repeat(53)
        val boards = (0..213).joinToString(",") { id ->
            val fortress = id < 188
            val rows = (0 until 64).joinToString(",") { row -> "\"${if (fortress && row == 10) wall else plain}\"" }
            val counts = if (fortress) "\"P\":4095,\"F\":0,\"M\":0,\"R\":0,\"W\":1" else
                "\"P\":4096,\"F\":0,\"M\":0,\"R\":0,\"W\":0"
            """{"id":$id,"kind":"${if (fortress) "FORTRESS" else "FIELD"}","tileset":0,"image":"maps/battle_${id.toString().padStart(3, '0')}_ts0.png","landEligible":${id !in 209..212},"terrainCounts":{$counts},"terrainRows":[$rows]}"""
        }
        return """{"schemaVersion":1,"boardSize":64,"status":"owner-accepted derived catalog","source":{"originalBinaryCommitted":false},"boards":[$boards]}"""
    }
}
