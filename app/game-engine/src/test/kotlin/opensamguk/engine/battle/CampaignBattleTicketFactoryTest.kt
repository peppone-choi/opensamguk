package opensamguk.engine.battle

import java.security.MessageDigest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import opensamguk.common.world.WorldId
import opensamguk.gameapi.battle.realtime.BattleFrozenInputCodec
import opensamguk.infra.battle.realtime.FrozenBattleParticipant
import opensamguk.logic.battle.realtime.BattleKind
import opensamguk.logic.battle.realtime.GeneralStats
import opensamguk.logic.battle.realtime.Retinue
import opensamguk.logic.battle.realtime.TerrainProfile
import opensamguk.logic.battle.realtime.UnitKind
import opensamguk.logic.battle.realtime.WaryongBoardCatalogResource

class CampaignBattleTicketFactoryTest {
    private val catalog by lazy { WaryongBoardCatalogResource.load() }
    private val factory by lazy { CampaignBattleTicketFactory(catalog) }
    private val province = TerrainProfile.fromTiles("P".repeat(4096).asIterable())

    @Test
    fun `canonical handoff round trips through the battle actor input codec`() {
        val ticket = factory.freeze(request())
        assertEquals(sha(ticket.payloadJson), ticket.payloadSha256)
        assertEquals(catalog.catalogSha256, ticket.catalogSha256)
        val root = Json.parseToJsonElement(ticket.payloadJson).jsonObject
        assertEquals("REALTIME", root.getValue("pacingMode").jsonPrimitive.content)
        val state = BattleFrozenInputCodec(catalog).initialState(ticket)
        assertEquals(17L, state.seed)
        assertEquals(2, state.units.size)
        assertEquals(root.getValue("battlefieldId").jsonPrimitive.content.toInt(), state.battlefield.id)
    }

    @Test
    fun `input ordering cannot change ticket bytes and NPC pacing is sealed`() {
        val original = request().copy(participants = emptyList())
        val reordered = original.copy(entityRevisions = linkedMapOf(
            "general:100" to 5, "general:1" to 3))
        val first = factory.freeze(original)
        val second = factory.freeze(reordered)
        assertEquals(first.payloadJson, second.payloadJson)
        assertEquals(first.payloadSha256, second.payloadSha256)
        val root = Json.parseToJsonElement(first.payloadJson).jsonObject
        assertEquals("ACCELERATED_NPC", root.getValue("pacingMode").jsonPrimitive.content)
    }

    @Test
    fun `more than six troops or a missing participant general cannot silently disappear`() {
        val seven = (1..7).map(::retinue)
        assertFailsWith<IllegalArgumentException> {
            factory.freeze(request().copy(attacker = CampaignBattleSide(1, seven)))
        }
        assertFailsWith<IllegalArgumentException> {
            factory.freeze(request().copy(defender = CampaignBattleSide(100, emptyList())))
        }
        assertFailsWith<IllegalArgumentException> {
            factory.freeze(request().copy(participants = listOf(
                FrozenBattleParticipant(1, 42, 999, "ATTACKER", 3))))
        }
    }

    @Test
    fun `siege gate must refer to a wall on the selected immutable board`() {
        val selection = catalog.select("siege-1", BattleKind.SIEGE, province)
        val board = selection.board.battlefield
        val row = board.rows.indexOfFirst { 'W' in it }
        val col = board.rows[row].indexOf('W')
        val siege = request().copy(battleId = "siege-1", kind = BattleKind.SIEGE,
            gate = CampaignBattleGate(row, col, 100))
        val ticket = factory.freeze(siege)
        val state = BattleFrozenInputCodec(catalog).initialState(ticket)
        assertEquals(board.id, state.battlefield.id)
        assertEquals(100, state.gateHp)
        assertFailsWith<IllegalArgumentException> {
            factory.freeze(siege.copy(gate = CampaignBattleGate(row, col, 0)))
        }
        assertFailsWith<IllegalArgumentException> {
            factory.freeze(request().copy(gate = CampaignBattleGate(row, col, 100)))
        }
    }

    private fun request() = CampaignBattleFreezeRequest(
        worldId = WorldId(1), battleId = "encounter-1", causeEventId = "sealed-encounter-1",
        kind = BattleKind.ENCOUNTER,
        provinceTerrain = province, worldTerrainSha256 = "a".repeat(64), seed = 17,
        lockGeneration = 4, lockSetRevision = 2,
        joinDeadlineAt = Instant.parse("2026-09-30T12:01:00Z"),
        deadlineAt = Instant.parse("2026-09-30T12:06:00Z"),
        entityRevisions = linkedMapOf("general:1" to 3, "general:100" to 5),
        participants = listOf(FrozenBattleParticipant(1, 42, 1, "ATTACKER", 3)),
        attacker = CampaignBattleSide(1, listOf(retinue(1))),
        defender = CampaignBattleSide(100, listOf(retinue(100))),
    )

    private fun retinue(id: Int) = Retinue(id, GeneralStats(id, 80, 70, 60, 50, 40),
        troops = 123, kind = UnitKind.INFANTRY, training = 50, morale = 90,
        fatigue = 10, supply = 80, accompaniesCorps = true)

    private fun sha(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
