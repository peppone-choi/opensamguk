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
import opensamguk.logic.battle.realtime.TacticalBoardCatalog
import opensamguk.logic.battle.realtime.TacticalV2Cell
import opensamguk.logic.battle.realtime.TacticalV2Deployment
import opensamguk.logic.battle.realtime.TacticalV2SourceKey
import opensamguk.logic.battle.realtime.TacticalV2SourceKind
import opensamguk.logic.battle.realtime.TacticalV2UnitSource

class BattleV2FrozenPlacementCodecTest {
    private val catalog by lazy { TacticalBoardCatalog.parse(catalogPayload()) }
    private val ruleSha = "b".repeat(64)
    private val worldTerrainSha = "c".repeat(64)
    private val boardRowsSha = sha("P".repeat(4096))
    private val allowed = mapOf(BattleSide.ATTACKER to setOf(TacticalV2Cell(1, 1)),
        BattleSide.DEFENDER to setOf(TacticalV2Cell(1, 60)))

    @Test
    fun `full encounter ticket accepts pinned retinue sources`() {
        val deployment = deployment(cityDefender = false)
        val decoded = codec().initialDeployment(ticket(deployment))
        assertEquals(deployment, decoded)
        assertEquals(2, decoded.units.size)
    }

    @Test
    fun `full encounter ticket rejects correctly pinned but unbound city source`() {
        val deployment = deployment(cityDefender = true)
        val failure = assertFailsWith<IllegalArgumentException> {
            codec().initialDeployment(ticket(deployment))
        }
        assertEquals("encounter cannot include unbound city garrison sources", failure.message)
    }

    private fun codec() = BattleV2FrozenPlacementCodec(catalog, ruleSha) { allowed }

    private fun deployment(cityDefender: Boolean): TacticalV2Deployment {
        val attacker = TacticalV2UnitSource(
            TacticalV2SourceKey(TacticalV2SourceKind.RETINUE, "701"),
            BattleSide.ATTACKER, 7, 7, 100, 2)
        val defender = if (cityDefender) TacticalV2UnitSource(
            TacticalV2SourceKey(TacticalV2SourceKind.CITY_GARRISON_BUGOK, "real-g1", 9),
            BattleSide.DEFENDER, null, null, 50, 3) else TacticalV2UnitSource(
            TacticalV2SourceKey(TacticalV2SourceKind.RETINUE, "801"),
            BattleSide.DEFENDER, 8, 8, 50, 3)
        return TacticalV2Deployment(192, boardRowsSha, listOf(attacker, defender).sortedBy { it.key },
            mapOf(attacker.key to TacticalV2Cell(1, 1), defender.key to TacticalV2Cell(1, 60)), allowed)
    }

    private fun ticket(deployment: TacticalV2Deployment): FrozenBattleTicket {
        val pin = BattleV2PlacementPin.from(deployment)
        val body = """{"schemaVersion":2,"worldId":1,"battleId":"battle-v2","kind":"ENCOUNTER","battlefieldId":192,"ruleSha256":"$ruleSha","catalogSha256":"${catalog.catalogSha256}","terrainSha256":"$worldTerrainSha","seed":17,"lockGeneration":4,"lockSetRevision":2,"joinDeadlineAt":"2026-09-27T00:01:00Z","deadlineAt":"2026-09-27T00:06:00Z","tacticalInput":{"schemaVersion":2,"placement":{"schemaVersion":2,"boardId":${pin.boardId},"terrainSha256":"${pin.terrainSha256}","sourceCount":${pin.sourceCount},"deploymentBase64":"${pin.deploymentBase64}","deploymentSha256":"${pin.deploymentSha256}"}}}"""
        return FrozenBattleTicket(WorldId(1), "battle-v2", body, sha(body), ruleSha,
            catalog.catalogSha256, worldTerrainSha, 17, 4, 2,
            Instant.parse("2026-09-27T00:01:00Z"), Instant.parse("2026-09-27T00:06:00Z"),
            listOf(FrozenBattleParticipant(1, 42, 7, "ATTACKER", 0)))
    }

    private fun sha(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun catalogPayload(): String {
        val plain = "P".repeat(64)
        val wall = "P".repeat(10) + "W" + "P".repeat(53)
        val boards = (0..213).joinToString(",") { id ->
            val fortress = id < 188
            val rows = (0 until 64).joinToString(",") { row ->
                "\"${if (fortress && row == 10) wall else plain}\""
            }
            val counts = if (fortress) "\"P\":4095,\"F\":0,\"M\":0,\"R\":0,\"W\":1" else
                "\"P\":4096,\"F\":0,\"M\":0,\"R\":0,\"W\":0"
            """{"id":$id,"kind":"${if (fortress) "FORTRESS" else "FIELD"}","tileset":0,"image":"maps/battle_${id.toString().padStart(3, '0')}_ts0.png","landEligible":${id !in 209..212},"terrainCounts":{$counts},"terrainRows":[$rows]}"""
        }
        return """{"schemaVersion":1,"boardSize":64,"status":"owner-accepted derived catalog","source":{"originalBinaryCommitted":false},"boards":[$boards]}"""
    }
}
