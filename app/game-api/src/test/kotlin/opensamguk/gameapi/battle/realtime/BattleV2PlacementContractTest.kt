package opensamguk.gameapi.battle.realtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import opensamguk.infra.battle.realtime.FrozenBattleParticipant
import opensamguk.logic.battle.realtime.BattleSide
import opensamguk.logic.battle.realtime.TacticalV2Cell
import opensamguk.logic.battle.realtime.TacticalV2Deployment
import opensamguk.logic.battle.realtime.TacticalV2SourceKey
import opensamguk.logic.battle.realtime.TacticalV2SourceKind
import opensamguk.logic.battle.realtime.TacticalV2UnitSource

class BattleV2PlacementContractTest {
    private val first = TacticalV2UnitSource(
        TacticalV2SourceKey(TacticalV2SourceKind.RETINUE, "701"),
        BattleSide.DEFENDER, 7, 7, 100, 1)
    private val second = TacticalV2UnitSource(
        TacticalV2SourceKey(TacticalV2SourceKind.RETINUE, "801"),
        BattleSide.DEFENDER, 8, 8, 120, 2)
    private val city = TacticalV2UnitSource(
        TacticalV2SourceKey(TacticalV2SourceKind.CITY_GARRISON_BUGOK, "real-1", 9),
        BattleSide.DEFENDER, 7, 7, 50, 3)
    private val participants = listOf(FrozenBattleParticipant(1, 42, 7, "DEFENDER", 0),
        FrozenBattleParticipant(2, 43, 8, "DEFENDER", 0))

    @Test
    fun `same defender side does not grant the other owner or city AI source`() {
        val scopes = BattleV2AuthorityScopes.resolve(participants, listOf(first, second, city))
        assertEquals(setOf(first.key), scopes.getValue(1))
        assertEquals(setOf(second.key), scopes.getValue(2))
        assertFailsWith<IllegalArgumentException> {
            BattleV2AuthorityScopes.resolve(participants,
                listOf(first.copy(side = BattleSide.ATTACKER), second, city))
        }
    }

    @Test
    fun `typed placement pin round trips all sources and rejects another spawn catalog`() {
        val units = listOf(city, first, second).sortedBy { it.key }
        val allowed = mapOf(BattleSide.ATTACKER to emptySet(),
            BattleSide.DEFENDER to setOf(TacticalV2Cell(4, 60), TacticalV2Cell(5, 60),
                TacticalV2Cell(6, 60)))
        val deployment = TacticalV2Deployment(1, "a".repeat(64), units,
            mapOf(first.key to TacticalV2Cell(4, 60), second.key to TacticalV2Cell(5, 60),
                city.key to TacticalV2Cell(6, 60)), allowed)
        val pin = BattleV2PlacementPin.from(deployment)
        assertEquals(3, pin.sourceCount)
        assertEquals(deployment, pin.decode(allowed))
        assertFailsWith<IllegalArgumentException> {
            pin.decode(mapOf(BattleSide.ATTACKER to emptySet(), BattleSide.DEFENDER to emptySet()))
        }
    }
}
