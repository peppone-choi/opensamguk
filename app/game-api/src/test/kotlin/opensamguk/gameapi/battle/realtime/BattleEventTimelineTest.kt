package opensamguk.gameapi.battle.realtime

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import opensamguk.infra.battle.realtime.BattleEventRecord
import opensamguk.logic.battle.realtime.BattleDeployment
import opensamguk.logic.battle.realtime.BattleOrder
import opensamguk.logic.battle.realtime.BattleSide
import opensamguk.logic.battle.realtime.Battlefield
import opensamguk.logic.battle.realtime.GeneralStats
import opensamguk.logic.battle.realtime.Retinue
import opensamguk.logic.battle.realtime.TacticalBattle
import opensamguk.logic.battle.realtime.TacticalCommand
import opensamguk.logic.battle.realtime.TacticalState
import opensamguk.logic.battle.realtime.UnitKind

class BattleEventTimelineTest {
    private val field = Battlefield(192, "FIELD", List(64) { "P".repeat(64) })
    private fun retinue(id: Int) = Retinue(id, GeneralStats(id, 70, 70, 70, 70, 70),
        100, UnitKind.INFANTRY, 50, 90, 0, 100, true)
    private fun initial(): TacticalState = TacticalBattle.start(17, field,
        BattleDeployment.default(BattleSide.ATTACKER, 1, listOf(retinue(1))),
        BattleDeployment.default(BattleSide.DEFENDER, 2, listOf(retinue(2))))

    private fun event(seq: Long, tick: Int, effective: Int, type: String, json: String) =
        BattleEventRecord(seq, 1, tick, effective, type, json, sha(json))
    private fun sha(json: String): String = MessageDigest.getInstance("SHA-256")
        .digest(json.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    @Test
    fun `ordered control and commands replay across checkpoint to same hash`() {
        val join = event(1, 0, 0, "HUMAN_JOIN", """{"schemaVersion":1,"side":"ATTACKER"}""")
        val started = event(2, 0, 0, "SESSION_STARTED", """{"schemaVersion":1,"kind":"SESSION_STARTED"}""")
        val command = event(3, 0, 1, "COMMAND_ACCEPTED",
            """{"schemaVersion":1,"side":"ATTACKER","slot":null,"order":"CHARGE","rally":"CENTER"}""")
        val takeover = event(4, 1, 2, "AI_TAKEOVER", """{"schemaVersion":1,"side":"ATTACKER"}""")
        val future = event(5, 2, 3, "HUMAN_JOIN", """{"schemaVersion":1,"side":"ATTACKER"}""")
        val first = BattleEventTimeline.replay(initial(), 0, listOf(join, started, command), 1)
        val direct = TacticalBattle.step(initial().copy(humanSides = setOf(BattleSide.ATTACKER)),
            listOf(TacticalCommand(0, 3, BattleSide.ATTACKER, null, BattleOrder.CHARGE))).state
        assertEquals(TacticalBattle.stateHash(direct), first.stateHash)
        assertEquals(3L, first.consumedEventSeq)

        val continued = BattleEventTimeline.replay(first.state, first.consumedEventSeq,
            listOf(takeover, future), 2)
        assertEquals(TacticalBattle.stateHash(TacticalBattle.step(direct.copy(humanSides = emptySet())).state),
            continued.stateHash)
        assertEquals(4L, continued.consumedEventSeq)
        assertEquals(emptySet(), continued.state.humanSides)
        val recovered = BattleEventTimeline.replay(initial(), 0,
            listOf(join, started, command, takeover, future), 2)
        assertEquals(recovered.stateHash, continued.stateHash)
        assertEquals(4L, recovered.consumedEventSeq)
    }

    @Test
    fun `corrupt gap payload and unsupported event fail closed`() {
        val join = event(1, 0, 0, "HUMAN_JOIN", """{"schemaVersion":1,"side":"ATTACKER"}""")
        assertFailsWith<IllegalArgumentException> {
            BattleEventTimeline.replay(initial(), 0, listOf(join.copy(eventSeq = 2)), 0)
        }
        assertFailsWith<IllegalArgumentException> {
            BattleEventTimeline.replay(initial(), 0, listOf(join.copy(payloadJson = "{}")), 0)
        }
        assertFailsWith<IllegalStateException> {
            BattleEventTimeline.replay(initial(), 0,
                listOf(event(1, 0, 0, "DEPLOYMENT_SET", "{}")), 0)
        }
    }

    @Test
    fun `future event followed by immediate event cannot cross snapshot cursor`() {
        val future = event(1, 0, 1, "HUMAN_JOIN", """{"schemaVersion":1,"side":"ATTACKER"}""")
        val immediate = event(2, 0, 0, "HUMAN_LEFT", """{"schemaVersion":1,"side":"ATTACKER"}""")
        assertFailsWith<IllegalArgumentException> {
            BattleEventTimeline.replay(initial(), 0, listOf(future, immediate), 0)
        }
    }

    @Test
    fun `takeover later in one effective tick supersedes an accepted human order`() {
        val join = event(1, 0, 0, "HUMAN_JOIN", """{"schemaVersion":1,"side":"ATTACKER"}""")
        val order = event(2, 0, 1, "COMMAND_ACCEPTED",
            """{"schemaVersion":1,"side":"ATTACKER","slot":null,"order":"CHARGE","rally":"CENTER"}""")
        val takeover = event(3, 0, 1, "AI_TAKEOVER", """{"schemaVersion":1,"side":"ATTACKER"}""")
        val replayed = BattleEventTimeline.replay(initial(), 0, listOf(join, order, takeover), 1)
        val expected = TacticalBattle.step(initial()).state
        assertEquals(TacticalBattle.stateHash(expected), replayed.stateHash)
        assertEquals(3L, replayed.consumedEventSeq)
    }

    @Test
    fun `control event cannot apply retroactively at a checkpoint tick`() {
        val join = event(1, 0, 0, "HUMAN_JOIN", """{"schemaVersion":1,"side":"ATTACKER"}""")
        val order = event(2, 0, 1, "COMMAND_ACCEPTED",
            """{"schemaVersion":1,"side":"ATTACKER","slot":null,"order":"CHARGE","rally":"CENTER"}""")
        val lateLeft = event(3, 1, 1, "HUMAN_LEFT", """{"schemaVersion":1,"side":"ATTACKER"}""")
        val checkpoint = BattleEventTimeline.replay(initial(), 0, listOf(join, order), 1)

        assertFailsWith<IllegalArgumentException> {
            BattleEventTimeline.replay(initial(), 0, listOf(join, order, lateLeft), 1)
        }
        assertFailsWith<IllegalArgumentException> {
            BattleEventTimeline.replay(checkpoint.state, checkpoint.consumedEventSeq,
                listOf(lateLeft), 1)
        }
    }
}
